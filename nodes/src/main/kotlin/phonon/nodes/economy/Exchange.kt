/*
 * Stock market. Items listed under economy.exchange.items are bought and sold
 * by the server at a price that follows its stock: the fewer units the server
 * holds, the higher the price; the more it holds, the lower. Players sell into
 * the stock and buy out of it. On a timer the stock moves back toward its
 * target, so shortages refill and gluts drain. Every unit is priced one at a
 * time, so big trades move the price as they go.
 *
 * Buying and selling the same unit are priced from the same level, with a
 * spread between them, so buying and selling back always loses the spread.
 * Call everything that changes stock from inside Economy.transaction.
 */

package phonon.nodes.economy

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import phonon.nodes.Nodes

internal class ExItem(val base: Long, val target: Long)

object Exchange {
    private const val MAX_CATCH_UP = 48

    internal var enabled = false
    private var items: Map<Material, ExItem> = emptyMap()
    private var restockMs = 30 * 60_000L
    private var restockPercent = 20.0
    private var sensitivity = 0.5
    private var minMultiplier = 0.25
    private var maxMultiplier = 4.0
    private var halfSpread = 0.10
    private var capMultiplier = 4.0

    fun loadConfig(economy: ConfigurationSection) {
        val sec = economy.getConfigurationSection("exchange")
        if (sec == null || !sec.getBoolean("enabled", true)) {
            enabled = false
            items = emptyMap()
            return
        }
        restockMs = sec.getLong("restockMinutes", 30L).coerceIn(1L, 10_080L) * 60_000L
        restockPercent = sec.getDouble("restockPercent", 20.0).coerceIn(0.1, 100.0)
        sensitivity = sec.getDouble("sensitivity", 0.5).coerceIn(0.0, 3.0)
        minMultiplier = sec.getDouble("minMultiplier", 0.25).coerceIn(0.01, 1.0)
        maxMultiplier = sec.getDouble("maxMultiplier", 4.0).coerceIn(1.0, 100.0)
        halfSpread = sec.getDouble("spreadPercent", 20.0).coerceIn(0.0, 90.0) / 200.0
        capMultiplier = sec.getDouble("capMultiplier", 4.0).coerceIn(1.0, 100.0)

        val read = LinkedHashMap<Material, ExItem>()
        val list = sec.getConfigurationSection("items")
        for (key in list?.getKeys(false) ?: emptySet()) {
            val material = Material.matchMaterial(key)
            val price = Economy.optionalMoney(list?.get("$key.price"), "exchange.items.$key.price")
            val target = list?.getLong("$key.target", 1000L) ?: 1000L
            if (material == null || !material.isItem || material.isAir || price <= 0 || target < 1) {
                Nodes.logger?.warning("[Economy] Ignoring exchange item '$key'")
                continue
            }
            read[material] = ExItem(price, target.coerceAtMost(10_000_000L))
        }
        items = read
        enabled = read.isNotEmpty()
    }

    fun has(material: Material): Boolean = enabled && items.containsKey(material)

    fun materials(): List<Material> = if (enabled) items.keys.sortedBy { it.name } else emptyList()

    fun target(material: Material): Long = items[material]?.target ?: 0L

    // units the server holds; a new item starts at its target
    fun stock(material: Material): Long = Economy.state.exchangeStock[material.name] ?: (items[material]?.target ?: 0L)

    private fun capacity(item: ExItem): Long = maxOf(item.target, (item.target * capMultiplier).toLong())

    // middle price when the server holds `level` units
    private fun mid(item: ExItem, level: Long): Long {
        val ratio = item.target.toDouble() / maxOf(level, 1L)
        val multiplier = Math.pow(ratio, sensitivity).coerceIn(minMultiplier, maxMultiplier)
        return maxOf(1L, Math.round(item.base * multiplier))
    }

    // price of the unit that takes stock from `level` to level - 1
    private fun ask(item: ExItem, level: Long): Long = Math.ceil(mid(item, level) * (1.0 + halfSpread)).toLong()

    // price of the unit that takes stock from level - 1 to `level`
    private fun bid(item: ExItem, level: Long): Long = Math.floor(mid(item, level) * (1.0 - halfSpread)).toLong()

