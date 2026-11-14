/*
 * Running Nodes on several servers that share one world (Reef sharding). Phase 1 of
 * SHARDING-DESIGN.md: one shard is the leader and owns all Nodes state; the others (followers)
 * mirror the leader's towns, nations, claims, ports and war state, so protection and info work
 * everywhere. State-changing commands only run on the leader for now.
 *
 * The leader publishes its saved state files to Redis whenever they change; followers write them
 * to their own plugin folder and reload. Settings are under `sharding:` in the config. With
 * `sharding.enabled: false` (the default) none of this runs.
 */

package phonon.nodes.shard

import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.Plugin
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object Shards {
    var enabled = false
        private set
    var leader = true
        private set
    var name = "main"
        private set
    private var host = "127.0.0.1"
    private var port = 6379
    private var password = ""
    private var database = 0
    private var prefix = "nodes"
    private var syncSeconds = 5L

    // true on a shard that mirrors the leader: it must never save or run timed logic
    val follower: Boolean get() = enabled && !leader

    private var plugin: Plugin? = null
    private var commands: RedisConnection? = null
    private var subscriber: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastHash = ""

    @Volatile
    private var lastSyncAt = 0L

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var syncCount = 0

    // hash of a snapshot a follower has fetched and queued to apply on the main thread
    @Volatile
    private var pendingHash = ""
    private val fetchLock = Any()

    // logs when Redis goes down or comes back, once, instead of on every retry
    internal fun failed(e: Exception) {
        val message = e.message ?: e.javaClass.simpleName
        if (lastError == null) {
            plugin?.logger?.warning("[Shards] Redis problem: $message (retrying every ${syncSeconds}s; /nda shards shows the status)")
        }
        lastError = message
    }

    private fun recovered() {
        if (lastError != null) {
            plugin?.logger?.info("[Shards] Redis is reachable again")
        }
        lastError = null
    }

    fun loadConfig(config: ConfigurationSection) {
        val sec = config.getConfigurationSection("sharding")
        enabled = sec?.getBoolean("enabled", false) ?: false
        leader = !(sec?.getString("role", "leader") ?: "leader").equals("follower", ignoreCase = true)
        name = sec?.getString("name", "main") ?: "main"
        host = sec?.getString("redis.host", "127.0.0.1") ?: "127.0.0.1"
        port = sec?.getInt("redis.port", 6379) ?: 6379
        password = sec?.getString("redis.password", "") ?: ""
        database = sec?.getInt("redis.database", 0) ?: 0
        prefix = (sec?.getString("keyPrefix", "nodes") ?: "nodes").ifBlank { "nodes" }
        syncSeconds = (sec?.getLong("syncSeconds", 5L) ?: 5L).coerceIn(1L, 300L)
    }

    // the state files that make up the mirror, by the field name used in Redis
    private fun files(): Map<String, Path> = mapOf(
        "world" to Config.pathWorld,
        "towns" to Config.pathTowns,
        "ports" to Config.pathPorts,
        "war" to Config.pathWar,
    )

    private val snapshotKey: String get() = "$prefix:snapshot"
    private val channel: String get() = "$prefix:events"
    internal val commandsKey: String get() = "$prefix:commands"
    internal val presenceKey: String get() = "$prefix:presence"

    internal val pluginOrNull: Plugin? get() = plugin
    internal val isRunning: Boolean get() = running

    // the snapshot this shard has loaded (follower) or last published (leader)
    internal val appliedHash: String get() = lastHash

    internal val presenceMaxAgeMs: Long get() = (syncSeconds * 4 + 10) * 1000L

    /**
     * The online player with this id: on this shard, or on the leader also a player online on
     * another shard (a stand-in that only receives messages, relayed to them). Without sharding
     * this is Bukkit.getPlayer.
     */
    fun player(uuid: java.util.UUID): org.bukkit.entity.Player? {
        Bukkit.getPlayer(uuid)?.let { return it }
        if (!enabled || !leader || !running) {
            return null
        }
        RemoteCommands.current(uuid)?.let { return it }
        val (shard, name) = RemoteCommands.findRemote(uuid) ?: return null
        return RemoteCommands.messenger(uuid, name, shard)
    }

    /** On the leader, while running a command from another shard: that command's player. */
    fun commandPlayer(uuid: java.util.UUID): org.bukkit.entity.Player? = if (enabled && leader) RemoteCommands.current(uuid) else null

    fun player(name: String): org.bukkit.entity.Player? {
        Bukkit.getPlayerExact(name)?.let { return it }
        if (!enabled || !leader || !running) {
            return Bukkit.getPlayer(name)
        }
        val (shard, uuid) = RemoteCommands.findRemote(name) ?: return Bukkit.getPlayer(name)
        RemoteCommands.current(uuid)?.let { return it }
        return RemoteCommands.messenger(uuid, name, shard)
    }

    internal fun newConnection(readTimeoutMs: Int) = RedisConnection(host, port, password, database, readTimeoutMs)

    // runs a command on the shared connection, reconnecting once if it dropped
    internal fun <T> redis(block: (RedisConnection) -> T): T {
        synchronized(this) {
            var conn = commands
            if (conn == null || !conn.connected) {
                conn = newConnection(10_000)
                conn.connect()
                commands = conn
            }
            try {
                return block(conn)
            } catch (e: RedisError) {
                throw e
            } catch (e: java.io.IOException) {
                conn.close()
                val fresh = newConnection(10_000)
                fresh.connect()
                commands = fresh
                return block(fresh)
            }
        }
    }

    /**
     * Called before the world is loaded. A follower pulls the leader's state first, so it starts
     * from the same towns and claims.
     */
    fun start(plugin: Plugin) {
        this.plugin = plugin
        if (!enabled) {
            return
        }
        val log = plugin.logger
        log.info("[Shards] Sharding on: this is ${if (leader) "the LEADER" else "a follower"} \"$name\" (redis $host:$port)")
        try {
            redis { it.command("PING") }
        } catch (e: Exception) {
            failed(e)
            log.severe("[Shards] Cannot reach Redis at $host:$port: ${e.message}")
            if (follower) {
                log.severe("[Shards] This follower starts from its own old files and will not change anything until Redis is back")
            }
        }
        if (follower) {
            try {
                if (pull()) {
                    log.info("[Shards] Loaded the leader's state")
                } else {
                    log.warning("[Shards] The leader has not published any state yet; using local files")
                }
            } catch (e: Exception) {
                lastError = e.message
                log.severe("[Shards] Could not load the leader's state: ${e.message}")
            }
        }

        running = true
        plugin.server.pluginManager.registerEvents(FollowerGuard(), plugin)
        val period = syncSeconds * 20L
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable { tick() }, period, period)
        if (follower) {
            startSubscriber()
            plugin.server.scheduler.runTaskTimer(plugin, Runnable { RemoteCommands.deliverHeld() }, 20L, 20L)
        } else {
            plugin.server.pluginManager.registerEvents(LeaderListener(), plugin)
            RemoteCommands.startLeaderWorker()
        }
    }

    fun stop() {
        if (!enabled) {
            return
        }
        running = false
        if (leader) {
            // the final save has just been written
            try {
                publish()
            } catch (_: Exception) {
            }
        }
        subscriber?.interrupt()
        RemoteCommands.stop()
        synchronized(this) {
            commands?.close()
            commands = null
        }
    }

    private fun tick() {
        if (!running) {
            return
        }
        try {
            redis { it.command("SET", "$prefix:shard:$name", "${if (leader) "leader" else "follower"}|${System.currentTimeMillis()}|${Bukkit.getOnlinePlayers().size}", "EX", "${syncSeconds * 4 + 10}") }
            val remote = (redis { it.command("HGET", snapshotKey, "hash") } as? ByteArray)?.let { String(it) }
            if (leader) {
                // Redis lost the snapshot (restarted without persistence, flushed): publish it again
                if (remote != lastHash) {
                    lastHash = ""
                }
                publish()
                RemoteCommands.refreshPresence()
            } else {
                if (remote != null && remote != lastHash) {
                    // in case a published message was missed
                    fetchAndApply()
                }
                plugin?.let { p -> p.server.scheduler.runTask(p, Runnable { RemoteCommands.sendPresence() }) }
            }
            recovered()
        } catch (e: Exception) {
            failed(e)
        }
    }

    // ===================================
    // leader: publish the saved files when they changed
    // ===================================
    private fun publish() {
        val contents = LinkedHashMap<String, ByteArray>()
        val digest = MessageDigest.getInstance("SHA-256")
        for ((field, path) in files()) {
            if (!Files.exists(path)) {
                continue
            }
            val bytes = Files.readAllBytes(path)
            // a file caught in the middle of a save is not valid json: try again next time
            try {
                JsonParser.parseString(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return
            }
            contents[field] = bytes
            digest.update(field.toByteArray())
            // attack progress changes war.json every second during a war; publishing (and a full
            // reload on every follower) for that alone would cause a hitch every few seconds.
            // Progress still goes out with the next real change.
            digest.update(if (field == "war") ATTACK_PROGRESS.replace(String(bytes, Charsets.UTF_8), "\"p\":0").toByteArray() else bytes)
        }
        if (contents.isEmpty()) {
            return
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash == lastHash) {
            return
        }
        val args = ArrayList<ByteArray>()
        args.add("HSET".toByteArray())
        args.add(snapshotKey.toByteArray())
        for ((field, bytes) in contents) {
            args.add(field.toByteArray())
            args.add(bytes)
        }
        args.add("hash".toByteArray())
        args.add(hash.toByteArray())
        args.add("leader".toByteArray())
        args.add(name.toByteArray())
        args.add("time".toByteArray())
        args.add(System.currentTimeMillis().toString().toByteArray())
        redis { it.commandBytes(*args.toTypedArray()) }
        redis { it.command("PUBLISH", channel, "snapshot $hash") }
        lastHash = hash
        lastSyncAt = System.currentTimeMillis()
        syncCount++
    }

    // ===================================
    // follower: fetch the files and reload
    // ===================================

    // writes the leader's files into this shard's plugin folder; false if there is nothing new
    private fun pull(): Boolean {
        val fields = fetch() ?: return false
        write(fields)
        return true
    }

    // the leader's snapshot from Redis, or null if it is missing or already applied. Any thread.
    private fun fetch(): Map<String, ByteArray>? {
        val reply = redis { it.command("HGETALL", snapshotKey) } as? List<*> ?: return null
        val fields = HashMap<String, ByteArray>()
        var i = 0
        while (i + 1 < reply.size) {
            val key = reply[i] as? ByteArray
            val value = reply[i + 1] as? ByteArray
            if (key != null && value != null) {
                fields[String(key)] = value
            }
            i += 2
        }
        val hash = fields["hash"]?.let { String(it) } ?: return null
        if (hash == lastHash) {
            return null
        }
        return fields
    }

    private fun write(fields: Map<String, ByteArray>) {
        val hash = fields["hash"]?.let { String(it) } ?: return
        for ((field, path) in files()) {
            val bytes = fields[field] ?: continue
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".shard.tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        lastHash = hash
        lastSyncAt = System.currentTimeMillis()
        syncCount++
    }

    // fetches the new snapshot off the main thread, then applies it on the main thread
    private fun fetchAndApply() {
        val plugin = this.plugin ?: return
        if (!running) {
            return
        }
        val fields = synchronized(fetchLock) {
            val fetched = fetch() ?: return
            val hash = fetched["hash"]?.let { String(it) } ?: return
            if (hash == pendingHash) {
                return
            }
            pendingHash = hash
            fetched
        }
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                try {
                    if (running && fields["hash"]?.let { String(it) } != lastHash) {
                        write(fields)
                        reload()
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    plugin.logger.severe("[Shards] Could not apply the leader's state: ${e.message}")
                } finally {
                    pendingHash = ""
                }
            },
        )
    }

    // main thread: rebuild towns, claims and war from the files just written
    private fun reload() {
        val start = System.nanoTime()
        // per-player things that are not part of the saved state
        val minimaps = HashMap<java.util.UUID, Int>()
        val chatModes = HashMap<java.util.UUID, phonon.nodes.chat.ChatMode>()
        for (resident in Nodes.residents.values) {
            resident.minimap?.let { minimaps[resident.uuid] = it.size }
            chatModes[resident.uuid] = resident.chatMode
            resident.destroyMinimap()
        }
        phonon.nodes.war.FlagWar.clearMirror()

        Nodes.loadWorld()
        Nodes.initializeOnlinePlayers()

        for ((uuid, mode) in chatModes) {
            Nodes.residents[uuid]?.chatMode = mode
        }
        for ((uuid, size) in minimaps) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            Nodes.residents[uuid]?.createMinimap(player, size)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        plugin?.logger?.info("[Shards] Applied the leader's state in ${ms}ms (${Nodes.getTownCount()} towns, ${Nodes.getTerritoryCount()} territories)")
        RemoteCommands.deliverHeld()
    }

    // ===================================
    // leader: save and publish right after a change
    // ===================================
    private val flushCallbacks = ArrayList<(String) -> Unit>() // main thread
    private var flushScheduled = false

    /**
     * Main thread, leader. Saves what changed and publishes it on the next tick (once for all the
     * commands of this tick), then calls [then] with the published snapshot hash, off the main thread.
     */
    internal fun flushSoon(then: ((String) -> Unit)? = null) {
        val plugin = this.plugin ?: return
        if (!enabled || !leader || !running) {
            return
        }
        if (then != null) {
            flushCallbacks.add(then)
        }
        if (flushScheduled) {
            return
        }
        flushScheduled = true
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                flushScheduled = false
                val callbacks = ArrayList(flushCallbacks)
                flushCallbacks.clear()
                try {
                    Nodes.saveWorld(checkIfNeedsSave = true, async = false)
                    if (phonon.nodes.war.FlagWar.needsSave) {
                        phonon.nodes.war.FlagWar.needsSave = false
                        phonon.nodes.war.WarSerializer.save(false)
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("[Shards] Could not save before publishing: $e")
                }
                plugin.server.scheduler.runTaskAsynchronously(
                    plugin,
                    Runnable {
                        try {
                            publish()
                            recovered()
                        } catch (e: Exception) {
                            failed(e)
                        }
                        val hash = lastHash
                        for (callback in callbacks) {
                            try {
                                callback(hash)
                            } catch (e: Exception) {
                                failed(e)
                            }
                        }
                    },
                )
            },
        )
    }

    /** Any thread: a message on the events channel ("<type> <payload>"), sent off the main thread. */
    internal fun sendEvent(type: String, payload: String) {
        val plugin = this.plugin ?: return
        val send = Runnable {
            try {
                redis { it.command("PUBLISH", channel, "$type $payload") }
            } catch (e: Exception) {
                failed(e)
            }
        }
        if (Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, send)
        } else {
            send.run()
        }
    }

    /**
     * Leader: Nodes broadcasts (new towns, wars, ...) are shown on every shard. They go out after
     * the next publish, and followers show them once they have that state, so "X has created the
     * town" is never followed by a /town info that does not know the town yet.
     */
    fun relayBroadcast(message: net.kyori.adventure.text.Component) {
        val plugin = this.plugin ?: return
        if (!enabled || !leader || !running) {
            return
        }
        val json = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(message)
        val queue = Runnable { flushSoon { hash -> RemoteCommands.sendBroadcast(hash, json) } }
        if (Bukkit.isPrimaryThread()) {
            queue.run()
        } else {
            plugin.server.scheduler.runTask(plugin, queue)
        }
    }

    // after a player's Nodes command on the leader itself, publish right away too
    private class LeaderListener : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onCommand(event: PlayerCommandPreprocessEvent) {
            val label = event.message.removePrefix("/").trim().substringBefore(' ').lowercase().substringAfter(":")
            val command = Bukkit.getPluginCommand(label) ?: return
            val plugin = plugin ?: return
            if (command.plugin === plugin) {
                // the command runs after this event: flush on the next tick
                plugin.server.scheduler.runTask(plugin, Runnable { flushSoon() })
            }
        }
    }

    private fun startSubscriber() {
        val thread = Thread({
            while (running) {
                val conn = newConnection(0)
                try {
                    conn.connect()
                    conn.send("SUBSCRIBE".toByteArray(), channel.toByteArray())
                    while (running) {
                        val message = conn.read() as? List<*> ?: continue
                        val kind = (message.getOrNull(0) as? ByteArray)?.let { String(it) }
                        if (kind == "message") {
                            val data = (message.getOrNull(2) as? ByteArray)?.let { String(it, Charsets.UTF_8) } ?: ""
                            when (data.substringBefore(' ')) {
                                "reply" -> RemoteCommands.onReply(data.substringAfter(' '))
                                else -> fetchAndApply()
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    if (running) {
                        failed(e)
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
        }, "nodes-shard-subscriber")
        thread.isDaemon = true
        thread.start()
        subscriber = thread
    }

    // ===================================
    // /nda shards
    // ===================================
    fun status(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Shards:")
        if (!enabled) {
            Message.print(sender, "Sharding is off (sharding.enabled in config.yml)")
            return
        }
        Message.print(sender, "This shard${ChatColor.WHITE}: $name (${if (leader) "leader" else "follower"})")
        val age = if (lastSyncAt == 0L) "never" else "${(System.currentTimeMillis() - lastSyncAt) / 1000}s ago"
        Message.print(sender, "${if (leader) "Last publish" else "Last update from leader"}${ChatColor.WHITE}: $age ($syncCount so far)")
        val error = lastError
        if (error != null) {
            Message.error(sender, "Redis problem: $error")
        }
        val plugin = this.plugin ?: return
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val lines = ArrayList<String>()
                try {
                    val keys = redis { it.command("KEYS", "$prefix:shard:*") } as? List<*> ?: emptyList<Any?>()
                    val now = System.currentTimeMillis()
                    for (key in keys) {
                        val keyName = String(key as? ByteArray ?: continue)
                        val value = (redis { it.command("GET", keyName) } as? ByteArray)?.let { String(it) } ?: continue
                        val parts = value.split("|")
                        val seen = parts.getOrNull(1)?.toLongOrNull()?.let { "${(now - it) / 1000}s ago" } ?: "?"
                        lines.add("${keyName.substringAfterLast(":")}${ChatColor.WHITE}: ${parts[0]}, ${parts.getOrNull(2) ?: "?"} online, seen $seen")
                    }
                } catch (e: Exception) {
                    lines.add("${ChatColor.RED}Could not list shards: ${e.message}")
                }
                plugin.server.scheduler.runTask(plugin, Runnable { lines.sorted().forEach { Message.print(sender, it) } })
            },
        )
    }

    // ===================================
    // followers do not change state yet: commands that would are refused
    // ===================================
    private val readOnlyRoots = setOf(
        "nodes", "nd", "map", "territory", "captimes", "calendar", "stats", "leaderboard", "player",
        "globalchat", "gc", "townchat", "tc", "nationchat", "nc", "allychat", "ac", "localchat", "lc", "rpchat", "rp",
    )
    private val noArgumentRoots = setOf("town", "nation", "nodesadmin", "skills", "duel", "war", "port", "plot", "refinery", "bounty")
    private val readOnlySubs = setOf("help", "info", "list", "online", "map", "minimap", "top", "prices", "balance", "bal", "show", "shards")

    private val ATTACK_PROGRESS = Regex("\"p\":\\d+")

    private class FollowerGuard : Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
            if (follower) {
                RemoteCommands.sendPresence()
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
            val plugin = plugin ?: return
            if (follower) {
                // the quitting player is still listed during this event
                plugin.server.scheduler.runTask(plugin, Runnable { RemoteCommands.sendPresence() })
            }
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        fun onCommand(event: PlayerCommandPreprocessEvent) {
            if (!follower) {
                return
            }
            val parts = event.message.removePrefix("/").trim().split(Regex("\\s+"))
            val label = parts[0].lowercase().substringAfter(":")
            val command = Bukkit.getPluginCommand(label) ?: return
            if (command.plugin !== plugin || label in readOnlyRoots || command.name.lowercase() in readOnlyRoots) {
                return
            }
            // with no arguments most commands only print help; the rest open menus that can change things
            val sub = parts.getOrNull(1)?.lowercase()
            when (RemoteCommands.route(command.name.lowercase(), sub)) {
                RemoteCommands.Route.LOCAL -> return
                RemoteCommands.Route.FORWARD -> {
                    event.isCancelled = true
                    RemoteCommands.forward(event.player, command.name.lowercase(), parts.drop(1))
                    return
                }
                RemoteCommands.Route.REFUSE -> {
                    event.isCancelled = true
                    Message.error(event.player, "That command only works on the main server for now.")
                    return
                }
                null -> {}
            }
            if (sub == null && command.name.lowercase() in noArgumentRoots) {
                return
            }
            if (sub != null && sub in readOnlySubs) {
                return
            }
            event.isCancelled = true
            Message.error(event.player, "This part of the map only shows towns and nations for now. Run that command on the main server.")
        }
    }
}
