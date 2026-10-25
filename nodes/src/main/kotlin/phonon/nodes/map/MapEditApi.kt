/**
 * World editing entry point for the NodesMap web editor.
 *
 * NodesMap calls these static methods by reflection (no compile dependency
 * between the two plugins). Everything is plain strings in and out:
 *
 *   worldJson()                      current plugins/nodes/world.json
 *   apply(json, dryRun, force, by)   validate an edited world and, unless dryRun,
 *                                    back up + write world.json and hot-reload it
 *
 * Safety rules enforced here (the editor can't bypass them):
 *   - a territory owned or occupied by a town can never be deleted
 *   - nothing is applied while flag attacks or raids are running
 *   - every territory needs its core chunk, and only known resource nodes
 *   - a chunk can belong to only one territory
 *   - changes to claimed territories need force=true (the editor asks first)
 *
 * Parsing, validation and neighbor/edge computation run on the calling (web)
 * thread. Only the ownership checks and the reload itself run on the main thread.
 */

package phonon.nodes.map

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Coord
import phonon.nodes.objects.DefaultResourceAttributeLoader
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Raid
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

public object MapEditApi {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** Parsed + validated edit, ready to compare against live state. */
    private class Prepared(
        val root: JsonObject,
        val territories: Map<Int, ParsedTerritory>,
        val errors: List<String>,
        val warnings: List<String>,
    )

    private class ParsedTerritory(
        val id: Int,
        val name: String,
        val chunks: Set<Long>,
        val nodes: List<String>,
    )

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    private fun keyX(k: Long): Int = (k shr 32).toInt()

    private fun keyZ(k: Long): Int = k.toInt()

    // ------------------------------------------------------------------

    @JvmStatic
    public fun worldJson(): String {
        val path = Config.pathWorld
        if (!Files.exists(path)) {
            return "{\"meta\":{\"type\":\"world\"},\"nodes\":{},\"territories\":{}}"
        }
        return Files.readString(path)
    }

