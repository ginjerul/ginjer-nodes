/*
 * Duels: one player challenges another, both are taken to an admin-set arena, fight with a
 * countdown and a time limit, and are put back exactly as they were. Nobody really dies: a
 * killing blow ends the duel instead. Optional kits (everyone gets the same loadout) and money
 * wagers (held in escrow and paid to the winner). Other players can watch with /duel spectate.
 */

package phonon.nodes.duel

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.economy.Economy
import phonon.nodes.economy.EconomyException
import java.util.UUID

class Duel(
    val id: String,
    val arena: Arena,
    val first: UUID,
    val second: UUID,
    val kit: Kit?,
    val wager: Long,
) {
    var counting = true
    var secondsLeft = 0
    val spectators = HashSet<UUID>()
    val effects = HashMap<UUID, Collection<PotionEffect>>()

    fun has(id: UUID): Boolean = id == first || id == second

    fun opponent(id: UUID): UUID = if (id == first) second else first
}

private class Request(
    val from: UUID,
    val kit: Kit?,
    val wager: Long,
    val expires: Long,
)

object Duels {
    internal const val ESCROW = "duel-escrow"

    var enabled = true
    private var requestSeconds = 60
    private var countdownSeconds = 5
    private var maxSeconds = 300
    private var allowWager = true
    private var maxWager = 0L
    private var kitOnly = false
    private var defaultKit = ""
    private var announce = true
    private var allowedCommands: Set<String> = setOf("duel")

    private val requests = HashMap<UUID, Request>()
    internal val duels = HashMap<UUID, Duel>()
    internal val watching = HashMap<UUID, Duel>()
    internal val moving = HashSet<UUID>()
    private var task: BukkitTask? = null

    fun loadConfig(config: ConfigurationSection) {
        val sec = config.getConfigurationSection("duel")
        enabled = sec?.getBoolean("enabled", true) ?: true
        requestSeconds = (sec?.getInt("requestSeconds", 60) ?: 60).coerceIn(5, 600)
        countdownSeconds = (sec?.getInt("countdownSeconds", 5) ?: 5).coerceIn(1, 30)
        maxSeconds = (sec?.getInt("maxSeconds", 300) ?: 300).coerceIn(30, 3600)
        allowWager = sec?.getBoolean("allowWager", true) ?: true
        maxWager = Economy.optionalMoney(sec?.get("maxWager"), "duel.maxWager")
        kitOnly = sec?.getBoolean("kitOnly", false) ?: false
        defaultKit = sec?.getString("defaultKit", "") ?: ""
        announce = sec?.getBoolean("announce", true) ?: true
        val commands = sec?.getStringList("allowedCommands") ?: emptyList()
        allowedCommands = (commands + "duel").map { it.lowercase().removePrefix("/") }.toSet()
    }

    fun isAllowed(command: String): Boolean = allowedCommands.contains(command)

