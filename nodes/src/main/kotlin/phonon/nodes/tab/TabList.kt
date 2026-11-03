/*
 * Four column tab list: Staff | Nation | Allies | Enemies. Each viewer sees their own nation's
 * towns with the online players of each (leaders and officers get icons), the towns allied to
 * them and the towns at war with them. It needs the PacketEvents plugin: tab columns are made
 * of fake entries sent as packets (TabPackets). Without PacketEvents, or with tabList.enabled
 * false, nothing here runs and the normal tab list stays.
 */

package phonon.nodes.tab

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import phonon.nodes.Nodes
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Town
import java.util.UUID

// one tab cell: its text and, optionally, the player whose head it shows
class TabCell(val text: Component, val head: Player? = null)

private class StaffRank(val permission: String, val prefix: String, val color: String)

object TabList {
    const val COLUMNS = 4
    const val ROWS = 20

    private val mm = MiniMessage.miniMessage()

    private var enabled = false
    private var updateTicks = 40L
    private var headers = mapOf<String, String>()
    private var headerLines: List<String> = emptyList()
    private var footerLines: List<String> = emptyList()
    private var staff: List<StaffRank> = emptyList()
    private var iconNationLeader = "<gold>♛ </gold>"
    private var iconLeader = "<gold>★ </gold>"
    private var iconOfficer = "<yellow>✦ </yellow>"
    private var iconMember = "<dark_gray>▪ </dark_gray>"
    private var maxPerSection = 15
    private var showPing = false
    private var task: org.bukkit.scheduler.BukkitTask? = null

    fun loadConfig(config: ConfigurationSection) {
        val sec = config.getConfigurationSection("tabList")
        enabled = sec?.getBoolean("enabled", false) ?: false
        if (sec == null) {
            return
        }
        updateTicks = sec.getLong("updateTicks", 40L).coerceIn(10L, 1200L)
        headers = listOf("staff", "nation", "allies", "enemies").associateWith {
            sec.getString("columnHeaders.$it", "") ?: ""
        }
        headerLines = sec.getStringList("header")
        footerLines = sec.getStringList("footer")
        val ranks = ArrayList<StaffRank>()
        val staffSec = sec.getConfigurationSection("staffPermissions")
        for (key in staffSec?.getKeys(false) ?: emptySet()) {
            val perm = staffSec?.getString("$key.permission") ?: continue
            ranks.add(StaffRank(perm, staffSec.getString("$key.prefix", "") ?: "", staffSec.getString("$key.color", "") ?: ""))
        }
        staff = ranks
        iconNationLeader = sec.getString("icons.nationLeader", iconNationLeader) ?: iconNationLeader
        iconLeader = sec.getString("icons.leader", iconLeader) ?: iconLeader
        iconOfficer = sec.getString("icons.officer", iconOfficer) ?: iconOfficer
        iconMember = sec.getString("icons.member", iconMember) ?: iconMember
        maxPerSection = sec.getInt("maxPlayersPerSection", 15).coerceIn(1, ROWS - 2)
        showPing = sec.getBoolean("showPlayerPing", false)
    }

