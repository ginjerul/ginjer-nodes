/*
 * Points of interest (POIs) and who has discovered them. POIs are placed by admins and saved in
 * plugins/nodes/pois.json; each player's discoveries and titles are in plugins/nodes/discoveries.json.
 * A player only ever sees the POIs they found, so there is nothing to browse before exploring.
 */

package phonon.nodes.poi

import com.google.gson.GsonBuilder
import org.bukkit.Material
import phonon.nodes.Nodes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

enum class PoiType(val label: String, val icon: Material) {
    VILLAGE("Abandoned village", Material.OAK_DOOR),
    RUIN("Ruins", Material.MOSSY_STONE_BRICKS),
    CAVE("Cave", Material.LANTERN),
    SHRINE("Shrine", Material.AMETHYST_SHARD),
    SHIPWRECK("Shipwreck", Material.OAK_BOAT),
    DUNGEON("Dungeon", Material.SPAWNER),
    CAMP("Enemy camp", Material.CAMPFIRE),
    ;

    companion object {
        fun parse(text: String): PoiType? = values().firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

class Poi(
    var id: Int = 0,
    var name: String = "",
    var type: String = PoiType.RUIN.name,
    var world: String = "",
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var radius: Double = 12.0,
    var description: String = "",
    // reward for the first time a player finds it: money in cents, items as MATERIAL:amount, a title
    var money: Long = 0L,
    var items: MutableList<String> = mutableListOf(),
    var title: String? = null,
    // exact items (custom names, enchantments, plugin items) saved from the admin's hand, base64
    var customItems: MutableList<String> = mutableListOf(),
    // the first player to find it
    var firstBy: String? = null,
    var firstByName: String? = null,
    var firstAt: Long = 0L,
    // players cannot find it before this time (ms), so the admin who places it does not discover it
    var activeAt: Long = 0L,
) {
    fun poiType(): PoiType = PoiType.parse(type) ?: PoiType.RUIN
}

// one player's progress: poi id to the time found, and titles earned
class PoiProgress(
    var found: MutableMap<String, Long> = mutableMapOf(),
    var titles: MutableList<String> = mutableListOf(),
    var selectedTitle: String? = null,
)

private class PoiFile(
    var nextId: Int = 1,
    var pois: MutableList<Poi> = mutableListOf(),
)

private class DiscoveryFile(
    var players: MutableMap<String, PoiProgress> = mutableMapOf(),
)

object PoiData {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val poiPath: Path = Paths.get("plugins/nodes/pois.json")
    private val discPath: Path = Paths.get("plugins/nodes/discoveries.json")
    private var poiFile = PoiFile()
    private var discFile = DiscoveryFile()

    fun load() {
        poiFile = PoiFile()
        discFile = DiscoveryFile()
        poiFile = read(poiPath, PoiFile::class.java) ?: PoiFile()
        discFile = read(discPath, DiscoveryFile::class.java) ?: DiscoveryFile()
    }

    private fun <T> read(path: Path, type: Class<T>): T? {
        if (!Files.exists(path)) {
            return null
        }
        return try {
            gson.fromJson(String(Files.readAllBytes(path), StandardCharsets.UTF_8), type)
        } catch (e: Exception) {
            Nodes.logger?.severe("[POI] Could not read ${path.fileName}: ${e.message}")
            null
        }
    }

    private fun write(path: Path, data: Any) {
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.write(tmp, gson.toJson(data).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Nodes.logger?.severe("[POI] Could not save ${path.fileName}: ${e.message}")
        }
    }

    fun savePois() = write(poiPath, poiFile)

    fun saveDiscoveries() = write(discPath, discFile)

    fun pois(): List<Poi> = poiFile.pois.sortedBy { it.id }

    fun get(id: Int): Poi? = poiFile.pois.firstOrNull { it.id == id }

    fun add(poi: Poi): Poi {
        poi.id = poiFile.nextId++
        poiFile.pois.add(poi)
        savePois()
        return poi
    }

    fun remove(id: Int): Boolean {
        val removed = poiFile.pois.removeIf { it.id == id }
        if (removed) {
            discFile.players.values.forEach { it.found.remove("$id") }
            savePois()
            saveDiscoveries()
        }
        return removed
    }

    fun progress(uuid: String): PoiProgress = discFile.players.getOrPut(uuid) { PoiProgress() }

    fun peek(uuid: String): PoiProgress? = discFile.players[uuid]

    fun foundCount(uuid: String): Int {
        val p = discFile.players[uuid] ?: return 0
        return pois().count { p.found.containsKey("${it.id}") }
    }

    fun hasFound(uuid: String, id: Int): Boolean = discFile.players[uuid]?.found?.containsKey("$id") == true

    fun selectedTitle(uuid: String): String? = discFile.players[uuid]?.selectedTitle

    // admin reset: one POI or everything for a player
    fun reset(uuid: String, id: Int?): Boolean {
        val p = discFile.players[uuid] ?: return false
        if (id == null) {
            discFile.players.remove(uuid)
        } else if (p.found.remove("$id") == null) {
            return false
        }
        saveDiscoveries()
        return true
    }
}
