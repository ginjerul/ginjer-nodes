/**
 * Admin commands to manage world
 * - modify towns, nations
 * - war enable/disable
 *
 *    /nodesadmin command ...
 *    /na command
 */

package phonon.nodes.commands

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.Nodes.addPortToGroup
import phonon.nodes.Nodes.removePortFromGroup
import phonon.nodes.commands.MiningTowerCommand
import phonon.nodes.mining.MiningBoost
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.utils.sanitizeString
import phonon.nodes.utils.string.filterByStart
import phonon.nodes.utils.string.filterNation
import phonon.nodes.utils.string.filterNationTown
import phonon.nodes.utils.string.filterPlayer
import phonon.nodes.utils.string.filterPort
import phonon.nodes.utils.string.filterPortGroup
import phonon.nodes.utils.string.filterResident
import phonon.nodes.utils.string.filterTown
import phonon.nodes.utils.string.filterTownOrNation
import phonon.nodes.utils.string.filterTownResident
import phonon.nodes.utils.stringInputIsValid
import phonon.nodes.war.Treaty
import phonon.nodes.war.TreatyTermItems
import phonon.nodes.war.TreatyTermOccupation
import phonon.nodes.war.WarTimer
import kotlin.getOrElse
import kotlin.math.roundToInt
private val SUBCOMMANDS: List<String> = listOf(
    "help",
    "reload",
    "war",
    "surrender",
    "port",
    "portgroup",
    "resident",
    "town",
    "nation",
    "enemy",
    "peace",
    "ally",
    "allyremove",
    "truce",
    "truceremove",
    "treaty",
    "save",
    "load",
    "runincome",
    "playersonline",
    "debug",
    "miningboost",
    "stats",
    "rallycap",
    "announce",
    "plot",
)

private val RELOAD_SUBCOMMANDS: List<String> = listOf(
    "config",
    "managers",
    "resources",
    "territory",
)

private val WAR_SUBCOMMANDS: List<String> = listOf(
    "enable",
    "skirmish",
    "disable",
    "whitelist",
    "blacklist",
)

private val PORT_SUBCOMMANDS: List<String> = listOf(
    "create",
    "delete",
    "addgroup",
    "removegroup",
)

private val PORTGROUP_SUBCOMMANDS: List<String> = listOf(
    "create",
    "delete",
)

private val RESIDENT_SUBCOMMANDS: List<String> = listOf(
    "towncooldown",
)

private val TOWN_SUBCOMMANDS: List<String> = listOf(
    "create",
    "delete",
    "addplayer",
    "removeplayer",
    "addterritory",
    "removeterritory",
    "captureterritory",
    "releaseterritory",
    "addofficer",
    "removeofficer",
    "leader",
    "removeleader",
    "claimsbonus",
    "claimsannex",
    "claimspenalty",
    "open",
    "income",
    "incomeadd",
    "incomeremove",
    "setspawn",
    "spawn",
    "sethome",
    "sethomecooldown",
    "addoutpost",
    "removeoutpost",
    "defaulttownspawns",
)

private val NATION_SUBCOMMANDS: List<String> = listOf(
    "create",
    "delete",
    "addtown",
    "removetown",
    "capital",
)

private val DEBUG_SUBCOMMANDS: List<String> = listOf(
    "resource",
    "chunk",
    "territory",
    "resident",
    "town",
    "nation",
)

