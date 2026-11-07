/*
 * Duel arenas, kits, win/loss records and crash recovery data, saved in plugins/nodes/duels.json.
 * Pending restores hold where a player has to be put back (and their inventory in kit duels), so a
 * crash in the middle of a duel never loses anything. Escrow holds wagers that were taken but not
 * yet paid out; anything left there on startup is refunded.
 */

package phonon.nodes.duel

import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Nodes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Base64

class ArenaPoint(
    var world: String = "",
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
) {
    fun toLocation(): Location? {
        val w = Bukkit.getWorld(world) ?: return null
        return Location(w, x, y, z, yaw, pitch)
    }

    companion object {
        fun of(loc: Location): ArenaPoint = ArenaPoint(loc.world?.name ?: "", loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
    }
}

class Arena(
    var name: String = "",
    var spawn1: ArenaPoint? = null,
    var spawn2: ArenaPoint? = null,
    var spectate: ArenaPoint? = null,
) {
    fun ready(): Boolean = spawn1 != null && spawn2 != null && spawn1?.world == spawn2?.world
}

// a loadout: inventory slot (0-35 items, 36-39 armor, 40 offhand) to the item encoded in base64
class Kit(
    var name: String = "",
    var items: MutableMap<Int, String> = mutableMapOf(),
)

class DuelStats(
    var name: String = "",
    var wins: Int = 0,
    var losses: Int = 0,
    var streak: Int = 0,
    var bestStreak: Int = 0,
)

// where a player goes back to after a duel or spectating; items only for kit duels
class Restore(
    var world: String = "",
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var gameMode: String = "SURVIVAL",
    var health: Double = 20.0,
    var food: Int = 20,
    var items: MutableMap<Int, String>? = null,
)

class Escrow(
    var first: String = "",
    var second: String = "",
    var cents: Long = 0L,
)

private class DuelFile(
    var arenas: MutableMap<String, Arena> = mutableMapOf(),
    var kits: MutableMap<String, Kit> = mutableMapOf(),
    var stats: MutableMap<String, DuelStats> = mutableMapOf(),
    var pending: MutableMap<String, Restore> = mutableMapOf(),
    var escrow: MutableMap<String, Escrow> = mutableMapOf(),
)

object DuelData {
    const val SLOTS = 41

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/duels.json")
    private var file = DuelFile()

    fun load() {
        file = DuelFile()
        if (!Files.exists(path)) {
            return
        }
        try {
            gson.fromJson(String(Files.readAllBytes(path), StandardCharsets.UTF_8), DuelFile::class.java)?.let { file = it }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Duels] Could not read duels.json: ${e.message}")
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("duels.tmp")
            Files.write(tmp, gson.toJson(file).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Nodes.logger?.severe("[Duels] Could not save duels.json: ${e.message}")
        }
    }

    // ===================================
    // arenas and kits
    // ===================================
    fun arenas(): List<Arena> = file.arenas.values.sortedBy { it.name.lowercase() }

    fun arena(name: String): Arena? = file.arenas.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun addArena(name: String): Arena {
        val arena = Arena(name)
        file.arenas[name.lowercase()] = arena
        save()
        return arena
    }

    fun removeArena(name: String): Boolean {
        val removed = file.arenas.remove(name.lowercase()) != null
        if (removed) {
            save()
        }
        return removed
    }

    fun kits(): List<Kit> = file.kits.values.sortedBy { it.name.lowercase() }

    fun kit(name: String): Kit? = file.kits.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun saveKit(name: String, player: Player) {
        file.kits[name.lowercase()] = Kit(name, snapshot(player))
        save()
    }

    fun removeKit(name: String): Boolean {
        val removed = file.kits.remove(name.lowercase()) != null
        if (removed) {
            save()
        }
        return removed
    }

    // ===================================
    // items
    // ===================================
    fun snapshot(player: Player): MutableMap<Int, String> {
        val out = mutableMapOf<Int, String>()
        for (slot in 0 until SLOTS) {
            val item = player.inventory.getItem(slot) ?: continue
            if (item.type.isAir) {
                continue
            }
            out[slot] = Base64.getEncoder().encodeToString(item.serializeAsBytes())
        }
        return out
    }

    // replaces the player's whole inventory with these items
    fun apply(player: Player, items: Map<Int, String>) {
        player.inventory.clear()
        for ((slot, text) in items) {
            if (slot !in 0 until SLOTS) {
                continue
            }
            try {
                player.inventory.setItem(slot, ItemStack.deserializeBytes(Base64.getDecoder().decode(text)))
            } catch (e: Exception) {
                Nodes.logger?.warning("[Duels] Skipped an unreadable item in slot $slot: ${e.message}")
            }
        }
    }

    // ===================================
    // pending restores and escrow
    // ===================================
    fun pending(uuid: String): Restore? = file.pending[uuid]

    fun setPending(uuid: String, restore: Restore) {
        file.pending[uuid] = restore
        save()
    }

    fun clearPending(uuid: String) {
        if (file.pending.remove(uuid) != null) {
            save()
        }
    }

    fun escrow(): Map<String, Escrow> = file.escrow

    fun setEscrow(id: String, escrow: Escrow) {
        file.escrow[id] = escrow
        save()
    }

    fun clearEscrow(id: String) {
        if (file.escrow.remove(id) != null) {
            save()
        }
    }

    // ===================================
    // records
    // ===================================
    fun stats(uuid: String): DuelStats? = file.stats[uuid]

    fun recordWin(uuid: String, name: String) {
        val s = file.stats.getOrPut(uuid) { DuelStats(name) }
        s.name = name
        s.wins++
        s.streak++
        if (s.streak > s.bestStreak) {
            s.bestStreak = s.streak
        }
        save()
    }

    fun recordLoss(uuid: String, name: String) {
        val s = file.stats.getOrPut(uuid) { DuelStats(name) }
        s.name = name
        s.losses++
        s.streak = 0
        save()
    }

    fun resetStats(uuid: String): Boolean {
        val removed = file.stats.remove(uuid) != null
        if (removed) {
            save()
        }
        return removed
    }

    fun top(count: Int): List<DuelStats> = file.stats.values.sortedWith(compareByDescending<DuelStats> { it.wins }.thenBy { it.losses }).take(count)
}
