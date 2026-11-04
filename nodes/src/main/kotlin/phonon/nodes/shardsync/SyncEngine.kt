/**
 * The publish side of shard sync, without Bukkit: one shard's view of the shared store.
 * Remembers the last state it agreed on with the store (base) so every round can be merged
 * three ways (see StateMerge). ShardSync drives it from the server; tests drive it directly.
 */

package phonon.nodes.shardsync

import com.google.gson.JsonObject
import com.google.gson.JsonParser

public class SyncOutcome(
    /** Version of the store after this round. */
    public val version: Long,
    /** True when this round wrote a new version to the store. */
    public val wrote: Boolean,
    /** Entities (as "section/key") changed by other shards that this shard has not applied. */
    public val pendingFromOthers: List<String>,
    /** Entities where both sides changed and ours won. */
    public val conflicts: List<String>,
    /** Broken references in the merged state. */
    public val issues: List<String>,
)

public class SyncEngine(
    private val store: SyncStore,
    base: JsonObject? = null,
    baseVersion: Long = 0L,
) {
    public var base: JsonObject? = base
        private set
    public var baseVersion: Long = baseVersion
        private set

    /** True when a round could do something: our state changed or the store moved on. */
    public fun needsRound(localString: String, lastLocalString: String?): Boolean {
        return localString != lastLocalString || store.version() != baseVersion
    }

    /** Merge [localString] (this shard's current towns.json-shaped state) with the store and publish. */
    public fun sync(localString: String): SyncOutcome {
        val local = JsonParser.parseString(localString).asJsonObject

        for (attempt in 1..MAX_ATTEMPTS) {
            val remote = store.read()

            if (remote == null) {
                // first shard to publish
                val v = store.writeIfUnchanged(0L, localString)
                if (v != null) {
                    done(local, v)
                    return SyncOutcome(v, true, emptyList(), emptyList(), StateMerge.findIssues(local))
                }
                continue
            }

            val remoteJson = JsonParser.parseString(remote.json).asJsonObject
            val result = StateMerge.merge(base, local, remoteJson)
            val issues = StateMerge.findIssues(result.merged)

            if (result.changedFromRemote.isEmpty()) {
                // the store already has everything we have
                done(local, remote.version)
                return SyncOutcome(remote.version, false, result.changedFromLocal, result.conflicts, issues)
            }

            val v = store.writeIfUnchanged(remote.version, result.merged.toString())
            if (v != null) {
                done(local, v)
                return SyncOutcome(v, true, result.changedFromLocal, result.conflicts, issues)
            }
            // another shard wrote in between: read again and merge again
        }
        throw IllegalStateException("shared store kept changing, retry next round")
    }

    private fun done(local: JsonObject, version: Long) {
        base = local
        baseVersion = version
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
