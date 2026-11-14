/*
 * Sharding phase 2: actions on a follower that move items or need more than a command line
 * (town income, selling to and buying from the server shop, chest protection). The leader keeps
 * the state (town income, the economy ledger, protected chests); the follower has the player and
 * their inventory.
 *
 * Exactly once: every action has an id. Before applying it the leader claims the key
 * `<prefix>:txn:<id>` with SET NX. A follower that gets no answer claims the same key as
 * "cancelled": if that works, the leader never applied it and the follower gives back anything it
 * took from the player; if not, the leader's stored result says what happened.
 *
 * Items for a player (income taken, shop purchases) go to their mailbox `<prefix>:mail:<uuid>`.
 * Whichever shard the player is on takes them out with LPOP, so nothing is given twice and nothing
 * is lost if the player moves to another shard or logs off first.
 */

package phonon.nodes.shard

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.economy.EconomyException
import phonon.nodes.economy.EconomyExtras
import phonon.nodes.objects.Town
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object RemoteActions {
    // after this long without an answer the follower tries to cancel
    private const val ANSWER_WAIT_MS = 8_000L

    // the leader claimed the action but never finished it (crashed while applying)
    private const val GIVE_UP_MS = 60_000L

    private const val TXN_TTL_SECONDS = "3600"

    private fun txnKey(id: String) = "${Shards.keyPrefix}:txn:$id"

    private fun mailKey(uuid: UUID) = "${Shards.keyPrefix}:mail:$uuid"

    // ===================================
    // follower: which commands are actions
    // ===================================

    /** Main thread, follower. True if this command was handled here as an action. */
    fun handleCommand(player: Player, root: String, args: List<String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()
        val second = args.getOrNull(1)?.lowercase()
        when {
            root == "town" && sub == "income" -> openIncome(player)
            root == "economy" && sub == "sell" && (second == "hand" || second == "all") -> sellInventory(player, second == "all")
            root == "economy" && sub == "shop" && second == "sell" -> shopSell(player, args)
            root == "economy" && sub == "shop" && second == "buy" -> shopBuy(player, args)
            else -> return false
        }
        return true
    }

    // ===================================
    // follower: send, wait, finish
    // ===================================
    private class Pending(
        val id: String,
        val uuid: UUID?,
        val escrow: List<ItemStack>,
        val sentAt: Long,
    ) {
        @Volatile
        var checking = false
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Main thread. [escrow]: items already taken from the player, given back if the action fails. */
    private fun send(player: Player?, action: String, payload: JsonObject, escrow: List<ItemStack> = emptyList()) {
        val plugin = Shards.pluginOrNull ?: return
        val id = UUID.randomUUID().toString()
        val json = JsonObject()
        json.addProperty("action", action)
        json.addProperty("id", id)
        json.addProperty("shard", Shards.name)
        if (player != null) {
            json.addProperty("uuid", player.uniqueId.toString())
            json.addProperty("name", player.name)
        }
        json.add("data", payload)
        pending[id] = Pending(id, player?.uniqueId, escrow, System.currentTimeMillis())
        val text = json.toString()
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    Shards.redis { it.command("RPUSH", Shards.commandsKey, text) }
                } catch (e: Exception) {
                    // never reached the leader: nothing happened
                    Shards.failed(e)
                    plugin.server.scheduler.runTask(plugin, Runnable { finish(id, false, "Cannot reach the main server right now, try again in a moment") })
                }
            },
        )
    }

    /** Any thread: an "action" message from the leader. */
    fun onReply(text: String) {
        val plugin = Shards.pluginOrNull ?: return
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return
        }
        val id = json.get("id")?.asString ?: return
        val ok = json.get("ok")?.asBoolean ?: false
        val message = json.get("message")?.asString ?: ""
        val uuid = json.get("uuid")?.takeIf { !it.isJsonNull }?.asString?.let { UUID.fromString(it) }
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                if (pending.containsKey(id)) {
                    finish(id, ok, message)
                } else if (uuid != null && Bukkit.getPlayer(uuid) != null) {
                    // the player moved here from the shard that asked: their items are in the mailbox
                    drainMailbox(uuid)
                }
            },
        )
    }

    // main thread
    private fun finish(id: String, ok: Boolean, message: String) {
        val p = pending.remove(id) ?: return
        val uuid = p.uuid ?: return
        if (!ok && p.escrow.isNotEmpty()) {
            give(uuid, p.escrow)
        }
        val player = Bukkit.getPlayer(uuid)
        if (player != null && message.isNotEmpty()) {
            if (ok) Message.print(player, message) else Message.error(player, message)
        }
        drainMailbox(uuid)
    }

    /** Main thread, follower, once a second: actions the leader has not answered. */
    fun tick() {
        val plugin = Shards.pluginOrNull ?: return
        val now = System.currentTimeMillis()
        for (p in pending.values) {
            if (p.checking || now - p.sentAt < ANSWER_WAIT_MS) {
                continue
            }
            p.checking = true
            plugin.server.scheduler.runTaskAsynchronously(
                plugin,
                Runnable {
                    try {
                        val cancelled = Shards.redis { it.command("SET", txnKey(p.id), "cancelled", "NX", "EX", TXN_TTL_SECONDS) }
                        if (cancelled == "OK") {
                            plugin.server.scheduler.runTask(plugin, Runnable { finish(p.id, false, "The main server did not answer in time. Nothing was changed; try again in a moment") })
                            return@Runnable
                        }
                        val state = (Shards.redis { it.command("GET", txnKey(p.id)) } as? ByteArray)?.let { String(it, Charsets.UTF_8) }
                        if (state != null && state.startsWith("{")) {
                            val json = JsonParser.parseString(state).asJsonObject
                            plugin.server.scheduler.runTask(
                                plugin,
                                Runnable { finish(p.id, json.get("ok").asBoolean, json.get("message").asString) },
                            )
                        } else if (System.currentTimeMillis() - p.sentAt > GIVE_UP_MS) {
                            plugin.logger.warning("[Shards] Action ${p.id} was started by the leader but never finished")
                            plugin.server.scheduler.runTask(
                                plugin,
                                Runnable {
                                    pending.remove(p.id)
                                    p.uuid?.let { Bukkit.getPlayer(it) }?.let {
                                        Message.error(it, "The main server did not confirm that action. Check the result before trying again")
                                    }
                                },
                            )
                        } else {
                            p.checking = false
                        }
                    } catch (e: Exception) {
                        Shards.failed(e)
                        p.checking = false
                    }
                },
            )
        }
    }

    // ===================================
    // mailbox: items for a player, on any shard
    // ===================================

    /** Any thread: give [uuid] whatever is waiting in their mailbox, if they are on this shard. */
    fun drainMailbox(uuid: UUID) {
        val plugin = Shards.pluginOrNull ?: return
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val items = ArrayList<ItemStack>()
                try {
                    while (true) {
                        val entry = (Shards.redis { it.command("LPOP", mailKey(uuid)) } as? ByteArray)?.let { String(it, Charsets.UTF_8) } ?: break
                        parseItems(entry, items)
                    }
                } catch (e: Exception) {
                    Shards.failed(e)
                }
                if (items.isNotEmpty()) {
                    plugin.server.scheduler.runTask(plugin, Runnable { give(uuid, items) })
                }
            },
        )
    }

    // main thread: into the inventory, the rest at their feet; back to the mailbox if they left
    private fun give(uuid: UUID, items: List<ItemStack>) {
        val player = Bukkit.getPlayer(uuid)
        if (player == null) {
            mail(uuid, items)
            return
        }
        val left = player.inventory.addItem(*items.map { it.clone() }.toTypedArray())
        for (item in left.values) {
            player.world.dropItemNaturally(player.location, item)
        }
        player.saveData()
    }

    // any thread
    private fun mail(uuid: UUID, items: List<ItemStack>) {
        if (items.isEmpty()) {
            return
        }
        val entry = itemsJson(items.map { it.type to it.amount })
        val send = Runnable {
            try {
                Shards.redis { it.command("RPUSH", mailKey(uuid), entry) }
            } catch (e: Exception) {
                Shards.failed(e)
            }
        }
        val plugin = Shards.pluginOrNull ?: return
        if (Bukkit.isPrimaryThread()) plugin.server.scheduler.runTaskAsynchronously(plugin, send) else send.run()
    }

    private fun itemsJson(items: List<Pair<Material, Int>>): String {
        val array = JsonArray()
        for ((material, amount) in items) {
            val o = JsonObject()
            o.addProperty("m", material.name)
            o.addProperty("a", amount)
            array.add(o)
        }
        return array.toString()
    }

    private fun parseItems(text: String, into: MutableList<ItemStack>) {
        try {
            for (e in JsonParser.parseString(text).asJsonArray) {
                val o = e.asJsonObject
                val material = Material.matchMaterial(o.get("m").asString) ?: continue
                var amount = o.get("a").asInt
                while (amount > 0) {
                    val n = minOf(amount, material.maxStackSize.coerceAtLeast(1))
                    into.add(ItemStack(material, n))
                    amount -= n
                }
            }
        } catch (_: Exception) {
        }
    }

    // ===================================
    // follower: town income
    // ===================================
    private class IncomeView(val town: String) : InventoryHolder {
        lateinit var view: Inventory

        override fun getInventory(): Inventory = view
    }

    private fun openIncome(player: Player) {
        val resident = Nodes.getResident(player)
        val town = resident?.town
        if (town == null) {
            Message.error(player, "You do not belong to a town")
            return
        }
        if (!Nodes.canTakeTownIncome(resident, town)) {
            Message.error(player, "You do not have permissions to view town income")
            return
        }
        val holder = IncomeView(town.name)
        holder.view = Bukkit.createInventory(holder, 54, "Town Income")
        fill(holder.view, town)
        player.openInventory(holder.view)
    }

    private fun fill(inventory: Inventory, town: Town) {
        inventory.clear()
        for ((material, amount) in town.income.storage) {
            if (amount > 0) {
                inventory.addItem(ItemStack(material, amount))
            }
        }
    }

    /** Main thread, after this shard loaded new state: update open income views. */
    fun refreshViews() {
        for (player in Bukkit.getOnlinePlayers()) {
            val holder = player.openInventory.topInventory.holder as? IncomeView ?: continue
            val town = Nodes.getTownFromName(holder.town)
            if (town == null) {
                player.closeInventory()
            } else {
                fill(holder.view, town)
            }
        }
    }

    // ===================================
    // follower: selling and buying
    // ===================================
    private fun playing(player: Player): Boolean {
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) {
            Message.error(player, "Use survival or adventure mode")
            return false
        }
        return true
    }

    private fun sellInventory(player: Player, all: Boolean) {
        if (!playing(player)) {
            return
        }
        val taken = ArrayList<ItemStack>()
        val inventory = player.inventory
        if (all) {
            val contents = inventory.storageContents
            for (slot in contents.indices) {
                val item = contents[slot]
                if (EconomyExtras.buysPlainItem(item)) {
                    taken.add(item!!.clone())
                    contents[slot] = null
                }
            }
            if (taken.isEmpty()) {
                Message.error(player, "You have nothing the server buys. See /economy prices")
                return
            }
            inventory.storageContents = contents
        } else {
            val item = inventory.itemInMainHand
            if (!EconomyExtras.buysPlainItem(item)) {
                Message.error(player, "The server does not buy that item. See /economy prices")
                return
            }
            taken.add(item.clone())
            inventory.setItemInMainHand(null)
        }
        player.saveData()
        send(player, "sell", countsJson(taken), taken)
    }

    private fun shopSell(player: Player, args: List<String>) {
        if (!playing(player)) {
            return
        }
        val material = args.getOrNull(2)?.let { Material.matchMaterial(it) }
        val wanted = args.getOrNull(3)?.toIntOrNull()
        if (material == null || wanted == null || wanted < 1 || wanted > 2304) {
            Message.error(player, "Usage: /economy shop sell <material> <amount 1-2304>")
            return
        }
        if (!EconomyExtras.buysPlainItem(ItemStack(material))) {
            Message.error(player, "The server does not buy that item. See /economy prices")
            return
        }
        val contents = player.inventory.storageContents
        val taken = ArrayList<ItemStack>()
        var left = wanted
        for (slot in contents.indices) {
            val item = contents[slot]
            if (left > 0 && item != null && item.type == material && !item.hasItemMeta()) {
                val take = minOf(left, item.amount)
                left -= take
                taken.add(ItemStack(material, take))
                if (take == item.amount) contents[slot] = null else item.amount -= take
            }
        }
        if (taken.isEmpty()) {
            Message.error(player, "You have no plain ${material.name} to sell")
            return
        }
        player.inventory.storageContents = contents
        player.saveData()
        send(player, "sell", countsJson(taken), taken)
    }

    private fun shopBuy(player: Player, args: List<String>) {
        if (!playing(player)) {
            return
        }
        val material = args.getOrNull(2)?.let { Material.matchMaterial(it) }
        val amount = args.getOrNull(3)?.toIntOrNull()
        if (material == null || amount == null || amount < 1 || amount > 2304) {
            Message.error(player, "Usage: /economy shop buy <material> <amount 1-2304>")
            return
        }
        // the items arrive later: check for space now, like the shop does
        if (!EconomyExtras.hasSpaceFor(player, material, amount)) {
            Message.error(player, "Make space in your inventory first")
            return
        }
        val payload = JsonObject()
        payload.addProperty("material", material.name)
        payload.addProperty("amount", amount)
        send(player, "shopBuy", payload)
    }

    private fun countsJson(items: List<ItemStack>): JsonObject {
        val counts = JsonObject()
        val sums = EnumMap<Material, Int>(Material::class.java)
        for (item in items) {
            sums[item.type] = (sums[item.type] ?: 0) + item.amount
        }
        for ((material, amount) in sums) {
            counts.addProperty(material.name, amount)
        }
        val payload = JsonObject()
        payload.add("counts", counts)
        return payload
    }

    // ===================================
    // follower: chest protection (from Nodes.protectTownChest)
    // ===================================
    fun protectChest(town: Town, blocks: List<Block>, protect: Boolean) {
        val payload = JsonObject()
        payload.addProperty("town", town.name)
        payload.addProperty("protect", protect)
        payload.addProperty("world", blocks.firstOrNull()?.world?.name ?: "world")
        val array = JsonArray()
        for (block in blocks) {
            val xyz = JsonArray()
            xyz.add(block.x)
            xyz.add(block.y)
            xyz.add(block.z)
            array.add(xyz)
        }
        payload.add("blocks", array)
        send(null, "chestProtect", payload)
    }

    // ===================================
    // leader: claim and apply
    // ===================================
    private class Outcome(val ok: Boolean, val message: String, val give: List<Pair<Material, Int>> = emptyList())

    /** Leader, worker thread: [text] is an action request. */
    fun onRequest(text: String) {
        val plugin = Shards.pluginOrNull ?: return
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return
        }
        val id = json.get("id")?.asString ?: return
        // the follower may have given up and cancelled it already
        val claimed = Shards.redis { it.command("SET", txnKey(id), "running", "NX", "EX", TXN_TTL_SECONDS) }
        if (claimed != "OK") {
            plugin.logger.info("[Shards] Skipped action $id: cancelled by ${json.get("shard")?.asString}")
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable { apply(id, json) })
    }

    // main thread, leader
    private fun apply(id: String, json: JsonObject) {
        val plugin = Shards.pluginOrNull ?: return
        val uuid = json.get("uuid")?.asString?.let { UUID.fromString(it) }
        val data = json.getAsJsonObject("data")
        val outcome = try {
            when (json.get("action").asString) {
                "incomeTake" -> incomeTake(uuid, data)
                "sell" -> sell(uuid, data)
                "shopBuy" -> buy(uuid, data)
                "chestProtect" -> chestProtect(data)
                else -> Outcome(false, "Unknown action")
            }
        } catch (e: EconomyException) {
            Outcome(false, e.message ?: "That did not work")
        } catch (e: Exception) {
            plugin.logger.warning("[Shards] Action ${json.get("action")} from ${json.get("shard")} failed: $e")
            Outcome(false, "That failed on the main server")
        }
        // save and publish the change, then hand out items, store the result and answer
        Shards.flushSoon { hash ->
            try {
                if (uuid != null && outcome.give.isNotEmpty()) {
                    Shards.redis { it.command("RPUSH", mailKey(uuid), itemsJson(outcome.give)) }
                }
                val result = JsonObject()
                result.addProperty("ok", outcome.ok)
                result.addProperty("message", outcome.message)
                Shards.redis { it.command("SET", txnKey(id), result.toString(), "XX", "EX", TXN_TTL_SECONDS) }
                val reply = JsonObject()
                reply.addProperty("id", id)
                if (uuid != null) reply.addProperty("uuid", uuid.toString())
                reply.addProperty("ok", outcome.ok)
                reply.addProperty("message", outcome.message)
                reply.addProperty("hash", hash)
                Shards.sendEvent("action", reply.toString())
            } catch (e: Exception) {
                Shards.failed(e)
            }
        }
    }

    private fun incomeTake(uuid: UUID?, data: JsonObject): Outcome {
        val resident = uuid?.let { Nodes.getResidentFromUUID(it) } ?: return Outcome(false, "You do not belong to a town")
        val town = resident.town ?: return Outcome(false, "You do not belong to a town")
        if (town.name != data.get("town").asString) {
            return Outcome(false, "You are no longer in that town")
        }
        if (!Nodes.canTakeTownIncome(resident, town)) {
            return Outcome(false, "You do not have permissions to view town income")
        }
        val material = Material.matchMaterial(data.get("material").asString) ?: return Outcome(false, "Unknown item")
        val wanted = data.get("amount").asInt.coerceIn(1, 64 * 54)
        val taken = town.income.take(material, wanted)
        if (taken <= 0) {
            return Outcome(false, "Someone already took that")
        }
        town.needsUpdate()
        Nodes.needsSave = true
        return Outcome(true, "", listOf(material to taken))
    }

    private fun sell(uuid: UUID?, data: JsonObject): Outcome {
        uuid ?: return Outcome(false, "Unknown player")
        val counts = EnumMap<Material, Int>(Material::class.java)
        for ((name, amount) in data.getAsJsonObject("counts").entrySet()) {
            val material = Material.matchMaterial(name) ?: return Outcome(false, "Unknown item $name")
            counts[material] = amount.asInt
        }
        return Outcome(true, EconomyExtras.sellCounts(uuid, counts))
    }

    private fun buy(uuid: UUID?, data: JsonObject): Outcome {
        uuid ?: return Outcome(false, "Unknown player")
        val material = Material.matchMaterial(data.get("material").asString) ?: return Outcome(false, "Unknown material")
        val amount = data.get("amount").asInt
        val message = EconomyExtras.shopCharge(uuid, material, amount)
        return Outcome(true, message, listOf(material to amount))
    }

    private fun chestProtect(data: JsonObject): Outcome {
        val town = Nodes.getTownFromName(data.get("town").asString) ?: return Outcome(false, "")
        val world = Bukkit.getWorld(data.get("world").asString) ?: return Outcome(false, "")
        val protect = data.get("protect").asBoolean
        for (e in data.getAsJsonArray("blocks")) {
            val xyz = e.asJsonArray
            val block = world.getBlockAt(xyz[0].asInt, xyz[1].asInt, xyz[2].asInt)
            if (protect) {
                // only chests in the town's own land
                if (Nodes.getTerritoryFromBlock(block.x, block.z)?.town === town) {
                    town.protectedBlocks.add(block)
                }
            } else {
                town.protectedBlocks.remove(block)
            }
        }
        town.needsUpdate()
        Nodes.needsSave = true
        return Outcome(true, "")
    }

    // ===================================
    // events on every shard
    // ===================================
    class ActionListener : Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        fun onJoin(event: PlayerJoinEvent) {
            drainMailbox(event.player.uniqueId)
        }

        // follower income view: a click takes the clicked stack from the leader's town income
        @EventHandler(priority = EventPriority.HIGHEST)
        fun onClick(event: InventoryClickEvent) {
            val holder = event.view.topInventory.holder as? IncomeView ?: return
            event.isCancelled = true
            val player = event.whoClicked as? Player ?: return
            if (event.clickedInventory !== event.view.topInventory) {
                return
            }
            val item = event.currentItem ?: return
            if (item.type == Material.AIR || item.amount <= 0) {
                return
            }
            val payload = JsonObject()
            payload.addProperty("town", holder.town)
            payload.addProperty("material", item.type.name)
            payload.addProperty("amount", item.amount)
            // gone from this view right away; the leader decides how many the player really gets
            event.clickedInventory?.setItem(event.slot, null)
            send(player, "incomeTake", payload)
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        fun onDrag(event: InventoryDragEvent) {
            if (event.view.topInventory.holder is IncomeView) {
                event.isCancelled = true
            }
        }
    }
}
