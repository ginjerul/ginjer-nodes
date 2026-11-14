/*
 * Sharding phase 2: Nodes commands typed on a follower shard run on the leader.
 *
 * Follower: a command that changes Nodes state is not run locally. It goes to the leader as a
 * request (player, position, Nodes permissions, the command line) on the Redis list
 * `<prefix>:commands`. The player gets the leader's messages back once this shard has loaded the
 * state the command produced, so a following `/town info` is already up to date.
 *
 * Leader: takes requests from that list and runs them with the normal command code for a
 * stand-in player (RemotePlayer). Then it saves and publishes at once, so every shard sees the
 * change within about a second, and sends the collected messages back on `<prefix>:events`.
 */

package phonon.nodes.shard

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object RemoteCommands {
    enum class Route { LOCAL, FORWARD, REFUSE }

    // requests older than this are dropped by the leader: the player was already told it failed
    private const val REQUEST_MAX_AGE_MS = 10_000L

    // a follower gives up waiting for the leader after this long
    private const val REPLY_TIMEOUT_MS = 6_000L

    // a reply is held until this shard has the leader's new state, but no longer than this
    private const val HOLD_MAX_MS = 3_000L

    // ===================================
    // which commands run where (on a follower)
    // ===================================

    // read only: answered from this shard's mirror
    private val townLocal = setOf("help", "list", "info", "online", "onlinetop", "map", "minimap")
    private val nationLocal = setOf("help", "list", "info", "online", "onlinetop")
    private val economyLocal = setOf("help", "prices")

    // change state but need nothing from the player except who and where they are
    private val townForward = setOf(
        "create", "new", "delete", "disband", "officer", "promote", "demote", "leader",
        "apply", "join", "invite", "accept", "deny", "reject", "leave", "kick", "setspawn",
        "color", "claim", "unclaim", "prefix", "suffix", "rename", "pvp", "perms", "permissions",
        "trust", "untrust", "capital", "annex", "outpost",
    )
    private val nationForward = setOf(
        "announce", "create", "new", "delete", "disband", "leave", "capital", "invite",
        "accept", "deny", "reject", "color", "rename",
    )

    // economy: the leader keeps the one ledger, so even balances are read there. Not forwarded:
    // anything that opens a menu or takes items from the inventory.
    private val economyRefuse = setOf("menu", "sell", "market", "shop")

    // diplomacy commands, forwarded whole
    private val diplomacyRoots = setOf("ally", "unally", "war", "peace", "truce")

    /** null when [root] is not one of the commands this file decides. */
    fun route(root: String, sub: String?): Route? = when (root) {
        "town" -> when (sub) {
            null, in townLocal -> Route.LOCAL
            in townForward -> Route.FORWARD
            else -> Route.REFUSE
        }
        "nation" -> when (sub) {
            null, in nationLocal -> Route.LOCAL
            in nationForward -> Route.FORWARD
            else -> Route.REFUSE
        }
        "economy" -> when (sub) {
            null, in economyLocal -> Route.LOCAL
            in economyRefuse -> Route.REFUSE
            else -> Route.FORWARD
        }
        in diplomacyRoots -> Route.FORWARD
        else -> null
    }

    // ===================================
    // follower: send and wait
    // ===================================
    private class Pending(val uuid: UUID, val sentAt: Long)

    // uuid null: a broadcast for everyone on this shard
    private class Reply(val id: String?, val uuid: UUID?, val hash: String, val messages: List<String>, val receivedAt: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val held = ArrayList<Reply>() // main thread only

    /** Main thread. Sends [player]'s command to the leader. */
    fun forward(player: Player, label: String, args: List<String>) {
        val plugin = Shards.pluginOrNull ?: return
        val id = UUID.randomUUID().toString()
        val loc = player.location
        val json = JsonObject()
        json.addProperty("id", id)
        json.addProperty("shard", Shards.name)
        json.addProperty("uuid", player.uniqueId.toString())
        json.addProperty("name", player.name)
        json.addProperty("world", loc.world?.name ?: "world")
        json.addProperty("x", loc.x)
        json.addProperty("y", loc.y)
        json.addProperty("z", loc.z)
        json.addProperty("yaw", loc.yaw)
        json.addProperty("pitch", loc.pitch)
        json.addProperty("op", player.isOp)
        val perms = JsonArray()
        for (info in player.effectivePermissions) {
            if (info.value && info.permission.startsWith("nodes")) {
                perms.add(info.permission.lowercase())
            }
        }
        json.add("perms", perms)
        json.addProperty("label", label)
        val jsonArgs = JsonArray()
        args.forEach { jsonArgs.add(it) }
        json.add("args", jsonArgs)
        json.addProperty("sentAt", System.currentTimeMillis())

        pending[id] = Pending(player.uniqueId, System.currentTimeMillis())
        val payload = json.toString()
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    Shards.redis { it.command("RPUSH", Shards.commandsKey, payload) }
                } catch (e: Exception) {
                    pending.remove(id)
                    Shards.failed(e)
                    plugin.server.scheduler.runTask(
                        plugin,
                        Runnable { Message.error(player, "Cannot reach the main server right now, try again in a moment") },
                    )
                }
            },
        )
    }

    /** Any thread: a "reply" message from the leader. */
    fun onReply(text: String) {
        val plugin = Shards.pluginOrNull ?: return
        val reply = try {
            val json = JsonParser.parseString(text).asJsonObject
            Reply(
                json.get("id")?.takeIf { !it.isJsonNull }?.asString,
                json.get("uuid")?.takeIf { !it.isJsonNull }?.asString?.let { UUID.fromString(it) },
                json.get("hash")?.asString ?: "",
                json.getAsJsonArray("messages").map { it.asString },
                System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            plugin.logger.warning("[Shards] Bad reply from the leader: ${e.message}")
            return
        }
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                // replies go to every follower: keep the ones for players who are here
                if (reply.uuid != null && !pending.containsKey(reply.id ?: "") && Bukkit.getPlayer(reply.uuid) == null) {
                    return@Runnable
                }
                if (reply.hash.isEmpty() || reply.hash == Shards.appliedHash) {
                    deliver(reply)
                } else {
                    held.add(reply)
                }
            },
        )
    }

    /** Main thread: after this shard loaded new state, and once a second. */
    fun deliverHeld() {
        val now = System.currentTimeMillis()
        val iter = held.iterator()
        while (iter.hasNext()) {
            val reply = iter.next()
            if (reply.hash == Shards.appliedHash || now - reply.receivedAt > HOLD_MAX_MS) {
                iter.remove()
                deliver(reply)
            }
        }
        val late = pending.entries.filter { now - it.value.sentAt > REPLY_TIMEOUT_MS }
        for ((id, p) in late) {
            pending.remove(id)
            Bukkit.getPlayer(p.uuid)?.let {
                Message.error(it, "The main server did not answer in time. Nothing was changed; try again in a moment")
            }
        }
    }

    private fun deliver(reply: Reply) {
        if (reply.id != null) {
            pending.remove(reply.id)
        }
        val player = reply.uuid?.let { Bukkit.getPlayer(it) ?: return }
        for (message in reply.messages) {
            try {
                val component = GsonComponentSerializer.gson().deserialize(message)
                if (player != null) player.sendMessage(component) else Bukkit.broadcast(component)
            } catch (_: Exception) {
            }
        }
    }

    // ===================================
    // leader: run requests
    // ===================================
    private var worker: Thread? = null

    fun startLeaderWorker() {
        val plugin = Shards.pluginOrNull ?: return
        val thread = Thread({
            while (Shards.isRunning) {
                val conn = Shards.newConnection(15_000)
                try {
                    conn.connect()
                    while (Shards.isRunning) {
                        val reply = conn.command("BLPOP", Shards.commandsKey, "5") as? List<*> ?: continue
                        val text = (reply.getOrNull(1) as? ByteArray)?.let { String(it, Charsets.UTF_8) } ?: continue
                        val request = parse(text)
                        if (request == null) {
                            plugin.logger.warning("[Shards] Ignored a bad command request")
                            continue
                        }
                        // so "is that officer online?" checks see players who just joined another shard
                        try {
                            refreshPresence()
                        } catch (_: Exception) {
                        }
                        plugin.server.scheduler.runTask(plugin, Runnable { run(request) })
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    if (Shards.isRunning) {
                        Shards.failed(e)
                    }
                } finally {
                    conn.close()
                }
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "nodes-shard-commands")
        thread.isDaemon = true
        thread.start()
        worker = thread
    }

    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun parse(text: String): CommandRequest? = try {
        val json = JsonParser.parseString(text).asJsonObject
        CommandRequest(
            json.get("id").asString,
            json.get("shard").asString,
            UUID.fromString(json.get("uuid").asString),
            json.get("name").asString,
            json.get("world").asString,
            json.get("x").asDouble,
            json.get("y").asDouble,
            json.get("z").asDouble,
            json.get("yaw").asFloat,
            json.get("pitch").asFloat,
            json.get("op").asBoolean,
            json.getAsJsonArray("perms").map { it.asString }.toHashSet(),
            json.get("label").asString,
            json.getAsJsonArray("args").map { it.asString },
            json.get("sentAt").asLong,
        )
    } catch (_: Exception) {
        null
    }

    // ===================================
    // presence: who is online on which shard
    // ===================================
    private class ShardPlayers(val players: Map<UUID, String>, val receivedAt: Long)

    private val presence = ConcurrentHashMap<String, ShardPlayers>()

    /** Follower, main thread: tell the leader who is online here (after joins, quits and on every sync). */
    fun sendPresence() {
        val plugin = Shards.pluginOrNull ?: return
        val json = JsonObject()
        val players = JsonObject()
        for (p in Bukkit.getOnlinePlayers()) {
            players.addProperty(p.uniqueId.toString(), p.name)
        }
        json.add("presence", players)
        json.addProperty("time", System.currentTimeMillis())
        val payload = json.toString()
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    // one entry per shard, overwritten: nothing piles up while the leader is down
                    Shards.redis { it.command("HSET", Shards.presenceKey, Shards.name, payload) }
                } catch (e: Exception) {
                    Shards.failed(e)
                }
            },
        )
    }

    /** Leader, off the main thread (every sync): read who is online on the other shards. */
    fun refreshPresence() {
        val reply = Shards.redis { it.command("HGETALL", Shards.presenceKey) } as? List<*> ?: return
        val seen = HashSet<String>()
        var i = 0
        while (i + 1 < reply.size) {
            val shard = (reply[i] as? ByteArray)?.let { String(it, Charsets.UTF_8) }
            val text = (reply[i + 1] as? ByteArray)?.let { String(it, Charsets.UTF_8) }
            i += 2
            if (shard == null || text == null || shard == Shards.name) {
                continue
            }
            try {
                val json = JsonParser.parseString(text).asJsonObject
                val players = HashMap<UUID, String>()
                for ((uuid, name) in json.getAsJsonObject("presence").entrySet()) {
                    players[UUID.fromString(uuid)] = name.asString
                }
                presence[shard] = ShardPlayers(players, json.get("time").asLong)
                seen.add(shard)
            } catch (_: Exception) {
            }
        }
        presence.keys.retainAll(seen)
    }

    // a shard that stopped reporting is treated as empty
    private fun live(): List<Pair<String, ShardPlayers>> {
        val cutoff = System.currentTimeMillis() - Shards.presenceMaxAgeMs
        return presence.entries.filter { it.value.receivedAt > cutoff }.map { it.key to it.value }
    }

    /** Leader: the shard and name of a player online on another shard, if any. */
    fun findRemote(uuid: UUID): Pair<String, String>? {
        for ((shard, info) in live()) {
            info.players[uuid]?.let { return shard to it }
        }
        return null
    }

    fun findRemote(name: String): Pair<String, UUID>? {
        for ((shard, info) in live()) {
            for ((uuid, playerName) in info.players) {
                if (playerName.equals(name, ignoreCase = true)) {
                    return shard to uuid
                }
            }
        }
        return null
    }

    // stand-ins of the commands being run right now, so a command that looks its own player up gets the same object
    private val running = HashMap<UUID, Player>()

    fun current(uuid: UUID): Player? = running[uuid]

    /** Leader: a stand-in for a player on another shard that can only receive messages. */
    fun messenger(uuid: UUID, name: String, shard: String): Player {
        val world = Bukkit.getWorlds()[0]
        val spawn = world.spawnLocation
        val request = CommandRequest("", shard, uuid, name, world.name, spawn.x, spawn.y, spawn.z, 0f, 0f, false, emptySet(), "", emptyList(), System.currentTimeMillis())
        return RemotePlayer.create(request) { component -> relay(uuid, component) }
    }

    // a message for a player on another shard, shown once their shard has the state that goes with it
    private fun relay(uuid: UUID, component: Component) {
        val plugin = Shards.pluginOrNull ?: return
        val json = GsonComponentSerializer.gson().serialize(component)
        val queue = Runnable { Shards.flushSoon { hash -> sendReply(null, uuid, hash, listOf(json)) } }
        if (Bukkit.isPrimaryThread()) queue.run() else plugin.server.scheduler.runTask(plugin, queue)
    }

    /** Main thread, leader. */
    private fun run(request: CommandRequest) {
        val plugin = Shards.pluginOrNull ?: return
        if (System.currentTimeMillis() - request.sentAt > REQUEST_MAX_AGE_MS) {
            plugin.logger.info("[Shards] Dropped a stale command from ${request.name} on ${request.shard}")
            return
        }
        val messages = ArrayList<String>()
        var finished = false
        val player = RemotePlayer.create(request) { component ->
            if (finished) {
                // sent later (a delayed task kept the player): deliver on its own
                relay(request.uuid, component)
            } else {
                messages.add(GsonComponentSerializer.gson().serialize(component))
            }
        }
        running[request.uuid] = player
        try {
            // a player who only ever joined followers is new to the leader
            if (Nodes.getResidentFromUUID(request.uuid) == null) {
                Nodes.createResident(player)
            }
            val command = Bukkit.getPluginCommand(request.label)
            if (command == null || command.plugin !== plugin) {
                messages.add(GsonComponentSerializer.gson().serialize(Component.text("${ChatColor.RED}Unknown command")))
            } else {
                command.execute(player, request.label, request.args.toTypedArray())
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Shards] Command \"/${request.label} ${request.args.joinToString(" ")}\" from ${request.name} on ${request.shard} failed: $e")
            messages.add(GsonComponentSerializer.gson().serialize(Component.text("${ChatColor.RED}That command failed on the main server")))
        }
        finished = true
        running.remove(request.uuid)
        // save and publish now, then answer with the state the follower must have before it shows the answer
        Shards.flushSoon { hash -> sendReply(request.id, request.uuid, hash, messages) }
    }

    /** Any thread, leader: a broadcast to show on every follower once it has [hash]. */
    fun sendBroadcast(hash: String, message: String) = sendReply(null, null, hash, listOf(message))

    // any thread
    private fun sendReply(id: String?, uuid: UUID?, hash: String, messages: List<String>) {
        val json = JsonObject()
        if (id != null) {
            json.addProperty("id", id)
        }
        if (uuid != null) {
            json.addProperty("uuid", uuid.toString())
        }
        json.addProperty("hash", hash)
        val array = JsonArray()
        messages.forEach { array.add(it) }
        json.add("messages", array)
        Shards.sendEvent("reply", json.toString())
    }
}
