/*
 * Economy menus: a main menu and a paged market browser. Clicks run the
 * matching /economy command, so every rule and permission check is the same
 * as typing the command.
 */

package phonon.nodes.economy

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
import org.bukkit.inventory.ItemStack
import phonon.nodes.Nodes
import phonon.nodes.bounty.BountyItems

private class MenuHolder(val kind: String, val page: Int, val ids: Array<String?>) : InventoryHolder {
    lateinit var inv: Inventory

    override fun getInventory(): Inventory = inv
}

object EconomyMenu {
    private fun named(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName("${ChatColor.RESET}$name")
        meta?.lore = lore.map { "${ChatColor.GRAY}$it" }
        item.itemMeta = meta
        return item
    }

    fun openMain(player: Player) {
        val holder = MenuHolder("main", 0, arrayOfNulls(27))
        val inv = Bukkit.createInventory(holder, 27, "Economy")
        holder.inv = inv
        inv.setItem(10, named(Material.GOLD_INGOT, "${ChatColor.GOLD}Your money", EconomyExtras.overview(player)))
        inv.setItem(12, named(Material.CHEST, "${ChatColor.GREEN}Market", listOf("Browse and buy player listings")))
        inv.setItem(14, named(Material.EMERALD, "${ChatColor.GREEN}Sell to server", listOf("Click: sell everything the server buys", "Shift-click: sell the item in your hand")))
        inv.setItem(16, named(Material.DIAMOND, "${ChatColor.AQUA}Richest players", listOf("Click to view the top list")))
        inv.setItem(22, named(Material.BOOK, "${ChatColor.YELLOW}History", listOf("Your latest transactions")))
        player.openInventory(inv)
    }

    fun openMarket(player: Player, requestedPage: Int) {
        val all = EconomyExtras.listings()
        val pages = maxOf(1, (all.size + EconomyExtras.PAGE_SIZE - 1) / EconomyExtras.PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val holder = MenuHolder("market", page, arrayOfNulls(54))
        val inv = Bukkit.createInventory(holder, 54, "Market ${page + 1}/$pages")
        holder.inv = inv
        val mine = player.uniqueId.toString()
        all.drop(page * EconomyExtras.PAGE_SIZE).take(EconomyExtras.PAGE_SIZE).forEachIndexed { slot, (id, listing) ->
            val item = BountyItems.decode(listing.item)?.clone() ?: ItemStack(Material.BARRIER)
            val meta = item.itemMeta
            val lore = (meta?.lore ?: mutableListOf()).toMutableList()
            lore.add("${ChatColor.GOLD}Price: ${Economy.show(listing.price)}")
            lore.add("${ChatColor.GRAY}Seller: ${Nodes.getResidentFromUUID(java.util.UUID.fromString(listing.seller))?.name ?: "unknown"}")
            lore.add(if (listing.seller == mine) "${ChatColor.RED}Click to cancel your listing" else "${ChatColor.GREEN}Click to buy")
            meta?.lore = lore
            item.itemMeta = meta
            inv.setItem(slot, item)
            holder.ids[slot] = id
        }
        if (page > 0) {
            inv.setItem(45, named(Material.ARROW, "Previous page", listOf()))
        }
        inv.setItem(49, named(Material.BARRIER, "Back", listOf()))
        if (page < pages - 1) {
            inv.setItem(53, named(Material.ARROW, "Next page", listOf()))
        }
        player.openInventory(inv)
    }

    // runs a command the way the player would type it, then reopens the market if asked
    internal fun run(player: Player, command: String, then: (() -> Unit)?) {
        player.closeInventory()
        player.performCommand(command)
        if (then != null) {
            Bukkit.getScheduler().runTask(Nodes.plugin!!, Runnable { then() })
        }
    }
}

class EconomyMenuListener : Listener {
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is MenuHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot < 0 || event.rawSlot >= event.view.topInventory.size) {
            return
        }
        val slot = event.rawSlot
        if (holder.kind == "main") {
            when (slot) {
                12 -> EconomyMenu.openMarket(player, 0)
                14 -> EconomyMenu.run(player, if (event.isShiftClick) "economy sell hand" else "economy sell all", null)
                16 -> EconomyMenu.run(player, "economy top players", null)
                22 -> EconomyMenu.run(player, "economy history player", null)
            }
            return
        }
        when (slot) {
            45 -> if (holder.page > 0) EconomyMenu.openMarket(player, holder.page - 1)

            49 -> EconomyMenu.openMain(player)

            53 -> EconomyMenu.openMarket(player, holder.page + 1)

            else -> {
                val id = holder.ids.getOrNull(slot) ?: return
                val own = EconomyExtras.listings().firstOrNull { it.first == id }?.second?.seller == player.uniqueId.toString()
                val command = if (own) "economy market cancel $id" else "economy market buy $id"
                EconomyMenu.run(player, command) { EconomyMenu.openMarket(player, holder.page) }
            }
        }
    }
}
