/**
 * Port commands
 * /port [command]
 */

package phonon.nodes.commands

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.economy.Economy
import phonon.nodes.economy.EconomyException
import phonon.nodes.economy.EconomyExtras
import phonon.nodes.objects.Port
import phonon.nodes.objects.Town
import phonon.nodes.ports.PortPolicies
import phonon.nodes.ports.PortPolicy
import phonon.nodes.ports.PortRequests
import phonon.nodes.tasks.PortWarpTask
import phonon.nodes.utils.string.filterByStart
import phonon.nodes.utils.string.filterPort

private val PORT_COMMANDS: List<String> = listOf(
    "help",
    "list",
    "info",
    "warp",
    "allow",
    "fee",
    "request",
)

private val PORT_RELATIONS: List<String> = listOf("ally", "neutral", "enemy")

private fun fee(cents: Long): String = if (cents > 0) " (fee ${Economy.show(cents)})" else ""

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

public class PortCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        if (args.size == 0) {
            printHelp(sender)
            return true
        }

        val arg = args[0].lowercase()
        when (arg) {
            "help" -> printHelp(sender)

            "list" -> list(sender)

            "info" -> info(sender, args)

            "warp" -> doWarp(sender, args)

            "allow" -> allow(sender, args)

            "fee" -> setFee(sender, args)

            "request" -> request(sender, args)

            else -> {
                Message.error(sender, "Invalid port command")
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            return filterByStart(PORT_COMMANDS, args[0])
        } else if (args.size > 1) {
            when (args[0].lowercase()) {
                "info" -> {
                    if (args.size == 2) {
                        return filterPort(args[1])
                    }
                }

                "warp" -> {
                    if (args.size == 2) {
                        return filterPort(args[1])
                    }
                }

                "allow", "fee" -> when (args.size) {
                    2 -> return filterByStart(PORT_RELATIONS, args[1])
                    3 -> if (args[0].equals("allow", ignoreCase = true)) return filterByStart(listOf("true", "false"), args[2])
                }

                "request" -> {
                    if (args.size == 2) {
                        return filterByStart(listOf("cancel"), args[1])
                    }
                    if (args.size > 2) {
                        return filterByStart(Nodes.portGroups.keys.toList(), args[args.size - 1])
                    }
                }
            }
        }

        return listOf()
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}[Nodes] Port Commands:")
        Message.print(sender, "${ChatColor.AQUA}/port help${ChatColor.WHITE}: help")
        Message.print(sender, "${ChatColor.AQUA}/port list${ChatColor.WHITE}: list all ports")
        Message.print(sender, "${ChatColor.AQUA}/port info${ChatColor.WHITE}: print info about a port")
        Message.print(sender, "${ChatColor.AQUA}/port warp${ChatColor.WHITE}: warp to a port")
        Message.print(sender, "${ChatColor.AQUA}/port allow [ally|neutral|enemy] [true|false]${ChatColor.WHITE}: your town sets who may warp to ALL of its ports (no value shows the current setting)")
        Message.print(sender, "${ChatColor.AQUA}/port fee [ally|neutral|enemy] [amount]${ChatColor.WHITE}: your town sets the warp fee for ALL of its ports, paid to the town treasury")
        Message.print(sender, "${ChatColor.AQUA}/port request [name] [groups...]${ChatColor.WHITE}: ask admins for a port at your position (/port request cancel)")
        return
    }

    // the sender's own town, when they are its leader or an officer: port access and fees
    // are one policy for all of that town's ports
    private fun managedTown(sender: CommandSender): Town? {
        val resident = if (sender is Player) Nodes.getResident(sender) else null
        val town = resident?.town
        if (resident === null || town === null) {
            Message.error(sender, "Only a town leader or officer can change its port settings")
            return null
        }
        if (town.leader !== resident && !town.officers.contains(resident)) {
            Message.error(sender, "Only the leader or an officer of ${town.name} can change its port settings")
            return null
        }
        return town
    }

    private fun policyLines(sender: CommandSender, town: Town) {
        val policy = PortPolicies.of(town)
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Port access for ${town.name}'s ports:")
        Message.print(sender, "${ChatColor.AQUA}- Own town and nation: free")
        Message.print(sender, "${ChatColor.AQUA}- Allies: ${yesNo(policy.allowAlly)}${fee(policy.costAlly)}")
        Message.print(sender, "${ChatColor.AQUA}- Neutral: ${yesNo(policy.allowNeutral)}${fee(policy.costNeutral)}")
        Message.print(sender, "${ChatColor.AQUA}- Enemies: ${yesNo(policy.allowEnemy)}${fee(policy.costEnemy)}")
    }

    private fun allow(sender: CommandSender, args: Array<String>) {
        if (args.size == 1) {
            val town = (if (sender is Player) Nodes.getResident(sender)?.town else null)
            if (town === null) {
                Message.error(sender, "Usage: /port allow [ally|neutral|enemy] [true|false] (join a town to see its settings)")
            } else {
                policyLines(sender, town)
            }
            return
        }
        if (args.size < 3) {
            Message.error(sender, "Usage: /port allow [ally|neutral|enemy] [true|false]")
            return
        }
        val town = managedTown(sender) ?: return
        val value = args[2].lowercase().toBooleanStrictOrNull()
        if (value === null) {
            Message.error(sender, "Use true or false")
            return
        }
        val change: (PortPolicy) -> Unit = when (args[1].lowercase()) {
            "ally" -> { p -> p.allowAlly = value }

            "neutral" -> { p -> p.allowNeutral = value }

            "enemy" -> { p -> p.allowEnemy = value }

            else -> {
                Message.error(sender, "Group must be ally, neutral or enemy")
                return
            }
        }
        PortPolicies.edit(town, change)
        Message.print(sender, "${ChatColor.GREEN}${args[1].lowercase()} warp to all of ${town.name}'s ports is now ${if (value) "allowed" else "blocked"}")
    }

    private fun setFee(sender: CommandSender, args: Array<String>) {
        if (!Economy.enabled) {
            Message.error(sender, "Port fees need the economy to be enabled")
            return
        }
        if (args.size < 3) {
            Message.error(sender, "Usage: /port fee [ally|neutral|enemy] [amount] (0 = free)")
            return
        }
        val town = managedTown(sender) ?: return
        val cents = try {
            if (args[2] == "0") 0L else Economy.parseMoney(args[2])
        } catch (e: EconomyException) {
            Message.error(sender, e.message ?: "Invalid amount")
            return
        }
        if (cents > Economy.portMaxFee) {
            Message.error(sender, "The highest allowed fee is ${Economy.show(Economy.portMaxFee)}")
            return
        }
        val change: (PortPolicy) -> Unit = when (args[1].lowercase()) {
            "ally" -> { p -> p.costAlly = cents }

            "neutral" -> { p -> p.costNeutral = cents }

            "enemy" -> { p -> p.costEnemy = cents }

            else -> {
                Message.error(sender, "Group must be ally, neutral or enemy")
                return
            }
        }
        PortPolicies.edit(town, change)
        Message.print(sender, "${ChatColor.GREEN}${args[1].lowercase()} warp fee for all of ${town.name}'s ports is now ${Economy.show(cents)}")
    }

    private fun request(sender: CommandSender, args: Array<String>) {
        val player = if (sender is Player) sender else null
        if (player === null) {
            Message.error(sender, "Only players in game can use")
            return
        }
        if (args.size < 2) {
            Message.error(sender, "Usage: /port request [name] [groups...] while standing where the port should be")
            return
        }
        if (args[1].equals("cancel", ignoreCase = true)) {
            val error = PortRequests.cancel(player)
            if (error !== null) {
                Message.error(player, error)
            } else {
                Message.print(player, "${ChatColor.GREEN}Port request cancelled")
            }
            return
        }
        val error = PortRequests.submit(player, args[1], args.drop(2))
        if (error !== null) {
            Message.error(player, error)
            return
        }
        Message.print(player, "${ChatColor.GREEN}Port request \"${args[1].lowercase()}\" sent to the admins")
    }

    private fun list(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}List of ports:")
        val ports = Nodes.ports
        for (group in Nodes.portGroups.values) {
            Message.print(sender, "${ChatColor.AQUA}${group.name}:")
            for (port in ports.values) {
                if (port.groups.contains(group)) {
                    val groupNames = port.groups.joinToString(", ") { it.name }

                    val status = if (port.isPublic) {
                        "(public)"
                    } else {
                        "(owned)"
                    }

                    Message.print(
                        sender,
                        "- ${ChatColor.AQUA}${port.name} ${ChatColor.GRAY}- $groupNames $status",
                    )
                }
            }
        }

        Message.print(sender, "${ChatColor.DARK_AQUA}Use \"/port info [name]\" for details")
    }

    private fun info(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /port info [name]")
            return
        }

        val portName = args[1].lowercase()
        val port = Nodes.ports.get(portName)
        if (port !== null) {
            Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Port ${port.name}:")
            Message.print(sender, "${ChatColor.AQUA}- (x,z): (${port.locX}, ${port.locZ})")
            Message.print(sender, "${ChatColor.AQUA}- Groups:")
            for (group in port.groups) {
                Message.print(sender, "${ChatColor.AQUA}  - ${group.name}")
            }

            if (port.isPublic) {
                Message.print(sender, "${ChatColor.AQUA}- Public")
            } else {
                val owner = Nodes.getPortOwner(port)
                val ownerName = if (owner !== null) {
                    owner.name
                } else {
                    "${ChatColor.GRAY}None"
                }
                Message.print(sender, "${ChatColor.AQUA}- Owner: $ownerName")
                val policy = if (owner !== null) PortPolicies.of(owner) else PortPolicy()
                Message.print(sender, "${ChatColor.AQUA}- Own town and nation: free")
                Message.print(sender, "${ChatColor.AQUA}- Allies: ${yesNo(policy.allowAlly)}${fee(policy.costAlly)}")
                Message.print(sender, "${ChatColor.AQUA}- Neutral: ${yesNo(policy.allowNeutral)}${fee(policy.costNeutral)}")
                Message.print(sender, "${ChatColor.AQUA}- Enemies: ${yesNo(policy.allowEnemy)}${fee(policy.costEnemy)}")
            }
        }
    }

    private fun doWarp(sender: CommandSender, args: Array<String>) {
        val player = if (sender is Player) sender else null
        if (player === null) {
            Message.error(sender, "Only players in game can use")
            return
        }

        if (args.size < 2) {
            Message.error(sender, "Usage: /port warp [destination]")
            return
        }

        val portName = args[1].lowercase()

        doPortWarp(player, portName)
    }
}

