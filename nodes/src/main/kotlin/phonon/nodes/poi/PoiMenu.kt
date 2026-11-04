/*
 * /map: the places a player has discovered, newest first, with a page for every 45.
 * Clicking one prints where it is.
 */

package phonon.nodes.poi

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message

private class PoiHolder : InventoryHolder {
    lateinit var inv: Inventory
    val actions = HashMap<Int, () -> Unit>()

    override fun getInventory(): Inventory = inv
}

object PoiMenu {
    private const val PAGE = 45

    private fun item(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val stack = ItemStack(material)
        val meta = stack.itemMeta
        meta?.setDisplayName("${ChatColor.RESET}$name")
        meta?.lore = lore.map { "${ChatColor.GRAY}$it" }
        meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        stack.itemMeta = meta
        return stack
    }

    private fun wrap(text: String, width: Int = 36): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(" ")) {
            if (line.isNotEmpty() && line.length + word.length + 1 > width) {
                lines.add(line)
                line = word
            } else {
                line = if (line.isEmpty()) word else "$line $word"
            }
        }
        if (line.isNotEmpty()) {
            lines.add(line)
        }
        return lines
    }

    fun open(player: Player, page: Int = 0) {
        val uuid = player.uniqueId.toString()
        val progress = PoiData.peek(uuid)
        val found = PoiData.pois()
            .filter { progress?.found?.containsKey("${it.id}") == true }
            .sortedByDescending { progress?.found?.get("${it.id}") ?: 0L }

        val pages = maxOf(1, (found.size + PAGE - 1) / PAGE)
        val current = page.coerceIn(0, pages - 1)
        val holder = PoiHolder()
        val total = if (Pois.showTotal) "/${PoiData.pois().size}" else ""
        val inv = Bukkit.createInventory(holder, 54, "Discoveries (${found.size}$total)")
        holder.inv = inv

        for ((i, poi) in found.drop(current * PAGE).take(PAGE).withIndex()) {
            val lore = mutableListOf("${ChatColor.YELLOW}${poi.poiType().label}")
            if (poi.description.isNotBlank()) {
                lore.addAll(wrap(poi.description))
            }
            lore.add("")
            lore.add("Found: ${Pois.date(progress?.found?.get("${poi.id}") ?: 0L)}")
            if (poi.firstByName != null) {
                lore.add("First found by: ${poi.firstByName}")
            }
            lore.add("")
            lore.add("Click to see where it is")
            inv.setItem(i, item(poi.poiType().icon, "${ChatColor.GOLD}${poi.name}", lore))
            holder.actions[i] = {
                player.closeInventory()
                Message.print(player, "${ChatColor.GOLD}${poi.name} ${ChatColor.GRAY}in ${poi.world} at ${poi.x.toInt()}, ${poi.y.toInt()}, ${poi.z.toInt()}")
            }
        }
        if (found.isEmpty()) {
            inv.setItem(22, item(Material.COMPASS, "${ChatColor.YELLOW}Nothing discovered yet", listOf("Explore the world to find places.")))
        }

        val titles = progress?.titles ?: emptyList()
        inv.setItem(
            49,
            item(
                Material.NAME_TAG,
                "${ChatColor.LIGHT_PURPLE}Titles",
                (if (titles.isEmpty()) listOf("None earned yet") else titles.map { Pois.colour(it) }) +
                    listOf("", "Selected: ${progress?.selectedTitle?.let { Pois.colour(it) } ?: "none"}", "Use /map title <name|none>"),
            ),
        )
        if (current > 0) {
            inv.setItem(45, item(Material.ARROW, "${ChatColor.YELLOW}Previous page"))
            holder.actions[45] = { open(player, current - 1) }
        }
        if (current < pages - 1) {
            inv.setItem(53, item(Material.ARROW, "${ChatColor.YELLOW}Next page"))
            holder.actions[53] = { open(player, current + 1) }
        }
        player.openInventory(inv)
    }
}

class PoiMenuListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? PoiHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) {
            return
        }
        holder.actions[event.rawSlot]?.invoke()
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is PoiHolder) {
            event.isCancelled = true
        }
    }
}
