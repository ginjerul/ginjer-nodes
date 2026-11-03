/*
 * Permissions for /nodesadmin (/nda). `nodes.admin` is everything. To give lower staff only part
 * of it, grant `nodes.admin.<command>` (all of that command, e.g. nodes.admin.town) or
 * `nodes.admin.<command>.<action>` (one action, e.g. nodes.admin.town.addplayer or
 * nodes.admin.resident.towncooldown). Permission plugins that support wildcards also accept
 * nodes.admin.town.* and the like.
 */

package phonon.nodes.utils

import org.bukkit.permissions.Permissible

object AdminPerms {
    // /nda stats used its own node before; it still works
    private val aliases: Map<String, String> = mapOf("stats" to "nodes.stats.admin")

    // may run `command` (and `action`, when given)
    fun has(who: Permissible, command: String, action: String? = null): Boolean {
        if (who.hasPermission("nodes.admin") || who.hasPermission("nodes.admin.$command")) {
            return true
        }
        val alias = aliases[command]
        if (alias != null && who.hasPermission(alias)) {
            return true
        }
        return action != null && who.hasPermission("nodes.admin.$command.${action.lowercase()}")
    }

    // may run at least one action of `command`, used to hide what staff cannot use
    fun canSee(who: Permissible, command: String): Boolean {
        if (has(who, command)) {
            return true
        }
        return who.effectivePermissions.any { it.value && it.permission.startsWith("nodes.admin.$command.") }
    }

    // holds any nodes.admin permission at all
    fun any(who: Permissible): Boolean {
        if (who.hasPermission("nodes.admin") || who.hasPermission("nodes.stats.admin")) {
            return true
        }
        return who.effectivePermissions.any { it.value && it.permission.startsWith("nodes.admin.") }
    }
}
