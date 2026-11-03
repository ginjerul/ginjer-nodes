/*
 * Ore bounties: players fund bounties on other players with real items from
 * their inventory. Whoever kills the target claims the ores, which go to the
 * hunter's town income or the hunter's inventory.
 */

package phonon.nodes.bounty

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.economy.Economy
import phonon.nodes.economy.EconomyException
import phonon.nodes.economy.EconomyExtras
import phonon.nodes.objects.Town
import phonon.nodes.vassal.Vassals
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

enum class BountyState { ACTIVE, CLAIMED, REFUNDED }

class Bounty(
    val id: String,
    val sponsor: UUID,
    val target: UUID,
    val targetName: String,
    val items: List<String>,
    val summary: String,
    val reason: String,
    val created: Long,
    var state: BountyState,
)

// a claimed reward waiting to be handed over
class BountyDelivery(
    val id: String,
    val bounty: String,
    var recipient: UUID,
    var toTown: Boolean,
    val items: List<String>,
    val summary: String,
)

object Bounties {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/bounties.json")

    // ===================================
    // config (bounties: section of config.yml)
    // ===================================
    // stays off until the bounties: section exists in config.yml
    var enabled = false
    var rewardToTown = true
    var townlessFallback = true
    var maxStacks = 36
    var maxContributions = 100
    val allowed: MutableSet<Material> = EnumSet.noneOf(Material::class.java)
    val packages: LinkedHashMap<String, List<Pair<Material, Int>>> = linkedMapOf()
    private var minimumAgeMs = 60_000L
    private var placementCooldownMs = 30_000L
    private var claimCooldownMs = 3_600_000L
    private var accountAgeMs = 3_600_000L
    private var blockSameTown = true
    private var blockSameNation = true
    private var blockAllies = true
    private var blockSameIp = true
    private var disabledWorlds: List<String> = listOf()
    private var broadcastPlacement = true
    private var broadcastClaims = true

    // ===================================
    // saved state
    // ===================================
    private val bounties: MutableList<Bounty> = mutableListOf()
    private val deliveries: MutableList<BountyDelivery> = mutableListOf()
    private val lastFunding: HashMap<UUID, Long> = hashMapOf()
    private val claims: HashMap<String, Long> = hashMapOf()

