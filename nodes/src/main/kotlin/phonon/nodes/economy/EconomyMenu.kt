/*
 * Economy menus: every /economy and /economyadmin command as clickable screens.
 * Amounts and names are typed in chat after a click (type "cancel" to stop).
 * Buttons call the same economy functions the commands use, so all rules,
 * permissions and checks are identical. The result of each action is printed
 * in chat and the screen opens again.
 */

package phonon.nodes.economy

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.bounty.BountyItems
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class GuiHolder : InventoryHolder {
    lateinit var inv: Inventory
    val actions = HashMap<Int, (InventoryClickEvent) -> Unit>()

    override fun getInventory(): Inventory = inv
}

private class Screen(val player: Player, title: String, rows: Int) {
    val holder = GuiHolder()
    val inv: Inventory = Bukkit.createInventory(holder, rows * 9, title)

    init {
        holder.inv = inv
    }

    fun set(slot: Int, item: ItemStack, action: ((InventoryClickEvent) -> Unit)? = null) {
        inv.setItem(slot, item)
        if (action != null) {
            holder.actions[slot] = action
        }
    }

    fun open() {
        player.openInventory(inv)
    }
}

// the next chat message from a player is taken as an answer instead of being sent
internal object ChatPrompt {
    private class Pending(val expires: Long, val onText: (String) -> Unit)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun ask(player: Player, message: String, onText: (String) -> Unit) {
        player.closeInventory()
        Message.print(player, "${ChatColor.AQUA}$message ${ChatColor.GRAY}(type cancel to stop)")
        pending[player.uniqueId] = Pending(System.currentTimeMillis() + 60_000L, onText)
    }

    fun take(uuid: UUID): ((String) -> Unit)? {
        val entry = pending.remove(uuid) ?: return null
        return if (entry.expires > System.currentTimeMillis()) entry.onText else null
    }
}

object EconomyMenu {
    private const val PAGE = 45
    private const val ADMIN = "nodes.economy.admin"

    // ===================================
    // building blocks
    // ===================================
    private fun item(material: Material, name: String, lore: List<String> = emptyList(), amount: Int = 1): ItemStack {
        val stack = ItemStack(material, amount.coerceIn(1, 64))
        val meta = stack.itemMeta
        meta?.setDisplayName("${ChatColor.RESET}$name")
        meta?.lore = lore.map { "${ChatColor.GRAY}$it" }
        stack.itemMeta = meta
        return stack
    }

