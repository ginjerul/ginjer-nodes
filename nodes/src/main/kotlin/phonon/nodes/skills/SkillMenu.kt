/*
 * /skills: a small menu with the player's level, progress and bonus in each skill.
 */

package phonon.nodes.skills

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

private class SkillHolder : InventoryHolder {
    lateinit var inv: Inventory

    override fun getInventory(): Inventory = inv
}

object SkillMenu {
    private fun bar(fraction: Double, width: Int = 20): String {
        val filled = (fraction.coerceIn(0.0, 1.0) * width).toInt()
        return "${ChatColor.GREEN}${"|".repeat(filled)}${ChatColor.DARK_GRAY}${"|".repeat(width - filled)}"
    }

    fun open(player: Player) {
        val holder = SkillHolder()
        val inv = Bukkit.createInventory(holder, 27, "Skills")
        holder.inv = inv
        val uuid = player.uniqueId.toString()
        val slots = listOf(10, 12, 14, 16)
        for ((i, skill) in Skill.values().withIndex()) {
            val xp = SkillData.xp(uuid, skill)
            val level = Skills.levelOf(xp)
            val stack = ItemStack(skill.icon)
            val meta = stack.itemMeta
            meta?.setDisplayName("${ChatColor.GOLD}${skill.label} ${ChatColor.WHITE}$level")
            val lore = mutableListOf<String>()
            if (level < Skills.maxLevel) {
                val into = Skills.progressIn(xp)
                val need = Skills.xpForNext(level)
                lore.add(bar(into.toDouble() / need))
                lore.add("${ChatColor.GRAY}$into / $need xp to level ${level + 1}")
            } else {
                lore.add("${ChatColor.GREEN}Max level")
            }
            lore.add("")
            lore.add("${ChatColor.GRAY}Extra drop chance: ${ChatColor.WHITE}${String.format("%.1f", Skills.dropPercent(level))}%")
            meta?.lore = lore
            meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            stack.itemMeta = meta
            inv.setItem(slots[i], stack)
        }
        player.openInventory(inv)
    }
}

class SkillMenuListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder is SkillHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is SkillHolder) {
            event.isCancelled = true
        }
    }
}
