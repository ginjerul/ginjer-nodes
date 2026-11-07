/*
 * /nda duel: arenas, kits and cleanup. Permission nodes.admin.duel or
 * nodes.admin.duel.<arena|kit|end|resetstats>.
 */

package phonon.nodes.duel

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.utils.AdminPerms

object DuelAdmin {
    private val ACTIONS = listOf("arena", "kit", "end", "resetstats")
    private val ARENA_ACTIONS = listOf("add", "remove", "list", "spawn1", "spawn2", "spectate", "tp")
    private val KIT_ACTIONS = listOf("save", "remove", "list")

    fun complete(args: Array<String>): List<String> {
        fun starts(options: List<String>, text: String) = options.filter { it.startsWith(text, ignoreCase = true) }
        val players = Bukkit.getOnlinePlayers().map { it.name }
        val action = args.getOrNull(1)?.lowercase()
        val sub = args.getOrNull(2)?.lowercase()
        return when {
            args.size == 2 -> starts(ACTIONS, args[1])
            args.size == 3 && action == "arena" -> starts(ARENA_ACTIONS, args[2])
            args.size == 3 && action == "kit" -> starts(KIT_ACTIONS, args[2])
            args.size == 3 && action in listOf("end", "resetstats") -> starts(players, args[2])
            args.size == 4 && action == "arena" && sub != "add" && sub != "list" -> starts(DuelData.arenas().map { it.name }, args[3])
            args.size == 4 && action == "kit" && sub != "save" && sub != "list" -> starts(DuelData.kits().map { it.name }, args[3])
            else -> emptyList()
        }
    }

    fun handle(sender: CommandSender, args: Array<String>) {
        val action = args.getOrNull(1)?.lowercase()
        if (action == null || action !in ACTIONS) {
            Message.print(sender, "/nda duel arena <add|remove|list|spawn1|spawn2|spectate|tp> [name] | kit <save|remove|list> [name] | end <player> | resetstats <player>")
            return
        }
        if (!AdminPerms.has(sender, "duel", action)) {
            Message.error(sender, "You do not have permission for /nodesadmin duel $action")
            return
        }
        when (action) {
            "arena" -> arena(sender, args)
            "kit" -> kit(sender, args)
            "end" -> end(sender, args)
            "resetstats" -> resetStats(sender, args)
        }
    }

    private fun arena(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(2)?.lowercase()
        val name = args.getOrNull(3)
        if (sub == "list") {
            val arenas = DuelData.arenas()
            if (arenas.isEmpty()) {
                Message.print(sender, "No arenas yet. Use /nda duel arena add <name>")
            }
            for (a in arenas) {
                val state = if (a.ready()) "${ChatColor.GREEN}ready" else "${ChatColor.RED}needs spawn1 and spawn2"
                val watch = if (a.spectate != null) "spectator spot set" else "no spectator spot"
                Message.print(sender, "${ChatColor.GOLD}${a.name} $state ${ChatColor.GRAY}($watch)")
            }
            return
        }
        if (sub == null || sub !in ARENA_ACTIONS || name == null) {
            Message.error(sender, "Usage: /nda duel arena <add|remove|spawn1|spawn2|spectate|tp> <name>")
            return
        }
        if (sub == "add") {
            if (DuelData.arena(name) != null) {
                Message.error(sender, "That arena already exists")
                return
            }
            DuelData.addArena(name)
            Message.print(sender, "${ChatColor.GREEN}Created arena $name. Stand at each fighter spot and run spawn1, spawn2 (and spectate)")
            return
        }
        val arena = DuelData.arena(name)
        if (arena == null) {
            Message.error(sender, "No arena called $name")
            return
        }
        if (sub == "remove") {
            DuelData.removeArena(arena.name)
            Message.print(sender, "${ChatColor.GREEN}Removed arena ${arena.name}")
            return
        }
        val player = sender as? Player
        if (player == null) {
            Message.error(sender, "Only players can do that")
            return
        }
        if (sub == "tp") {
            val loc = (arena.spectate ?: arena.spawn1)?.toLocation()
            if (loc == null) {
                Message.error(sender, "That arena has no location set yet")
            } else {
                player.teleport(loc)
            }
            return
        }
        val point = ArenaPoint.of(player.location)
        when (sub) {
            "spawn1" -> arena.spawn1 = point
            "spawn2" -> arena.spawn2 = point
            else -> arena.spectate = point
        }
        DuelData.save()
        Message.print(sender, "${ChatColor.GREEN}Set $sub of ${arena.name} to where you stand")
    }

    private fun kit(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(2)?.lowercase()
        val name = args.getOrNull(3)
        if (sub == "list") {
            Message.print(sender, "Kits: ${Duels.kitNames()}")
            return
        }
        if (sub == null || sub !in KIT_ACTIONS || name == null) {
            Message.error(sender, "Usage: /nda duel kit <save|remove> <name> (save copies your inventory and armor)")
            return
        }
        if (sub == "remove") {
            if (DuelData.removeKit(name)) {
                Message.print(sender, "${ChatColor.GREEN}Removed kit $name")
            } else {
                Message.error(sender, "No kit called $name")
            }
            return
        }
        val player = sender as? Player
        if (player == null) {
            Message.error(sender, "Only players can do that")
            return
        }
        DuelData.saveKit(name, player)
        Message.print(sender, "${ChatColor.GREEN}Saved your inventory, armor and offhand as kit $name")
    }

    private fun end(sender: CommandSender, args: Array<String>) {
        val target = args.getOrNull(2)?.let { Bukkit.getPlayerExact(it) }
        val duel = target?.let { Duels.duels[it.uniqueId] }
        if (duel == null) {
            Message.error(sender, "That player is not in a duel")
            return
        }
        Duels.end(duel, null, "ended by staff")
        Message.print(sender, "${ChatColor.GREEN}Ended the duel as a draw, wagers refunded")
    }

    private fun resetStats(sender: CommandSender, args: Array<String>) {
        val name = args.getOrNull(2)
        if (name == null) {
            Message.error(sender, "Give a player name")
            return
        }
        val uuid = Bukkit.getOfflinePlayer(name).uniqueId.toString()
        if (DuelData.resetStats(uuid)) {
            Message.print(sender, "${ChatColor.GREEN}Reset duel stats of $name")
        } else {
            Message.error(sender, "$name has no duel stats")
        }
    }
}
