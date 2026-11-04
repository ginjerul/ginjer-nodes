/*
 * /calendar menu: a month grid (Monday to Sunday), one slot per day. A day with events shows the
 * icon of its first event and lists them all; click a day to read it in chat. Arrows change month.
 */

package phonon.nodes.calendar

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private class CalendarHolder : InventoryHolder {
    lateinit var inv: Inventory
    val actions = HashMap<Int, () -> Unit>()

    override fun getInventory(): Inventory = inv
}

object CalendarMenu {
    private fun icon(type: EventType): Material = when (type) {
        EventType.WAR -> Material.IRON_SWORD
        EventType.COMPETITION -> Material.GOLD_INGOT
        EventType.COMMUNITY -> Material.CAKE
        EventType.BOOST -> Material.DIAMOND_PICKAXE
        EventType.SPECIAL -> Material.NETHER_STAR
        EventType.OTHER -> Material.PAPER
    }

    private fun item(material: Material, name: String, lore: List<String> = emptyList(), amount: Int = 1, glow: Boolean = false): ItemStack {
        val stack = ItemStack(material, amount.coerceIn(1, 64))
        val meta = stack.itemMeta
        meta?.setDisplayName("${ChatColor.RESET}$name")
        meta?.lore = lore.map { "${ChatColor.GRAY}$it" }
        meta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        if (glow) {
            meta?.addEnchant(Enchantment.UNBREAKING, 1, true)
        }
        stack.itemMeta = meta
        return stack
    }

    fun open(player: Player, month: YearMonth = YearMonth.from(Calendar.today())) {
        val holder = CalendarHolder()
        val title = "Calendar - ${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"
        val inv = Bukkit.createInventory(holder, 54, title)
        holder.inv = inv
        val today = Calendar.today()
        val offset = month.atDay(1).dayOfWeek.value - 1

        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val index = offset + day - 1
            val slot = (index / 7) * 9 + 1 + index % 7
            val events = CalendarData.eventsOn(date)
            val isToday = date == today
            val head = "${if (isToday) "${ChatColor.GREEN}${ChatColor.BOLD}" else ChatColor.YELLOW}${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $day"
            val stack = if (events.isEmpty()) {
                item(
                    if (isToday) Material.LIME_STAINED_GLASS_PANE else Material.GRAY_STAINED_GLASS_PANE,
                    head,
                    listOf("Nothing planned"),
                    day,
                    isToday,
                )
            } else {
                item(
                    icon(events[0].eventType()),
                    head,
                    events.map { Calendar.eventLine(it) } + listOf("", "Click to read in chat"),
                    day,
                    isToday,
                )
            }
            inv.setItem(slot, stack)
            holder.actions[slot] = {
                player.closeInventory()
                Calendar.printDay(player, date)
            }
        }

        val todayEvents = CalendarData.eventsOn(today)
        inv.setItem(
            0,
            item(
                Material.CLOCK,
                "${ChatColor.GREEN}Today",
                listOf(Calendar.dateText(today)) + (if (todayEvents.isEmpty()) listOf("Nothing planned") else todayEvents.map { Calendar.eventLine(it) }) + listOf("", "Click to jump to this month"),
            ),
        )
        holder.actions[0] = { open(player) }

        val announcements = CalendarData.announcements(System.currentTimeMillis())
        inv.setItem(
            8,
            item(
                Material.BELL,
                "${ChatColor.RED}Announcements",
                if (announcements.isEmpty()) listOf("None right now") else announcements.map { it.text },
            ),
        )
        holder.actions[8] = {
            player.closeInventory()
            Calendar.printAnnouncements(player)
        }

        inv.setItem(45, item(Material.ARROW, "${ChatColor.YELLOW}Previous month"))
        holder.actions[45] = { open(player, month.minusMonths(1)) }
        inv.setItem(53, item(Material.ARROW, "${ChatColor.YELLOW}Next month"))
        holder.actions[53] = { open(player, month.plusMonths(1)) }

        player.openInventory(inv)
    }
}

class CalendarMenuListener : Listener {
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? CalendarHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) {
            return
        }
        val action = holder.actions[event.rawSlot] ?: return
        action()
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is CalendarHolder) {
            event.isCancelled = true
        }
    }
}