    fun loadConfig(config: FileConfiguration) {
        val sec = config.getConfigurationSection("bounties")
        if (sec == null) {
            enabled = false
            return
        }
        enabled = sec.getBoolean("enabled", true)
        rewardToTown = !sec.getString("rewardDestination", "TOWN_INCOME")!!.equals("PLAYER", ignoreCase = true)
        townlessFallback = sec.getBoolean("townlessPlayerFallback", true)
        maxStacks = sec.getInt("maxStacksPerBounty", 36).coerceIn(1, 216)
        maxContributions = sec.getInt("maxContributionsPerTarget", 100).coerceIn(1, 1000)
        minimumAgeMs = seconds(sec.getLong("minimumAgeSeconds", 60L))
        placementCooldownMs = seconds(sec.getLong("placementCooldownSeconds", 30L))
        claimCooldownMs = seconds(sec.getLong("claimCooldownSeconds", 3600L))
        accountAgeMs = seconds(sec.getLong("minimumAccountAgeSeconds", 3600L))
        blockSameTown = sec.getBoolean("blockSameTown", true)
        blockSameNation = sec.getBoolean("blockSameNation", true)
        blockAllies = sec.getBoolean("blockAllies", true)
        blockSameIp = sec.getBoolean("blockSameIp", true)
        disabledWorlds = sec.getStringList("disabledWorlds")
        broadcastPlacement = sec.getBoolean("broadcastPlacement", true)
        broadcastClaims = sec.getBoolean("broadcastClaims", true)

        allowed.clear()
        for (name in sec.getStringList("allowedMaterials")) {
            val material = Material.matchMaterial(name)
            if (material == null || !material.isItem || material.isAir) {
                Nodes.logger?.warning("[Bounties] Ignoring invalid allowed material: $name")
                continue
            }
            allowed.add(material)
        }

        packages.clear()
        val pkgSec = sec.getConfigurationSection("packages")
        if (pkgSec != null) {
            for (id in pkgSec.getKeys(false)) {
                val specs = mutableListOf<Pair<Material, Int>>()
                for (spec in pkgSec.getStringList(id)) {
                    val parts = spec.trim().split(":")
                    val material = Material.matchMaterial(parts[0])
                    val amount = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size != 2 || material == null || amount == null || amount < 1 || material !in allowed) {
                        Nodes.logger?.warning("[Bounties] Ignoring invalid package item '$spec' in package $id")
                        continue
                    }
                    specs.add(Pair(material, amount))
                }
                if (specs.isNotEmpty()) {
                    packages[id.lowercase()] = specs
                }
            }
        }
    }

    private fun seconds(value: Long): Long = value.coerceIn(0L, 31_536_000L) * 1000L

    fun packageItems(id: String): List<ItemStack>? {
        val specs = packages[id.lowercase()] ?: return null
        val items = specs.flatMap { BountyItems.stacks(it.first, it.second) }
        return if (items.size > maxStacks) null else items
    }

    // ===================================
    // persistence
    // ===================================
    fun load() {
        bounties.clear()
        deliveries.clear()
        lastFunding.clear()
        claims.clear()
        if (!Files.exists(path)) {
            return
        }
        try {
            val root = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            root.getAsJsonArray("bounties")?.forEach { el ->
                val o = el.asJsonObject
                bounties.add(
                    Bounty(
                        id = o.get("id").asString,
                        sponsor = UUID.fromString(o.get("sponsor").asString),
                        target = UUID.fromString(o.get("target").asString),
                        targetName = o.get("targetName").asString,
                        items = o.getAsJsonArray("items").map { it.asString },
                        summary = o.get("summary").asString,
                        reason = o.get("reason").asString,
                        created = o.get("created").asLong,
                        state = BountyState.valueOf(o.get("state").asString),
                    ),
                )
            }
            root.getAsJsonArray("deliveries")?.forEach { el ->
                val o = el.asJsonObject
                deliveries.add(
                    BountyDelivery(
                        id = o.get("id").asString,
                        bounty = o.get("bounty").asString,
                        recipient = UUID.fromString(o.get("recipient").asString),
                        toTown = o.get("toTown").asBoolean,
                        items = o.getAsJsonArray("items").map { it.asString },
                        summary = o.get("summary").asString,
                    ),
                )
            }
            root.getAsJsonObject("lastFunding")?.entrySet()?.forEach { (k, v) ->
                lastFunding[UUID.fromString(k)] = v.asLong
            }
            root.getAsJsonObject("claims")?.entrySet()?.forEach { (k, v) ->
                claims[k] = v.asLong
            }
            Nodes.logger?.info("[Bounties] Loaded ${bounties.size} bounties, ${deliveries.size} queued deliveries")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Bounties] load failed: ${e.message}")
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val bountyArr = JsonArray()
            for (b in bounties) {
                val o = JsonObject()
                o.addProperty("id", b.id)
                o.addProperty("sponsor", b.sponsor.toString())
                o.addProperty("target", b.target.toString())
                o.addProperty("targetName", b.targetName)
                o.add("items", stringArray(b.items))
                o.addProperty("summary", b.summary)
                o.addProperty("reason", b.reason)
                o.addProperty("created", b.created)
                o.addProperty("state", b.state.name)
                bountyArr.add(o)
            }
            root.add("bounties", bountyArr)
            val deliveryArr = JsonArray()
            for (d in deliveries) {
                val o = JsonObject()
                o.addProperty("id", d.id)
                o.addProperty("bounty", d.bounty)
                o.addProperty("recipient", d.recipient.toString())
                o.addProperty("toTown", d.toTown)
                o.add("items", stringArray(d.items))
                o.addProperty("summary", d.summary)
                deliveryArr.add(o)
            }
            root.add("deliveries", deliveryArr)
            val funding = JsonObject()
            lastFunding.forEach { (k, v) -> funding.addProperty(k.toString(), v) }
            root.add("lastFunding", funding)
            val claimObj = JsonObject()
            claims.forEach { (k, v) -> claimObj.addProperty(k, v) }
            root.add("claims", claimObj)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Bounties] save failed: ${e.message}")
        }
    }

    private fun stringArray(values: List<String>): JsonArray {
        val arr = JsonArray()
        values.forEach { arr.add(it) }
        return arr
    }

    // ===================================
    // helpers
    // ===================================
    private fun newId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().substring(0, 8)
            if (bounties.none { it.id == id } && deliveries.none { it.id == id }) {
                return id
            }
        }
    }

    private fun isPlaying(player: Player): Boolean = player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE

    private fun oldEnough(player: OfflinePlayer, now: Long): Boolean = player.firstPlayed > 0 && now - player.firstPlayed >= accountAgeMs

    private fun townByUuid(uuid: UUID): Town? = Nodes.towns.values.firstOrNull { it.uuid == uuid }

    fun active(target: UUID): List<Bounty> = bounties
        .filter { it.target == target && it.state == BountyState.ACTIVE }
        .sortedByDescending { it.created }

    // targets with active bounties, most contributions first
    fun targets(): List<Pair<Bounty, Int>> = bounties
        .filter { it.state == BountyState.ACTIVE }
        .groupBy { it.target }
        .map { (_, list) -> Pair(list.first(), list.size) }
        .sortedByDescending { it.second }

    fun queuedFor(player: UUID): List<BountyDelivery> = deliveries.filter { !it.toTown && it.recipient == player }

    fun queuedCount(): Int = deliveries.size

    fun queued(): List<BountyDelivery> = deliveries.toList()

    // a reason a hunter and target may not collect from each other, or null
    private fun relation(a: UUID, b: UUID): String? {
        val ra = Nodes.getResidentFromUUID(a) ?: return "Nodes has not loaded a player yet"
        val rb = Nodes.getResidentFromUUID(b) ?: return "Nodes has not loaded a player yet"
        val townA = ra.town
        val townB = rb.town
        val nationA = ra.nation
        val nationB = rb.nation
        if (blockSameTown && townA != null && townA === townB) {
            return "same town"
        }
        if (blockSameNation && nationA != null && nationA === nationB) {
            return "same nation"
        }
        if (townA != null && townB != null && Vassals.isBound(townA, townB)) {
            return "vassal and overlord"
        }
        if (blockAllies) {
            val alliedTowns = townA != null && townB != null && (townA.allies.contains(townB) || townB.allies.contains(townA))
            val alliedNations = nationA != null && nationB != null && (nationA.allies.contains(nationB) || nationB.allies.contains(nationA))
            if (alliedTowns || alliedNations) {
                return "allied towns/nations"
            }
        }
        return null
    }

    // ===================================
    // funding
    // ===================================
    // returns an error message, or null when the bounty was placed
    // rules shared by ore and money bounties; an error message, or null when allowed
    private fun preCheck(player: Player, target: OfflinePlayer): String? {
        if (!enabled) {
            return "Bounties are disabled"
        }
        if (player.uniqueId == target.uniqueId) {
            return "You cannot fund a bounty on yourself"
        }
        if (!isPlaying(player)) {
            return "Use survival/adventure mode to fund bounties"
        }
        val now = System.currentTimeMillis()
        if (!oldEnough(player, now) || !oldEnough(target, now)) {
            return "Both players must meet the minimum server account age"
        }
        relation(player.uniqueId, target.uniqueId)?.let { return "Bounty placement blocked: $it" }
        if (now - (lastFunding[player.uniqueId] ?: 0L) < placementCooldownMs) {
            return "Please wait before placing another bounty"
        }
        return null
    }

    // returns an error message, or null when the money bounty was placed.
    // The money moves from the funder's wallet into escrow in the economy ledger.
    fun fundMoney(player: Player, target: OfflinePlayer, targetName: String, amount: String, reason: String): String? {
        preCheck(player, target)?.let { return it }
        if (!Economy.enabled) {
            return "The economy is disabled"
        }
        try {
            val cents = Economy.parseMoney(amount)
            val id = EconomyExtras.fundMoney(player, target, targetName, cents, reason)
            lastFunding[player.uniqueId] = System.currentTimeMillis()
            save()
            val text = "${player.name} funded a money bounty on $targetName: ${Economy.show(cents)}"
            if (broadcastPlacement) {
                Message.broadcast("${ChatColor.GOLD}[Bounty] ${ChatColor.WHITE}$text")
            } else {
                Message.print(player, text)
            }
            Message.print(player, "Contribution ID: $id")
            return null
        } catch (e: EconomyException) {
            return e.message ?: "Economy error"
        }
    }

    fun fund(player: Player, target: OfflinePlayer, targetName: String, items: List<ItemStack>, reason: String): String? {
        preCheck(player, target)?.let { return it }
        val now = System.currentTimeMillis()
        if (items.isEmpty()) {
            return "No items to fund"
        }
        items.firstOrNull { it.type !in allowed }?.let { return "${it.type} is not an allowed ore reward. See /bounty materials" }
        if (items.size > maxStacks) {
            return "Reward exceeds the stack limit of $maxStacks"
        }
        if (bounties.count { it.target == target.uniqueId && it.state == BountyState.ACTIVE } >= maxContributions) {
            return "This target has reached the active contribution limit"
        }
        val encoded = mutableListOf<String>()
        for (item in items) {
            encoded.add(BountyItems.encode(item) ?: return "Could not store those items")
        }

        val remaining = BountyItems.remove(player.inventory.storageContents, items)
            ?: return "You do not hold enough matching reward items. Named/enchanted items must match exactly"
        player.inventory.storageContents = remaining
        player.saveData()

        val bounty = Bounty(
            id = newId(),
            sponsor = player.uniqueId,
            target = target.uniqueId,
            targetName = targetName,
            items = encoded,
            summary = BountyItems.summary(items),
            reason = reason,
            created = now,
            state = BountyState.ACTIVE,
        )
        bounties.add(bounty)
        lastFunding[player.uniqueId] = now
        save()

        val text = "${player.name} funded an ore bounty on $targetName: ${bounty.summary}"
        if (broadcastPlacement) {
            Message.broadcast("${ChatColor.GOLD}[Bounty] ${ChatColor.WHITE}$text")
        } else {
            Message.print(player, text)
        }
        Message.print(player, "Contribution ID: ${bounty.id}")
        return null
    }

    // ===================================
    // claiming
    // ===================================
    fun handleKill(hunter: Player, victim: Player) {
        if (!enabled || hunter.uniqueId == victim.uniqueId) {
            return
        }
        if (!hunter.hasPermission("nodes.bounty.hunt")) {
            return
        }
        if (!isPlaying(hunter) || !isPlaying(victim) || victim.world.name in disabledWorlds) {
            return
        }
        val activeBounties = active(victim.uniqueId)
        val hasMoney = Economy.enabled && EconomyExtras.activeMoney(victim.uniqueId).isNotEmpty()
        if (activeBounties.isEmpty() && !hasMoney) {
            return
        }
        val now = System.currentTimeMillis()
        if (!oldEnough(hunter, now) || !oldEnough(victim, now)) {
            return
        }
        if (blockSameIp) {
            val hunterIp = hunter.address?.address
            val victimIp = victim.address?.address
            if (hunterIp == null || victimIp == null) {
                return
            }
            if (hunterIp == victimIp) {
                Message.error(hunter, "Bounty claim blocked: same IP address")
                return
            }
        }
        relation(hunter.uniqueId, victim.uniqueId)?.let {
            Message.error(hunter, "Bounty claim blocked: $it")
            return
        }

        val town = Nodes.getResident(hunter)?.town
        val toTown = rewardToTown && town != null
        val itemsBlocked = rewardToTown && town == null && !townlessFallback
        if (itemsBlocked && activeBounties.isNotEmpty()) {
            Message.error(hunter, "Bounty rewards go to a town. Join a town to claim; the bounty stays active")
            if (!hasMoney) {
                return
            }
        }

        val claimKey = "${hunter.uniqueId}:${victim.uniqueId}"
        val last = claims[claimKey] ?: 0L
        if (last > 0 && now - last < claimCooldownMs) {
            Message.error(hunter, "You claimed a bounty on ${victim.name} too recently")
            return
        }
        val eligible = if (itemsBlocked) {
            emptyList()
        } else {
            activeBounties.filter { it.sponsor != hunter.uniqueId && it.created <= now - minimumAgeMs }
        }
        val moneyEligible = if (hasMoney) EconomyExtras.eligibleMoney(victim.uniqueId, hunter.uniqueId, now - minimumAgeMs) else emptyList()
        if (eligible.isEmpty() && moneyEligible.isEmpty()) {
            if (!itemsBlocked) {
                Message.error(hunter, "No eligible bounty rewards: still maturing, or you funded them yourself")
            }
            return
        }

        var anyToTown = false
        for (bounty in eligible) {
            val decoded = bounty.items.mapNotNull { BountyItems.decode(it) }
            // town income only stores plain materials, so custom items go to the hunter instead
            val thisToTown = toTown && town != null && decoded.none { it.hasItemMeta() }
            anyToTown = anyToTown || thisToTown
            deliveries.add(
                BountyDelivery(
                    id = newId(),
                    bounty = bounty.id,
                    recipient = if (thisToTown) town!!.uuid else hunter.uniqueId,
                    toTown = thisToTown,
                    items = bounty.items,
                    summary = bounty.summary,
                ),
            )
            bounty.state = BountyState.CLAIMED
        }
        val moneyPaid = try {
            EconomyExtras.claimMoney(moneyEligible, hunter)
        } catch (e: EconomyException) {
            Message.error(hunter, "Money reward failed: ${e.message}")
            0L
        }
        claims[claimKey] = now
        save()

        val parts = mutableListOf<String>()
        if (eligible.isNotEmpty()) {
            parts.add("${eligible.size} ore reward package(s)")
        }
        if (moneyPaid > 0) {
            parts.add("${Economy.show(moneyPaid)} in money")
        }
        if (parts.isEmpty()) {
            return
        }
        val text = "${hunter.name} claimed ${parts.joinToString(" and ")} for defeating ${victim.name}"
        if (broadcastClaims) {
            Message.broadcast("${ChatColor.GOLD}[Bounty] ${ChatColor.WHITE}$text")
        } else {
            Message.print(hunter, text)
        }
        deliverPending(null)
        if (anyToTown) {
            Message.print(hunter, "Ores go to your town income. Reopen /t income to view them")
        }
        if (eligible.size > 0 && deliveries.any { !it.toTown && it.recipient == hunter.uniqueId }) {
            Message.print(hunter, "Some rewards go to your inventory. If it is full, make space and use /bounty collect")
        }
    }

    // ===================================
    // delivery
    // ===================================
    // hands over queued rewards; only the given player's personal deliveries when player is set
    fun deliverPending(only: Player?) {
        var processed = 0
        var townDelivered = false
        val finished = mutableListOf<BountyDelivery>()
        for (d in deliveries.toList()) {
            if (processed >= 20) {
                break
            }
            val items = d.items.mapNotNull { BountyItems.decode(it) }
            if (items.size != d.items.size || items.isEmpty()) {
                Nodes.logger?.warning("[Bounties] Delivery ${d.id} has unreadable items and stays queued for staff")
                continue
            }
            if (d.toTown) {
                if (only != null) {
                    continue
                }
                val town = townByUuid(d.recipient) ?: continue
                if (items.any { it.hasItemMeta() }) {
                    continue
                }
                val totals = EnumMap<Material, Long>(Material::class.java)
                for (item in items) {
                    totals[item.type] = (totals[item.type] ?: 0L) + item.amount
                }
                val overflow = totals.entries.any { (town.income.storage[it.key] ?: 0).toLong() + it.value > Int.MAX_VALUE }
                if (overflow) {
                    continue
                }
                for (item in items) {
                    town.income.add(item.type, item.amount, 0)
                }
                town.needsUpdate()
                Nodes.needsSave = true
                townDelivered = true
                finished.add(d)
                processed++
            } else {
                val player = Bukkit.getPlayer(d.recipient) ?: continue
                if (only != null && only.uniqueId != player.uniqueId) {
                    continue
                }
                val inventory = BountyItems.add(player.inventory.storageContents, items, player.inventory.maxStackSize) ?: continue
                player.inventory.storageContents = inventory
                player.saveData()
                Message.print(player, "Received bounty ores: ${d.summary}")
                finished.add(d)
                processed++
            }
        }
        if (finished.isEmpty()) {
            return
        }
        deliveries.removeAll(finished.toSet())
        // town income must hit disk with the delivery record, or a crash could lose the ores
        if (townDelivered) {
            Nodes.saveWorld(checkIfNeedsSave = false, async = false)
        }
        save()
    }

    // ===================================
    // admin
    // ===================================
    fun refund(id: String): String? {
        val bounty = bounties.firstOrNull { it.id == id && it.state == BountyState.ACTIVE }
            ?: return "No active contribution with that ID"
        deliveries.add(
            BountyDelivery(
                id = newId(),
                bounty = bounty.id,
                recipient = bounty.sponsor,
                toTown = false,
                items = bounty.items,
                summary = bounty.summary,
            ),
        )
        bounty.state = BountyState.REFUNDED
        save()
        deliverPending(null)
        return null
    }

    // redirects a still-queued claimed reward back to the funder (e.g. its town was deleted)
    fun returnQueued(id: String): String? {
        val delivery = deliveries.firstOrNull { it.id == id } ?: return "No queued delivery with that ID"
        val bounty = bounties.firstOrNull { it.id == delivery.bounty && it.state == BountyState.CLAIMED }
            ?: return "That delivery is already a refund"
        delivery.recipient = bounty.sponsor
        delivery.toTown = false
        bounty.state = BountyState.REFUNDED
        save()
        deliverPending(null)
        return null
    }
}
