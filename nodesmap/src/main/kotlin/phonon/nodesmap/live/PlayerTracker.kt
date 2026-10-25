package phonon.nodesmap.live

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import phonon.nodesmap.MapConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Publishes visible player positions as /data/players.json.
 * Main-thread cost: one pass over online players every N ticks.
 * Output only changes when someone moves, so idle polls are 304s.
 */
class PlayerTracker(
    private val plugin: Plugin,
    private val world: World,
    private val publish: (String) -> Unit,
    /** when the built-in server is off, also write the file for nginx/caddy */
    private val fileOut: Path?,
) {
    private var task: BukkitTask? = null
    private var last: String = ""

    fun start(cfg: MapConfig) {
        stop()
        last = ""
        if (!cfg.playersShow) {
            // tells open maps to clear players and hide the Players option
            emit("""{"players":[],"disabled":true}""")
            return
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { update(cfg) }, 20L, cfg.playersUpdateTicks)
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun visible(p: Player, cfg: MapConfig): Boolean {
        if (p.world !== world) return false
        if (p.hasPermission("nodesmap.hidden")) return false
        if (cfg.hideSpectators && p.gameMode == GameMode.SPECTATOR) return false
        if (cfg.hideSneaking && p.isSneaking) return false
        if (cfg.hideInvisible && p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false
        // common vanish plugins (SuperVanish, PremiumVanish, Essentials) set this metadata
        if (p.getMetadata("vanished").any { it.asBoolean() }) return false
        return true
    }

    private fun update(cfg: MapConfig) {
        val sb = StringBuilder(64 + Bukkit.getOnlinePlayers().size * 64)
        sb.append("{\"players\":[")
        var first = true
        for (p in Bukkit.getOnlinePlayers()) {
            if (!visible(p, cfg)) continue
            if (!first) sb.append(',')
            first = false
            val l = p.location
            sb.append("{\"name\":\"").append(p.name).append("\",\"uuid\":\"").append(p.uniqueId)
                .append("\",\"x\":").append(l.blockX)
                .append(",\"z\":").append(l.blockZ)
                .append(",\"yaw\":").append(l.yaw.toInt())
                .append('}')
        }
        sb.append("]}")
        val json = sb.toString()
        if (json == last) return
        emit(json)
    }

    private fun emit(json: String) {
        last = json
        publish(json)
        if (fileOut != null) {
            Bukkit.getScheduler().runTaskAsynchronously(
                plugin,
                Runnable {
                    try {
                        Files.createDirectories(fileOut.parent)
                        val tmp = fileOut.resolveSibling("players.json.tmp")
                        Files.writeString(tmp, json)
                        Files.move(tmp, fileOut, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: Exception) {}
                },
            )
        }
    }
}