public class NodesAdminCommand :
    CommandExecutor,
    TabCompleter {
    private fun miningBoost(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.print(sender, "/nda miningboost global <duration> [tier]")
            Message.print(sender, "/nda miningboost player <name> <duration> [tier]")
            Message.print(sender, "/nda miningboost clear global")
            Message.print(sender, "/nda miningboost clear player <name>")
            Message.print(sender, "No tier → +1 (max ${MiningBoost.MAX_TIER}). Time stacks if already active.")
            return
        }
        when (args[1].lowercase()) {
            "global" -> {
                if (args.size < 3) {
                    Message.error(sender, "Usage: /nda miningboost global <duration> [tier]")
                    return
                }
                val dur = MiningBoost.parseDurationMs(args[2])
                if (dur == null) {
                    Message.error(sender, "Invalid duration (e.g. 30m, 2h)")
                    return
                }
                if (args.size >= 4) {
                    val tier = args[3].toIntOrNull()
                    if (tier == null || tier < 1 || tier > MiningBoost.MAX_TIER) {
                        Message.error(sender, "Tier must be 1–${MiningBoost.MAX_TIER}")
                        return
                    }
                    MiningBoost.setGlobal(tier, dur, sender.name)
                } else {
                    MiningBoost.bumpGlobal(dur, sender.name)
                }
            }
            "player" -> {
                if (args.size < 4) {
                    Message.error(sender, "Usage: /nda miningboost player <name> <duration> [tier]")
                    return
                }
                val offline = Bukkit.getOfflinePlayer(args[2])
                val dur = MiningBoost.parseDurationMs(args[3])
                if (dur == null) {
                    Message.error(sender, "Invalid duration (e.g. 30m, 2h, 1d)")
                    return
                }
                if (args.size >= 5) {
                    val tier = args[4].toIntOrNull()
                    if (tier == null || tier < 1 || tier > MiningBoost.MAX_TIER) {
                        Message.error(sender, "Tier must be 1–${MiningBoost.MAX_TIER}")
                        return
                    }
                    MiningBoost.setPlayer(
                        offline.uniqueId,
                        args[2],
                        tier,
                        dur,
                        sender.name,
                    )
                    Message.print(sender, "Player mining boost for ${args[2]} set to tier $tier")
                } else {
                    MiningBoost.bumpPlayer(
                        offline.uniqueId,
                        args[2],
                        dur,
                        sender.name,
                    )
                    Message.print(sender, "Player mining boost for ${args[2]} tier +1")
                }
            }
            "clear" -> {
                if (args.size < 3) {
                    Message.error(sender, "Usage: /nda miningboost clear global|player <name>")
                    return
                }
                when (args[2].lowercase()) {
                    "global" -> MiningBoost.clearGlobal()
                    "player" -> {
                        if (args.size < 4) {
                            Message.error(sender, "Usage: /nda miningboost clear player <name>")
                            return
                        }
                        MiningBoost.clearPlayer(Bukkit.getOfflinePlayer(args[3]).uniqueId)
                        Message.print(sender, "Cleared boost for ${args[3]}")
                    }
                    else -> Message.error(sender, "Use: clear global OR clear player <name>")
                }
            }
            else -> Message.error(sender, "Use: miningboost global|player|clear")
        }
    }
    private fun adminPlot(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.print(sender, "Usage: /nda plot list | /nda plot delete <name>")
            return
        }
        when (args[1].lowercase()) {
            "list" -> {
                val all = phonon.nodes.plot.Plots.all()
                if (all.isEmpty()) {
                    Message.print(sender, "No plots")
                    return
                }
                for (plot in all) {
                    Message.print(
                        sender,
                        "${plot.name} town=${plot.townName} owner=${plot.ownerName}",
                    )
                }
            }
            "delete" -> {
                if (args.size < 3) {
                    Message.error(sender, "Usage: /nda plot delete <name>")
                    return
                }
                if (phonon.nodes.plot.Plots.delete(args[2])) {
                    Message.print(sender, "Deleted plot ${args[2]}")
                } else {
                    Message.error(sender, "Plot not found")
                }
            }
            else -> Message.error(sender, "Usage: /nda plot list | delete <name>")
        }
    }
    private fun adminRallyCap(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nda rallycap <nation> <n|-1>")
            return
        }
        val name = args[1]
        val nation = Nodes.getNationFromName(name)
            ?: run {
                Message.error(sender, "Nation not found")
                return
            }
        val n = args[2].toIntOrNull()
            ?: run {
                Message.error(sender, "Cap must be an integer (-1 = unlimited)")
                return
            }
        if (n < -1) {
            Message.error(sender, "Cap must be >= -1")
            return
        }
        phonon.nodes.RallyCap.set(nation.name, n)
        val shown = if (n < 0) "unlimited" else n.toString()
        Message.print(sender, "${org.bukkit.ChatColor.GREEN}Rally cap for ${nation.name}: $shown")
    }
    private fun adminStats(sender: CommandSender, args: Array<String>) {
        if (!sender.hasPermission("nodes.stats.admin") && !sender.isOp) {
            Message.error(sender, "No permission")
            return
        }
        if (args.size >= 3 && args[1].equals("reset", ignoreCase = true)) {
            val offline = org.bukkit.Bukkit.getOfflinePlayer(args[2])
            phonon.nodes.stats.PlayerStats.reset(offline.uniqueId)
            Message.print(sender, "Reset stats for ${args[2]}")
            return
        }
        Message.print(sender, "Usage: /nda stats reset <player>")
    }

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        if (args.size == 0) {
            Message.print(sender, "${ChatColor.BOLD}Nodes ${Nodes.version}")
            printHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "reload" -> reload(sender, args)
            "war" -> war(sender, args)
            "surrender" -> adminSurrender(sender, args)
            "miningboost" -> miningBoost(sender, args)
            "stats" -> adminStats(sender, args)
            "announce" -> adminAnnounce(sender, args)
            "resident" -> manageResident(sender, args)
            "town" -> manageTown(sender, args)
            "port" -> managePort(sender, args)
            "portgroup" -> managePortGroup(sender, args)
            "nation" -> manageNation(sender, args)
            "enemy" -> setEnemy(sender, args)
            "peace" -> setPeace(sender, args)
            "ally" -> setAlly(sender, args)
            "allyremove" -> removeAlly(sender, args)
            "truce" -> setTruce(sender, args)
            "truceremove" -> removeTruce(sender, args)
            "treaty" -> manageTreaty(sender, args)
            "save" -> saveWorld(sender, args)
            "load" -> loadWorld(sender)
            "runincome" -> Nodes.runIncome()
            "playersonline" -> Nodes.refreshPlayersOnline()
            "plot" -> adminPlot(sender, args)
            "rallycap" -> adminRallyCap(sender, args)
            "debug" -> debugger(sender, args)
            else -> {
                Message.error(sender, "Invalid command, use \"/nodesadmin help\"")
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            return filterByStart(SUBCOMMANDS, args[0])
        } else if (args.size > 1) {
            when (args[0].lowercase()) {
                "reload" -> {
                    if (args.size == 2) {
                        return filterByStart(RELOAD_SUBCOMMANDS, args[1])
                    }
                }

                "stats" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("reset"), args[1])
                    }
                }

                "war" -> {
                    if (args.size == 2) {
                        return filterByStart(WAR_SUBCOMMANDS, args[1])
                    }
                    if (args.size == 3 && (args[1].equals("enable", true) || args[1].equals("skirmish", true))) {
                        return filterByStart(listOf("30m", "1h", "2h", "3h", "6h", "12h", "24h", "48h", "72h"), args[2])
                    }
                }
                "surrender" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("reset"), args[1])
                    }
                    if (args.size == 3) {
                        return filterTown(args[2])
                    }
                }
                "miningboost" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("global", "player", "clear"), args[1])
                    }
                    if (args.size == 3) {
                        when (args[1].lowercase()) {
                            "player" -> return filterPlayer(args[2])
                            "clear" -> return filterByStart(listOf("global", "player"), args[2])
                        }
                    }
                    if (args.size == 4) {
                        when (args[1].lowercase()) {
                            "player" -> {
                            }
                            "clear" -> {
                                if (args[2].equals("player", ignoreCase = true)) {
                                    return filterPlayer(args[3])
                                }
                            }
                        }
                    }
                }

                "port" -> {
                    if (args.size == 2) {
                        return filterByStart(PORT_SUBCOMMANDS, args[1])
                    } else if (args.size == 3) {
                        when (args[1].lowercase()) {
                            "delete",
                            "addgroup",
                            "removegroup",
                            -> {
                                return filterPort(args[2])
                            }
                        }
                    } else if (args[1].lowercase() == "create") {
                        when (args.size) {
                            4 -> {
                                return listOf(Bukkit.getPlayer(sender.name)?.x?.roundToInt().toString())
                            }
                            5 -> {
                                return listOf(Bukkit.getPlayer(sender.name)?.z?.roundToInt().toString())
                            }
                        }
                    } else if (args.size == 4) {
                        when (args[1].lowercase()) {
                            "addgroup",
                            "removegroup",
                            -> {
                                return filterPortGroup(args[3])
                            }
                        }
                    }
                }

                "portgroup" -> {
                    if (args.size == 2) {
                        return filterByStart(PORTGROUP_SUBCOMMANDS, args[1])
                    }
                    when (args[1].lowercase()) {
                        "delete" -> {
                            return filterPortGroup(args[2])
                        }
                    }
                }

                "resident" -> {
                    if (args.size == 2) {
                        return filterByStart(RESIDENT_SUBCOMMANDS, args[1])
                    } else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            "towncooldown" -> {
                                if (args.size == 3) {
                                    return filterResident(args[2])
                                }
                            }
                        }
                    }
                }

                "town" -> {
                    if (args.size == 2) {
                        return filterByStart(TOWN_SUBCOMMANDS, args[1])
                    } else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            "delete",
                            "addterritory",
                            "removeterritory",
                            "captureterritory",
                            "releaseterritory",
                            "claimsbonus",
                            "claimsannex",
                            "claimspenalty",
                            "open",
                            "income",
                            "setspawn",
                            "spawn",
                            "sethome",
                            "sethomecooldown",
                            "removeleader",
                            -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                }
                            }

                            "addplayer",
                            "removeplayer",
                            -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else {
                                    return filterPlayer(args[args.size - 1])
                                }
                            }

                            "leader" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size == 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town !== null) {
                                        return filterTownResident(town, args[3])
                                    }
                                }
                            }

                            "addofficer",
                            "removeofficer",
                            -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size >= 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town !== null) {
                                        return filterTownResident(town, args[args.size - 1])
                                    }
                                }
                            }

                            "addoutpost" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                }
                            }

                            "removeoutpost" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                } else if (args.size >= 4) {
                                    val town = Nodes.getTownFromName(args[2])
                                    if (town !== null) {
                                        return filterByStart(town.outposts.keys.toList(), args[3])
                                    }
                                }
                            }
                        }
                    }
                }

                "nation" -> {
                    if (args.size == 2) {
                        return filterByStart(NATION_SUBCOMMANDS, args[1])
                    } else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            "delete" -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                }
                            }

                            "addtown",
                            "removetown",
                            -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                } else {
                                    return filterTown(args[args.size - 1])
                                }
                            }

                            "capital" -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                } else if (args.size == 4) {
                                    val nation = Nodes.getNationFromName(args[2])
                                    if (nation !== null) {
                                        return filterNationTown(nation, args[3])
                                    }
                                }
                            }
                        }
                    }
                }

                "enemy",
                "peace",
                "ally",
                "allyremove",
                "truce",
                "truceremove",
                -> {
                    if (args.size == 2) {
                        return filterTownOrNation(args[1])
                    } else if (args.size == 3) {
                        return filterTownOrNation(args[2])
                    }
                }

                "treaty" -> {
                    if (args.size == 2) {
                        return filterTownOrNation(args[1])
                    } else if (args.size == 3) {
                        return filterTownOrNation(args[2])
                    }
                }
                "plot" -> filterByStart(listOf("list", "delete"), args[1])"plot" -> filterByStart(listOf("list", "delete"), args[1])

                "debug" -> {
                    if (args.size == 2) {
                        return filterByStart(DEBUG_SUBCOMMANDS, args[1])
                    } else if (args.size > 2) {
                        when (args[1].lowercase()) {
                            "resident" -> {
                                if (args.size == 3) {
                                    return filterResident(args[2])
                                }
                            }

                            "town" -> {
                                if (args.size == 3) {
                                    return filterTown(args[2])
                                }
                            }

                            "nation" -> {
                                if (args.size == 3) {
                                    return filterNation(args[2])
                                }
                            }
                        }
                    }
                }
            }
        }

        return listOf()
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin commands:")
        Message.print(sender, "/nodesadmin reload${ChatColor.WHITE}: Reloads modules of plugin")
        Message.print(sender, "/nodesadmin war${ChatColor.WHITE}: Enable/disable war")
        Message.print(sender, "/nodesadmin surrender reset <town>${ChatColor.WHITE}: Clear a town's surrender cooldown")
        Message.print(sender, "/nodesadmin town${ChatColor.WHITE}: Manage towns (see \"/nodesadmin town help\")")
        Message.print(sender, "/nodesadmin nation${ChatColor.WHITE}: Manage nations (see \"/nodesadmin nation help\")")
        Message.print(sender, "/nodesadmin portgroup${ChatColor.WHITE}: Manage port groups (see \"/nodesadmin portgroup help\")")
        Message.print(sender, "/nodesadmin port${ChatColor.WHITE}: Manage ports (see \"/nodesadmin port help\")")
        Message.print(sender, "/nodesadmin enemy${ChatColor.WHITE}: Make two towns/nations enemies")
        Message.print(sender, "/nodesadmin peace${ChatColor.WHITE}: Sets peace between two towns/nations")
        Message.print(sender, "/nodesadmin ally${ChatColor.WHITE}: Sets alliance between two towns/nations")
        Message.print(sender, "/nodesadmin allyremove${ChatColor.WHITE}: Removes alliance between two towns/nations")
        Message.print(sender, "/nodesadmin truce${ChatColor.WHITE}: Sets truce between two towns/nations")
        Message.print(sender, "/nodesadmin truceremove${ChatColor.WHITE}: Removes truce between two towns/nations")
        Message.print(sender, "/nodesadmin save${ChatColor.WHITE}: Force save world")
        Message.print(sender, "/nodesadmin load${ChatColor.WHITE}: Force load world")
        Message.print(sender, "/nodesadmin runincome${ChatColor.WHITE}: Runs income for all towns")
        Message.print(sender, "/nodesadmin debug${ChatColor.WHITE}: World object debugger")
        Message.print(sender, "/nodesadmin miningboost${ChatColor.WHITE}: Mining rate/amount boosts")
        Message.print(sender, "/nodesadmin stats reset <player>${ChatColor.WHITE}: Reset K/D stats")
        Message.print(sender, "/nodesadmin announce <message>${ChatColor.WHITE}: Announce for all players")
        Message.print(sender, "/nodesadmin plot list|delete <name>${ChatColor.WHITE}: Manage plots")
        Message.print(sender, "/nodesadmin rallycap <nation> <n|-1>${ChatColor.WHITE}: Manage rallycaps")
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("NodesMap") != null) {
            Message.print(sender, "/nodesmap${ChatColor.WHITE}: Web map rendering and editor (see \"/nodesmap help\")")
        }
        return
    }

    /**
     * @command /nodesadmin reload [config|managers|territory]
     * Reload components of Nodes engine
     */
    private fun reload(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.print(sender, "Reload possibilities: \"/nodesadmin reload [config|managers|resources|territory]\"")
            return
        }

        val subcommand = args[1].lowercase()
        if (subcommand == "config") {
            Nodes.reloadConfig()
            Message.print(sender, "[Nodes] reloaded configs")
        } else if (subcommand == "managers") {
            Nodes.reloadManagers()
            Message.print(sender, "[Nodes] reloaded manager tasks")
        } else if (subcommand == "resources") {
            val success = Nodes.reloadWorldJson(
                reloadResources = true,
                reloadTerritories = false,
            )
            if (success) {
                Message.print(sender, "[Nodes] reloaded resources and territories")
            } else {
                Message.error(sender, "[Nodes] failed to reload resources and territories")
            }
        } else if (subcommand == "territory") {
            if (args.size < 3) {
                Message.print(sender, "Usage: \"/nodesadmin reload territory *\"${ChatColor.WHITE}: reloads ALL territories")
                Message.print(sender, "Usage: \"/nodesadmin reload territory id1 id2 id3 ...\"${ChatColor.WHITE}: reloads specific ids")
                return
            }

            val terrIds: List<TerritoryId>? = if (args[2] == "*") { // reload ALL territories (don't specify ids)
                null
            } else {
                val ids = HashSet<TerritoryId>()

                // validate id exists in territories (don't allow reloading NEW territories,
                // since this can cause issues and instability if ids/neighbors are changing)
                for (i in 2 until args.size) {
                    try {
                        val id = TerritoryId(args[i].toInt())
                        val terr = Nodes.getTerritoryFromId(id)
                        if (terr == null) {
                            Message.error(sender, "Territory id \"${id}\" does not exist. This can only reload existing territories.")
                            return
                        }
                        ids.add(id)
                    } catch (err: Exception) {
                        Message.error(sender, "Invalid id: \"${args[i]}\"")
                        return
                    }
                }

                ids.toList()
            }

            val success = Nodes.reloadWorldJson(
                reloadResources = false,
                reloadTerritories = true,
                territoryIds = terrIds,
            )

            val terrIdsString = terrIds?.let { ids -> "territories $ids" } ?: "all territories"

            if (success) {
                Message.print(sender, "[Nodes] reloaded $terrIdsString")
            } else {
                Message.error(sender, "[Nodes] failed to reload $terrIdsString")
            }
        } else {
            Message.print(sender, "Reload possibilities: \"/nodesadmin reload [config|managers|resources|territory]\"")
        }
    }

    /**
     * @command /nodesadmin war [enable|disable]
     * Enables/disables war
     */
    /** War/skirmish length: needs a unit, e.g. 2h, 90m, 1h30m, 2d. */
    private fun parseWarDuration(sender: CommandSender, input: String): Long? {
        if (input.trim().toLongOrNull() != null) {
            Message.error(sender, "Add a unit to the duration: ${input.trim()}h for hours, ${input.trim()}m for minutes, ${input.trim()}d for days")
            return null
        }
        val ms = phonon.nodes.mining.MiningBoost.parseDurationMs(input)
        if (ms == null) {
            Message.error(sender, "Invalid duration \"$input\". Use e.g. 2h, 90m, 1h30m or 2d")
        }
        return ms
    }

    private fun formatWarDuration(ms: Long): String {
        val totalMin = ms / 60_000L
        val d = totalMin / 1440
        val h = (totalMin % 1440) / 60
        val m = totalMin % 60
        val parts = ArrayList<String>()
        if (d > 0) parts.add("${d}d")
        if (h > 0) parts.add("${h}h")
        if (m > 0) parts.add("${m}m")
        if (parts.isEmpty()) parts.add("${ms / 1000L}s")
        return parts.joinToString(" ")
    }

    private fun adminSurrender(sender: CommandSender, args: Array<String>) {
        if (args.size < 3 || !args[1].equals("reset", true)) {
            Message.print(sender, "Usage: /nda surrender reset <town>")
            return
        }
        val town = Nodes.getTownFromName(args[2])
        if (town == null) {
            Message.error(sender, "Town not found: ${args[2]}")
            return
        }
        if (phonon.nodes.surrender.Surrender.resetCooldown(town)) {
            Message.print(sender, "Surrender cooldown of ${town.name} cleared")
        } else {
            Message.print(sender, "${town.name} has no surrender cooldown")
        }
    }

    private fun war(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Nodes.war.printInfo(sender, true)
            Message.print(sender, "Usage: /nda war enable [duration] | skirmish [duration] | disable  (duration like 2h, 90m, 1h30m, 2d)")
        } else {
            val function = args[1].lowercase()
            when (function) {
                "enable" -> {
                    val durationMs = if (args.size >= 3) {
                        parseWarDuration(sender, args[2]) ?: return
                    } else {
                        null
                    }

                    Nodes.enableWar(true, false, true)
                    phonon.nodes.stats.WarStats.startWarSession(fullWar = true)

                    if (durationMs != null) {
                        phonon.nodes.war.WarTimer.scheduleDisable(durationMs, "War time expired")

                        val durationLabel = formatWarDuration(durationMs)
                        Message.broadcast(
                            "${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled for $durationLabel",
                        )
                        phonon.nodes.util.DiscordRelay.war(
                            "**[War]** Nodes war **enabled** for **$durationLabel**",
                        )
                    } else {
                        phonon.nodes.war.WarTimer.cancel()
                        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
                    }

                    for (p in Bukkit.getOnlinePlayers()) {
                        p.playSound(p.location, Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.0f)
                    }
                }

                "skirmish" -> {
                    val durationMs = if (args.size >= 3) {
                        parseWarDuration(sender, args[2]) ?: return
                    } else {
                        null
                    }

                    Nodes.enableWar(false, true, Config.allowDestructionDuringSkirmish)
                    // War KDR is only tracked for full war, so no war session here

                    if (durationMs != null) {
                        phonon.nodes.war.WarTimer.scheduleDisable(durationMs, "Skirmish time expired", "Skirmish")
                        Message.broadcast(
                            "${ChatColor.DARK_RED}${ChatColor.BOLD}Border skirmishes enabled for ${formatWarDuration(durationMs)}",
                        )
                    } else {
                        phonon.nodes.war.WarTimer.cancel()
                        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishes enabled")
                    }

                    for (p in Bukkit.getOnlinePlayers()) {
                        p.playSound(p.location, Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.0f)
                    }
                }

                "disable" -> {
                    if (Nodes.war.enabled == true) {
                        Nodes.disableWar()
                        phonon.nodes.war.WarTimer.cancel()
                        phonon.nodes.stats.WarStats.endWarSessionAndSave()
                        Message.broadcast("${ChatColor.BOLD}Nodes war disabled")
                    } else {
                        Message.error(sender, "Nodes war already disabled")
                    }
                }

                "whitelist" -> {
                    if (Config.warUseWhitelist) {
                        Message.print(sender, "[War] Town attack whitelist:")
                        for (town in Nodes.towns.values) {
                            if (Config.warWhitelist.contains(town.uuid)) {
                                Message.print(sender, "${ChatColor.GRAY}- ${town.name}")
                            }
                        }
                    } else {
                        Message.print(sender, "${ChatColor.GRAY}[War] No town whitelist")
                    }
                }

                "blacklist" -> {
                    if (Config.warUseBlacklist) {
                        Message.print(sender, "[War] Town attack blacklist:")
                        for (town in Nodes.towns.values) {
                            if (Config.warBlacklist.contains(town.uuid)) {
                                Message.print(sender, "${ChatColor.GRAY}- ${town.name}")
                            }
                        }
                    } else {
                        Message.print(sender, "${ChatColor.GRAY}[War] No town blacklist")
                    }
                }

                else -> {
                    Message.print(sender, "Nodes war usage: \"/nodesadmin war [enable|disable]\"")
                }
            }
        }
    }

    // =============================================================
    // port management commands
    // - create/delete ports
    // - edit port properties
    // =============================================================

    private fun managePort(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printPortHelp(sender)
        } else {
            when (args[1].lowercase()) {
                "create" -> createPort(sender, args)
                "delete" -> deletePort(sender, args)
                "addgroup" -> addPortGroup(sender, args)
                "removegroup" -> removePortGroup(sender, args)
                else -> printPortHelp(sender)
            }
        }
    }

    private fun printPortHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port create [name] [x] [z] [groups] [public]${ChatColor.WHITE}: Create a new port")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port delete [name]${ChatColor.WHITE}: Delete a port")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port addgroup [port] [group]${ChatColor.WHITE}: Add a port to a group")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin port removegroup [port] [group]${ChatColor.WHITE}: Remove a port from a group")
    }

    /**
     * @command /nodesadmin port create [name] [x] [z]
     * Creates a port from name and a set of coordinates.
     */
    private fun createPort(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin port create [name] [x] [z]")
            Message.error(sender, "Example: /nodesadmin port create panama 100 200")
            return
        }

        val portName = args[2].lowercase()

        val x = args[3].toIntOrNull()
        val z = args[4].toIntOrNull()
        if (x === null || z === null) {
            Message.error(sender, "Invalid coordinates: x and z must be integers")
            return
        }

        Nodes.createPort(
            portName,
            x,
            z,
            hashSetOf(),
            false,
        ).getOrElse({ err ->
            Message.error(sender, "Failed to create port: ${err.message}")
            return
        })

        Message.print(sender, "${ChatColor.GREEN}Created port \"${portName}\" at ($x, $z)")
    }

    /**
     * @command /nodesadmin port delete [name]
     * Deletes a port.
     */
    private fun deletePort(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin port delete [name]")
            Message.error(sender, "Example: /nodesadmin port delete panama")
            return
        }

        val portName = args[2].lowercase()
        val port = Nodes.getPortFromName(portName)

        if (port === null) {
            Message.error(sender, "Port \"${portName}\" does not exist")
            return
        }

        Nodes.destroyPort(port)

        Message.print(sender, "Port \"${portName}\" has been deleted")
    }

    /**
     * @command /nodesadmin port addgroup [port] [group]
     * Adds a port to a group.
     */
    private fun addPortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin port addgroup [port] [group]")
            Message.error(sender, "Example: /nodesadmin port addgroup panama atlantic")
            return
        }

        val portName = args[2].lowercase()
        val port = Nodes.getPortFromName(portName)

        val groupName = args[3].lowercase()
        val group = Nodes.getPortGroupFromName(groupName)

        if (port === null) {
            Message.error(sender, "Port \"${portName}\" does not exist")
            return
        }

        if (group === null) {
            Message.error(sender, "Group \"${groupName}\" does not exist")
            return
        }

        if (port.groups.contains(group)) {
            Message.error(sender, "Port is already part of ths group")
            return
        }

        addPortToGroup(port, group)

        Message.print(sender, "Port \"${portName}\" has been added to $groupName group")
    }

    /**
     * @command /nodesadmin port removegroup [port] [group]
     * Removes a port from a group
     */
    private fun removePortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin port removegroup [port] [group]")
            Message.error(sender, "Example: /nodesadmin port removegroup panama atlantic")
            return
        }

        val portName = args[2].lowercase()
        val port = Nodes.getPortFromName(portName)

        val groupName = args[3].lowercase()
        val group = Nodes.getPortGroupFromName(groupName)

        if (port === null) {
            Message.error(sender, "Port \"${portName}\" does not exist")
            return
        }

        if (group === null) {
            Message.error(sender, "Group \"${groupName}\" does not exist")
            return
        }

        if (!port.groups.contains(group)) {
            Message.error(sender, "Port \"$portName\" is not part of \"$groupName\" group")
            return
        }

        removePortFromGroup(port, group)

        Message.print(sender, "Port \"${portName}\" has been removed from $groupName group")
    }

    // =============================================================
    // port group management commands
    // - create/delete port groups
    // =============================================================

    private fun managePortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printPortGroupHelp(sender)
        } else {
            when (args[1].lowercase()) {
                "create" -> createPortGroup(sender, args)
                "delete" -> deletePortGroup(sender, args)
                else -> printPortGroupHelp(sender)
            }
        }
    }

    private fun printPortGroupHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin portgroup create [name]${ChatColor.WHITE}: Create a new port group")
        Message.print(sender, "${ChatColor.AQUA}/nodesadmin portgroup delete [name]${ChatColor.WHITE}: Delete a port group")
    }

    /**
     * @command /nodesadmin portgroup create [name]
     * Creates a port group.
     */
    private fun createPortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin portgroup create [name]")
            Message.error(sender, "Example: /nodesadmin portgroup create atlantic")
            return
        }

        val portGroupName = args[2].lowercase()

        Nodes.createPortGroup(
            portGroupName,
        ).getOrElse({ err ->
            Message.error(sender, "Failed to create group: ${err.message}")
            return
        })

        Message.print(sender, "${ChatColor.GREEN}Created group \"${portGroupName}\"")
    }

    /**
     * @command /nodesadmin portgroup delete [name]
     * Deletes a port group.
     */
    private fun deletePortGroup(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin portgroup delete [name]")
            Message.error(sender, "Example: /nodesadmin portgroup delete atlantic")
            return
        }

        val portGroupName = args[2].lowercase()
        val portGroup = Nodes.getPortGroupFromName(portGroupName)

        if (portGroup === null) {
            Message.error(sender, "Port group \"${portGroupName}\" does not exist")
            return
        }

        Nodes.destroyPortGroup(portGroup)

        Message.print(sender, "${ChatColor.GREEN}Deleted group \"${portGroupName}\"")
    }

    // =============================================================
    // resident management commands
    // - change town create cooldown
    // =============================================================

    private fun manageResident(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printTownHelp(sender)
        } else {
            when (args[1].lowercase()) {
                "help" -> printResidentHelp(sender)
                "towncooldown" -> setResidentTownCooldown(sender, args)
                else -> {
                    printResidentHelp(sender)
                }
            }
        }
    }

    private fun printResidentHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin town management:")
        Message.print(sender, "/nodesadmin resident towncooldown${ChatColor.WHITE}: Change resident town cooldown")
        Message.print(sender, "Run a command with no args to see usage.")
    }

    private fun setResidentTownCooldown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin resident towncooldown [player] [number]")
            return
        }

        val residentName = args[2]
        val resident = Nodes.getResidentFromName(residentName)
        if (resident === null) {
            Message.error(sender, "Resident \"${residentName}\" does not exist")
            return
        }

        val cooldown: Long = Math.max(0, args[3].toLong())

        Nodes.setResidentTownCreateCooldown(resident, cooldown)
    }

    // =============================================================
    // town management commands
    // - create/delete towns
    // - toggle town properties, players
    // - modify town territories, claims bonus, ...
    // =============================================================

    private fun manageTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printTownHelp(sender)
        } else {
            when (args[1].lowercase()) {
                "create" -> createTown(sender, args)
                "delete" -> deleteTown(sender, args)
                "addplayer" -> addPlayerToTown(sender, args)
                "removeplayer" -> removePlayerFromTown(sender, args)
                "addterritory" -> addTerritoryToTown(sender, args)
                "removeterritory" -> removeTerritoryFromTown(sender, args)
                "captureterritory" -> captureTerritory(sender, args)
                "releaseterritory" -> releaseTerritory(sender, args)
                "claimsbonus" -> setClaimsBonus(sender, args)
                "claimsannex" -> setClaimsAnnexed(sender, args)
                "claimspenalty" -> setClaimsPenalty(sender, args)
                "setspawn" -> townSetSpawn(sender, args)
                "spawn" -> townSpawn(sender, args)
                "addofficer" -> addTownOfficer(sender, args)
                "removeofficer" -> removeTownOfficer(sender, args)
                "leader" -> setTownLeader(sender, args)
                "removeleader" -> removeTownLeader(sender, args)
                "open" -> setTownOpen(sender, args)
                "income" -> townIncome(sender, args)
                "incomeadd" -> townIncomeAdd(sender, args)
                "incomeremove" -> townIncomeRemove(sender, args)
                "sethome" -> setTownHome(sender, args)
                "sethomecooldown" -> setTownMoveHomeCooldown(sender, args)
                "addoutpost" -> addOutpostToTown(sender, args)
                "removeoutpost" -> removeOutpostFromTown(sender, args)
                "defaulttownspawns" -> defaultTownSpawns(sender, args)
                else -> {
                    printTownHelp(sender)
                }
            }
        }
    }

    private fun printTownHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin town management:")
        Message.print(sender, "/nodesadmin town create${ChatColor.WHITE}: Create a new town")
        Message.print(sender, "/nodesadmin town delete${ChatColor.WHITE}: Delete existing town")
        Message.print(sender, "/nodesadmin town addplayer${ChatColor.WHITE}: Add players to town")
        Message.print(sender, "/nodesadmin town removeplayer${ChatColor.WHITE}: Remove players from town")
        Message.print(sender, "/nodesadmin town addterritory${ChatColor.WHITE}: Add territories to town")
        Message.print(sender, "/nodesadmin town removeterritory${ChatColor.WHITE}: Remove territories from town")
        Message.print(sender, "/nodesadmin town captureterritory${ChatColor.WHITE}: Add captured territories to town")
        Message.print(sender, "/nodesadmin town releaseterritory${ChatColor.WHITE}: Release captured territories")
        Message.print(sender, "/nodesadmin town claimsbonus${ChatColor.WHITE}: Set town bonus claims")
        Message.print(sender, "/nodesadmin town claimsannex${ChatColor.WHITE}: Set town annexed claims penalty")
        Message.print(sender, "/nodesadmin town setspawn${ChatColor.WHITE}: Set town's spawn to location")
        Message.print(sender, "/nodesadmin town spawn${ChatColor.WHITE}: Go to town's spawn")
        Message.print(sender, "/nodesadmin town addofficer${ChatColor.WHITE}: Add officer to town")
        Message.print(sender, "/nodesadmin town removeofficer${ChatColor.WHITE}: Remove officer from town")
        Message.print(sender, "/nodesadmin town leader${ChatColor.WHITE}: Set town leader to player")
        Message.print(sender, "/nodesadmin town open${ChatColor.WHITE}: Toggle town is open to join")
        Message.print(sender, "/nodesadmin town income${ChatColor.WHITE}: View a town's income inventory")
        Message.print(sender, "/nodesadmin town addoutpost${ChatColor.WHITE}: Add an outpost to a town")
        Message.print(sender, "/nodesadmin town removeoutpost${ChatColor.WHITE}: Remove an outpost from a town")
        Message.print(sender, "Run a command with no args to see usage.")
    }

    /**
     * @command /nodesadmin town create [name] [id1] [id2] ...
     * Creates a town from name and list of territory ids.
     * The first id is required and becomes the home territory.
     * This town is created without any residents or leader.
     */
    private fun createTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town create [name] [id1] [id2] ...")
            Message.error(sender, "Each id is a territory id (first id required)")
            return
        }

        if (!stringInputIsValid(args[2])) {
            Message.error(sender, "Invalid town name")
            return
        }
        val name = sanitizeString(args[2])

        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr.town != null) {
                Message.error(sender, "Invalid territory id=$id: either does not exist or already has town")
                return
            } else {
                territories.add(terr)
            }
        }

        val town = Nodes.createTown(name, territories[0], null).getOrElse({ err ->
            Message.error(sender, "Failed to create town: ${err.message}")
            return
        })

        for (i in 1 until territories.size) {
            Nodes.addTerritoryToTown(town, territories[i])
        }

        Message.print(sender, "Created town \"${name}\" with ${territories.size} territories")
    }

    /**
     * @command /nodesadmin town delete [name]
     * Deletes town with given name.
     */
    private fun deleteTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: \"/nodesadmin town delete [name]")
            return
        }

        val name = args[2]
        val town = Nodes.towns.get(name)
        if (town != null) {
            Nodes.destroyTown(town)
            Message.print(sender, "Town \"${name}\" has been deleted")
        } else {
            Message.error(sender, "Town \"${name}\" does not exist")
        }
    }

    /**
     * @command /nodesadmin town addplayer [name] [player1] [player2] ...
     * Add list of player names [player] to town from [name].
     */
    private fun addPlayerToTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addplayer [name] [player1] [player2] ...")
            Message.error(sender, "First player name is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res == null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            residents.add(res)
        }

        for (r in residents) {
            Nodes.addResidentToTown(town, r)
            Message.print(sender, "Added \"${r.name}\" to town \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town removeplayer [name] [player1] [player2] ...
     * Remove list of player names [player] from town from [name].
     */
    private fun removePlayerFromTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeplayer [name] [player1] [player2] ...")
            Message.error(sender, "First player name is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res == null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            residents.add(res)
        }

        for (r in residents) {
            Nodes.removeResidentFromTown(town, r)
            Message.print(sender, "Removed \"${r.name}\" from town \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town addterritory [name] [id1] [id2] ...
     * Add list of territory from their ids to town from [name].
     * This ignores chunk claiming limits for a town.
     */
    private fun addTerritoryToTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addterritory [name] [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr.town != null) {
                Message.error(sender, "Invalid territory id=$id: either does not exist or already has town")
                return
            } else {
                territories.add(terr)
            }
        }

        for (terr in territories) {
            Nodes.addTerritoryToTown(town, terr)
        }

        Message.print(sender, "Added ${territories.size} territories to town \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town removeterritory [name] [id1] [id2] ...
     * Remove list of territory from their ids to town from [name].
     */
    private fun removeTerritoryFromTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeterritory [name] [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr?.town != town) {
                Message.error(sender, "Invalid territory id=$id: does not belong to town")
                return
            } else if (town.home == terr.id) {
                Message.error(sender, "Cannot remove town's home territory id=$id")
                return
            } else {
                territories.add(terr)
            }
        }

        for (terr in territories) {
            Nodes.unclaimTerritory(town, terr)
        }

        Message.print(sender, "Removed ${territories.size} territories to town \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town captureterritory [town] [id1] [id2] ...
     * Makes town capture list of territory from their ids
     */
    private fun captureTerritory(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town captureterritory [name] [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val territories: MutableList<Territory> = mutableListOf()
        for (i in 3 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null || terr.town == town) {
                Message.error(sender, "Invalid territory id=$id: either does not exist or belongs to town")
                return
            } else {
                territories.add(terr)
            }
        }

        for (terr in territories) {
            Nodes.captureTerritory(town, terr)
        }

        Message.print(sender, "Captured ${territories.size} territories for town \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town releaseterritory [id1] [id2] ...
     * Releases list of territory ids from their current occupier
     */
    private fun releaseTerritory(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town releaseterritory [id1] [id2] ...")
            Message.error(sender, "First territory id is required")
            return
        }

        val territories: MutableList<Territory> = mutableListOf()
        for (i in 2 until args.size) {
            val id = TerritoryId(args[i].toInt())
            val terr = Nodes.territories[id]
            if (terr == null) {
                Message.error(sender, "Invalid territory id=$id: does not exist")
                return
            } else {
                territories.add(terr)
            }
        }

        for (terr in territories) {
            Nodes.releaseTerritory(terr)
        }

        Message.print(sender, "Released ${territories.size} territories under occupation")
    }

    /**
     * @command /nodesadmin town claimsbonus [town] [#]
     * Set town bonus claims.
     */
    private fun setClaimsBonus(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town claimsbonus [town] [#]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        if (args.size == 3) {
            Message.print(sender, "Town \"${townName}\" bonus claims: ${town.claimsBonus}")
        } else if (args.size > 3) {
            val bonus = args[3].toInt()

            Nodes.setClaimsBonus(town, bonus)

            Message.print(sender, "Town \"${townName}\" bonus claims set to $bonus")
        }
    }

    /**
     * @command /nodesadmin town claimspenalty [town] [#]
     * Set town penalty claims.
     */
    private fun setClaimsPenalty(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town claimspenalty [town] [#]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        if (args.size == 3) {
            Message.print(sender, "Town \"${townName}\" claims penalty: ${town.claimsPenalty}")
        } else if (args.size > 3) {
            val num = args[3].toInt()

            Nodes.setClaimsPenalty(town, num)

            Message.print(sender, "Town \"${townName}\" claims penalty set to $num")
        }
    }

    /**
     * @command /nodesadmin town claimsannex [town] [#]
     * Set town annexed claims penalty.
     */
    private fun setClaimsAnnexed(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town claimsannex [town] [#]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        if (args.size == 3) {
            Message.print(sender, "Town \"${townName}\" annexed claims penalty: ${town.claimsAnnexed}")
        } else if (args.size > 3) {
            val value = args[3].toInt()

            Nodes.setClaimsAnnexed(town, value)

            Message.print(sender, "Town \"${townName}\" annexed claims penalty set to $value")
        }
    }

    /**
     * @command /nodesadmin town addofficer [town] [player]
     * Add officer to town
     */
    private fun addTownOfficer(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town addofficer [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res === null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            if (res.town !== town) {
                Message.error(sender, "Player \"${args[i]}\" does not belong to ${town.name}")
                return
            }
            residents.add(res)
        }

        for (r in residents) {
            Nodes.townAddOfficer(town, r)
            Message.print(sender, "Made \"${r.name}\" officer of \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town removeofficer [town] [player]
     * Remove officer from town
     */
    private fun removeTownOfficer(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeofficer [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val residents: MutableList<Resident> = mutableListOf()
        for (i in 3 until args.size) {
            val res = Nodes.getResidentFromName(args[i])
            if (res === null) {
                Message.error(sender, "Player \"${args[i]}\" does not exist")
                return
            }
            if (res.town !== town) {
                Message.error(sender, "Player \"${args[i]}\" does not belong to ${town.name}")
                return
            }
            residents.add(res)
        }

        for (r in residents) {
            Nodes.townRemoveOfficer(town, r)
            Message.print(sender, "Removed \"${r.name}\" as officer of \"${town.name}\"")
        }
    }

    /**
     * @command /nodesadmin town leader [town] [player]
     * Set town resident as new town leader
     */
    private fun setTownLeader(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town leader [town] [player]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val playerName = args[3]
        val resident = Nodes.getResidentFromName(playerName)
        if (resident === null) {
            Message.error(sender, "Player \"${playerName}\" does not exist")
            return
        }
        if (resident.town !== town) {
            Message.error(sender, "Player \"${playerName}\" is not a member of \"${town.name}\"")
            return
        }

        Nodes.townSetLeader(town, resident)
        Message.print(sender, "Player \"${playerName}\" is now leader of \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town removeleader [town]
     * Remove town's leader (makes town leaderless)
     */
    private fun removeTownLeader(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town removeleader [town]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        Nodes.townSetLeader(town, null)
        Message.print(sender, "Removed leader of \"${town.name}\"")
    }

    /**
     * @command /nodesadmin town open [town]
     * Toggle whether town is open to join
     */
    private fun setTownOpen(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town open [town]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        Nodes.setTownOpen(town, !town.isOpen)

        Message.print(sender, "Town \"${townName}\" isOpen set to ${town.isOpen}")
    }

    /**
     * @command /nodesadmin town income [town]
     * View a town's income inventory gui (must run ingame).
     */
    private fun townIncome(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player == null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town income [town]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        player.openInventory(Nodes.getTownIncomeInventory(town))
    }

    /**
     * @command /nodesadmin town incomeadd [town]
     * Adds item player is holding to input town income inventories.
     */
    private fun townIncomeAdd(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player === null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town incomeadd [town]")
            return
        }

        val townName = args[2]
        val towns = Nodes.matchTowns(townName)
        if (towns.size == 0) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val item = player.inventory.itemInMainHand
        if (item === null || item.type == Material.AIR) {
            Message.error(sender, "You must be holding an item")
            return
        }

        for (town in towns) {
            Nodes.addToIncome(town, item.type, item.amount)
            Message.print(sender, "Added item to \"${town.name}\" income inventory")
        }
    }

    /**
     * @command /nodesadmin town incomeremove [town] [material]
     * Removes items in town income inventories. If material is not specified,
     * removes all items.
     */
    private fun townIncomeRemove(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player === null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town incomeremove [town] [material]")
            return
        }

        val townName = args[2]
        val towns = Nodes.matchTowns(townName)
        if (towns.size == 0) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val material = if (args.size >= 4) Material.matchMaterial(args[3]) else null

        for (town in towns) {
            Nodes.townIncomeRemove(town, material)
            if (material !== null) {
                Message.print(sender, "Removed $material from \"${town.name}\" income inventory")
            } else {
                Message.print(sender, "Removed all items from \"${town.name}\" income inventory")
            }
        }
    }

    /**
     * @command /nodesadmin town setspawn [town]
     * Set town spawn to player location (must run ingame).
     */
    private fun townSetSpawn(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player == null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town setspawn [town]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val result = Nodes.setTownSpawn(town, player.location)

        if (result == true) {
            Message.print(player, "Town \"${townName}\" spawn set to current location")
        } else {
            Message.error(player, "Spawn location must be within town's home territory")
        }
    }

    /**
     * @command /nodesadmin town spawn [town]
     * Spawn at a town's spawn
     */
    private fun townSpawn(sender: CommandSender, args: Array<String>) {
        val player: Player? = if (sender is Player) sender else null
        if (player == null) {
            Message.print(sender, "Must be run ingame")
            return
        }

        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town spawn [town]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        player.teleport(town.spawnpoint)
    }

    /**
     * @command /nodesadmin town sethome [town] [id]
     * Set a town's home territory
     */
    private fun setTownHome(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town sethome [name] [id]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val id = TerritoryId(args[3].toInt())
        val terr = Nodes.territories[id]
        if (terr == null) {
            Message.error(sender, "Invalid territory id=$id: does not exist")
            return
        }

        if (town !== terr.town) {
            Message.error(sender, "Invalid territory id=$id: does not belong to town")
            return
        }

        if (town.home == terr.id) {
            Message.error(sender, "Invalid territory id=$id: already is home territory")
            return
        }

        Nodes.setTownHomeTerritory(town, terr)
        Message.print(sender, "Moved \"${townName}\" home territory to id = ${terr.id}")
    }

    /**
     * @command /nodesadmin town setHomeCooldown [town] [int]
     * Set a town's move home cooldown
     */
    private fun setTownMoveHomeCooldown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town sethome [name] [id]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val cooldown: Long = Math.max(0L, args[3].toLong())

        Nodes.setTownHomeMoveCooldown(town, cooldown)
        Message.print(sender, "Set town \"${townName}\" move cooldown to $cooldown ms")
    }

    /**
     * @command /nodesadmin town addoutpost [town] [name] [id]
     * Add an outpost with name to town at territory id
     */
    private fun addOutpostToTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 5) {
            Message.error(sender, "Usage: /nodesadmin town addoutpost [town] [name] [id]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val name = args[3]
        if (town.outposts.contains(name)) {
            Message.error(sender, "Town already has outpost named: \"${name}\"")
            return
        }

        val id = TerritoryId(args[4].toInt())
        val terr = Nodes.territories.get(id)
        if (terr == null) {
            Message.error(sender, "Invalid territory id=$id: does not exist")
            return
        }

        if (town !== terr.town) {
            Message.error(sender, "Invalid territory id=$id: does not belong to town")
            return
        }

        val result = Nodes.createTownOutpost(town, name, terr)
        if (result == true) {
            Message.print(sender, "Created outpost \"${name}\" for \"${townName}\" in territory id = ${terr.id}")
        } else {
            Message.error(sender, "Failed to create outpost \"${name}\" for \"${townName}\"")
        }
    }

    /**
     * @command /nodesadmin town removeoutpost [town] [name]
     * Remove outpost from town with given name
     */
    private fun removeOutpostFromTown(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin town removeoutpost [town] [name]")
            return
        }

        val townName = args[2]
        val town = Nodes.towns.get(townName)
        if (town == null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        val name = args[3]
        if (!town.outposts.contains(name)) {
            Message.error(sender, "Town does not have outpost named: \"${name}\"")
            return
        }

        val result = Nodes.destroyTownOutpost(town, name)
        if (result == true) {
            Message.print(sender, "Removed outpost \"${name}\" from \"${townName}\"")
        } else {
            Message.error(sender, "Failed to remove outpost \"${name}\" from \"${townName}\"")
        }
    }

    /**
     * @command /nodesadmin town defaulttownspawns [town]
     * Reset town spawns to default position (highest block before air)
     */
    private fun defaultTownSpawns(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin town defaulttownspawns [town]")
            return
        }

        val townName = args[2]
        val towns = Nodes.matchTowns(townName)
        if (towns.size == 0) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }

        for (town in towns) {
            val terrHome = Nodes.territories.get(town.home)
            if (terrHome !== null) {
                val spawnpoint = Nodes.getDefaultSpawnLocation(terrHome)
                town.spawnpoint = spawnpoint
                town.needsUpdate()
                Message.print(sender, "Set town \"${town.name}\" spawnpoint to $spawnpoint")
            } else {
                Message.error(sender, "Town \"${town.name}\" home territory ${town.home} does not exist")
            }
        }

        Nodes.needsSave = true
    }

    // =============================================================
    // nation management commands:
    // - create/delete nations
    // - toggle nation properties, alliances, towns, ...
    // =============================================================

    private fun manageNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            printNationHelp(sender)
        } else {
            when (args[1].lowercase()) {
                "create" -> createNation(sender, args)
                "delete" -> deleteNation(sender, args)
                "addtown" -> addTownToNation(sender, args)
                "removetown" -> removeTownFromNation(sender, args)
                "capital" -> setNationCapital(sender, args)
                else -> {
                    printNationHelp(sender)
                }
            }
        }
    }

    private fun printNationHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Admin nation management:")
        Message.print(sender, "/nodesadmin nation create${ChatColor.WHITE}: Create a new nation")
        Message.print(sender, "/nodesadmin nation delete${ChatColor.WHITE}: Delete existing nation")
        Message.print(sender, "/nodesadmin nation addtown${ChatColor.WHITE}: Add towns to nation")
        Message.print(sender, "/nodesadmin nation removetown${ChatColor.WHITE}: Remove towns from nation")
        Message.print(sender, "/nodesadmin nation capital${ChatColor.WHITE}: Set nation's capital town")
        Message.print(sender, "Run a command with no args to see usage.")
    }

    /**
     * @command /nodesadmin nation create [name] [town1] [town2] ...
     * Creates a new nation with [name] with list of towns.
     * [town1] required (cannot have empty nations).
     */
    private fun createNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation create [name] [town1] [town2] ...")
            Message.error(sender, "List is town names")
            return
        }

        if (!stringInputIsValid(args[2])) {
            Message.error(sender, "Invalid nation name")
            return
        }
        val nationName = sanitizeString(args[2])

        val towns: MutableList<Town> = mutableListOf()
        for (i in 3 until args.size) {
            val townName = args[i]
            val town = Nodes.towns.get(townName)
            if (town == null || town.nation != null) {
                Message.error(sender, "Invalid town \"${townName}\": either does not exist or already has nation")
                return
            } else {
                towns.add(town)
            }
        }

        val nation = Nodes.createNation(nationName, towns[0], towns[0].leader).getOrElse({ err ->
            Message.error(sender, "Failed to create nation: ${err.message}")
            return
        })

        for (i in 1 until towns.size) {
            Nodes.addTownToNation(nation, towns[i])
        }

        Message.print(sender, "Created nation \"${nationName}\" with ${towns.size} towns")
    }

    /**
     * @command /nodesadmin nation delete [name]
     * Deletes nation [name].
     */
    private fun deleteNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin nation delete [name]")
            return
        }

        val name = args[2]
        val nation = Nodes.nations.get(name)
        if (nation != null) {
            Nodes.destroyNation(nation)
            Message.print(sender, "Nation \"${name}\" has been deleted")
        } else {
            Message.error(sender, "Nation \"${name}\" does not exist")
        }
    }

    /**
     * @command /nodesadmin nation addtown [name] [town1] [town2] ...
     * Add list of towns to nation [name].
     */
    private fun addTownToNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation addtown [name] [town1] [town2] ...")
            Message.error(sender, "First town name is required")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations.get(nationName)
        if (nation == null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        val towns: MutableList<Town> = mutableListOf()
        for (i in 3 until args.size) {
            val town = Nodes.towns.get(args[i])
            if (town == null || town.nation != null) {
                Message.error(sender, "Invalid town \"${args[i]}\": does not exist or has a nation already")
                return
            }
            towns.add(town)
        }

        for (town in towns) {
            Nodes.addTownToNation(nation, town)
            Message.print(sender, "Added town \"${town.name}\" to nation \"${nation.name}\"")
        }
    }

    /**
     * @command /nodesadmin nation removetown [name] [town1] [town2] ...
     * Remove list of towns from nation [name].
     */
    private fun removeTownFromNation(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation removetown [name] [town1] [town2] ...")
            Message.error(sender, "First town name is required")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations.get(nationName)
        if (nation == null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        val towns: MutableList<Town> = mutableListOf()
        for (i in 3 until args.size) {
            val town = Nodes.towns.get(args[i])
            if (town == null || town.nation != nation) {
                Message.error(sender, "Invalid town \"${args[i]}\": does not belong to nation")
                return
            }
            towns.add(town)
        }

        for (town in towns) {
            Nodes.removeTownFromNation(nation, town)
            Message.print(sender, "Removed town \"${town.name}\" from nation \"${nation.name}\"")
        }
    }

    /**
     * @command /nodesadmin nation capital [nation] [town]
     * Make input town the new capital of nation. Town must already
     * be part of the nation.
     */
    private fun setNationCapital(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            Message.error(sender, "Usage: /nodesadmin nation capital [nation] [town]")
            return
        }

        val nationName = args[2]
        val nation = Nodes.nations.get(nationName)
        if (nation === null) {
            Message.error(sender, "Nation \"${nationName}\" does not exist")
            return
        }

        val townName = args[3]
        val town = Nodes.getTownFromName(townName)
        if (town === null) {
            Message.error(sender, "Town \"${townName}\" does not exist")
            return
        }
        if (town.nation !== nation) {
            Message.error(sender, "Town does not belong to this nation")
            return
        }
        if (town === nation.capital) {
            Message.error(sender, "Town is already the nation capital")
            return
        }

        Nodes.setNationCapital(nation, town)

        Message.print(sender, "${town.name} is now the capital of ${nation.name}")
    }

    // =============================================================
    // Diplomacy commands
    // force add/remove war, peace, ally
    // =============================================================

    /**
     * @command /nodesadmin enemy [name1] [name2]
     * Sets enemy status between [name1] and [name2]
     * (either town or nation names)
     */
    private fun setEnemy(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin enemy [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.addEnemy(town1, town2)

        Message.print(sender, "Set war between $name1 and $name2")
    }

    /**
     * @command /nodesadmin peace [name1] [name2]
     * Removes enemy status between [name1] and [name2]
     * (either town or nation names)
     */
    private fun setPeace(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin peace [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.removeEnemy(town1, town2)

        Message.print(sender, "Set peace between $name1 and $name2")
    }

    /**
     * @command /nodesadmin ally [name1] [name2]
     * Makes [name1] and [name2] allies (either town or nation names).
     */
    private fun setAlly(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.addAlly(town1, town2)

        Message.print(sender, "Set alliance between $name1 and $name2")
    }

    /**
     * @command /nodesadmin allyremove [name1] [name2]
     * Removes alliance between [name1] and [name2] (either town or nation names).
     */
    private fun removeAlly(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.removeAlly(town1, town2)

        Message.print(sender, "Removed any alliance between $name1 and $name2")
    }

    /**
     * @command /nodesadmin truce [name1] [name2]
     * Sets a truce between [name1] and [name2] (either town or nation names).
     */
    private fun setTruce(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.addTruce(town1, town2)

        Message.print(sender, "Set truce between $name1 and $name2")
    }

    /**
     * @command /nodesadmin truceremove [name1] [name2]
     * Removes any truce between [name1] and [name2] (either town or nation names).
     */
    private fun removeTruce(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /nodesadmin ally [name1] [name2]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        Nodes.removeTruce(town1, town2)

        Message.print(sender, "Removed any truce between $name1 and $name2")
    }

    // =============================================================
    // treaty manipulation commands
    // - add/remove terms from active peace treaties (mostly for debug)
    // - main form:
    //   /nda treaty [name1] [name2] [add/remove] [term] [side: 0 or 1] [args...]
    //
    //   for term side, 0 == name1 and 1 == name2
    // =============================================================
    private fun manageTreaty(sender: CommandSender, args: Array<String>) {
        if (args.size < 6) {
            Message.error(sender, "Usage: /nodesadmin treaty [name1] [name2] [add/remove] [term] [side] [args...]")
            return
        }

        val name1 = args[1]
        val name2 = args[2]

        val nation1 = Nodes.nations.get(name1)
        val nation2 = Nodes.nations.get(name2)

        val town1 = if (nation1 !== null) {
            nation1.capital
        } else {
            Nodes.towns.get(name1)
        }

        val town2 = if (nation2 !== null) {
            nation2.capital
        } else {
            Nodes.towns.get(name2)
        }

        if (town1 == null) {
            Message.error(sender, "\"${name1}\" does not exist")
            return
        }
        if (town2 == null) {
            Message.error(sender, "\"${name2}\" does not exist")
            return
        }

        val treaty = Treaty.get(town1, town2)
        if (treaty === null) {
            Message.error(sender, "No treaty exists between ${town1.name} and ${town2.name}")
            return
        }

        var provider: Town? = null
        var receiver: Town? = null
        if (args[5] == "0") {
            provider = town1
            receiver = town2
        } else if (args[5] == "1") {
            provider = town2
            receiver = town1
        } else {
            Message.error(sender, "Term side arg[5] must be \"0\" or \"1\"")
            return
        }
        provider = provider as Town
        receiver = receiver as Town

        if (args[3] == "add") {
            when (args[4]) {
                "occupy" -> {
                    if (args.size < 7) {
                        Message.error(sender, "Usage: /nodesadmin treaty [name1] [name2] [add/remove] occupy [side] [id]")
                        return
                    }

                    val territory = Nodes.getTerritoryFromId(TerritoryId(args[6].toInt()))
                    if (territory === null) {
                        Message.error(sender, "Invalid territory id")
                        return
                    }

                    treaty.add(TreatyTermOccupation(provider, receiver, territory.id))

                    Message.print(sender, "Added occupation term to treaty between ${town1.name} and ${town2.name}")
                }
                "item" -> {
                    if (args.size < 8) {
                        Message.error(sender, "Usage: /nodesadmin treaty [name1] [name2] [add/remove] item [side] [type] [count]")
                        return
                    }

                    val itemType = Material.matchMaterial(args[6])
                    if (itemType == null) {
                        return
                    }
                    val itemCount = args[7].toInt()

                    treaty.add(TreatyTermItems(provider, receiver, ItemStack(itemType, itemCount), null))

                    Message.print(sender, "Added item term to treaty between ${town1.name} and ${town2.name}")
                }
            }
        } else if (args[3] == "remove") {
        } else {
            Message.error(sender, "arg[3] must be \"add\" or \"remove\"")
        }
    }

    // =============================================================
    // World save/load commands
    // =============================================================

    private fun saveWorld(sender: CommandSender, args: Array<String>) {
        if (args.size > 1 && args[1] == "sync") {
            Message.print(sender, "[Nodes] Saving world (sync)")
            Nodes.saveWorld(checkIfNeedsSave = false, async = false)
        } else {
            Message.print(sender, "[Nodes] Saving world (async)")
            Nodes.saveWorld(checkIfNeedsSave = false, async = true)
        }
    }

    private fun loadWorld(sender: CommandSender) {
        Message.print(sender, "[Nodes] Loading world")
        Nodes.loadWorld()
    }

    // =============================================================
    // Debug commands:
    // Commands to print fields from resident, town, nation objects
    // using reflection. Only intended for developer use.
    // =============================================================

    /**
     * @command /nodesadmin debug
     * Console command to debug runtime world object state.
     * General usage is /nodesadmin debug [type] [name/id] [field]
     *
     * @subcommand /nodesadmin debug resource [name] [field]
     *
     * @subcommand /nodesadmin debug chunk [x,z] [field]
     * To input coord, format is "x,z" with no spaces
     *
     * @subcommand /nodesadmin debug territory [id] [field]
     *
     * @subcommand /nodesadmin debug resident [name] [field]
     *
     * @subcommand /nodesadmin debug town [name] [field]
     *
     * @subcommand /nodesadmin debug nation [name] [field]
     */
    private fun debugger(sender: CommandSender, args: Array<String>) {
        if (args.size < 4) {
            printDebuggerHelp(sender)
            return
        }

        val instance: Any? = when (args[1].lowercase()) {
            "resource" -> Nodes.resourceNodes.get(args[2])
            "chunk" -> Nodes.territoryChunks.get(Coord.fromString(args[2]))
            "territory" -> Nodes.territories.get(TerritoryId(args[2].toInt()))
            "resident" -> Nodes.getResidentFromName(args[2])
            "town" -> Nodes.towns.get(args[2])
            "nation" -> Nodes.nations.get(args[2])
            else -> null
        }

        if (instance == null) {
            Message.error(sender, "Invalid object: ${args[2]}")
            return
        }

        val classType = instance.javaClass
        val fieldName = args[3]
        try {
            val classField = classType.getDeclaredField(fieldName)
            classField.setAccessible(true)
            println(classField.get(instance))
        } catch (e: NoSuchFieldException) {
            Message.error(sender, "No such field: $fieldName")
        }
    }
    private fun adminAnnounce(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /nda announce <message>")
            return
        }
        val msg = args.drop(1).joinToString(" ")
        val title = org.bukkit.ChatColor.translateAlternateColorCodes('&', msg)
        for (p in Bukkit.getOnlinePlayers()) {
            p.sendTitle(
                "${ChatColor.GOLD}${ChatColor.BOLD}Announcement",
                title,
                10, // fade in ticks
                70, // stay
                20, // fade out
            )
            p.sendMessage("${ChatColor.GOLD}[Announce] ${ChatColor.WHITE}$title")
        }
        Message.print(sender, "Announced to ${Bukkit.getOnlinePlayers().size} players")
    }

    private fun printDebuggerHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Runtime debugger:")
        Message.print(sender, "/nodesadmin debug resource${ChatColor.WHITE}: Get resource node data")
        Message.print(sender, "/nodesadmin debug chunk${ChatColor.WHITE}: Get chunk data")
        Message.print(sender, "/nodesadmin debug territory${ChatColor.WHITE}: Get territory data")
        Message.print(sender, "/nodesadmin debug resident${ChatColor.WHITE}: Get resident data")
        Message.print(sender, "/nodesadmin debug town${ChatColor.WHITE}: Get town data")
        Message.print(sender, "/nodesadmin debug nation${ChatColor.WHITE}: Get nation data")
        Message.print(sender, "Used to print debug info into system console from object data fields.")
        Message.print(sender, "General usage is \"/nodesadmin debug [type] [name/id] [field]\"")
    }
}
