/**
 * 1.13+ Player town nametag
 *
 * NOTE: this conflicts with any other plugin doing nametag prefix/suffix (e.g. TAB)
 * Make sure all other plugins that affect prefix/suffix are disabled
 *
 * Each viewer has their own scoreboard (see PlayerScoreboardManager). Every other
 * online player in a town is put into a team named after their town ("t<id>"),
 * whose prefix shows the town name coloured by the viewer's relation to it.
 *
 * Performance: updates are incremental. Each refresh computes the wanted
 * teams/prefixes/entries for a viewer and only changes what differs from what
 * was last sent, so an unchanged server sends (almost) no scoreboard packets.
 * Previously every refresh deleted and re-created one team per town for every
 * viewer, which scaled with players x towns and flooded clients with packets.
 *
 * TODO: make sure name is not too long (may cause bukkit error)
 */

package phonon.nodes.objects

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.PlayerScoreboardManager
import java.util.UUID

public fun townNametagViewedByPlayer(town: Town, viewer: Player): String {
    val otherTown = Nodes.getResident(viewer)?.town
    if (otherTown !== null) {
        if (town === otherTown) {
            return town.nametagTown
        } else if (town.nation !== null && town.nation === otherTown.nation) {
            return town.nametagNation
        } else if (town.allies.contains(otherTown)) {
            return town.nametagAlly
        } else if (town.enemies.contains(otherTown)) {
            return town.nametagEnemy
        }
    }

    return town.nametagNeutral
}

public object Nametag {

    private const val SELF_TEAM = "player" // also used by Minimap
    private const val TOWN_TEAM_PREFIX = "t"

    private var task: BukkitTask? = null

    @Volatile
    private var updateLock: Boolean = false

    /**
     * What we last sent to one viewer's scoreboard. Lives as long as the
     * viewer's scoreboard in PlayerScoreboardManager (which is never removed).
     */
    private class ViewerState {
        val entryTeam: HashMap<String, String> = HashMap() // player name -> team name
        val teamPrefix: HashMap<String, String> = HashMap() // team name -> prefix
        var selfVisible: Boolean? = null
        var cleanedLegacyTeams: Boolean = false
    }

    private val viewerStates: HashMap<UUID, ViewerState> = HashMap()

