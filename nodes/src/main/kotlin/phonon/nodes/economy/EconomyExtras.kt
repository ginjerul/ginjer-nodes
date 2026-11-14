/*
 * Economy extras: selling items to the server, vote rewards, the player
 * market, money bounties and leaderboards. All money moves go through the
 * Economy ledger, so each operation is saved atomically or not at all.
 * Items only change hands after the ledger transaction succeeded.
 */

package phonon.nodes.economy

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.bounty.BountyItems
import java.util.EnumMap
import java.util.UUID

object EconomyExtras {
    private const val DAY_MS = 86_400_000L
    private const val MAX_LISTINGS_TOTAL = 500
    private const val MAX_MONEY_PER_TARGET = 100
    const val PAGE_SIZE = 45

    private const val ESCROW = "bounty-escrow"

    private fun show(cents: Long) = Economy.show(cents)

    private fun wallet(uuid: UUID) = "wallet-$uuid"

    private fun nameOf(uuid: String): String = runCatching { UUID.fromString(uuid) }.getOrNull()
        ?.let { Nodes.getResidentFromUUID(it)?.name } ?: uuid.take(8)

    private fun requirePlaying(player: Player) {
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) {
            throw EconomyException("Use survival or adventure mode")
        }
    }

    // ===================================
    // selling to the server. Items on the exchange sell at the live price and
    // add to the server's stock; other items sell at the fixed sell.prices.
    // ===================================
    private fun buys(material: Material): Boolean = Exchange.has(material) || Economy.sellPrices.containsKey(material)

    private fun buys(item: ItemStack?): Boolean = item != null && !item.hasItemMeta() && buys(item.type)

    // pays out for the items; call inside a transaction. Exchange items move the server's stock.
    private fun payout(counts: Map<Material, Int>): Long {
        var total = 0L
        for ((material, amount) in counts) {
            val part = if (Exchange.has(material)) {
                Exchange.sell(material, amount)
            } else {
                Math.multiplyExact(Economy.sellPrices[material] ?: 0L, amount.toLong())
            }
            total = Math.addExact(total, part)
        }
        if (total <= 0) {
            throw EconomyException("That is worth nothing right now")
        }
        return total
    }

    // counts and ledger entry shared by every way of selling
    private fun settleSale(key: String, to: String, counts: Map<Material, Int>): Long = Economy.transaction {
        val total = payout(counts)
        addSold(key, total)
        Economy.credit(to, total)
        Economy.log("item-sale", "issuance", to, total)
        total
    }

    // counts what a player or town sold today and refuses sales over the daily limit
    private fun addSold(key: String, cents: Long) {
        val limit = Economy.sellDailyLimit
        if (limit <= 0) {
            return
        }
        val day = System.currentTimeMillis() / DAY_MS
        val sold = Economy.state.sold.getOrPut(key) { EcoSold() }
        if (sold.day != day) {
            sold.day = day
            sold.cents = 0L
        }
        if (Math.addExact(sold.cents, cents) > limit) {
            throw EconomyException("Daily sell limit of ${show(limit)} reached. Sell less or try again tomorrow")
        }
        sold.cents += cents
    }

    private fun trendWord(material: Material): String = when (Exchange.trend(material)) {
        1 -> "rising"
        -1 -> "falling"
        else -> "steady"
    }

    // one row per item the server buys or sells
    class ShopEntry(
        val material: Material,
        val buyPrice: Long?,
        val sellPrice: Long?,
        val stock: Long?,
        val target: Long?,
        val trend: Int,
    )

    fun shopEntries(): List<ShopEntry> {
        val materials = HashSet<Material>()
        materials.addAll(Exchange.materials())
        materials.addAll(Economy.shopPrices.keys)
        materials.addAll(Economy.sellPrices.keys)
        return materials.sortedBy { it.name }.map { material ->
            if (Exchange.has(material)) {
                ShopEntry(material, Exchange.nextBuy(material), Exchange.nextSell(material), Exchange.stock(material), Exchange.target(material), Exchange.trend(material))
            } else {
                ShopEntry(material, Economy.shopPrices[material], Economy.sellPrices[material], null, null, 0)
            }
        }
    }

    private fun describe(entry: ShopEntry): String {
        val buy = entry.buyPrice?.let { "buy ${show(it)}" } ?: "not for sale"
        val sell = entry.sellPrice?.let { "sell ${show(it)}" } ?: "not bought"
        val stock = if (entry.stock != null) " (stock ${entry.stock}/${entry.target}, ${trendWord(entry.material)})" else ""
        return "${entry.material.name}: $buy, $sell$stock"
    }

    fun prices(): List<String> = shopEntries().filter { it.sellPrice != null }.map { describe(it) }

    // ---- for sharding: the inventory part runs on a follower, the money part here on the leader ----

    /** An item the server buys (plain, with no meta). */
    internal fun buysPlainItem(item: ItemStack?): Boolean = buys(item)

    /** Leader: pay [uuid]'s wallet for items a follower already took from their inventory. */
    internal fun sellCounts(uuid: UUID, counts: Map<Material, Int>): String {
        if (counts.isEmpty() || counts.keys.any { !buys(it) }) {
            throw EconomyException("The server does not buy that item. See /economy prices")
        }
        val total = settleSale(uuid.toString(), wallet(uuid), counts)
        val parts = counts.entries.joinToString(", ") { "${it.value}x ${it.key.name}" }
        return "Sold $parts for ${show(total)} (paid to your wallet)"
    }

    /** Follower: room for a shop purchase, checked like shopBuy does. */
    internal fun hasSpaceFor(player: Player, material: Material, amount: Int): Boolean = BountyItems.add(player.inventory.storageContents, BountyItems.stacks(material, amount), player.inventory.maxStackSize) != null

    /** Leader: charge [uuid] for a shop purchase that a follower hands out; the shopBuy rules without the inventory. */
    internal fun shopCharge(uuid: UUID, material: Material, amount: Int): String {
        val fixed = Economy.shopPrices[material]
        if (!Exchange.has(material) && fixed == null) {
            throw EconomyException("The server shop does not sell that. See /economy shop list")
        }
        if (amount < 1 || amount > 2304) {
            throw EconomyException("Amount must be 1-2304")
        }
        val from = wallet(uuid)
        val total = Economy.transaction {
            val cost = if (Exchange.has(material)) Exchange.buy(material, amount) else Math.multiplyExact(fixed!!, amount.toLong())
            Economy.requireAvailable(from)
            Economy.credit(from, -cost)
            Economy.log("shop-purchase", from, "issuance", cost)
            cost
        }
        return "Bought ${amount}x ${material.name} for ${show(total)}"
    }

    fun sellHand(player: Player): String {
        requirePlaying(player)
        val item = player.inventory.itemInMainHand
        if (!buys(item)) {
            throw EconomyException("The server does not buy that item. See /economy prices")
        }
        val total = settleSale(player.uniqueId.toString(), wallet(player.uniqueId), mapOf(item.type to item.amount))
        player.inventory.setItemInMainHand(null)
        return "Sold ${item.amount}x ${item.type.name} for ${show(total)} (paid to your wallet)"
    }

    fun sellAll(player: Player): String {
        requirePlaying(player)
        val contents = player.inventory.storageContents
        val counts = EnumMap<Material, Int>(Material::class.java)
        for (item in contents) {
            if (buys(item)) {
                counts[item!!.type] = (counts[item.type] ?: 0) + item.amount
            }
        }
        if (counts.isEmpty()) {
            throw EconomyException("You have nothing the server buys. See /economy prices")
        }
        val total = settleSale(player.uniqueId.toString(), wallet(player.uniqueId), counts)
        for (slot in contents.indices) {
            if (buys(contents[slot])) {
                contents[slot] = null
            }
        }
        player.inventory.storageContents = contents
        player.saveData()
        val parts = counts.entries.joinToString(", ") { "${it.value}x ${it.key.name}" }
        return "Sold $parts for ${show(total)} (paid to your wallet)"
    }

    // sells up to `amountText` of one item from the inventory
    fun sellItem(player: Player, materialName: String, amountText: String): String {
        requirePlaying(player)
        val material = Material.matchMaterial(materialName) ?: throw EconomyException("Unknown material")
        if (!buys(material)) {
            throw EconomyException("The server does not buy that item. See /economy prices")
        }
        val wanted = amountText.toIntOrNull()
        if (wanted == null || wanted < 1 || wanted > 2304) {
            throw EconomyException("Amount must be 1-2304")
        }
        val contents = player.inventory.storageContents
        var have = 0
        for (item in contents) {
            if (item != null && item.type == material && !item.hasItemMeta()) {
                have += item.amount
            }
        }
        val amount = minOf(wanted, have)
        if (amount < 1) {
            throw EconomyException("You have no plain ${material.name} to sell")
        }
        val total = settleSale(player.uniqueId.toString(), wallet(player.uniqueId), mapOf(material to amount))
        var left = amount
        for (slot in contents.indices) {
            val item = contents[slot]
            if (left > 0 && item != null && item.type == material && !item.hasItemMeta()) {
                val take = minOf(left, item.amount)
                left -= take
                if (take == item.amount) contents[slot] = null else item.amount -= take
            }
        }
        player.inventory.storageContents = contents
        player.saveData()
        return "Sold ${amount}x ${material.name} for ${show(total)} (paid to your wallet)"
    }

    // sells the town's stored income into the town treasury
    fun sellTown(player: Player): String {
        val account = Economy.townAccount(player, true)
        val town = Nodes.getResident(player)?.town ?: throw EconomyException("Join a town first")
        val counts = EnumMap<Material, Int>(Material::class.java)
        for ((material, amount) in town.income.storage) {
            if (amount > 0 && buys(material)) {
                counts[material] = amount
            }
        }
        if (counts.isEmpty()) {
            throw EconomyException("Your town income has nothing the server buys")
        }
        val total = settleSale(account, account, counts)
        for (material in counts.keys) {
            town.income.storage.remove(material)
        }
        town.needsUpdate()
        Nodes.needsSave = true
        return "Sold stored income for ${show(total)}, paid into the town treasury"
    }

    // what the server would pay now for everything sellable in the player's inventory
    fun sellableValue(player: Player): Long {
        val counts = EnumMap<Material, Int>(Material::class.java)
        for (item in player.inventory.storageContents) {
            if (buys(item)) {
                counts[item!!.type] = (counts[item.type] ?: 0) + item.amount
            }
        }
        var total = 0L
        for ((material, amount) in counts) {
            total += if (Exchange.has(material)) Exchange.quoteSell(material, amount) else (Economy.sellPrices[material] ?: 0L) * amount
        }
        return total
    }

    // ===================================
    // server shop: items the server sells. Exchange items cost the live price
    // and come out of the server's stock; the others have fixed shop.prices.
    // The money leaves circulation, which balances the money created by selling.
    // ===================================
    fun shopLines(page: Int): List<String> {
        val all = shopEntries()
        val pages = maxOf(1, (all.size + 9) / 10)
        val p = page.coerceIn(1, pages)
        val lines = all.drop((p - 1) * 10).take(10).map { describe(it) }
        if (lines.isEmpty()) {
            return listOf("The server shop is empty")
        }
        val restock = if (Exchange.enabled) " Next restock in ${Exchange.minutesToRestock(System.currentTimeMillis())} min." else ""
        return lines + "Page $p/$pages. Buy with /economy shop buy <material> <amount>, sell with /economy shop sell <material> <amount>.$restock"
    }

    fun shopBuy(player: Player, materialName: String, amountText: String): String {
        requirePlaying(player)
        val material = Material.matchMaterial(materialName) ?: throw EconomyException("Unknown material")
        val fixed = Economy.shopPrices[material]
        if (!Exchange.has(material) && fixed == null) {
            throw EconomyException("The server shop does not sell that. See /economy shop list")
        }
        val amount = amountText.toIntOrNull()
        if (amount == null || amount < 1 || amount > 2304) {
            throw EconomyException("Amount must be 1-2304")
        }
        val result = BountyItems.add(player.inventory.storageContents, BountyItems.stacks(material, amount), player.inventory.maxStackSize)
            ?: throw EconomyException("Make space in your inventory first")
        val from = wallet(player.uniqueId)
        val total = Economy.transaction {
            val cost = if (Exchange.has(material)) Exchange.buy(material, amount) else Math.multiplyExact(fixed!!, amount.toLong())
            Economy.requireAvailable(from)
            Economy.credit(from, -cost)
            Economy.log("shop-purchase", from, "issuance", cost)
            cost
        }
        player.inventory.storageContents = result
        player.saveData()
        return "Bought ${amount}x ${material.name} for ${show(total)}"
    }

    // loan requests waiting for staff: id, borrower and amount
    fun pendingLoans(): List<Triple<String, String, Long>> = Economy.state.loans.entries
        .filter { it.value.status == "REQUESTED" }
        .map { Triple(it.key, Economy.label(it.value.borrower), it.value.owed) }

    // ===================================
    // vote rewards
    // ===================================
    fun vote(name: String, service: String): String {
        val reward = Economy.voteReward
        if (!Economy.enabled || reward <= 0) {
            return "Vote rewards are disabled"
        }
        val resident = Nodes.getResidentFromName(name) ?: throw EconomyException("Unknown player $name")
        val to = wallet(resident.uuid)
        Economy.transaction {
            Economy.credit(to, reward)
            Economy.log("vote-reward $service", "issuance", to, reward)
        }
        Bukkit.getPlayer(resident.uuid)?.let { Message.print(it, "Thanks for voting! ${show(reward)} was added to your wallet") }
        return "Paid ${show(reward)} to ${resident.name} for a vote on $service"
    }

    // ===================================
    // player market
    // ===================================
    private fun requireMarket() {
        if (!Economy.marketEnabled) {
            throw EconomyException("The market is disabled")
        }
    }

    fun marketSell(player: Player, priceText: String): String {
        requireMarket()
        requirePlaying(player)
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) {
            throw EconomyException("Hold the item stack you want to sell")
        }
        val price = Economy.parseMoney(priceText)
        if (price > Economy.marketMaxPrice) {
            throw EconomyException("The highest allowed price is ${show(Economy.marketMaxPrice)}")
        }
        val encoded = BountyItems.encode(item) ?: throw EconomyException("That item cannot be listed")
        val seller = player.uniqueId.toString()
        val id = Economy.newId(Economy.state.listings)
        Economy.transaction {
            val listings = Economy.state.listings
            if (listings.size >= MAX_LISTINGS_TOTAL) {
                throw EconomyException("The market is full")
            }
            if (listings.values.count { it.seller == seller } >= Economy.marketMaxListings) {
                throw EconomyException("You already have ${Economy.marketMaxListings} listings. Cancel one first")
            }
            listings[id] = EcoListing(
                seller = seller,
                item = encoded,
                price = price,
                summary = BountyItems.summary(listOf(item)),
                created = System.currentTimeMillis(),
            )
            Economy.log("market-listed $id", seller, "market", 0)
        }
        player.inventory.setItemInMainHand(null)
        return "Listed ${BountyItems.summary(listOf(item))} for ${show(price)}. ID: $id"
    }

    private fun give(player: Player, item: ItemStack): Boolean {
        val result = BountyItems.add(player.inventory.storageContents, listOf(item), player.inventory.maxStackSize) ?: return false
        player.inventory.storageContents = result
        player.saveData()
        return true
    }

    private fun fits(player: Player, item: ItemStack): Boolean = BountyItems.add(player.inventory.storageContents, listOf(item), player.inventory.maxStackSize) != null

    fun marketBuy(player: Player, id: String): String {
        requireMarket()
        requirePlaying(player)
        val listing = Economy.state.listings[id] ?: throw EconomyException("No listing with that ID")
        val buyer = player.uniqueId.toString()
        if (listing.seller == buyer) {
            throw EconomyException("That is your listing. Use /economy market cancel $id")
        }
        val item = BountyItems.decode(listing.item) ?: throw EconomyException("That listing is unreadable; ask staff")
        if (!fits(player, item)) {
            throw EconomyException("Make space in your inventory first")
        }
        val from = wallet(player.uniqueId)
        val to = "wallet-${listing.seller}"
        val fee = Economy.percentOf(listing.price, Economy.marketFeePercent)
        val price = listing.price
        Economy.transaction {
            Economy.state.listings.remove(id) ?: throw EconomyException("That listing was just sold")
            Economy.requireAvailable(from)
            Economy.move(from, to, price - fee, "market-sale $id")
            if (fee > 0) {
                Economy.move(from, "reserve", fee, "market-fee $id")
            }
        }
        give(player, item)
        runCatching { UUID.fromString(listing.seller) }.getOrNull()?.let { seller ->
            Bukkit.getPlayer(seller)?.let { Message.print(it, "${player.name} bought ${listing.summary} for ${show(price)}; ${show(price - fee)} went to your wallet") }
        }
        return "Bought ${listing.summary} for ${show(price)}"
    }

    fun marketCancel(player: Player, id: String, admin: Boolean): String {
        val listing = Economy.state.listings[id] ?: throw EconomyException("No listing with that ID")
        if (listing.seller != player.uniqueId.toString() && !admin) {
            throw EconomyException("That is not your listing")
        }
        val item = BountyItems.decode(listing.item) ?: throw EconomyException("That listing is unreadable; ask staff")
        if (!fits(player, item)) {
            throw EconomyException("Make space in your inventory first")
        }
        Economy.transaction {
            Economy.state.listings.remove(id) ?: throw EconomyException("That listing is gone")
            Economy.log("market-cancelled $id", "market", listing.seller, 0)
        }
        give(player, item)
        return "Listing cancelled; ${listing.summary} returned to you"
    }

    // newest first
    fun listings(): List<Pair<String, EcoListing>> = Economy.state.listings.entries
        .sortedByDescending { it.value.created }
        .map { Pair(it.key, it.value) }

    fun marketLines(page: Int, onlySeller: UUID? = null): List<String> {
        val all = listings().filter { onlySeller == null || it.second.seller == onlySeller.toString() }
        val pages = maxOf(1, (all.size + 9) / 10)
        val p = page.coerceIn(1, pages)
        val lines = all.drop((p - 1) * 10).take(10).map { (id, l) ->
            "$id ${l.summary} for ${show(l.price)} by ${nameOf(l.seller)}"
        }
        return if (lines.isEmpty()) listOf("No listings") else lines + "Page $p/$pages"
    }

    // ===================================
    // port warp fees (paid from the wallet into the port owner's town treasury)
    // ===================================
    fun canPay(player: Player, cents: Long): Boolean = Economy.balance(wallet(player.uniqueId)) >= cents

    fun payPortFee(player: Player, owner: phonon.nodes.objects.Town, cents: Long, portName: String) {
        val from = wallet(player.uniqueId)
        Economy.transaction {
            Economy.requireAvailable(from)
            Economy.move(from, "town-${owner.uuid}", cents, "port-fee $portName")
        }
    }

    // ===================================
    // leaderboards
    // ===================================
    fun topPlayers(limit: Int): List<String> {
        val totals = HashMap<String, Long>()
        for ((account, cents) in Economy.state.balances) {
            if (account.startsWith("player-") || account.startsWith("wallet-")) {
                val uuid = account.substringAfter('-')
                totals[uuid] = (totals[uuid] ?: 0L) + cents
            }
        }
        return totals.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(limit)
            .mapIndexed { i, e -> "${i + 1}. ${nameOf(e.key)}: ${show(e.value)}" }
    }

    fun topNations(limit: Int): List<String> = topByPrefix("nation-", limit)

    fun topTowns(limit: Int): List<String> = topByPrefix("town-", limit)

    private fun topByPrefix(prefix: String, limit: Int): List<String> = Economy.state.balances.entries
        .filter { it.key.startsWith(prefix) && it.value > 0 }
        .sortedByDescending { it.value }
        .map { Pair(Economy.label(it.key), it.value) }
        .filter { it.first != "a deleted nation" && it.first != "a deleted town" }
        .take(limit)
        .mapIndexed { i, e -> "${i + 1}. ${e.first}: ${show(e.second)}" }

    // wallet, bank and treasuries for the menu
    fun overview(player: Player): List<String> {
        val resident = Nodes.getResident(player)
        val lines = mutableListOf(
            "Wallet: ${show(Economy.balance(wallet(player.uniqueId)))}",
            "Bank: ${show(Economy.balance("player-${player.uniqueId}"))}",
        )
        resident?.town?.let { lines.add("Town (${it.name}): ${show(Economy.balance("town-${it.uuid}"))}") }
        resident?.nation?.let { lines.add("Nation (${it.name}): ${show(Economy.balance("nation-${it.uuid}"))}") }
        return lines
    }

    // ===================================
    // money bounties (rules are checked by Bounties, the money lives here)
    // ===================================
    fun activeMoney(target: UUID): List<Pair<String, EcoBountyMoney>> = Economy.state.bountyMoney.entries
        .filter { it.value.target == target.toString() && it.value.status == "ACTIVE" }
        .map { Pair(it.key, it.value) }

    fun activeMoneyTotal(target: UUID): Long = activeMoney(target).sumOf { it.second.cents }

    // targets with active money bounties and their totals
    fun moneyTargets(): List<Triple<UUID, String, Long>> = Economy.state.bountyMoney.values
        .filter { it.status == "ACTIVE" }
        .groupBy { it.target }
        .mapNotNull { (target, list) ->
            val uuid = runCatching { UUID.fromString(target) }.getOrNull() ?: return@mapNotNull null
            Triple(uuid, list.first().targetName, list.sumOf { it.cents })
        }

    // returns the contribution ID
    fun fundMoney(sponsor: Player, target: OfflinePlayer, targetName: String, cents: Long, reason: String): String {
        if (!Economy.enabled) {
            throw EconomyException("The economy is disabled")
        }
        val id = Economy.newId(Economy.state.bountyMoney)
        val from = wallet(sponsor.uniqueId)
        Economy.transaction {
            if (activeMoney(target.uniqueId).size >= MAX_MONEY_PER_TARGET) {
                throw EconomyException("This target has reached the money contribution limit")
            }
            Economy.requireAvailable(from)
            Economy.move(from, ESCROW, cents, "bounty-funded $id")
            Economy.state.bountyMoney[id] = EcoBountyMoney(
                sponsor = sponsor.uniqueId.toString(),
                target = target.uniqueId.toString(),
                targetName = targetName,
                cents = cents,
                reason = reason,
                created = System.currentTimeMillis(),
            )
        }
        return id
    }

    // money contributions the hunter may claim now
    fun eligibleMoney(target: UUID, hunter: UUID, createdBefore: Long): List<String> = activeMoney(target)
        .filter { it.second.sponsor != hunter.toString() && it.second.created <= createdBefore }
        .map { it.first }

    // pays the escrowed money to the hunter's wallet and returns the total
    fun claimMoney(ids: List<String>, hunter: Player): Long {
        if (ids.isEmpty() || !Economy.enabled) {
            return 0L
        }
        var total = 0L
        Economy.transaction {
            for (id in ids) {
                val entry = Economy.state.bountyMoney[id] ?: continue
                if (entry.status != "ACTIVE") {
                    continue
                }
                Economy.move(ESCROW, wallet(hunter.uniqueId), entry.cents, "bounty-claimed $id")
                entry.status = "CLAIMED"
                total += entry.cents
            }
        }
        return total
    }

    // returns an error message, or null when the contribution was refunded
    fun refundMoney(id: String): String? {
        val entry = Economy.state.bountyMoney[id]
        if (entry == null || entry.status != "ACTIVE") {
            return null
        }
        try {
            Economy.transaction {
                Economy.move(ESCROW, "wallet-${entry.sponsor}", entry.cents, "bounty-refunded $id")
                entry.status = "REFUNDED"
            }
        } catch (e: EconomyException) {
            return e.message
        }
        return null
    }

    fun hasMoneyContribution(id: String): Boolean = Economy.state.bountyMoney[id]?.status == "ACTIVE"
}