internal fun doPortWarp(
    player: Player,
    destinationName: String,
) {
    val playerUuid = player.getUniqueId()

    if (Nodes.playerWarpTasks.contains(playerUuid)) {
        Message.print(player, "${ChatColor.RED}You are already warping somewhere")
        return
    }

    val locPlayer = player.location
    val source = Nodes.chunkToPort.get(listOf(Math.floorDiv(locPlayer.x.toInt(), 16), Math.floorDiv(locPlayer.z.toInt(), 16)))
    if (source === null) {
        Message.print(player, "${ChatColor.RED}You must be in the same chunk as a port to warp")
        return
    }

    val destination = Nodes.ports.get(destinationName.lowercase())
    if (destination === null) {
        Message.error(player, "Port does not exist...")
        return
    }

    if (source === destination) {
        Message.error(player, "You are already at this port...")
        return
    }

    if (!Nodes.sharePortGroups(source, destination)) {
        Message.error(player, "These ports are not in the same region group...")
        return
    }

    var warpFee = 0L
    var feeOwner: Town? = null
    if (!destination.isPublic) {
        val owner = Nodes.getPortOwner(destination)
        if (owner !== null) {
            val relation = Nodes.getRelationshipOfPlayerToTown(player, owner)

            val policy = PortPolicies.of(owner)
            val canAccess: Boolean
            val cost: Long
            when (relation) {
                DiplomaticRelationship.TOWN,
                DiplomaticRelationship.NATION,
                -> {
                    canAccess = true
                    cost = 0L
                }

                DiplomaticRelationship.ALLY -> {
                    canAccess = policy.allowAlly
                    cost = policy.costAlly
                }

                DiplomaticRelationship.NEUTRAL -> {
                    canAccess = policy.allowNeutral
                    cost = policy.costNeutral
                }

                DiplomaticRelationship.ENEMY -> {
                    canAccess = policy.allowEnemy
                    cost = policy.costEnemy
                }
            }

            if (!canAccess) {
                Message.error(player, "Port $destinationName's owner ${owner.name} does not allow ${relation.name.lowercase()} players to warp (you are $relation)")
                return
            }

            // fees are money from the wallet; without the economy, warps are free
            if (cost > 0 && Economy.enabled) {
                if (!EconomyExtras.canPay(player, cost)) {
                    Message.error(player, "Warping to $destinationName costs ${Economy.show(cost)} and your wallet has less. See /economy balance player")
                    return
                }
                warpFee = cost
                feeOwner = owner
            }
        }
    }

    val playersToWarp: ArrayList<Player> = arrayListOf()
    val entitiesToWarp: ArrayList<Entity> = arrayListOf()

    val entityVehicle = player.getVehicle()
    if (entityVehicle === null) {
        if (!Config.allowPortWarpWithoutBoat) {
            Message.error(player, "You must be in a boat or a ship vehicle to warp to ports")
            return
        }

        playersToWarp.add(player)
    } else {
        if (entityVehicle.type.toString().contains("_BOAT")) {
            entitiesToWarp.add(entityVehicle)
        }
    }

    // warping without a ship takes longer
    val warpTime = if (entityVehicle === null) Config.portWarpTime * Config.portNotInShipMultiplier else Config.portWarpTime

    val task = PortWarpTask(
        player,
        destination,
        playersToWarp.toList(),
        entitiesToWarp.toList(),
        player.location.clone(),
        warpTime,
        2.0,
        warpFee,
        feeOwner,
    )

    Nodes.playerWarpTasks.put(
        player.getUniqueId(),
        task.runTaskTimerAsynchronously(Nodes.plugin!!, 2, 2),
    )
}

