/**
 * Keeps the Nodes state of several shards (Reef servers sharing one world) in step.
 *
 * Step 1, publish only: every few seconds this shard builds its current state, and when it
 * changed, merges it with the shared copy (see StateMerge) and writes the result to the shared
 * store. Changes made on OTHER shards are counted and reported by /nda shardsync, but are not
 * applied to the live objects yet.
 *
 * Off by default (config shardSync.enabled).
 */

package phonon.nodes.shardsync

import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.serdes.Serializer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

public object ShardSync {
    private var task: BukkitTask? = null
    private var store: SyncStore? = null
    private var engine: SyncEngine? = null
    private val busy = AtomicBoolean(false)

    // last state we synced, to skip rounds where nothing changed
    private var lastLocalString: String? = null

    // numbers for /nda shardsync
    @Volatile private var lastRun: Long = 0L

    @Volatile private var lastPublish: Long = 0L

    @Volatile private var published: Long = 0L

    @Volatile private var conflictCount: Long = 0L

    @Volatile private var pendingRemote: List<String> = emptyList()

    @Volatile private var lastIssues: List<String> = emptyList()

    @Volatile private var lastError: String? = null

    public val running: Boolean get() = task != null

    private fun basePath(): Path = Paths.get(Config.pathPlugin, "shardsync-base.json").normalize()

    private fun basePathVersion(): Path = Paths.get(Config.pathPlugin, "shardsync-base.version").normalize()

    public fun start(plugin: Plugin) {
        stop()
        if (!Config.shardSyncEnabled) {
            return
        }
        if (Config.shardSyncFolder.isBlank()) {
            plugin.logger.warning("[ShardSync] shardSync.enabled is true but shardSync.folder is empty: not starting")
            return
        }

        try {
            store = FolderSyncStore(Paths.get(Config.shardSyncFolder))
        } catch (ex: Exception) {
            plugin.logger.warning("[ShardSync] cannot use folder ${Config.shardSyncFolder}: ${ex.message}")
            return
        }

        engine = loadBase(store!!)

        val period = (Config.shardSyncIntervalSeconds * 20L).coerceAtLeast(20L)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick(plugin) }, period, period)
        plugin.logger.info(
            "[ShardSync] publishing Nodes state to ${Config.shardSyncFolder} every ${Config.shardSyncIntervalSeconds}s",
        )
    }

    public fun stop() {
        task?.cancel()
        task = null
    }

    /** Runs on the main thread: snapshot the state, then merge and publish off the main thread. */
    private fun tick(plugin: Plugin) {
        val eng = engine ?: return
        if (!Nodes.initialized || !busy.compareAndSet(false, true)) {
            return
        }

        val local = try {
            Serializer.worldToJson(
                Nodes.residents.values.map { it.getSaveState() },
                Nodes.towns.values.map { it.getSaveState() },
                Nodes.nations.values.map { it.getSaveState() },
            )
        } catch (ex: Exception) {
            busy.set(false)
            lastError = "snapshot failed: ${ex.message}"
            return
        }

        // nothing changed here and nothing new in the store: nothing to do
        if (!eng.needsRound(local, lastLocalString)) {
            busy.set(false)
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    sync(eng, local)
                    lastError = null
                } catch (ex: Exception) {
                    lastError = ex.message ?: ex.javaClass.simpleName
                    plugin.logger.warning("[ShardSync] sync failed: $lastError")
                } finally {
                    busy.set(false)
                }
            },
        )
    }

    private fun sync(eng: SyncEngine, localString: String) {
        lastRun = System.currentTimeMillis()
        val outcome = eng.sync(localString)
        conflictCount += outcome.conflicts.size
        pendingRemote = outcome.pendingFromOthers
        lastIssues = outcome.issues
        lastLocalString = localString
        if (outcome.wrote) {
            lastPublish = System.currentTimeMillis()
            published++
        }
        saveBase(localString, outcome.version)
    }

    // The base survives a restart so a shard that was offline does not mistake
    // every change other shards made meanwhile for its own.
    private fun loadBase(s: SyncStore): SyncEngine {
        try {
            if (Files.exists(basePath()) && Files.exists(basePathVersion())) {
                val text = String(Files.readAllBytes(basePath()), StandardCharsets.UTF_8)
                val v = String(Files.readAllBytes(basePathVersion()), StandardCharsets.UTF_8).trim().toLong()
                return SyncEngine(s, JsonParser.parseString(text).asJsonObject, v)
            }
        } catch (ex: Exception) {
            // unreadable base: start without one (first-sync merge keeps everything)
        }
        return SyncEngine(s)
    }

    private fun saveBase(json: String, version: Long) {
        try {
            val tmp = basePath().resolveSibling("shardsync-base.json.tmp")
            Files.write(tmp, json.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, basePath(), StandardCopyOption.REPLACE_EXISTING)
            Files.write(basePathVersion(), version.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (ex: Exception) {
            lastError = "could not save base: ${ex.message}"
        }
    }

    public fun statusLines(): List<String> {
        if (!Config.shardSyncEnabled) {
            return listOf("Shard sync is off (shardSync.enabled: false)")
        }
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t == 0L) "never" else "${(now - t) / 1000}s ago"
        val lines = ArrayList<String>()
        lines.add("Shard sync: ${if (running) "running" else "NOT running"}, folder ${Config.shardSyncFolder}")
        lines.add("Store version: ${store?.version() ?: 0}, ours: ${engine?.baseVersion ?: 0}")
        lines.add("Published $published time(s), last ${ago(lastPublish)}; last check ${ago(lastRun)}")
        lines.add("Changes from other shards not applied here yet: ${pendingRemote.size}")
        if (pendingRemote.isNotEmpty()) {
            lines.add("  e.g. " + pendingRemote.take(5).joinToString(", "))
        }
        lines.add("Conflicts resolved in our favour so far: $conflictCount")
        if (lastIssues.isNotEmpty()) {
            lines.add("Broken references in the merged state: ${lastIssues.size}")
            for (i in lastIssues.take(5)) lines.add("  $i")
        }
        val err = lastError
        if (err != null) lines.add("Last error: $err")
        return lines
    }
}
