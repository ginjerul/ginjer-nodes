/*
 * /nda skills: set a player's level, add xp or reset them. Permission nodes.admin.skills or
 * nodes.admin.skills.<set|addxp|reset|show>.
 */

package phonon.nodes.skills

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import phonon.nodes.Message
import phonon.nodes.utils.AdminPerms

object SkillsAdmin {
    private val ACTIONS = listOf("show", "set", "addxp", "reset")

    fun complete(args: Array<String>): List<String> {
        fun starts(options: List<String>, text: String) = options.filter { it.startsWith(text, ignoreCase = true) }
        return when {
            args.size == 2 -> starts(ACTIONS, args[1])
            args.size == 3 -> starts(Bukkit.getOnlinePlayers().map { it.name }, args[2])
            args.size == 4 && args[1].lowercase() in listOf("set", "addxp") -> starts(Skill.values().map { it.name.lowercase() }, args[3])
            else -> emptyList()
        }
    }

    fun handle(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(1)?.lowercase()
        if (sub == null || sub !in ACTIONS) {
            Message.print(sender, "/nda skills show <player> | set <player> <skill> <level> | addxp <player> <skill> <xp> | reset <player>")
            return
        }
        if (!AdminPerms.has(sender, "skills", sub)) {
            Message.error(sender, "You do not have permission for /nodesadmin skills $sub")
            return
        }
        val name = args.getOrNull(2)
        if (name == null) {
            Message.error(sender, "Give a player name")
            return
        }
        val uuid = Bukkit.getOfflinePlayer(name).uniqueId.toString()
        when (sub) {
            "show" -> {
                for (skill in Skill.values()) {
                    val xp = SkillData.xp(uuid, skill)
                    Message.print(sender, "${ChatColor.GOLD}${skill.label} ${ChatColor.WHITE}${Skills.levelOf(xp)} ${ChatColor.GRAY}($xp xp)")
                }
            }
            "set", "addxp" -> {
                val skill = args.getOrNull(3)?.let { Skill.parse(it) }
                val value = args.getOrNull(4)?.toLongOrNull()
                if (skill == null || value == null || value < 0) {
                    Message.error(sender, "Usage: /nda skills $sub <player> <${Skill.values().joinToString("|") { it.name.lowercase() }}> <number>")
                    return
                }
                val xp = if (sub == "set") Skills.totalXpForLevel(value.toInt()) else SkillData.xp(uuid, skill) + value
                SkillData.setXp(uuid, name, skill, xp)
                SkillData.flush()
                Message.print(sender, "${ChatColor.GREEN}$name ${skill.label} is now level ${Skills.levelOf(xp)}")
            }
            "reset" -> {
                if (SkillData.reset(uuid)) {
                    SkillData.flush()
                    Message.print(sender, "${ChatColor.GREEN}Reset all skills of $name")
                } else {
                    Message.error(sender, "$name has no skill progress")
                }
            }
        }
    }
}
