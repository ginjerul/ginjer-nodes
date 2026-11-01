package phonon.nodes.war

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.mining.MiningBoost
import phonon.nodes.stats.WarStats
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Schedules automatic war/skirmish disable and shows a boss bar countdown.
 * The timer is saved to warTimer.json so it survives server restarts.
 */
object WarTimer {
    private val file: Path get() = Paths.get(Config.pathPlugin, "warTimer.json")
    private var reason: String = ""

    private var endTask: BukkitTask? = null
    private var tickTask: BukkitTask? = null

    private var endsAtMs: Long = 0L
    private var totalDurationMs: Long = 0L
    private var label: String = "War"

    private var bossBar: BossBar? = null

    fun cancel() {
        try {
            Files.deleteIfExists(file)
        } catch (_: Exception) {
        }
        endTask?.cancel()
        endTask = null
        tickTask?.cancel()
        tickTask = null
        endsAtMs = 0L
        totalDurationMs = 0L
        clearBossBar()
    }

    fun isTimed(): Boolean = endsAtMs > System.currentTimeMillis()

    /** epoch ms when the timed war ends, or 0 if the war isn't on a timer (used by the web map) */
    fun endsAt(): Long = if (isTimed()) endsAtMs else 0L

    fun scheduleDisable(durationMs: Long, reason: String, barLabel: String = "War") {
        val total = durationMs.coerceAtLeast(1L)
        start(System.currentTimeMillis() + total, total, reason, barLabel)
        save()
    }

    /**
     * After a restart: pick the saved timer back up. If it ran out while the
     * server was off, end the war now.
     */
    fun restore() {
        if (!Files.exists(file)) return
        val o: JsonObject = try {
            JsonParser.parseString(Files.readString(file)).asJsonObject
        } catch (e: Exception) {
            Nodes.plugin?.logger?.warning("[Nodes] couldn't read warTimer.json: ${e.message}")
            return
        }
        val ends = o.get("endsAt")?.asLong ?: 0L
        val total = o.get("total")?.asLong ?: 1L
        val why = o.get("reason")?.asString ?: "War time expired"
        val bar = o.get("label")?.asString ?: "War"
        if (!Nodes.war.enabled) {
            cancel()
            return
        }
        if (ends <= System.currentTimeMillis()) {
            Nodes.disableWar()
            WarStats.endWarSessionAndSave()
            Message.broadcast("${ChatColor.BOLD}[War] Disabled — $why (ran out while the server was offline)")
            phonon.nodes.util.DiscordRelay.war("**[War]** Nodes war **disabled** (timer ended)")
            cancel()
            return
        }
        start(ends, total, why, bar)
        save()
        Nodes.plugin?.logger?.info("[Nodes] war timer restored: ${formatLeft(ends - System.currentTimeMillis())} left")
    }

    private fun save() {
        try {
            val o = JsonObject()
            o.addProperty("endsAt", endsAtMs)
            o.addProperty("total", totalDurationMs)
            o.addProperty("label", label)
            o.addProperty("reason", reason)
            Files.writeString(file, o.toString())
        } catch (e: Exception) {
            Nodes.plugin?.logger?.warning("[Nodes] couldn't save warTimer.json: ${e.message}")
        }
    }

    private fun start(endsAt: Long, total: Long, why: String, barLabel: String) {
        cancel()
        val plugin = Nodes.plugin ?: return

        label = barLabel
        reason = why
        totalDurationMs = total
        endsAtMs = endsAt
        val durationMs = (endsAt - System.currentTimeMillis()).coerceAtLeast(1L)

        ensureBossBar()
        refreshBossBar()

        tickTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable { refreshBossBar() },
            20L,
            20L,
        )

        val ticks = (durationMs / 50L).coerceAtLeast(1L)
        endTask = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (Nodes.war.enabled) {
                    Nodes.disableWar()
                    WarStats.endWarSessionAndSave()
                    Message.broadcast("${ChatColor.BOLD}[War] Disabled — $why")
                    phonon.nodes.util.DiscordRelay.war("**[War]** Nodes war **disabled** (timer ended)")
                }
                cancel()
            },
            ticks,
        )
    }

    fun onPlayerJoin(player: Player) {
        if (!isTimed()) return
        ensureBossBar()
        bossBar?.addPlayer(player)
        refreshBossBar()
    }

    fun onPlayerQuit(player: Player) {
        bossBar?.removePlayer(player)
    }

    private fun ensureBossBar() {
        if (bossBar != null) return
        val bar = Bukkit.createBossBar(
            label,
            if (label.equals("Skirmish", ignoreCase = true)) BarColor.YELLOW else BarColor.RED,
            BarStyle.SOLID,
        )

        bar.isVisible = true
        for (p in Bukkit.getOnlinePlayers()) {
            bar.addPlayer(p)
        }
        bossBar = bar
    }

    private fun clearBossBar() {
        bossBar?.removeAll()
        bossBar = null
    }

    private fun refreshBossBar() {
        val bar = bossBar ?: return
        val now = System.currentTimeMillis()
        val left = (endsAtMs - now).coerceAtLeast(0L)

        if (left <= 0L || !Nodes.war.enabled) {
            clearBossBar()
            return
        }

        var title =
            "${ChatColor.RED}${ChatColor.BOLD}$label ${ChatColor.WHITE}ends in ${formatLeft(left)}"

        if (isGlobalMiningBoostActive()) {
            title += "${ChatColor.GREEN} | Mining boost on"
        }

        bar.setTitle(title)

        val progress = if (totalDurationMs > 0) {
            (left.toDouble() / totalDurationMs.toDouble()).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        bar.progress = progress

        for (p in Bukkit.getOnlinePlayers()) {
            if (!bar.players.contains(p)) {
                bar.addPlayer(p)
            }
        }
    }

    /** Whether a server-wide mining boost is running (shown on the war boss bar). */
    private fun isGlobalMiningBoostActive(): Boolean = try {
        MiningBoost.global.isActive()
    } catch (_: Throwable) {
        false
    }

    private fun formatLeft(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