    private fun buyCost(item: ExItem, current: Long, amount: Int): Long {
        var total = 0L
        for (i in 0 until amount) {
            total = Math.addExact(total, ask(item, current - i))
        }
        return total
    }

    private fun sellGain(item: ExItem, current: Long, amount: Int): Long {
        var total = 0L
        for (i in 0 until amount) {
            total = Math.addExact(total, bid(item, current + 1 + i))
        }
        return total
    }

    // price of the next unit a player buys, or null when sold out
    fun nextBuy(material: Material): Long? {
        val item = items[material] ?: return null
        val current = stock(material)
        return if (current > 0) ask(item, current) else null
    }

    // price of the next unit a player sells, or null when the server holds all it wants
    fun nextSell(material: Material): Long? {
        val item = items[material] ?: return null
        val current = stock(material)
        return if (current < capacity(item)) bid(item, current + 1) else null
    }

    // 1 when the price rose, -1 when it fell, 0 when about the same since the last restock
    fun trend(material: Material): Int {
        val item = items[material] ?: return 0
        val ref = Economy.state.exchangeRef[material.name] ?: return 0
        val now = mid(item, stock(material))
        return when {
            now > ref * 1.02 -> 1
            now < ref * 0.98 -> -1
            else -> 0
        }
    }

    // what selling `amount` would pay right now, limited to what the server still wants
    fun quoteSell(material: Material, amount: Int): Long {
        val item = items[material] ?: return 0L
        val current = stock(material)
        val room = (capacity(item) - current).coerceIn(0L, amount.toLong()).toInt()
        return sellGain(item, current, room)
    }

    // charges nothing itself; returns the cost and takes the units out of stock
    fun buy(material: Material, amount: Int): Long {
        val item = items[material] ?: throw EconomyException("The exchange does not trade that")
        val current = stock(material)
        if (current < amount) {
            throw EconomyException("The server only has $current ${material.name} in stock")
        }
        val cost = buyCost(item, current, amount)
        Economy.state.exchangeStock[material.name] = current - amount
        return cost
    }

    // pays nothing itself; returns the payout and adds the units to stock
    fun sell(material: Material, amount: Int): Long {
        val item = items[material] ?: throw EconomyException("The exchange does not trade that")
        val current = stock(material)
        val room = capacity(item) - current
        if (amount > room) {
            throw EconomyException("The server only wants ${maxOf(0L, room)} more ${material.name} right now")
        }
        val gain = sellGain(item, current, amount)
        Economy.state.exchangeStock[material.name] = current + amount
        return gain
    }

    // moves stock toward target once for every full interval that passed
    fun restock(now: Long) {
        if (!enabled) {
            return
        }
        val state = Economy.state
        if (state.exchangeLast == 0L) {
            state.exchangeLast = now
            refreshReferences()
            return
        }
        val intervals = (now - state.exchangeLast) / restockMs
        if (intervals <= 0L) {
            return
        }
        state.exchangeLast += intervals * restockMs
        repeat(minOf(intervals, MAX_CATCH_UP.toLong()).toInt()) {
            for ((material, item) in items) {
                val current = stock(material)
                val gap = item.target - current
                if (gap == 0L) {
                    continue
                }
                val step = maxOf(1L, Math.ceil(Math.abs(gap) * restockPercent / 100.0).toLong())
                val moved = minOf(step, Math.abs(gap))
                state.exchangeStock[material.name] = current + if (gap > 0) moved else -moved
            }
        }
        refreshReferences()
    }

    // the reference price trends are measured against
    private fun refreshReferences() {
        for ((material, item) in items) {
            Economy.state.exchangeRef[material.name] = mid(item, stock(material))
        }
    }

    // admin: put every item back at its target stock
    fun reset() {
        for ((material, item) in items) {
            Economy.state.exchangeStock[material.name] = item.target
        }
        refreshReferences()
    }

    fun minutesToRestock(now: Long): Long {
        val last = Economy.state.exchangeLast
        if (last == 0L) {
            return restockMs / 60_000L
        }
        return maxOf(0L, (last + restockMs - now + 59_999L) / 60_000L)
    }
}