    @JvmStatic
    public fun apply(json: String, dryRun: Boolean, force: Boolean, actor: String): String {
        val prepared = try {
            prepare(json)
        } catch (e: Exception) {
            return result(false, false, listOf("Could not read the edited world: ${e.message}"), emptyList(), null, null)
        }
        if (prepared.errors.isNotEmpty()) {
            return result(false, false, prepared.errors, prepared.warnings, null, null)
        }

        val plugin = Nodes.plugin ?: return result(false, false, listOf("Nodes is not enabled"), emptyList(), null, null)
        if (Bukkit.isPrimaryThread()) {
            return commitOnMainThread(prepared, dryRun, force, actor)
        }
        return try {
            Bukkit.getScheduler()
                .callSyncMethod(plugin, Callable { commitOnMainThread(prepared, dryRun, force, actor) })
                .get(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            result(false, false, listOf("Server did not respond in time: ${e.message}"), emptyList(), null, null)
        }
    }

    // ------------------------------------------------------------------
    // off-thread: parse, validate, compute neighbors + edges

    private fun prepare(json: String): Prepared {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val root = JsonParser.parseString(json).asJsonObject
        val nodesJson = root.get("nodes")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val terrJson = root.get("territories")?.takeIf { it.isJsonObject }?.asJsonObject
        if (terrJson == null) {
            errors.add("Missing \"territories\"")
            return Prepared(root, emptyMap(), errors, warnings)
        }

        // resource nodes must parse
        val loaded = try {
            DefaultResourceAttributeLoader.load(HashMap(0), nodesJson)
        } catch (e: Exception) {
            errors.add("Resource nodes could not be read: ${e.message}")
            HashMap()
        }
        for (name in nodesJson.keySet()) {
            if (!loaded.containsKey(name)) {
                errors.add("Resource node \"$name\" has invalid settings")
            }
        }

        val owner = HashMap<Long, Int>()
        val parsed = LinkedHashMap<Int, ParsedTerritory>()
        var duplicates = 0
        for (idStr in terrJson.keySet()) {
            val id = idStr.toIntOrNull()
            if (id == null) {
                errors.add("Territory id \"$idStr\" is not a number")
                continue
            }
            val t = terrJson.get(idStr).asJsonObject
            val name = t.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val label = if (name.isEmpty()) "Territory $id" else "Territory $id \"$name\""
            val arr = t.get("chunks")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            if (arr.size() % 2 != 0) {
                errors.add("$label has a broken chunk list")
                continue
            }
            if (arr.size() == 0) {
                errors.add("$label has no chunks")
                continue
            }
            val chunks = LinkedHashSet<Long>(arr.size())
            for (i in 0 until arr.size() step 2) {
                val k = key(arr[i].asInt, arr[i + 1].asInt)
                chunks.add(k)
                val prev = owner.put(k, id)
                if (prev != null && prev != id) {
                    duplicates++
                    if (duplicates <= 5) {
                        errors.add("Chunk ${keyX(k)}, ${keyZ(k)} is in both territory $prev and $id")
                    }
                }
            }
            val core = t.get("coreChunk")?.takeIf { it.isJsonArray }?.asJsonArray
            if (core == null || core.size() < 2) {
                errors.add("$label has no core chunk")
            } else if (!chunks.contains(key(core[0].asInt, core[1].asInt))) {
                errors.add("$label: core chunk ${core[0].asInt}, ${core[1].asInt} is outside the territory")
            }
            val nodes = ArrayList<String>()
            t.get("nodes")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { n ->
                val s = n.asString
                nodes.add(s)
                if (!nodesJson.has(s)) {
                    errors.add("$label uses resource \"$s\" which doesn't exist")
                }
            }
            parsed[id] = ParsedTerritory(id, name, chunks, nodes)
        }
        if (duplicates > 5) {
            errors.add("...and ${duplicates - 5} more chunks claimed by two territories")
        }

        // authoritative neighbors / isEdge / size (never trust the client for these)
        for ((id, p) in parsed) {
            val neighbors = sortedSetOf<Int>()
            var edge = false
            for (k in p.chunks) {
                val x = keyX(k)
                val z = keyZ(k)
                for (n in longArrayOf(key(x + 1, z), key(x - 1, z), key(x, z + 1), key(x, z - 1))) {
                    val o = owner[n]
                    if (o == null) {
                        edge = true
                    } else if (o != id) {
                        neighbors.add(o)
                    }
                }
            }
            val t = terrJson.get(id.toString()).asJsonObject
            val nArr = JsonArray()
            for (n in neighbors) {
                nArr.add(n)
            }
            t.add("neighbors", nArr)
            t.addProperty("isEdge", edge)
            t.addProperty("size", p.chunks.size)
        }

        return Prepared(root, parsed, errors, warnings)
    }

    // ------------------------------------------------------------------
    // main thread: compare with live state, then write + reload

    private fun commitOnMainThread(p: Prepared, dryRun: Boolean, force: Boolean, actor: String): String {
        val errors = ArrayList<String>(p.errors)
        val warnings = ArrayList<String>(p.warnings)

        if (FlagWar.chunkToAttacker.isNotEmpty()) {
            errors.add("Flag attacks are in progress. Apply the edit after they finish.")
        }
        if (Raid.byTerritory.isNotEmpty()) {
            errors.add("A raid is running. Apply the edit after it ends.")
        }

        val current = Nodes.territories
        val removed = current.keys.map { it.toInt() }.filter { it !in p.territories }
        val added = p.territories.keys.filter { TerritoryId(it) !in current }
        var changed = 0
        var changedClaimed = 0

        for (id in removed) {
            val t = current[TerritoryId(id)] ?: continue
            val holder: Town? = t.town ?: t.occupier
            if (holder != null) {
                errors.add("Territory $id \"${t.name}\" belongs to ${holder.name}. It can't be deleted while claimed.")
            }
        }
        for ((id, e) in p.territories) {
            val t = current[TerritoryId(id)] ?: continue
            val sameChunks = t.chunks.size == e.chunks.size && t.chunks.all { e.chunks.contains(key(it.x, it.z)) }
            val sameNodes = t.resourceNodes.toSet() == e.nodes.toSet()
            if (sameChunks && sameNodes && t.name == e.name) {
                continue
            }
            changed++
            val holder = t.town ?: t.occupier
            if (holder != null && (!sameChunks || !sameNodes)) {
                changedClaimed++
                if (changedClaimed <= 8) {
                    val what = buildList {
                        if (!sameChunks) add("size ${t.chunks.size} → ${e.chunks.size} chunks")
                        if (!sameNodes) add("resources changed")
                    }.joinToString(", ")
                    warnings.add("Territory $id \"${t.name}\" (claimed by ${holder.name}): $what")
                }
            }
        }
        if (changedClaimed > 8) {
            warnings.add("...and ${changedClaimed - 8} more claimed territories change")
        }

        val stats = Triple(added.size, removed.size, changed)
        if (errors.isNotEmpty()) {
            return result(false, false, errors, warnings, stats, null)
        }
        if (dryRun) {
            return result(true, false, errors, warnings, stats, null)
        }
        if (warnings.isNotEmpty() && !force) {
            return result(false, false, listOf("This edit changes claimed territories. Confirm to apply it anyway."), warnings, stats, null)
        }

        // ---- commit
        val path = Config.pathWorld
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val backup = Config.pathBackup.resolve("world-$stamp.json")
        try {
            Files.createDirectories(Config.pathBackup)
            if (Files.exists(path)) {
                Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
            }
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(p.root as JsonElement))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            return result(false, false, listOf("Could not write world.json: ${e.message}"), warnings, stats, null)
        }

        try {
            reloadKeepingWarState(removed)
        } catch (e: Exception) {
            Nodes.logger?.severe("[Nodes] map edit by $actor failed to load, restoring $backup: $e")
            try {
                if (Files.exists(backup)) {
                    Files.copy(backup, path, StandardCopyOption.REPLACE_EXISTING)
                }
                reloadKeepingWarState(emptyList())
            } catch (e2: Exception) {
                Nodes.logger?.severe("[Nodes] restoring the previous world also failed: $e2")
            }
            return result(false, false, listOf("The new world failed to load, so the previous one was restored: ${e.message}"), warnings, stats, null)
        }

        Nodes.logger?.info(
            "[Nodes] $actor applied a map edit: +${stats.first} new, -${stats.second} removed, " +
                "${stats.third} changed (backup ${backup.fileName})",
        )
        return result(true, true, emptyList(), warnings, stats, backup.fileName.toString())
    }