    /**
     * Initialization: set nametag refresh scheduler
     */
    public fun start(plugin: Plugin, period: Long) {
        if (this.task !== null) {
            return
        }

        val task = object : Runnable {
            public override fun run() {
                Nametag.pipelinedUpdateAllText()
            }
        }

        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, period, period)
    }

    public fun stop() {
        val task = this.task
        if (task === null) {
            return
        }

        task.cancel()
        this.task = null
    }

    /** No-op, kept for API compatibility. */
    public fun create(player: Player): Nametag? = null

    /** No-op, kept for API compatibility. */
    public fun destroy(player: Player) {}

    /** No-op, kept for API compatibility. */
    public fun clear() {}

    /** No-op, kept for API compatibility. */
    public fun visibility(player: Player, visible: Boolean) {}

    /**
     * Bring one viewer's nametag teams up to date. Must run on the main thread.
     */
    public fun updateTextForPlayer(player: Player) {
        val scoreboard = PlayerScoreboardManager.getScoreboard(player.getUniqueId())
        val state = viewerStates.getOrPut(player.getUniqueId()) { ViewerState() }

        if (!state.cleanedLegacyTeams) {
            removeUntrackedTownTeams(scoreboard, state)
            state.cleanedLegacyTeams = true
        }

        // 1. compute wanted state
        val wantedEntryTeam = HashMap<String, String>()
        val wantedTeamPrefix = HashMap<String, String>()
        var selfVisible = false

        for (otherPlayer in Bukkit.getOnlinePlayers()) {
            val town = Nodes.getTownFromPlayer(otherPlayer)
            if (player === otherPlayer) {
                wantedEntryTeam[player.getName()] = SELF_TEAM
                wantedTeamPrefix[SELF_TEAM] = if (town !== null) townNametagViewedByPlayer(town, player) else ""
                selfVisible = town !== null
            } else if (town !== null) {
                val teamName = "$TOWN_TEAM_PREFIX${town.townNametagId}"
                wantedEntryTeam[otherPlayer.getName()] = teamName
                if (!wantedTeamPrefix.containsKey(teamName)) {
                    wantedTeamPrefix[teamName] = townNametagViewedByPlayer(town, player)
                }
            }
        }

        // 2. create teams / update prefixes that changed
        for ((teamName, prefix) in wantedTeamPrefix) {
            var team = scoreboard.getTeam(teamName)
            if (team === null) {
                team = scoreboard.registerNewTeam(teamName)
                if (teamName != SELF_TEAM) {
                    team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS)
                }
                state.teamPrefix.remove(teamName)
                if (teamName == SELF_TEAM) {
                    state.selfVisible = null
                }
            }
            if (state.teamPrefix[teamName] != prefix) {
                team.setPrefix(prefix)
                state.teamPrefix[teamName] = prefix
            }
        }

        val selfTeam = scoreboard.getTeam(SELF_TEAM)
        if (selfTeam !== null && state.selfVisible != selfVisible) {
            selfTeam.setOption(
                Team.Option.NAME_TAG_VISIBILITY,
                if (selfVisible) Team.OptionStatus.ALWAYS else Team.OptionStatus.NEVER,
            )
            state.selfVisible = selfVisible
        }

        // 3. move players into the right team (addEntry also removes them from their old team)
        for ((entry, teamName) in wantedEntryTeam) {
            val team = scoreboard.getTeam(teamName) ?: continue
            if (state.entryTeam[entry] != teamName || !team.hasEntry(entry)) {
                team.addEntry(entry)
                state.entryTeam[entry] = teamName
            }
        }

        // 4. remove players that went offline or left their town
        val staleEntries = state.entryTeam.keys.filter { !wantedEntryTeam.containsKey(it) }
        for (entry in staleEntries) {
            val teamName = state.entryTeam.remove(entry)
            if (teamName !== null && teamName != SELF_TEAM) {
                scoreboard.getTeam(teamName)?.removeEntry(entry)
            }
        }

        // 5. remove town teams nobody online belongs to any more
        val staleTeams = state.teamPrefix.keys.filter { it != SELF_TEAM && !wantedTeamPrefix.containsKey(it) }
        for (teamName in staleTeams) {
            state.teamPrefix.remove(teamName)
            scoreboard.getTeam(teamName)?.unregister()
        }

        // 6. only re-apply the scoreboard if something else replaced it (e.g. minimap off)
        if (player.getScoreboard() !== scoreboard) {
            player.setScoreboard(scoreboard)
        }
    }

    /**
     * Remove town teams ("t<number>") on a scoreboard that this state doesn't
     * know about, e.g. left over from a reload or from the old update code.
     */
    private fun removeUntrackedTownTeams(scoreboard: Scoreboard, state: ViewerState) {
        for (team in scoreboard.getTeams().toList()) {
            val name = team.getName()
            if (name.length > 1 && name.startsWith(TOWN_TEAM_PREFIX) && name.substring(1).all { it.isDigit() } && !state.teamPrefix.containsKey(name)) {
                team.unregister()
            }
        }
    }

    public fun updateAllText() {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        for (player in onlinePlayers) {
            updateTextForPlayer(player)
        }
    }

    /**
     * Update all player nametags using a pipeline:
     * - only update subset of online players each tick
     */
    public fun pipelinedUpdateAllText() {
        if (Nametag.updateLock == true) {
            return
        }

        val onlinePlayers = Bukkit.getOnlinePlayers().toList()
        if (onlinePlayers.size <= 0) {
            return
        }

        Nametag.updateLock = true

        val updatesPerTick: Int = Math.max(1, Math.ceil(onlinePlayers.size.toDouble() / Config.nametagPipelineTicks.toDouble()).toInt())
        var index = 0
        var tickOffset = 0L

        while (index < onlinePlayers.size) {
            val idxStart = index
            val idxEnd = Math.min(index + updatesPerTick, onlinePlayers.size)
            Bukkit.getScheduler().runTaskLater(
                Nodes.plugin!!,
                object : Runnable {
                    override fun run() {
                        for (i in idxStart until idxEnd) {
                            val player = onlinePlayers[i]
                            if (player.isOnline()) {
                                Nametag.updateTextForPlayer(player)
                            }
                        }
                    }
                },
                tickOffset,
            )

            index += updatesPerTick
            tickOffset += 1L
        }

        Bukkit.getScheduler().runTaskLater(
            Nodes.plugin!!,
            object : Runnable {
                override fun run() {
                    Nametag.updateLock = false
                }
            },
            tickOffset,
        )
    }
}
