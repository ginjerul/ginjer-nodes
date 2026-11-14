/**
 * IncomeInventory
 *
 * Inventory container for adding income to town,
 * Uses multiple containers for storing items:
 * - EnumMap<Material, Int> main internal container (maps item -> amount)
 * - Special maps included for items with special cases:
 *   - spawn eggs
 *
 * Reason is that in >=1.13, materials are flattened so only the normal
 * EnumMap will be required. In 1.8-1.12, we have to handle special cases
 * (e.g. spawn egg + spawn egg meta), and it's easier to add in special cases
 * rather than polluting the normal EnumMap structure to handle 1 item type.
 *
 * TODO: potentially special case storage for colored items (wool, concrete)
 * if requested by users
 */

package phonon.nodes.objects

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.EnumMap

public class IncomeInventory : InventoryHolder {

    val storage: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)

    val storageSpawnEgg: EnumMap<EntityType, Int> = EnumMap<EntityType, Int>(EntityType::class.java)

    @Suppress("PropertyName")
    val _inventory: Inventory = Bukkit.createInventory(this, 54, "Town Income")

    @Suppress("FunctionName")
    private fun _add(mat: Material, amount: Int) {
        this.storage.get(mat)?.let { current ->
            storage.put(mat, current + amount)
        } ?: run {
            storage.put(mat, amount)
        }
    }

    @Suppress("FunctionName")
    private fun _addSpawnEgg(type: EntityType, amount: Int) {
        this.storageSpawnEgg.get(type)?.let { current ->
            storageSpawnEgg.put(type, current + amount)
        } ?: run {
            storageSpawnEgg.put(type, amount)
        }
    }

    // meta: EntityType ordinal or damage value, depending on material
    public fun add(mat: Material, amount: Int, meta: Int = 0) {
        if (amount <= 0) {
            return
        }

        this._add(mat, amount)
    }

    public fun empty(): Boolean = (storage.size == 0) && (storageSpawnEgg.size == 0)

    /**
     * Removes up to [amount] of [mat], from storage first, then from the open inventory view.
     * Returns how many were removed. Used when a player on another shard takes income.
     */
    public fun take(mat: Material, amount: Int): Int {
        var taken = 0
        val stored = this.storage[mat] ?: 0
        if (stored > 0) {
            taken = minOf(stored, amount)
            if (stored - taken > 0) this.storage[mat] = stored - taken else this.storage.remove(mat)
        }
        if (taken < amount) {
            val left = this._inventory.removeItem(ItemStack(mat, amount - taken))
            taken += (amount - taken) - left.values.sumOf { it.amount }
        }
        return taken
    }

    public override fun getInventory(): Inventory {
        while (this.storage.size > 0) {
            val item = this.storage.iterator().next()
            val material = item.key
            val amount = item.value
            this.storage.remove(material)

            val itemsFailedToAdd = this._inventory.addItem(ItemStack(material, amount))
            if (itemsFailedToAdd.size > 0) {
                val leftoverItems = itemsFailedToAdd.get(0)!!
                this.storage.put(material, leftoverItems.amount)
                return this._inventory
            }
        }

        return this._inventory
    }

    // moves items into storage and clear inventory
    // use this before saving game state
    // (still potential to dupe items if storage cleared and
    // game crashes before next save finishes)
    //
    // By default, only pushes to backend if no players are viewing
    // the income (so items don't seem like they're disappearing)
    // "force" option force-pushes items to backend (e.g. on server close)
    //
    // return if items moved (needed to determine if town needsUpdate()):
    // - true: if any items moved
    // - false: if no items moved
    public fun pushToStorage(force: Boolean): Boolean {
        var hasMovedItems = false

        val viewers = this._inventory.viewers
        if (viewers.size == 0 || force) {
            for (itemStack in this._inventory.iterator()) {
                if (itemStack != null) {
                    this._add(itemStack.type, itemStack.amount)

                    hasMovedItems = true
                }
            }
            this._inventory.clear()
        }

        return hasMovedItems
    }
}
