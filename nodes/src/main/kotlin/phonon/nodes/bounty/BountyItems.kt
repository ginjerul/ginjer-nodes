package phonon.nodes.bounty

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

internal object BountyItems {

    fun encode(item: ItemStack): String? = try {
        val out = ByteArrayOutputStream()
        BukkitObjectOutputStream(out).use { it.writeObject(item) }
        Base64.getEncoder().encodeToString(out.toByteArray())
    } catch (_: Exception) {
        null
    }

    fun decode(data: String): ItemStack? = try {
        BukkitObjectInputStream(ByteArrayInputStream(Base64.getDecoder().decode(data))).use {
            it.readObject() as ItemStack
        }
    } catch (_: Exception) {
        null
    }

    fun summary(items: List<ItemStack>): String {
        val parts = items.take(4).map { "${it.amount}x ${it.type.name}" }.toMutableList()
        if (items.size > 4) {
            parts.add("+${items.size - 4} stacks")
        }
        return parts.joinToString(", ")
    }

    // splits an amount of one material into full stacks
    fun stacks(type: Material, amount: Int): List<ItemStack> {
        val result = mutableListOf<ItemStack>()
        var left = amount
        while (left > 0) {
            val moved = minOf(type.maxStackSize, left)
            result.add(ItemStack(type, moved))
            left -= moved
        }
        return result
    }

    private fun copy(input: Array<ItemStack?>): Array<ItemStack?> = Array(input.size) { input[it]?.clone() }

    private fun isEmpty(item: ItemStack?): Boolean = item == null || item.type == Material.AIR || item.type == Material.CAVE_AIR || item.type == Material.VOID_AIR

    // returns the inventory after taking the required items, or null if any are missing
    fun remove(current: Array<ItemStack?>, required: List<ItemStack>): Array<ItemStack?>? {
        val result = copy(current)
        for (want in required) {
            var remaining = want.amount
            var slot = 0
            while (slot < result.size && remaining > 0) {
                val held = result[slot]
                if (held != null && held.isSimilar(want)) {
                    val taken = minOf(held.amount, remaining)
                    held.amount = held.amount - taken
                    remaining -= taken
                    if (held.amount <= 0) {
                        result[slot] = null
                    }
                }
                slot++
            }
            if (remaining > 0) {
                return null
            }
        }
        return result
    }

    // returns the inventory after adding the items, or null if they do not all fit
    fun add(current: Array<ItemStack?>, incoming: List<ItemStack>, inventoryLimit: Int): Array<ItemStack?>? {
        val limit = if (inventoryLimit < 1 || inventoryLimit > 64) 64 else inventoryLimit
        val result = copy(current)
        for (item in incoming) {
            var remaining = item.amount
            for (held in result) {
                if (remaining == 0) {
                    break
                }
                if (held != null && held.isSimilar(item)) {
                    val room = maxOf(0, minOf(held.maxStackSize, limit) - held.amount)
                    val moved = minOf(room, remaining)
                    held.amount = held.amount + moved
                    remaining -= moved
                }
            }
            var slot = 0
            while (slot < result.size && remaining > 0) {
                if (isEmpty(result[slot])) {
                    val moved = minOf(minOf(item.maxStackSize, limit), remaining)
                    val placed = item.clone()
                    placed.amount = moved
                    result[slot] = placed
                    remaining -= moved
                }
                slot++
            }
            if (remaining > 0) {
                return null
            }
        }
        return result
    }
}