    fun start(plugin: Plugin) {
        task?.cancel()
        task = null
        if (!enabled) {
            return
        }
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            plugin.logger.warning("[TabList] tabList.enabled is true but the PacketEvents plugin is not installed, the tab list stays vanilla")
            return
        }
        Bukkit.getPluginManager().registerEvents(TabListListener(), plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { refresh() }, 40L, updateTicks)
        plugin.logger.info("[TabList] Custom tab list enabled")
    }

    internal fun forget(uuid: UUID) {
        if (task != null) {
            TabPackets.forget(uuid)
        }
    }

    private fun refresh() {
        val online = Bukkit.getOnlinePlayers().toList()
        for (viewer in online) {
            try {
                val columns = build(viewer, online)
                TabPackets.show(viewer, online, columns)
                viewer.sendPlayerListHeaderAndFooter(joinLines(headerLines, viewer, online.size), joinLines(footerLines, viewer, online.size))
            } catch (e: Exception) {
                Nodes.logger?.warning("[TabList] Could not update the tab list of ${viewer.name}: ${e.message}")
            }
        }
    }

    private fun joinLines(lines: List<String>, viewer: Player, online: Int): Component {
        val tps = String.format("%.1f", Bukkit.getTPS()[0].coerceAtMost(20.0))
        var result = Component.empty()
        for ((i, line) in lines.withIndex()) {
            val text = line
                .replace("%ping%", viewer.ping.toString())
                .replace("%player%", viewer.name)
                .replace("%online%", online.toString())
                .replace("%tps%", tps)
            result = result.append(mm.deserialize(text))
            if (i < lines.size - 1) {
                result = result.append(Component.newline())
            }
        }
        return result
    }

    // the four columns of what this viewer sees, each ROWS cells long (null = empty cell)
    private fun build(viewer: Player, online: List<Player>): List<List<TabCell?>> {
        val resident = Nodes.getResident(viewer)
        val town = resident?.town
        val nation = resident?.nation

        val nationTowns: List<Town> = when {
            town == null -> emptyList()
            nation != null -> listOf(town) + nation.towns.filter { it !== town }.sortedBy { it.name }
            else -> listOf(town)
        }
        val allyTowns = if (town == null) emptyList() else town.allies.filter { it.nation == null || it.nation !== nation }.sortedBy { it.name }
        val enemyTowns = if (town == null) emptyList() else town.enemies.filter { it.nation == null || it.nation !== nation }.sortedBy { it.name }

        return listOf(
            column("staff", staffCells(online)),
            column("nation", townCells(nationTowns, TextColor.color(0x7DD4FF))),
            column("allies", townCells(allyTowns, TextColor.color(0x77DD77))),
            column("enemies", townCells(enemyTowns, TextColor.color(0xFF7070))),
        )
    }

    private fun column(key: String, cells: List<TabCell>): List<TabCell?> {
        val out = ArrayList<TabCell?>(ROWS)
        out.add(TabCell(mm.deserialize(headers[key] ?: "")))
        val room = ROWS - 1
        if (cells.size > room) {
            out.addAll(cells.subList(0, room - 1))
            out.add(TabCell(Component.text("+${cells.size - (room - 1)} more", TextColor.color(0x888888))))
        } else {
            out.addAll(cells)
        }
        while (out.size < ROWS) {
            out.add(null)
        }
        return out
    }

    private fun staffCells(online: List<Player>): List<TabCell> {
        val ranked = ArrayList<Pair<Int, Player>>()
        for (p in online) {
            val rank = staff.indexOfFirst { p.hasPermission(it.permission) }
            if (rank >= 0) {
                ranked.add(Pair(rank, p))
            }
        }
        ranked.sortWith(compareBy({ it.first }, { it.second.name.lowercase() }))
        return ranked.map { (rank, p) ->
            val entry = staff[rank]
            TabCell(mm.deserialize("${entry.prefix}${entry.color}<name>", Placeholder.unparsed("name", p.name)), p)
        }
    }

    private fun townCells(towns: List<Town>, accent: TextColor): List<TabCell> {
        val cells = ArrayList<TabCell>()
        for (town in towns) {
            cells.add(TabCell(Component.text("▎ ${town.name}", accent)))
            val players = town.playersOnline.sortedWith(compareBy({ rank(town, it) }, { it.name.lowercase() }))
            for (p in players.take(maxPerSection)) {
                cells.add(TabCell(icon(town, p).append(Component.text(p.name, TextColor.color(0xFFFFFF))), p))
            }
            if (players.size > maxPerSection) {
                cells.add(TabCell(Component.text("  +${players.size - maxPerSection} more", TextColor.color(0x888888))))
            }
        }
        return cells
    }

    // 0 nation leader, 1 town leader, 2 officer, 3 member
    private fun rank(town: Town, player: Player): Int {
        val resident = Nodes.getResident(player) ?: return 3
        val nation: Nation? = town.nation
        return when {
            nation != null && nation.capital.leader === resident -> 0
            town.leader === resident -> 1
            town.officers.contains(resident) -> 2
            else -> 3
        }
    }

    private fun icon(town: Town, player: Player): Component = mm.deserialize(
        when (rank(town, player)) {
            0 -> iconNationLeader
            1 -> iconLeader
            2 -> iconOfficer
            else -> iconMember
        },
    )

    internal fun pingEnabled(): Boolean = showPing
}

class TabListListener : Listener {
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        TabList.forget(event.player.uniqueId)
    }
}
