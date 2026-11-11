/*
 * /captimes [node id]: how long it takes to capture one chunk of a node (territory).
 * Without an id it uses the node the player is standing in, like /territory.
 */

package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.war.FlagWar

class CapTimesCommand :
    CommandExecutor,
    TabCompleter {
    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("help", ignoreCase = true)) {
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Capture time commands:")
            Message.print(sender, "/captimes${ChatColor.WHITE}: Time to capture a chunk in the node you stand in")
            Message.print(sender, "/captimes <node id>${ChatColor.WHITE}: Time to capture a chunk in that node")
            return true
        }
        val territory: Territory? = if (args.isEmpty()) {
            if (sender !is Player) {
                Message.error(sender, "Usage: /captimes <node id>")
                return true
            }
            Nodes.getTerritoryFromBlock(sender.location.blockX, sender.location.blockZ)
        } else {
            args[0].toIntOrNull()?.let { Nodes.territories[TerritoryId(it)] }
        }
        if (territory == null) {
            Message.error(sender, if (args.isEmpty()) "No node at your location (use /captimes <node id>)" else "Invalid node id \"${args[0]}\"")
            return true
        }

        val town = territory.town
        val owner = town?.name ?: "unclaimed"
        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}Capture time per chunk: node ${territory.id} ($owner)")

        val enemy = FlagWar.captureTicks(territory, null, true)
        Message.print(sender, "${ChatColor.RED}Enemies attacking: ${ChatColor.WHITE}${format(enemy)}")
        if (town != null) {
            val defend = FlagWar.captureTicks(territory, null, false)
            Message.print(sender, "${ChatColor.GREEN}Owner and allies liberating: ${ChatColor.WHITE}${format(defend)}")
        }

        val notes = mutableListOf<String>()
        if (territory.bordersWilderness) {
            notes.add("borders wilderness x${Config.chunkAttackFromWastelandMultiplier}")
        }
        if (town != null && territory.id == town.home) {
            notes.add("home node x${Config.chunkAttackHomeMultiplier}")
        }
        if (territory.attackerTimeMultiplier != 1.0) {
            notes.add("node attacker multiplier x${territory.attackerTimeMultiplier}")
        }
        if (territory.defenderTimeMultiplier != 1.0) {
            notes.add("node defender multiplier x${territory.defenderTimeMultiplier}")
        }
        if (notes.isNotEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}Includes: ${notes.joinToString(", ")}")
        }
        if (town != null) {
            Message.print(sender, "${ChatColor.GRAY}Fortification level and supply lines can lower or raise this for enemies while the war is on")
        }
        if (Config.playersInChunkMultiplier) {
            Message.print(
                sender,
                "${ChatColor.GRAY}Each extra player in the chunk takes off ${(Config.playersInChunkMultiplierPerPlayer * 100).toInt()}%, down to x${Config.playersInChunkMultiplierMin}",
            )
        }
        return true
    }

    // ticks to min:sec
    private fun format(ticks: Double): String {
        val seconds = (ticks / 20.0).toLong().coerceAtLeast(0L)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> = emptyList()
}
