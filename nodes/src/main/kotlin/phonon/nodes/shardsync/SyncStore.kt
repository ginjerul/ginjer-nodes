/**
 * Shared place where all shards keep the agreed Nodes state.
 *
 * The store only has to offer one thing: an atomic "write if nobody wrote since I read".
 * That makes two shards saving at the same moment safe: one wins, the other re-reads,
 * merges (see StateMerge) and tries again.
 */

package phonon.nodes.shardsync

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The shared state and the version it was written at. */
public class Snapshot(public val version: Long, public val json: String)

public interface SyncStore {
    /** Latest shared state, or null when nothing was written yet. */
    public fun read(): Snapshot?

    /** Cheap check of the latest version (0 when nothing was written), without reading the data. */
    public fun version(): Long

    /**
     * Write [json] as the next version, only if the store is still at [expectedVersion]
     * (0 = still empty). Returns the new version, or null when someone wrote first.
     */
    public fun writeIfUnchanged(expectedVersion: Long, json: String): Long?
}

/**
 * Store in a folder every shard can reach (the same folder the shards already share
 * for player data). A lock file makes the check-and-write atomic across processes.
 *
 * Layout: nodes-state.json (data), nodes-state.version (number), nodes-state.lock.
 */
public class FolderSyncStore(private val folder: Path) : SyncStore {
    private val data: Path = folder.resolve("nodes-state.json")
    private val versionFile: Path = folder.resolve("nodes-state.version")
    private val lockFile: Path = folder.resolve("nodes-state.lock")

    init {
        Files.createDirectories(folder)
    }

    public override fun version(): Long = readVersion()

    public override fun read(): Snapshot? = withLock {
        val v = readVersion()
        if (v == 0L || !Files.exists(data)) {
            null
        } else {
            Snapshot(v, String(Files.readAllBytes(data), StandardCharsets.UTF_8))
        }
    }

    public override fun writeIfUnchanged(expectedVersion: Long, json: String): Long? = withLock {
        val current = readVersion()
        if (current != expectedVersion) {
            null
        } else {
            val next = current + 1
            writeAtomic(data, json)
            writeAtomic(versionFile, next.toString())
            next
        }
    }

    private fun readVersion(): Long {
        return try {
            String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim().toLong()
        } catch (e: IOException) {
            0L
        } catch (e: NumberFormatException) {
            0L
        }
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // A file lock only keeps other processes out. Threads of this process (the sync task and
    // a command, say) would hit OverlappingFileLockException, so they queue on this monitor first.
    private fun <T> withLock(block: () -> T): T {
        val monitor = monitors.computeIfAbsent(lockFile.toAbsolutePath().normalize().toString()) { Any() }
        synchronized(monitor) {
            RandomAccessFile(lockFile.toFile(), "rw").use { raf ->
                raf.channel.lock().use {
                    return block()
                }
            }
        }
    }

    private companion object {
        val monitors = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
