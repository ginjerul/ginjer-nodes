/**
 * Three-way merge of the Nodes world state (residents, towns, nations) between shards.
 *
 * Every shard remembers the last state it agreed on with the shared store (the base).
 * When the shared copy moved on, the shard merges base, its own state and the shared
 * state one entity at a time:
 *  - changed only here         -> keep ours
 *  - changed only on the other -> take theirs
 *  - changed on both           -> ours wins (the later writer wins, like a normal save)
 *  - no base yet (first sync)  -> union of both sides, ours wins when both have the entity
 *  - deleted on one side and unchanged on the other -> deleted
 *
 * Pure JSON in, pure JSON out: no Bukkit, so it can be tested on its own.
 */

package phonon.nodes.shardsync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Sections of towns.json that are merged entity by entity. */
public val MERGED_SECTIONS: List<String> = listOf("residents", "towns", "nations")

public class MergeResult(
    /** The merged world, same shape as towns.json. */
    public val merged: JsonObject,
    /** Entities where both sides changed and ours won, as "section/key". */
    public val conflicts: List<String>,
    /** Entities whose merged value differs from ours, as "section/key". These must be applied locally. */
    public val changedFromLocal: List<String>,
    /** Entities whose merged value differs from the shared copy. These must be published. */
    public val changedFromRemote: List<String>,
)

public object StateMerge {

    public fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject): MergeResult {
        val merged = JsonObject()
        val conflicts = ArrayList<String>()
        val changedFromLocal = ArrayList<String>()
        val changedFromRemote = ArrayList<String>()

        // keep top level fields that are not merged (e.g. "meta") from the shared copy
        for ((k, v) in remote.entrySet()) {
            if (k !in MERGED_SECTIONS) {
                merged.add(k, v.deepCopy())
            }
        }

        for (section in MERGED_SECTIONS) {
            val b = base?.section(section)
            val l = local.section(section)
            val r = remote.section(section)

            val out = JsonObject()
            val keys = LinkedHashSet<String>()
            keys.addAll(l.keySet())
            keys.addAll(r.keySet())
            if (b != null) keys.addAll(b.keySet())

            for (key in keys) {
                val bv = b?.get(key)
                val lv = l.get(key)
                val rv = r.get(key)

                val picked: JsonElement?
                if (lv == rv) {
                    picked = lv
                } else if (b != null && lv == bv) {
                    picked = rv
                } else if (b != null && rv == bv) {
                    picked = lv
                } else if (b == null && lv == null) {
                    // no base yet (first sync): keep whatever exists
                    picked = rv
                } else if (b == null && rv == null) {
                    picked = lv
                } else {
                    // both sides changed it: ours wins
                    picked = lv
                    conflicts.add("$section/$key")
                }

                if (picked != null) {
                    out.add(key, picked.deepCopy())
                }
                if (picked != lv) changedFromLocal.add("$section/$key")
                if (picked != rv) changedFromRemote.add("$section/$key")
            }

            merged.add(section, out)
        }

        return MergeResult(merged, conflicts, changedFromLocal, changedFromRemote)
    }

    private fun JsonObject.section(name: String): JsonObject {
        val e = this.get(name)
        return if (e != null && e.isJsonObject) e.asJsonObject else JsonObject()
    }

    /**
     * References that do not line up after a merge, e.g. a resident who points at a town
     * the other shard deleted, or a territory claimed by two towns. The merge itself keeps
     * both sides' data, so the caller decides how to repair these.
     */
    public fun findIssues(world: JsonObject): List<String> {
        val issues = ArrayList<String>()
        val residents = world.section("residents")
        val towns = world.section("towns")
        val nations = world.section("nations")

        for ((uuid, r) in residents.entrySet()) {
            val o = r.asJsonObject
            val town = o.stringOrNull("town")
            if (town != null && !towns.has(town)) {
                issues.add("resident $uuid is in missing town $town")
            }
            val nation = o.stringOrNull("nation")
            if (nation != null && !nations.has(nation)) {
                issues.add("resident $uuid is in missing nation $nation")
            }
        }

        val owner = HashMap<Int, String>()
        for ((name, t) in towns.entrySet()) {
            val o = t.asJsonObject
            for (id in o.array("residents")) {
                if (!residents.has(id.asString)) {
                    issues.add("town $name has missing resident ${id.asString}")
                }
            }
            for (id in o.array("territories")) {
                val prev = owner.put(id.asInt, name)
                if (prev != null) {
                    issues.add("territory ${id.asInt} is claimed by both $prev and $name")
                }
            }
        }

        for ((name, n) in nations.entrySet()) {
            val o = n.asJsonObject
            for (t in o.array("towns")) {
                if (!towns.has(t.asString)) {
                    issues.add("nation $name has missing town ${t.asString}")
                }
            }
            val capital = o.stringOrNull("capital")
            if (capital != null && !towns.has(capital)) {
                issues.add("nation $name has missing capital $capital")
            }
        }

        return issues
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val e = this.get(name)
        return if (e == null || e.isJsonNull) null else e.asString
    }

    private fun JsonObject.array(name: String): JsonArray {
        val e = this.get(name)
        return if (e != null && e.isJsonArray) e.asJsonArray else JsonArray()
    }
}