    // lore lines of at most `width` characters
    private fun wrap(text: String, width: Int = 38): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(" ")) {
            if (line.isNotEmpty() && line.length + word.length + 1 > width) {
                lines.add(line)
                line = word
            } else {
                line = if (line.isEmpty()) word else "$line $word"
            }
        }
        if (line.isNotEmpty()) {
            lines.add(line)
        }
        return lines
    }

    private fun later(block: () -> Unit) {
        Bukkit.getScheduler().runTask(Nodes.plugin!!, Runnable { block() })
    }

    // runs an economy action, prints its result in chat and opens the screen again
    private fun act(p: Player, reopen: () -> Unit, block: () -> String?) {
        val text = try {
            block()
        } catch (e: EconomyException) {
            "${ChatColor.RED}${e.message ?: "Economy error"}"
        } catch (e: Exception) {
            Nodes.logger?.severe("[Economy] Menu action stopped: $e")
            "${ChatColor.RED}Operation stopped, see the server log. No partial transaction was saved"
        }
        if (text != null) {
            Message.print(p, text)
        }
        later { reopen() }
    }

    // asks for text in chat and then runs the answer
    private fun ask(p: Player, prompt: String, reopen: () -> Unit, onText: (String) -> String?) {
        ChatPrompt.ask(p, prompt) { text ->
            if (text.equals("cancel", ignoreCase = true)) {
                Message.print(p, "Cancelled")
                reopen()
            } else {
                act(p, reopen) { onText(text) }
            }
        }
    }

    // first step of a multi-step prompt: no action and no reopen, the next step takes over
    private fun step(p: Player, prompt: String, reopen: () -> Unit, onText: (String) -> Unit) {
        ChatPrompt.ask(p, prompt) { text ->
            if (text.equals("cancel", ignoreCase = true)) {
                Message.print(p, "Cancelled")
                reopen()
            } else {
                onText(text)
            }
        }
    }

    private class Entry(val stack: ItemStack, val action: ((InventoryClickEvent) -> Unit)? = null)

    // a paged list screen; slots 45/49/53 are previous, back and next
    private fun paged(
        p: Player,
        title: String,
        entries: List<Entry>,
        page: Int,
        rebuild: (Int) -> Unit,
        back: () -> Unit,
        extras: Map<Int, Entry> = emptyMap(),
    ) {
        val pages = maxOf(1, (entries.size + PAGE - 1) / PAGE)
        val current = page.coerceIn(0, pages - 1)
        val screen = Screen(p, "$title ${current + 1}/$pages", 6)
        entries.drop(current * PAGE).take(PAGE).forEachIndexed { slot, entry -> screen.set(slot, entry.stack, entry.action) }
        if (current > 0) {
            screen.set(45, item(Material.ARROW, "Previous page")) { rebuild(current - 1) }
        }
        for ((slot, extra) in extras) {
            screen.set(slot, extra.stack, extra.action)
        }
        screen.set(49, item(Material.BARRIER, "Back")) { back() }
        if (current < pages - 1) {
            screen.set(53, item(Material.ARROW, "Next page")) { rebuild(current + 1) }
        }
        screen.open()
    }

    private fun nationOptions(): List<String> = Nodes.nations.values.map { it.name }.sorted()

    // pick one of a list of names, shown as items
    private fun choose(p: Player, title: String, names: List<String>, icon: (String) -> Material, back: () -> Unit, onPick: (String) -> Unit) {
        fun show(page: Int) {
            val entries = names.map { name -> Entry(item(icon(name), name)) { onPick(name) } }
            paged(p, title, entries, page, { show(it) }, back)
        }
        show(0)
    }

    private fun hasTown(p: Player) = Nodes.getResident(p)?.town != null

    private fun hasNation(p: Player) = Nodes.getResident(p)?.nation != null

    // ===================================
    // main menu
    // ===================================
    fun openMain(p: Player) {
        val s = Screen(p, "Economy", 3)
        s.set(4, item(Material.GOLD_NUGGET, "${ChatColor.GOLD}Your money", EconomyExtras.overview(p)))
        s.set(10, item(Material.GOLD_INGOT, "${ChatColor.GOLD}Accounts", listOf("Balances, deposit, withdraw, pay,", "history, loans, business accounts"))) { accounts(p) }
        s.set(11, item(Material.CHEST, "${ChatColor.GREEN}Market", listOf("Player listings and the server exchange"))) { market(p) }
        s.set(12, item(Material.EMERALD, "${ChatColor.GREEN}Sell to server", listOf("Sell items the server buys"))) { sell(p) }
        s.set(14, item(Material.WHITE_BANNER, "${ChatColor.AQUA}Nation economy", listOf("Stock, trade routes, infrastructure"))) { nation(p) }
        s.set(15, item(Material.DIAMOND, "${ChatColor.AQUA}Richest", listOf("Top players, nations and towns"))) { top(p) }
        s.set(16, item(Material.BOOK, "${ChatColor.YELLOW}Command help", listOf("Show the typed commands in chat"))) {
            p.closeInventory()
            p.performCommand("economy help")
        }
        if (p.hasPermission(ADMIN)) {
            s.set(22, item(Material.COMMAND_BLOCK, "${ChatColor.RED}Admin", listOf("Loans, issue money, infrastructure"))) { openAdmin(p) }
        }
        s.open()
    }

    // ===================================
    // accounts
    // ===================================
    private fun accounts(p: Player) {
        val s = Screen(p, "Accounts", 3)
        s.set(10, item(Material.GOLD_NUGGET, "${ChatColor.GOLD}Wallet", listOf("Cash you carry.", "Selling items and votes pay here.", "Deposit it to a bank account.")))
        s.set(11, item(Material.GOLD_INGOT, "${ChatColor.GOLD}Your bank", listOf("Click to manage"))) { account(p, "player") }
        if (hasTown(p)) {
            s.set(12, item(Material.OAK_DOOR, "${ChatColor.GOLD}Town treasury", listOf("Click to manage", "Leader or officers can withdraw"))) { account(p, "town") }
        }
        if (hasNation(p)) {
            s.set(13, item(Material.WHITE_BANNER, "${ChatColor.GOLD}Nation treasury", listOf("Click to manage", "Capital leader or officers can withdraw"))) { account(p, "nation") }
        }
        Economy.state.businesses.filter { it.value == p.uniqueId.toString() }.keys.take(6).forEachIndexed { i, key ->
            s.set(14 + i, item(Material.CHEST_MINECART, "${ChatColor.GOLD}${key.removePrefix("business-")}", listOf("Business account", "Click to manage"))) { account(p, key) }
        }
        s.set(22, item(Material.WRITABLE_BOOK, "${ChatColor.GREEN}Open a business account", listOf("Type a name in chat"))) {
            ask(p, "Type the new business name (lowercase letters, numbers, - or _)", { accounts(p) }) { Economy.createBusiness(p, it) }
        }
        s.set(26, item(Material.BARRIER, "Back")) { openMain(p) }
        s.open()
    }

    private fun account(p: Player, name: String) {
        val s = Screen(p, "Account: ${name.removePrefix("business-")}", 3)
        val balance = try {
            Economy.balanceOf(p, name)
        } catch (e: EconomyException) {
            Message.error(p, e.message ?: "Economy error")
            later { accounts(p) }
            return
        }
        val back = { account(p, name) }
        s.set(4, item(Material.GOLD_INGOT, "${ChatColor.GOLD}Balance", wrap(balance)))
        s.set(10, item(Material.HOPPER, "${ChatColor.GREEN}Deposit", listOf("Wallet to this account"))) {
            ask(p, "Type the amount to deposit", back) { Economy.transfer(p, true, name, it) }
        }
        s.set(11, item(Material.DROPPER, "${ChatColor.GREEN}Withdraw", listOf("This account to your wallet"))) {
            ask(p, "Type the amount to withdraw", back) { Economy.transfer(p, false, name, it) }
        }
        s.set(12, item(Material.PLAYER_HEAD, "${ChatColor.GREEN}Pay a player", listOf("Send money to a player's bank"))) {
            step(p, "Type the player's name", back) { who ->
                ask(p, "Type the amount to pay $who", back) { amount -> Economy.pay(p, name, who, amount) }
            }
        }
        s.set(14, item(Material.BOOK, "${ChatColor.YELLOW}History", listOf("Latest 20 records"))) { history(p, name) }
        s.set(15, item(Material.PAPER, "${ChatColor.YELLOW}Loans", listOf("Request, view and repay loans"))) { loans(p, name) }
        if (name == "nation") {
            s.set(16, item(Material.WHITE_BANNER, "${ChatColor.AQUA}Nation economy", listOf("Stock, routes, infrastructure"))) { nation(p) }
        }
        s.set(22, item(Material.BARRIER, "Back")) { accounts(p) }
        s.open()
    }

    private fun history(p: Player, name: String) {
        val lines = try {
            Economy.history(p, name)
        } catch (e: EconomyException) {
            Message.error(p, e.message ?: "Economy error")
            return
        }
        val entries = lines.map { line -> Entry(item(Material.PAPER, "${ChatColor.WHITE}${line.take(40)}", wrap(line))) }
        paged(p, "History", entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "No records yet"))) }, 0, {}, { account(p, name) })
    }

    private fun loans(p: Player, name: String) {
        val lines = try {
            Economy.loanList(p, name)
        } catch (e: EconomyException) {
            Message.error(p, e.message ?: "Economy error")
            return
        }
        val back = { loans(p, name) }
        val s = Screen(p, "Loans", 5)
        lines.take(27).forEachIndexed { i, line ->
            val id = line.substringBefore(' ')
            val status = line.split(" ").getOrNull(1) ?: ""
            val repayable = status == "ACTIVE" || status == "DEFAULT"
            val lore = wrap(line) + if (repayable) listOf("${ChatColor.GREEN}Click to repay") else emptyList()
            s.set(i, item(if (repayable) Material.GOLD_NUGGET else Material.PAPER, "${ChatColor.WHITE}Loan $id", lore)) {
                if (repayable) {
                    ask(p, "Type the amount to repay on loan $id", back) { Economy.loanRepay(p, name, id, it) }
                }
            }
        }
        s.set(40, item(Material.EMERALD, "${ChatColor.GREEN}Request a loan", listOf("Staff approve requests"))) {
            ask(p, "Type the loan amount", back) { Economy.loanRequest(p, name, it) }
        }
        s.set(44, item(Material.BARRIER, "Back")) { account(p, name) }
        s.open()
    }

    // ===================================
    // nation economy
    // ===================================
    private fun nation(p: Player) {
        if (!hasNation(p)) {
            Message.error(p, "Join a nation first")
            return
        }
        val s = Screen(p, "Nation economy", 3)
        s.set(10, item(Material.CHEST, "${ChatColor.GREEN}Stock", listOf("Your nation's trade resources"))) { stock(p) }
        s.set(11, item(Material.RAIL, "${ChatColor.GREEN}Trade routes", listOf("Accept, cancel and view routes"))) { routes(p) }
        s.set(12, item(Material.WRITABLE_BOOK, "${ChatColor.GREEN}Offer a route", listOf("Sell resources to another nation", "on a schedule"))) { offerRoute(p) }
        s.set(13, item(Material.IRON_BLOCK, "${ChatColor.GREEN}Infrastructure", listOf("Upkeep and arrears"))) { infra(p) }
        s.set(14, item(Material.GOLD_BLOCK, "${ChatColor.GOLD}Nation treasury", listOf("Balance, deposit, withdraw"))) { account(p, "nation") }
        s.set(22, item(Material.BARRIER, "Back")) { openMain(p) }
        s.open()
    }

    private fun stock(p: Player) {
        val lines = try {
            Economy.stock(p)
        } catch (e: EconomyException) {
            Message.error(p, e.message ?: "Economy error")
            return
        }
        val entries = lines.map { line ->
            val mat = Material.matchMaterial(line.substringBefore(':')) ?: Material.BARREL
            Entry(item(mat, "${ChatColor.WHITE}${line.substringBefore(':')}", listOf("Quantity: ${line.substringAfter(": ")}")))
        }
        paged(p, "Stock", entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "No trade resources"))) }, 0, {}, { nation(p) })
    }

    private fun routes(p: Player) {
        val lines = try {
            Economy.routeList(p)
        } catch (e: EconomyException) {
            Message.error(p, e.message ?: "Economy error")
            return
        }
        val entries = lines.map { line ->
            val parts = line.split(" ")
            val id = parts[0]
            val mat = Material.matchMaterial(parts.getOrNull(2) ?: "") ?: Material.RAIL
            val lore = wrap(line) + listOf("${ChatColor.GREEN}Left-click: accept (buyer)", "${ChatColor.RED}Right-click: cancel")
            Entry(item(mat, "${ChatColor.WHITE}Route $id (${parts.getOrNull(1)})", lore)) { e ->
                if (e.click == ClickType.RIGHT) {
                    act(p, { routes(p) }) { Economy.routeCancel(p, id) }
                } else {
                    act(p, { routes(p) }) { Economy.routeAccept(p, id) }
                }
            }
        }
        paged(p, "Routes", entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "No routes"))) }, 0, {}, { nation(p) })
    }

    // buyer nation, then material, then quantity, price and interval typed in chat
    private fun offerRoute(p: Player) {
        val back = { nation(p) }
        choose(p, "Buyer nation", nationOptions(), { Material.WHITE_BANNER }, back) { buyer ->
            choose(p, "Resource", Economy.tradeResources().sorted(), { Material.matchMaterial(it) ?: Material.CHEST }, back) { material ->
                step(p, "Type the quantity per shipment", back) { qty ->
                    step(p, "Type the total price per shipment", back) { price ->
                        ask(p, "Type the minutes between shipments", back) { minutes ->
                            Economy.routeOffer(p, buyer, material, qty, price, minutes)
                        }
                    }
                }
            }
        }
    }

    private fun infra(p: Player) {
        val lines = try {
            Economy.infraList(p)
        } catch (e: EconomyException) {
            Message.error(p, e.message ?: "Economy error")
            return
        }
        val entries = lines.map { line ->
            val parts = line.split(" ")
            val name = parts[0]
            val icon = when (parts.getOrNull(1)) {
                "railway" -> Material.RAIL
                "port" -> Material.OAK_BOAT
                "road" -> Material.DIRT_PATH
                else -> Material.REDSTONE_BLOCK
            }
            Entry(item(icon, "${ChatColor.WHITE}$name", wrap(line) + listOf("${ChatColor.GREEN}Click to pay arrears"))) {
                act(p, { infra(p) }) { Economy.infraPay(p, name) }
            }
        }
        paged(p, "Infrastructure", entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "No infrastructure"))) }, 0, {}, { nation(p) })
    }

    // ===================================
    // selling to the server
    // ===================================
    private fun sell(p: Player) {
        val s = Screen(p, "Sell to server", 3)
        val value = EconomyExtras.sellableValue(p)
        s.set(4, item(Material.GOLD_NUGGET, "${ChatColor.GOLD}Worth in your inventory", listOf(Economy.show(value))))
        s.set(10, item(Material.EMERALD, "${ChatColor.GREEN}Sell everything", listOf("All items the server buys", "Paid to your wallet"))) {
            act(p, { sell(p) }) { EconomyExtras.sellAll(p) }
        }
        s.set(11, item(Material.IRON_INGOT, "${ChatColor.GREEN}Sell item in hand")) {
            act(p, { sell(p) }) { EconomyExtras.sellHand(p) }
        }
        if (hasTown(p)) {
            s.set(12, item(Material.OAK_DOOR, "${ChatColor.GREEN}Sell town income", listOf("Stored income to the town treasury", "Leader or officers only"))) {
                act(p, { sell(p) }) { EconomyExtras.sellTown(p) }
            }
        }
        s.set(14, item(Material.BOOK, "${ChatColor.YELLOW}Prices", listOf("Live prices and stock", "Click items to sell them"))) { shop(p, 0, true) }
        s.set(22, item(Material.BARRIER, "Back")) { openMain(p) }
        s.open()
    }

    // ===================================
    // market: player listings and the server shop
    // ===================================
    private fun market(p: Player) {
        val s = Screen(p, "Market", 3)
        s.set(10, item(Material.CHEST, "${ChatColor.GREEN}Player listings", listOf("Items other players sell"))) { players(p, 0) }
        s.set(12, item(Material.EMERALD_BLOCK, "${ChatColor.GREEN}Server exchange", listOf("Buy and sell preset items.", "Prices follow the server's stock"))) { shop(p, 0, false) }
        s.set(14, item(Material.WRITABLE_BOOK, "${ChatColor.GREEN}Sell your item", listOf("List the item stack in your hand", "You type the price"))) {
            ask(p, "Hold the stack and type its price", { market(p) }) { EconomyExtras.marketSell(p, it) }
        }
        s.set(16, item(Material.ENDER_CHEST, "${ChatColor.GREEN}My listings", listOf("Click one to take it back"))) { mine(p, 0) }
        s.set(22, item(Material.BARRIER, "Back")) { openMain(p) }
        s.open()
    }

    private fun listingEntry(p: Player, id: String, listing: EcoListing, rebuild: () -> Unit): Entry {
        val stack = BountyItems.decode(listing.item)?.clone() ?: ItemStack(Material.BARRIER)
        val meta = stack.itemMeta
        val lore = (meta?.lore ?: mutableListOf()).toMutableList()
        val own = listing.seller == p.uniqueId.toString()
        val seller = runCatching { UUID.fromString(listing.seller) }.getOrNull()?.let { Nodes.getResidentFromUUID(it)?.name } ?: "unknown"
        lore.add("${ChatColor.GOLD}Price: ${Economy.show(listing.price)}")
        lore.add("${ChatColor.GRAY}Seller: $seller")
        lore.add(if (own) "${ChatColor.RED}Click to take back" else "${ChatColor.GREEN}Click to buy")
        meta?.lore = lore
        stack.itemMeta = meta
        return Entry(stack) {
            act(p, rebuild) {
                if (own) EconomyExtras.marketCancel(p, id, p.hasPermission(ADMIN)) else EconomyExtras.marketBuy(p, id)
            }
        }
    }

    private fun players(p: Player, page: Int) {
        val entries = EconomyExtras.listings().map { (id, l) -> listingEntry(p, id, l) { players(p, page) } }
        paged(p, "Player listings", entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "No listings yet"))) }, page, { players(p, it) }, { market(p) })
    }

    private fun mine(p: Player, page: Int) {
        val own = p.uniqueId.toString()
        val entries = EconomyExtras.listings().filter { it.second.seller == own }.map { (id, l) -> listingEntry(p, id, l) { mine(p, page) } }
        paged(p, "My listings", entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "You have no listings"))) }, page, { mine(p, it) }, { market(p) })
    }

    // the server exchange: buy mode takes items out of the server's stock, sell mode adds to it
    private fun shop(p: Player, page: Int, sell: Boolean) {
        val trendText = mapOf(1 to "${ChatColor.GREEN}Price rising", -1 to "${ChatColor.RED}Price falling", 0 to "Price steady")
        val entries = EconomyExtras.shopEntries().filter { if (sell) it.sellPrice != null else it.buyPrice != null || it.stock != null }.map { entry ->
            val material = entry.material
            val lore = mutableListOf<String>()
            if (entry.stock != null) {
                lore.add("Server stock: ${entry.stock} of ${entry.target}")
                lore.add(trendText[entry.trend] ?: "")
            }
            val price = if (sell) entry.sellPrice else entry.buyPrice
            if (price == null) {
                lore.add("${ChatColor.RED}${if (sell) "The server holds all it wants" else "Sold out"}")
            } else if (sell) {
                lore.addAll(listOf("${ChatColor.GOLD}Pays ${Economy.show(price)} each (next unit)", "${ChatColor.GREEN}Left-click: sell 1", "${ChatColor.GREEN}Right-click: sell 16", "${ChatColor.GREEN}Shift-click: sell 64"))
            } else {
                lore.addAll(listOf("${ChatColor.GOLD}${Economy.show(price)} each (next unit)", "${ChatColor.GREEN}Left-click: buy 1", "${ChatColor.GREEN}Right-click: buy 16", "${ChatColor.GREEN}Shift-click: buy 64"))
            }
            Entry(item(material, "${ChatColor.WHITE}${material.name}", lore)) { e ->
                val amount = when (e.click) {
                    ClickType.RIGHT -> 16
                    ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT -> 64
                    else -> 1
                }
                act(p, { shop(p, page, sell) }) {
                    if (sell) EconomyExtras.sellItem(p, material.name, amount.toString()) else EconomyExtras.shopBuy(p, material.name, amount.toString())
                }
            }
        }
        val toggle = if (sell) {
            item(Material.EMERALD, "${ChatColor.GREEN}Mode: SELL to the server", listOf("Click to switch to buying"))
        } else {
            item(Material.GOLD_INGOT, "${ChatColor.GOLD}Mode: BUY from the server", listOf("Click to switch to selling"))
        }
        val restock = if (Exchange.enabled) listOf("Next restock in ${Exchange.minutesToRestock(System.currentTimeMillis())} min") else emptyList()
        val extras = mapOf(
            47 to Entry(toggle) { shop(p, 0, !sell) },
            51 to Entry(item(Material.CLOCK, "${ChatColor.YELLOW}How it works", listOf("Prices rise when the server's stock is low", "and fall when it is high.", "Stock refills over time.") + restock)),
        )
        val title = if (sell) "Sell to server" else "Server exchange"
        paged(p, title, entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "Nothing here yet"))) }, page, { shop(p, it, sell) }, { market(p) }, extras)
    }

    // ===================================
    // leaderboards
    // ===================================
    private fun top(p: Player) {
        val s = Screen(p, "Richest", 3)
        s.set(11, item(Material.PLAYER_HEAD, "${ChatColor.AQUA}Players")) { topList(p, "Richest players", EconomyExtras.topPlayers(45)) }
        s.set(13, item(Material.WHITE_BANNER, "${ChatColor.AQUA}Nations")) { topList(p, "Richest nations", EconomyExtras.topNations(45)) }
        s.set(15, item(Material.OAK_DOOR, "${ChatColor.AQUA}Towns")) { topList(p, "Richest towns", EconomyExtras.topTowns(45)) }
        s.set(22, item(Material.BARRIER, "Back")) { openMain(p) }
        s.open()
    }

    private fun topList(p: Player, title: String, lines: List<String>) {
        val entries = lines.map { Entry(item(Material.GOLD_INGOT, "${ChatColor.WHITE}${it.substringBeforeLast(':')}", listOf(it.substringAfterLast(": ")))) }
        paged(p, title, entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "Nothing to rank yet"))) }, 0, {}, { top(p) })
    }

    // ===================================
    // admin menu (/economyadmin)
    // ===================================
    fun openAdmin(p: Player) {
        if (!p.hasPermission(ADMIN)) {
            Message.error(p, "You do not have permission")
            return
        }
        val back = { openAdmin(p) }
        val s = Screen(p, "Economy admin", 3)
        s.set(10, item(Material.PAPER, "${ChatColor.GOLD}Loan requests", listOf("Left-click approves, right-click rejects"))) { adminLoans(p) }
        s.set(11, item(Material.GOLD_BLOCK, "${ChatColor.GOLD}Issue currency", listOf("To a player or the reserve"))) {
            step(p, "Type a player name, or reserve", back) { who ->
                ask(p, "Type the amount to issue to $who", back) { amount -> Economy.issue(who, amount, p.name) }
            }
        }
        s.set(12, item(Material.CHEST, "${ChatColor.GOLD}Issue trade resources", listOf("Give a nation resource stock"))) {
            choose(p, "Nation", nationOptions(), { Material.WHITE_BANNER }, back) { nationName ->
                choose(p, "Resource", Economy.tradeResources().sorted(), { Material.matchMaterial(it) ?: Material.CHEST }, back) { material ->
                    ask(p, "Type the quantity", back) { qty -> Economy.issueStock(nationName, material, qty, p.name) }
                }
            }
        }
        s.set(13, item(Material.IRON_BLOCK, "${ChatColor.GOLD}Register infrastructure", listOf("Railway, port, road or power"))) {
            choose(p, "Nation", nationOptions(), { Material.WHITE_BANNER }, back) { nationName ->
                choose(p, "Type", Economy.INFRA_TYPES, { Material.IRON_BLOCK }, back) { type ->
                    step(p, "Type the number of units", back) { units ->
                        ask(p, "Type a name for it (letters, numbers, - or _)", back) { name -> Economy.registerInfra(nationName, type, units, name) }
                    }
                }
            }
        }
        s.set(14, item(Material.TNT, "${ChatColor.GOLD}Retire infrastructure", listOf("Once its arrears are paid"))) {
            ask(p, "Type the infrastructure name", back) { Economy.retireInfra(it) }
        }
        if (Exchange.enabled) {
            s.set(15, item(Material.CLOCK, "${ChatColor.GOLD}Reset exchange stock", listOf("Put every item back at its target stock"))) {
                act(p, back) {
                    Economy.transaction { Exchange.reset() }
                    "Exchange stock reset to target"
                }
            }
        }
        s.set(22, item(Material.BARRIER, "Back")) { openMain(p) }
        s.open()
    }

    private fun adminLoans(p: Player) {
        val entries = EconomyExtras.pendingLoans().map { (id, who, owed) ->
            val lore = listOf("Borrower: $who", "Amount: ${Economy.show(owed)}", "${ChatColor.GREEN}Left-click: approve", "${ChatColor.RED}Right-click: reject")
            Entry(item(Material.PAPER, "${ChatColor.WHITE}Loan $id", lore)) { e ->
                act(p, { adminLoans(p) }) {
                    if (e.click == ClickType.RIGHT) Economy.loanReject(id) else Economy.loanApprove(id)
                }
            }
        }
        paged(p, "Loan requests", entries.ifEmpty { listOf(Entry(item(Material.BARRIER, "No pending requests"))) }, 0, {}, { openAdmin(p) })
    }
}

class EconomyMenuListener : Listener {
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is GuiHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? GuiHolder ?: return
        event.isCancelled = true
        if (event.rawSlot < 0 || event.rawSlot >= event.view.topInventory.size) {
            return
        }
        val action = holder.actions[event.rawSlot] ?: return
        try {
            action(event)
        } catch (e: Exception) {
            Nodes.logger?.severe("[Economy] Menu click failed: $e")
        }
    }

    // typed answers to a menu prompt are taken before chat sees them
    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val onText = ChatPrompt.take(event.player.uniqueId) ?: return
        event.isCancelled = true
        val text = event.message.trim()
        Bukkit.getScheduler().runTask(Nodes.plugin!!, Runnable { onText(text) })
    }
}