internal fun warpToPort(
    destination: Port,
    playersToWarp: List<Player>,
    entitiesToWarp: List<Entity>,
) {
    val defaultWorld = Bukkit.getWorlds().get(0)
    val x = destination.locX.toDouble()
    val y = Config.seaLevel
    val z = destination.locZ.toDouble()

    for (player in playersToWarp) {
        player.teleport(Location(defaultWorld, x, y, z))
    }

    for (entity in entitiesToWarp) {
        teleportEntity(entity, Location(defaultWorld, x, y, z))
    }
}

internal fun teleportEntity(entity: Entity, destination: Location) {
    val passengers = entity.getPassengers()

    // remove players from boats and teleport to destination
    for (p in passengers) {
        p.eject()
        entity.removePassenger(p)
        p.teleport(destination)
    }

    // schedule entity teleport (after players already teleported)
    Bukkit.getScheduler().runTaskLater(
        Nodes.plugin!!,
        object : Runnable {
            override fun run() {
                entity.teleport(destination)

                // force the chunk to load at destination to makes sure the entity syncs to the client
                val chunk = entity.location.chunk
                if (!chunk.isLoaded) {
                    chunk.load()
                }
            }
        },
        1L,
    )

    // schedule re-attaching player to boat
    Bukkit.getScheduler().runTaskLater(
        Nodes.plugin!!,
        object : Runnable {
            override fun run() {
                for (p in passengers) {
                    entity.addPassenger(p)
                }
            }
        },
        2L,
    )
}

/**
 * Create progress bar string. Input should be double
 * in range [0.0, 1.0] marking progress.
 */
internal fun progressBar(progress: Double): String = when (Math.round(progress * 10.0).toInt()) {
    0 -> "\u2503\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
    1 -> "\u2503\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
    2 -> "\u2503\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
    3 -> "\u2503\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
    4 -> "\u2503\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2592\u2503"
    5 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2592\u2503"
    6 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2592\u2503"
    7 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2592\u2503"
    8 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2592\u2503"
    9 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2592\u2503"
    10 -> "\u2503\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2503"
    else -> ""
}
