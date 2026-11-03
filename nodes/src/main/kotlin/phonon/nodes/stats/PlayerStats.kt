/**
 * KDR / kill-death stats for Nodes (no playtime — use Essentials).
 *
 * - Kills: player kills player only
 * - Deaths: all deaths (PvP, void, suicide, environment, etc.)
 * - KDR: kills / max(deaths, 1)
 * - Town/nation KDR: aggregate of current members' kills/deaths
 */

package phonon.nodes.stats

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Town
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class KillDeathRecord(
    var kills: Int = 0,
    var deaths: Int = 0,
) {
    fun kdr(): Double {
        val d = if (deaths <= 0) 1 else deaths
        return kills.toDouble() / d.toDouble()
    }

    fun kdrString(): String = String.format("%.2f", kdr())
}

object PlayerStats {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    val path: Path = Paths.get("plugins/nodes/player-stats.json")

    val records: ConcurrentHashMap<UUID, KillDeathRecord> = ConcurrentHashMap()

    fun get(uuid: UUID): KillDeathRecord = records.getOrPut(uuid) { KillDeathRecord() }

    fun addKill(uuid: UUID) {
        get(uuid).kills += 1
        needsSave = true
    }

    fun addDeath(uuid: UUID) {
        get(uuid).deaths += 1
        needsSave = true
    }

    fun reset(uuid: UUID) {
        records[uuid] = KillDeathRecord()
        needsSave = true
        save()
    }

    // wipes every player's kills and deaths. The old file is copied to
    // player-stats.backup-<time>.json first. Returns how many records were cleared.
    fun resetAll(): Int {
        val count = records.size
        try {
            if (Files.exists(path)) {
                Files.copy(path, path.resolveSibling("player-stats.backup-${System.currentTimeMillis()}.json"))
            }
        } catch (e: Exception) {
            Nodes.logger?.warning("[Stats] Could not back up player-stats.json before reset: ${e.message}")
        }
        records.clear()
        needsSave = true
        save()
        return count
    }

    fun formatLine(name: String, rec: KillDeathRecord): String = "${ChatColor.YELLOW}$name ${ChatColor.WHITE}${rec.kills}K / ${rec.deaths}D ${ChatColor.GRAY}(KDR ${rec.kdrString()})"

    fun topKills(n: Int = 10): List<Pair<String, KillDeathRecord>> = topBy(n) { it.kills.toDouble() }

    fun topDeaths(n: Int = 10): List<Pair<String, KillDeathRecord>> = topBy(n) { it.deaths.toDouble() }

    fun topKdr(n: Int = 10): List<Pair<String, KillDeathRecord>> = topBy(n) { it.kdr() }

    private fun topBy(n: Int, score: (KillDeathRecord) -> Double): List<Pair<String, KillDeathRecord>> = records.entries
        .map { (uuid, rec) ->
            val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().substring(0, 8)
            name to rec
        }
        .sortedByDescending { score(it.second) }
        .take(n)

    /** Aggregate kills/deaths of residents currently in the town. */
    fun townRecord(town: Town): KillDeathRecord {
        val total = KillDeathRecord()
        for (res in town.residents) {
            val r = get(res.uuid)
            total.kills += r.kills
            total.deaths += r.deaths
        }
        return total
    }

    fun nationRecord(nation: Nation): KillDeathRecord {
        val total = KillDeathRecord()
        for (town in nation.towns) {
            val tr = townRecord(town)
            total.kills += tr.kills
            total.deaths += tr.deaths
        }
        return total
    }

    fun topTownsKdr(n: Int = 10): List<Pair<String, KillDeathRecord>> = Nodes.towns.values
        .map { it.name to townRecord(it) }
        .filter { it.second.kills > 0 || it.second.deaths > 0 }
        .sortedByDescending { it.second.kdr() }
        .take(n)

    fun topNationsKdr(n: Int = 10): List<Pair<String, KillDeathRecord>> = Nodes.nations.values
        .map { it.name to nationRecord(it) }
        .filter { it.second.kills > 0 || it.second.deaths > 0 }
        .sortedByDescending { it.second.kdr() }
        .take(n)

    fun printPlayer(sender: org.bukkit.command.CommandSender, targetName: String, uuid: UUID) {
        val rec = get(uuid)
        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}Stats — $targetName")
        Message.print(sender, "  ${ChatColor.WHITE}Kills: ${ChatColor.GREEN}${rec.kills}")
        Message.print(sender, "  ${ChatColor.WHITE}Deaths: ${ChatColor.RED}${rec.deaths}")
        Message.print(sender, "  ${ChatColor.WHITE}KDR: ${ChatColor.AQUA}${rec.kdrString()}")
    }

    fun printTop(sender: org.bukkit.command.CommandSender, title: String, rows: List<Pair<String, KillDeathRecord>>) {
        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}$title")
        if (rows.isEmpty()) {
            Message.print(sender, "  (empty)")
            return
        }
        rows.forEachIndexed { i, (name, rec) ->
            Message.print(sender, "  ${ChatColor.GRAY}${i + 1}. ${formatLine(name, rec)}")
        }
    }

    var needsSave: Boolean = false
    private var saveTask: org.bukkit.scheduler.BukkitTask? = null

    fun load() {
        records.clear()
        if (!Files.exists(path)) return
        try {
            val json = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            val players = json.getAsJsonObject("players") ?: return
            for ((k, v) in players.entrySet()) {
                val o = v.asJsonObject
                records[UUID.fromString(k)] = KillDeathRecord(
                    o.get("kills")?.asInt ?: 0,
                    o.get("deaths")?.asInt ?: 0,
                )
            }
            Nodes.logger?.info("[Stats] Loaded ${records.size} player record(s)")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Stats] load failed: ${e.message}")
        }
        ensureSaveTask()
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val players = JsonObject()
            for ((id, rec) in records) {
                val o = JsonObject()
                o.addProperty("kills", rec.kills)
                o.addProperty("deaths", rec.deaths)
                players.add(id.toString(), o)
            }
            root.add("players", players)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
            needsSave = false
        } catch (e: Exception) {
            Nodes.logger?.severe("[Stats] save failed: ${e.message}")
        }
    }

    fun ensureSaveTask() {
        val plugin = Nodes.plugin ?: return
        if (saveTask == null || saveTask?.isCancelled == true) {
            saveTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable {
                    if (needsSave) save()
                },
                20L * 60,
                20L * 60,
            )
        }
    }

    fun shutdown() {
        saveTask?.cancel()
        saveTask = null
        save()
    }
}
