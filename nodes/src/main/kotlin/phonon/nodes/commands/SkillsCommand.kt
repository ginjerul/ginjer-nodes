/*
 * /skills: opens the skills menu. /skills top <skill> prints the ten highest players.
 */

package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.skills.Skill
import phonon.nodes.skills.SkillData
import phonon.nodes.skills.SkillMenu
import phonon.nodes.skills.Skills

class SkillsCommand :
    CommandExecutor,
    TabCompleter {
    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (!Skills.isEnabled()) {
            Message.error(sender, "Skills are turned off")
            return true
        }
        if (args.isNotEmpty() && args[0].equals("top", ignoreCase = true)) {
            val skill = args.getOrNull(1)?.let { Skill.parse(it) }
            if (skill == null) {
                Message.error(sender, "Usage: /skills top <${Skill.values().joinToString("|") { it.name.lowercase() }}>")
                return true
            }
            Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}Top ${skill.label}")
            val top = SkillData.top(skill, 10)
            if (top.isEmpty()) {
                Message.print(sender, "${ChatColor.GRAY}Nobody has any yet")
            }
            for ((i, entry) in top.withIndex()) {
                Message.print(sender, "${ChatColor.YELLOW}${i + 1}. ${ChatColor.WHITE}${entry.first} ${ChatColor.GRAY}level ${Skills.levelOf(entry.second)}")
            }
            return true
        }
        if (sender is Player) {
            SkillMenu.open(sender)
        } else {
            Message.print(sender, "Usage: /skills top <skill>")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            return listOf("top").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("top", ignoreCase = true)) {
            return Skill.values().map { it.name.lowercase() }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