    /**
     * Reload resources + all territories from world.json. Unlike a plain reload this:
     * drops deleted territories, rebuilds the chunk index from scratch (chunks can move
     * between territories), keeps per-chunk war state, and recalculates town claims.
     */
    private fun reloadKeepingWarState(removed: List<Int>) {
        val warState = HashMap<Coord, Pair<Town?, Town?>>()
        for ((c, tc) in Nodes.territoryChunks) {
            if (tc.attacker != null || tc.occupier != null) {
                warState[c] = tc.attacker to tc.occupier
            }
        }

        for (id in removed) {
            Nodes.territories.remove(TerritoryId(id))
        }
        Nodes.resourceNodes.clear()
        if (!Nodes.reloadWorldJson(reloadResources = true, reloadTerritories = true)) {
            throw IllegalStateException("world.json not found")
        }

        Nodes.territoryChunks.clear()
        for (t in Nodes.territories.values) {
            for (c in t.chunks) {
                val tc = TerritoryChunk(c, t)
                warState[c]?.let { (attacker, occupier) ->
                    tc.attacker = attacker
                    tc.occupier = occupier
                }
                Nodes.territoryChunks[c] = tc
            }
        }

        for (town in Nodes.towns.values) {
            town.territories.removeIf { Nodes.territories[it] == null }
            var used = 0
            for (id in town.territories) {
                Nodes.territories[id]?.let { used += it.cost }
            }
            for (id in town.annexed) {
                Nodes.territories[id]?.let { used -= it.cost }
            }
            town.claimsUsed = used
            town.claimsMax = Nodes.calculateMaxClaims(town)
        }
        Nodes.needsSave = true
        MapExporter.worldChanged()
    }

    // ------------------------------------------------------------------

    private fun result(
        ok: Boolean,
        applied: Boolean,
        errors: List<String>,
        warnings: List<String>,
        stats: Triple<Int, Int, Int>?,
        backup: String?,
    ): String {
        val j = Json(512)
        j.obj {
            key("ok").bool(ok)
            key("applied").bool(applied)
            key("errors").strs(errors)
            key("warnings").strs(warnings)
            if (stats != null) {
                key("added").num(stats.first)
                key("removed").num(stats.second)
                key("changed").num(stats.third)
            }
            key("backup").strOrNull(backup)
        }
        return j.toString()
    }
}
