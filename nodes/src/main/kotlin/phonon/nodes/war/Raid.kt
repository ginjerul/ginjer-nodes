package phonon.nodes.war

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.util.DiscordRelay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RaidPhase {
    PREP,
    LIVE,
    ENDED,
}

data class RaidState(
    val id: UUID = UUID.randomUUID(),
    val attacker: Town,
    val defender: Town,
    val territoryId: TerritoryId,
    var phase: RaidPhase = RaidPhase.PREP,
    val prepEndsAt: Long,
    var liveEndsAt: Long = 0L,
    val firedReminders: MutableSet<Long> = mutableSetOf(),
)

/**
 * Timed node raids (prep → live flag war on one territory).
 */
object Raid {
    /** Reminder times: prep/2, then keep halving while >= 10s */
    private fun prepReminderThresholds(prepMs: Long): List<Long> {
        val list = mutableListOf<Long>()
        var t = prepMs / 2
        while (t >= 10_000L) {
            list.add(t)
            t /= 2
        }
        return list
    }

    val byTerritory: ConcurrentHashMap<Int, RaidState> = ConcurrentHashMap()

    private val cooldownUntil: ConcurrentHashMap<UUID, Long> = ConcurrentHashMap()

    private var tickTask: BukkitTask? = null

    fun load() {
        ensureTask()
    }

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
    }

    fun get(territory: Territory): RaidState? = byTerritory[territory.id.toInt()]

    fun getLive(territory: Territory): RaidState? {
        val r = get(territory) ?: return null
        return if (r.phase == RaidPhase.LIVE) r else null
    }

    fun isLiveTerritory(territory: Territory?): Boolean {
        if (territory == null) return false
        return getLive(territory) != null
    }

    fun isPrepOrLive(territory: Territory?): Boolean {
        if (territory == null) return false
        val r = get(territory) ?: return false
        return r.phase == RaidPhase.PREP || r.phase == RaidPhase.LIVE
    }

    fun townInActiveRaid(town: Town): Boolean = byTerritory.values.any {
        (it.phase == RaidPhase.PREP || it.phase == RaidPhase.LIVE) &&
            (it.attacker === town || it.defender === town)
    }

    fun canRaidAttack(attackingTown: Town, territory: Territory): Boolean {
        val r = getLive(territory) ?: return false
        return attackingTown === r.attacker
    }

    fun isDefenderSide(town: Town, state: RaidState): Boolean {
        if (town === state.attacker) return false
        if (town === state.defender) return true
        if (state.defender.allies.contains(town)) return true
        val nation = state.defender.nation
        return nation !== null && nation === town.nation
    }

    fun canRaidRetake(town: Town, chunk: phonon.nodes.objects.TerritoryChunk): Boolean {
        val r = getLive(chunk.territory) ?: return false
        if (!isDefenderSide(town, r)) return false
        return chunk.occupier === r.attacker
    }

    fun canStart(attacker: Town, defender: Town, territory: Territory): String? {
        if (!Config.raidEnabled) return "Raids are disabled"
        if (Nodes.war.enabled) return "Cannot raid during war"
        if (attacker === defender) return "Cannot raid your own town"
        if (attacker.allies.contains(defender)) return "Cannot raid an ally"
        if (attacker.nation !== null && attacker.nation === defender.nation) {
            return "Cannot raid a town in your nation"
        }
        if (Warzone.isWarzoneTown(defender)) return "Cannot raid a warzone town"
        if (defender.territories.size < 2) return "Target must own more than one territory"
        if (territory.town !== defender) return "That territory is not owned by ${defender.name}"
        if (territory.id == defender.home) return "Cannot raid a home node"
        if (townInActiveRaid(attacker) || townInActiveRaid(defender)) {
            return "One of the towns is already in a raid"
        }
        val now = System.currentTimeMillis()
        val cdA = cooldownUntil[attacker.uuid] ?: 0L
        val cdD = cooldownUntil[defender.uuid] ?: 0L
        if (Config.raidCooldownMs > 0) {
            if (now < cdA) {
                return "Your town is on raid cooldown (${formatLeft(cdA - now)})"
            }
            if (now < cdD) {
                return "Target town is on raid cooldown (${formatLeft(cdD - now)})"
            }
        }
        if (Config.raidMinDefenderOnline > 0 &&
            defender.playersOnline.size < Config.raidMinDefenderOnline
        ) {
            return "Target town must have at least ${Config.raidMinDefenderOnline} player(s) online (has ${defender.playersOnline.size})"
        }
        if (!bordersTown(territory, attacker)) {
            return "That territory must border your town"
        }
        return null
    }

    fun bordersTown(territory: Territory, other: Town): Boolean {
        for (neighborId in territory.neighbors) {
            val neighbor = Nodes.getTerritoryFromId(neighborId) ?: continue
            if (neighbor.town === other) return true
        }
        return false
    }

    fun start(attacker: Town, defender: Town, territory: Territory): Result<RaidState> {
        canStart(attacker, defender, territory)?.let {
            return Result.failure(Exception(it))
        }
        val now = System.currentTimeMillis()
        val state = RaidState(
            attacker = attacker,
            defender = defender,
            territoryId = territory.id,
            phase = RaidPhase.PREP,
            prepEndsAt = now + Config.raidPrepMs,
        )
        byTerritory[territory.id.toInt()] = state
        ensureTask()
        val prepMin = Config.raidPrepMs / 60000.0
        val liveMin = Config.raidLiveMs / 60000.0
        Message.broadcast(
            "${ChatColor.DARK_RED}${ChatColor.BOLD}[Raid] ${attacker.name} is raiding ${defender.name} " +
                "on territory ${territory.id}! Prep ${formatMinutes(Config.raidPrepMs)}, " +
                "then live ${formatMinutes(Config.raidLiveMs)}. Allies may defend.",
        )
        DiscordRelay.raid(
            "**[Raid]** ${attacker.name} is raiding ${defender.name} on territory `${territory.id.toInt()}` " +
                "(prep ${formatMinutes(Config.raidPrepMs)}, then live ${formatMinutes(Config.raidLiveMs)})",
        )
        return Result.success(state)
    }

    /**
     * Attacker cancels during PREP only.
     * If ≤ cancel window left → defender wins (reward). Else abort, no reward.
     */
    fun cancelByAttacker(attacker: Town): String? {
        val state = byTerritory.values.firstOrNull {
            it.attacker === attacker && it.phase == RaidPhase.PREP
        } ?: return "You have no raid in prep to cancel"

        val left = state.prepEndsAt - System.currentTimeMillis()
        byTerritory.remove(state.territoryId.toInt())
        clearRaidCombat(state)

        if (left <= Config.raidCancelDefenderWinWindowMs) {
            awardAndFinish(state, defenderWins = true, reason = "Raid cancelled late in prep")
        } else {
            Message.broadcast(
                "${ChatColor.GOLD}[Raid] ${state.attacker.name} cancelled the raid on ${state.defender.name} (no reward)",
            )
            DiscordRelay.raid("**[Raid]** ${state.attacker.name} cancelled the raid on ${state.defender.name}")
        }
        return null
    }

    /** Ends raids between [town] and towns of [nation] without rewards; used when [town] surrenders into [nation]. */
    fun cancelForSurrender(town: Town, nation: Nation) {
        val states = byTerritory.values.filter { state ->
            (state.attacker === town && state.defender.nation === nation) ||
                (state.defender === town && state.attacker.nation === nation)
        }
        for (state in states) {
            byTerritory.remove(state.territoryId.toInt())
            clearRaidCombat(state)
            Message.broadcast(
                "${ChatColor.GOLD}[Raid] The raid between ${state.attacker.name} and ${state.defender.name} " +
                    "ended: ${town.name} surrendered (no reward)",
            )
            DiscordRelay.raid(
                "**[Raid]** ${town.name} surrendered, ending the raid between ${state.attacker.name} and ${state.defender.name}",
            )
        }
    }

    fun onCoreCaptured(attackerTown: Town, territory: Territory) {
        val state = getLive(territory) ?: return
        if (attackerTown !== state.attacker) return

        byTerritory.remove(state.territoryId.toInt())
        clearRaidCombat(state)
        awardAndFinish(state, defenderWins = false, reason = "Attackers captured the node")
    }
    fun statusLines(): List<String> {
        if (byTerritory.isEmpty()) return listOf("No active raids.")
        val now = System.currentTimeMillis()
        return byTerritory.values.map { s ->
            when (s.phase) {
                RaidPhase.PREP -> {
                    val left = (s.prepEndsAt - now).coerceAtLeast(0)
                    "[PREP] ${s.attacker.name} → ${s.defender.name} terr ${s.territoryId} (${formatLeft(left)} left)"
                }
                RaidPhase.LIVE -> {
                    val left = (s.liveEndsAt - now).coerceAtLeast(0)
                    "[LIVE] ${s.attacker.name} → ${s.defender.name} terr ${s.territoryId} (${formatLeft(left)} left)"
                }
                RaidPhase.ENDED -> "[ENDED] ${s.territoryId}"
            }
        }
    }

    fun adminForceEnd(territoryId: Int, defenderWins: Boolean): String? {
        val state = byTerritory[territoryId] ?: return "No raid on that territory"
        byTerritory.remove(territoryId)
        clearRaidCombat(state)
        awardAndFinish(
            state,
            defenderWins = defenderWins,
            reason = "Admin ended raid",
        )
        return null
    }

    private fun awardAndFinish(state: RaidState, defenderWins: Boolean, reason: String) {
        if (state.phase == RaidPhase.ENDED) return
        state.phase = RaidPhase.ENDED

        val winner = if (defenderWins) state.defender else state.attacker
        val rewards = if (defenderWins) Config.raidDefenderReward else Config.raidAttackerReward
        for ((mat, amount) in rewards) {
            if (amount > 0) {
                Nodes.addToIncome(winner, mat, amount)
            }
        }

        val now = System.currentTimeMillis()
        if (Config.raidCooldownMs > 0) {
            cooldownUntil[state.attacker.uuid] = now + Config.raidCooldownMs
            cooldownUntil[state.defender.uuid] = now + Config.raidCooldownMs
        }

        Message.broadcast(
            "${ChatColor.GOLD}${ChatColor.BOLD}[Raid] ${winner.name} wins! " +
                "($reason) Rewards added to /t income.",
        )
        DiscordRelay.raid(
            "**[Raid]** ${winner.name} won the raid: ${state.attacker.name} vs ${state.defender.name} " +
                "on territory `${state.territoryId.toInt()}` ($reason)",
        )
    }

    private fun clearRaidCombat(state: RaidState) {
        val territory = Nodes.getTerritoryFromId(state.territoryId) ?: return
        try {
            for (coord in territory.chunks) {
                val tc = Nodes.getTerritoryChunkFromCoord(coord) ?: continue
                FlagWar.chunkToAttacker[tc.coord]?.cancel()
                tc.attacker = null
                if (tc.occupier === state.attacker) {
                    tc.occupier = null
                    FlagWar.occupiedChunks.remove(tc.coord)
                }
            }
            FlagWar.needsSave = true
            Nodes.renderMinimaps()
        } catch (e: Exception) {
            Nodes.logger?.warning("[Raid] clearRaidCombat: ${e.message}")
        }
    }

    private fun ensureTask() {
        if (tickTask != null) return
        val plugin = Nodes.plugin ?: return
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        for (state in byTerritory.values.toList()) {
            when (state.phase) {
                RaidPhase.PREP -> {
                    val left = state.prepEndsAt - now
                    for (th in prepReminderThresholds(Config.raidPrepMs)) {
                        if (left <= th && state.firedReminders.add(th)) {
                            Message.broadcast(
                                "${ChatColor.GOLD}[Raid] ${formatLeft(th)} until raid LIVE: " +
                                    "${state.attacker.name} vs ${state.defender.name} " +
                                    "(territory ${state.territoryId})",
                            )
                        }
                    }
                    if (now >= state.prepEndsAt) {
                        state.phase = RaidPhase.LIVE
                        state.liveEndsAt = now + Config.raidLiveMs
                        Message.broadcast(
                            "${ChatColor.DARK_RED}${ChatColor.BOLD}[Raid] LIVE! " +
                                "${state.attacker.name} may flag territory ${state.territoryId} " +
                                "(${formatMinutes(Config.raidLiveMs)}).",
                        )
                        DiscordRelay.raid(
                            "**[Raid]** LIVE: ${state.attacker.name} vs ${state.defender.name} " +
                                "on territory `${state.territoryId.toInt()}` (${formatMinutes(Config.raidLiveMs)})",
                        )
                    }
                }
                RaidPhase.LIVE -> {
                    if (now >= state.liveEndsAt) {
                        byTerritory.remove(state.territoryId.toInt())
                        clearRaidCombat(state)
                        awardAndFinish(state, defenderWins = true, reason = "Defenders held the node")
                    }
                }
                RaidPhase.ENDED -> {
                    byTerritory.remove(state.territoryId.toInt())
                }
            }
        }
        if (byTerritory.isEmpty()) {
            tickTask?.cancel()
            tickTask = null
        }
    }

    private fun formatLeft(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return if (m > 0) "${m}m ${r}s" else "${r}s"
    }

    private fun formatMinutes(ms: Long): String {
        val m = ms / 60000.0
        return if (m == m.toLong().toDouble()) "${m.toLong()}m" else "${"%.1f".format(m)}m"
    }
}