    fun start(plugin: Plugin) {
        task?.cancel()
        DuelData.load()
        refundLeftovers()
        val listener = DuelListener(plugin)
        plugin.server.pluginManager.registerEvents(listener, plugin)
        listener.registerXc()
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    // players mid-duel on shutdown are returned, wagers go back to the wallets
    fun shutdown() {
        task?.cancel()
        task = null
        for (duel in duels.values.toSet()) {
            end(duel, null, "The server is restarting")
        }
        for (uuid in watching.keys.toList()) {
            watching.remove(uuid)
            restore(uuid, null)
        }
    }

    private fun refundLeftovers() {
        for ((id, escrow) in DuelData.escrow().toMap()) {
            try {
                Economy.transaction {
                    Economy.move(ESCROW, "wallet-${escrow.first}", escrow.cents, "duel refund")
                    Economy.move(ESCROW, "wallet-${escrow.second}", escrow.cents, "duel refund")
                }
                DuelData.clearEscrow(id)
            } catch (e: Exception) {
                Nodes.logger?.severe("[Duels] Could not refund wager $id: ${e.message}")
            }
        }
    }

    fun busy(player: Player): Boolean = duels.containsKey(player.uniqueId) || watching.containsKey(player.uniqueId)

    // ===================================
    // challenge, accept, deny
    // ===================================
    fun challenge(from: Player, to: Player, kitName: String?, wagerText: String?) {
        if (!enabled) {
            Message.error(from, "Duels are turned off")
            return
        }
        if (from.uniqueId == to.uniqueId) {
            Message.error(from, "You cannot duel yourself")
            return
        }
        if (busy(from)) {
            Message.error(from, "You are already in a duel or watching one. Use /duel leave")
            return
        }
        if (busy(to)) {
            Message.error(from, "${to.name} is busy in a duel")
            return
        }
        val kit = pickKit(kitName)
        if (kitName != null && kit == null) {
            Message.error(from, "No kit called $kitName. Kits: ${kitNames()}")
            return
        }
        if (kit == null && kitOnly) {
            Message.error(from, "Pick a kit: /duel <player> <kit>. Kits: ${kitNames()}")
            return
        }
        var wager = 0L
        if (wagerText != null) {
            if (!allowWager) {
                Message.error(from, "Wagers are turned off")
                return
            }
            wager = try {
                Economy.parseMoney(wagerText)
            } catch (e: EconomyException) {
                Message.error(from, e.message ?: "Invalid amount")
                return
            }
            if (maxWager > 0 && wager > maxWager) {
                Message.error(from, "The most you can wager is ${Economy.show(maxWager)}")
                return
            }
            if (Economy.balance("wallet-${from.uniqueId}") < wager) {
                Message.error(from, "You do not have ${Economy.show(wager)} in your wallet")
                return
            }
        }
        if (freeArena() == null) {
            Message.error(from, "There is no free arena right now")
            return
        }
        requests[to.uniqueId] = Request(from.uniqueId, kit, wager, System.currentTimeMillis() + requestSeconds * 1000L)
        val kitText = if (kit != null) "kit ${kit.name}" else "your own gear"
        val wagerLine = if (wager > 0) ", wager ${Economy.show(wager)} each" else ""
        Message.print(from, "${ChatColor.GREEN}Challenged ${to.name} ($kitText$wagerLine). It expires in $requestSeconds seconds")
        Message.print(to, "${ChatColor.GOLD}${from.name} challenges you to a duel ${ChatColor.GRAY}($kitText$wagerLine)")
        Message.print(to, "${ChatColor.GRAY}Type ${ChatColor.WHITE}/duel accept ${from.name}${ChatColor.GRAY} or ${ChatColor.WHITE}/duel deny")
    }

    fun accept(player: Player, fromName: String?) {
        val req = requests[player.uniqueId]
        if (req == null) {
            Message.error(player, "Nobody has challenged you")
            return
        }
        val from = Bukkit.getPlayer(req.from)
        if (from == null || (fromName != null && !from.name.equals(fromName, ignoreCase = true))) {
            Message.error(player, "That challenge is not from ${fromName ?: "an online player"}")
            return
        }
        requests.remove(player.uniqueId)
        begin(from, player, req.kit, req.wager)
    }

    fun deny(player: Player) {
        val req = requests.remove(player.uniqueId)
        if (req == null) {
            Message.error(player, "Nobody has challenged you")
            return
        }
        Message.print(player, "${ChatColor.GRAY}Challenge declined")
        Bukkit.getPlayer(req.from)?.let { Message.print(it, "${ChatColor.GRAY}${player.name} declined your duel") }
    }

    fun cancel(player: Player) {
        val keys = requests.filterValues { it.from == player.uniqueId }.keys.toList()
        if (keys.isEmpty()) {
            Message.error(player, "You have no pending challenge")
            return
        }
        keys.forEach { requests.remove(it) }
        Message.print(player, "${ChatColor.GRAY}Challenge cancelled")
    }

    private fun pickKit(name: String?): Kit? {
        if (name != null) {
            return if (name.equals("own", ignoreCase = true)) null else DuelData.kit(name)
        }
        return if (defaultKit.isEmpty()) null else DuelData.kit(defaultKit)
    }

    fun kitNames(): String = DuelData.kits().joinToString(", ") { it.name }.ifEmpty { "none yet" }

    private fun freeArena(): Arena? {
        val used = duels.values.map { it.arena.name }.toSet()
        return DuelData.arenas().filter { it.ready() && !used.contains(it.name) && it.spawn1?.toLocation() != null }.randomOrNull()
    }

    // ===================================
    // starting and ending
    // ===================================
    private fun begin(a: Player, b: Player, kit: Kit?, wager: Long) {
        if (busy(a) || busy(b)) {
            Message.error(b, "One of you is already busy")
            return
        }
        val arena = freeArena()
        val spawn1 = arena?.spawn1?.toLocation()
        val spawn2 = arena?.spawn2?.toLocation()
        if (arena == null || spawn1 == null || spawn2 == null) {
            Message.error(b, "There is no free arena right now")
            Message.error(a, "There is no free arena right now")
            return
        }
        val id = UUID.randomUUID().toString().substring(0, 8)
        if (wager > 0) {
            try {
                Economy.transaction {
                    Economy.requireAvailable("wallet-${a.uniqueId}")
                    Economy.requireAvailable("wallet-${b.uniqueId}")
                    Economy.move("wallet-${a.uniqueId}", ESCROW, wager, "duel wager")
                    Economy.move("wallet-${b.uniqueId}", ESCROW, wager, "duel wager")
                }
            } catch (e: EconomyException) {
                val msg = "The wager failed: ${e.message}"
                Message.error(a, msg)
                Message.error(b, msg)
                return
            }
            DuelData.setEscrow(id, Escrow(a.uniqueId.toString(), b.uniqueId.toString(), wager))
        }
        val duel = Duel(id, arena, a.uniqueId, b.uniqueId, kit, wager)
        duel.secondsLeft = countdownSeconds
        duels[a.uniqueId] = duel
        duels[b.uniqueId] = duel
        prepare(duel, a, spawn1)
        prepare(duel, b, spawn2)
        val kitText = if (kit != null) "kit ${kit.name}" else "own gear"
        val wagerText = if (wager > 0) ", ${Economy.show(wager)} each" else ""
        Message.print(a, "${ChatColor.GOLD}Duel against ${b.name} ($kitText$wagerText) starts in $countdownSeconds seconds")
        Message.print(b, "${ChatColor.GOLD}Duel against ${a.name} ($kitText$wagerText) starts in $countdownSeconds seconds")
    }

    @Suppress("DEPRECATION")
    private fun prepare(duel: Duel, player: Player, spawn: org.bukkit.Location) {
        val loc = player.location
        val items = if (duel.kit != null) DuelData.snapshot(player) else null
        DuelData.setPending(
            player.uniqueId.toString(),
            Restore(loc.world?.name ?: "", loc.x, loc.y, loc.z, loc.yaw, loc.pitch, player.gameMode.name, player.health, player.foodLevel, items),
        )
        duel.effects[player.uniqueId] = player.activePotionEffects.toList()
        player.closeInventory()
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.gameMode = GameMode.SURVIVAL
        duel.kit?.let { DuelData.apply(player, it.items) }
        player.health = player.maxHealth
        player.foodLevel = 20
        player.saturation = 20f
        player.fireTicks = 0
        player.fallDistance = 0f
        teleport(player, spawn)
    }

    internal fun teleport(player: Player, to: org.bukkit.Location) {
        moving.add(player.uniqueId)
        try {
            player.teleport(to)
        } finally {
            moving.remove(player.uniqueId)
        }
    }

    // winner null = draw (time ran out, admin ended it, shutdown)
    fun end(duel: Duel, winner: UUID?, reason: String) {
        duels.remove(duel.first)
        duels.remove(duel.second)
        val nameOf = { id: UUID -> Bukkit.getOfflinePlayer(id).name ?: "?" }
        if (duel.wager > 0) {
            try {
                Economy.transaction {
                    if (winner != null) {
                        Economy.move(ESCROW, "wallet-$winner", duel.wager * 2, "duel win")
                    } else {
                        Economy.move(ESCROW, "wallet-${duel.first}", duel.wager, "duel refund")
                        Economy.move(ESCROW, "wallet-${duel.second}", duel.wager, "duel refund")
                    }
                }
                DuelData.clearEscrow(duel.id)
            } catch (e: Exception) {
                Nodes.logger?.severe("[Duels] Payout of duel ${duel.id} failed, it is refunded on next start: ${e.message}")
            }
        }
        val text: String
        if (winner != null) {
            val loser = duel.opponent(winner)
            DuelData.recordWin(winner.toString(), nameOf(winner))
            DuelData.recordLoss(loser.toString(), nameOf(loser))
            val prize = if (duel.wager > 0) " and wins ${Economy.show(duel.wager * 2)}" else ""
            text = "${ChatColor.GOLD}${nameOf(winner)} defeated ${nameOf(loser)} in a duel$prize ${ChatColor.GRAY}($reason)"
        } else {
            text = "${ChatColor.GRAY}The duel between ${nameOf(duel.first)} and ${nameOf(duel.second)} ended in a draw ($reason)"
        }
        if (announce) {
            Message.broadcast(text)
        } else {
            listOf(duel.first, duel.second).mapNotNull { Bukkit.getPlayer(it) }.forEach { Message.print(it, text) }
        }
        restore(duel.first, duel)
        restore(duel.second, duel)
        for (spectator in duel.spectators.toList()) {
            watching.remove(spectator)
            restore(spectator, null)
        }
    }

    // puts an online player back; offline players are restored when they join
    @Suppress("DEPRECATION")
    internal fun restore(uuid: UUID, duel: Duel?) {
        val saved = DuelData.pending(uuid.toString()) ?: return
        val player = Bukkit.getPlayer(uuid) ?: return
        player.closeInventory()
        saved.items?.let { DuelData.apply(player, it) }
        val world = Bukkit.getWorld(saved.world)
        val target = if (world != null) org.bukkit.Location(world, saved.x, saved.y, saved.z, saved.yaw, saved.pitch) else Bukkit.getWorlds()[0].spawnLocation
        teleport(player, target)
        player.gameMode = try {
            GameMode.valueOf(saved.gameMode)
        } catch (_: Exception) {
            GameMode.SURVIVAL
        }
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        duel?.effects?.get(uuid)?.forEach { player.addPotionEffect(it) }
        player.health = saved.health.coerceIn(1.0, player.maxHealth)
        player.foodLevel = saved.food
        player.fireTicks = 0
        player.fallDistance = 0f
        DuelData.clearPending(uuid.toString())
    }

    // ===================================
    // spectating
    // ===================================
    fun spectate(player: Player, targetName: String) {
        if (busy(player)) {
            Message.error(player, "You are already in a duel or watching one. Use /duel leave")
            return
        }
        val target = Bukkit.getPlayerExact(targetName)
        val duel = target?.let { duels[it.uniqueId] }
        if (duel == null) {
            Message.error(player, "$targetName is not in a duel")
            return
        }
        val point = duel.arena.spectate ?: duel.arena.spawn1
        val loc = point?.toLocation()
        if (loc == null) {
            Message.error(player, "That arena cannot be watched")
            return
        }
        val here = player.location
        DuelData.setPending(
            player.uniqueId.toString(),
            Restore(here.world?.name ?: "", here.x, here.y, here.z, here.yaw, here.pitch, player.gameMode.name, player.health, player.foodLevel, null),
        )
        duel.spectators.add(player.uniqueId)
        watching[player.uniqueId] = duel
        teleport(player, loc)
        player.gameMode = GameMode.SPECTATOR
        Message.print(player, "${ChatColor.GREEN}Watching the duel. Type /duel leave to go back")
    }

    fun leave(player: Player) {
        val duel = duels[player.uniqueId]
        if (duel != null) {
            val winner = duel.opponent(player.uniqueId)
            end(duel, winner, "${player.name} forfeited")
            return
        }
        val watched = watching.remove(player.uniqueId)
        if (watched == null) {
            Message.error(player, "You are not in a duel")
            return
        }
        watched.spectators.remove(player.uniqueId)
        restore(player.uniqueId, null)
    }

    // ===================================
    // the clock, once a second
    // ===================================
    private fun tick() {
        val now = System.currentTimeMillis()
        val expired = requests.filterValues { it.expires < now }.keys.toList()
        for (target in expired) {
            val req = requests.remove(target) ?: continue
            Bukkit.getPlayer(req.from)?.let { Message.print(it, "${ChatColor.GRAY}Your duel challenge expired") }
        }
        for (duel in duels.values.toSet()) {
            tickDuel(duel)
        }
    }

    @Suppress("DEPRECATION")
    private fun tickDuel(duel: Duel) {
        val players = listOf(duel.first, duel.second).mapNotNull { Bukkit.getPlayer(it) }
        duel.secondsLeft--
        if (duel.counting) {
            if (duel.secondsLeft > 0) {
                players.forEach { it.sendTitle("${ChatColor.GOLD}${duel.secondsLeft}", "", 0, 25, 5) }
            } else {
                duel.counting = false
                duel.secondsLeft = maxSeconds
                players.forEach { it.sendTitle("${ChatColor.RED}FIGHT", "", 0, 25, 5) }
            }
            return
        }
        if (duel.secondsLeft <= 0) {
            end(duel, null, "time ran out")
        } else if (duel.secondsLeft == 60 || duel.secondsLeft == 10) {
            players.forEach { Message.print(it, "${ChatColor.GRAY}${duel.secondsLeft} seconds left") }
        }
    }

    // ===================================
    // stats
    // ===================================
    fun showStats(sender: org.bukkit.command.CommandSender, name: String) {
        val uuid = Bukkit.getOfflinePlayer(name).uniqueId.toString()
        val s = DuelData.stats(uuid)
        if (s == null) {
            Message.print(sender, "${ChatColor.GRAY}$name has not dueled yet")
            return
        }
        val total = s.wins + s.losses
        val rate = if (total == 0) 0 else s.wins * 100 / total
        Message.print(sender, "${ChatColor.GOLD}${s.name}${ChatColor.WHITE}: ${s.wins} wins, ${s.losses} losses ($rate%), streak ${s.streak}, best ${s.bestStreak}")
    }

    fun showTop(sender: org.bukkit.command.CommandSender) {
        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}Top duelists")
        val top = DuelData.top(10)
        if (top.isEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}Nobody has dueled yet")
        }
        for ((i, s) in top.withIndex()) {
            Message.print(sender, "${ChatColor.YELLOW}${i + 1}. ${ChatColor.WHITE}${s.name} ${ChatColor.GRAY}${s.wins}W ${s.losses}L")
        }
    }
}
