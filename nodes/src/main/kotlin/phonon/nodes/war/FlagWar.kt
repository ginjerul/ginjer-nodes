/**
 * Flag war implementation conceptually based on
 * Towny flag war:
 * https://github.com/TownyAdvanced/Towny/tree/master/src/com/palmergames/bukkit/towny/war/flagwar
 *
 * War handled by placing a "flag" block onto a chunk to start
 * a "conquer" timer. When timer ends, the chunk is claimed
 * by the attacker's town.
 *
 * When a territory's core is taken, the territory is converted
 * to "occupied" status by the attacking town.
 *
 * Flag block object:
 *     i       <- torch for light (so players can see it)
 *    [ ]      <- wool beacon block (destroy to cancel)
 *     |       <- initial item placed to start claim
 *
 * ----
 * i dont even fully understand save architecture anymore after 6 months
 * hope this doesnt break during war time :^)
 * t. xeth
 */

package phonon.nodes.war

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import phonon.nms.blockedit.FastBlockEditSession
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.ErrorAlreadyCaptured
import phonon.nodes.constants.ErrorAlreadyUnderAttack
import phonon.nodes.constants.ErrorAttackCustomCancel
import phonon.nodes.constants.ErrorChunkNotEdge
import phonon.nodes.constants.ErrorFlagTooHigh
import phonon.nodes.constants.ErrorNotBorderTerritory
import phonon.nodes.constants.ErrorNotEnemy
import phonon.nodes.constants.ErrorSkyBlocked
import phonon.nodes.constants.ErrorTooManyAttacks
import phonon.nodes.constants.ErrorTownBlacklisted
import phonon.nodes.constants.ErrorTownNotWhitelisted
import phonon.nodes.event.WarAttackCancelEvent
import phonon.nodes.event.WarAttackFinishEvent
import phonon.nodes.event.WarAttackStartEvent
import phonon.nodes.fortify.Fortifications
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.Town
import phonon.nodes.supply.SupplyLines
import phonon.nodes.war.Raid
import java.nio.file.Files
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val SKY_BEACON_FRAME_BLOCK = Material.SEA_LANTERN
private val SKY_BEACON_BLOCK = Material.BLACK_WOOL

private val SKY_BEACON_MATERIALS: EnumSet<Material> = EnumSet.of(
    SKY_BEACON_FRAME_BLOCK,
    SKY_BEACON_BLOCK,
)

public object FlagWar {

    internal var enabled: Boolean = false
    internal var canAnnexTerritories: Boolean = false
    internal var canOnlyAttackBorders: Boolean = false
    internal var destructionEnabled: Boolean = false
    public var saveTaskPeriod: Long = 20

    internal var plugin: JavaPlugin? = null
    internal val flagMaterials: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    internal var skyBeaconSize: Int = 6

    internal val attackers: HashMap<UUID, ArrayList<Attack>> = hashMapOf()
    internal val chunkToAttacker: ConcurrentHashMap<Coord, Attack> = ConcurrentHashMap()
    internal val blockToAttacker: HashMap<Block, Attack> = hashMapOf()
    internal val occupiedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet()

    internal val ATTACK_TICK: Long = 20
    internal var needsSave: Boolean = false
    internal var saveTask: BukkitTask? = null
    internal var attackTickTask: BukkitTask? = null

    public fun initialize(flagMaterials: EnumSet<Material>) {
        FlagWar.flagMaterials.addAll(flagMaterials)
        FlagWar.skyBeaconSize = Math.max(2, Math.min(16, Config.flagBeaconSize))
    }

    public fun printInfo(sender: CommandSender, detailed: Boolean = false) {
        val status = if (Nodes.war.enabled == true) "enabled" else "${ChatColor.GRAY}disabled"
        Message.print(sender, "${ChatColor.BOLD}Nodes war status: $status")
        if (Nodes.war.enabled) {
            Message.print(sender, "- Can Annex Territories${ChatColor.WHITE}: ${Nodes.war.canAnnexTerritories}")
            Message.print(sender, "- Can Only Attack Borders${ChatColor.WHITE}: ${Nodes.war.canOnlyAttackBorders}")
            Message.print(sender, "- Destruction Enabled${ChatColor.WHITE}: ${Nodes.war.destructionEnabled}")
            if (detailed) {
                Message.print(sender, "- Using Towns Whitelist${ChatColor.WHITE}: ${Config.warUseWhitelist}")
                Message.print(sender, "- Can leave town${ChatColor.WHITE}: ${Config.canLeaveTownDuringWar}")
                Message.print(sender, "- Can create town${ChatColor.WHITE}: ${Config.canCreateTownDuringWar}")
                Message.print(sender, "- Can destroy town${ChatColor.WHITE}: ${Config.canDestroyTownDuringWar}")
                Message.print(sender, "- Annex disabled${ChatColor.WHITE}: ${Config.annexDisabled}")
            }
        }
    }

    internal object SaveLoop : Runnable {
        public override fun run() {
            if (FlagWar.needsSave) {
                FlagWar.needsSave = false
                WarSerializer.save(true)
            }
        }
    }

    internal fun load() {
        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()

        if (Files.exists(Config.pathWar)) {
            WarDeserializer.fromJson(Config.pathWar)
        }

        if (FlagWar.enabled) {
            if (FlagWar.canOnlyAttackBorders) {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishing enabled")
                phonon.nodes.util.DiscordRelay.war("**[War]** Nodes border skirmish enabled ")
            } else {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
                phonon.nodes.util.DiscordRelay.war(
                    "**[War]** Nodes war **enabled** " +
                        "(annex=$canAnnexTerritories, bordersOnly=$canOnlyAttackBorders, destruction=$destructionEnabled)",
                )
            }
            FlagWar.ensureAttackTickTask()
        }
    }

    internal fun loadOccupiedChunk(townName: String, coord: Coord) {
        val town = Nodes.towns[townName] ?: return
        val terrChunk = Nodes.getTerritoryChunkFromCoord(coord) ?: return
        terrChunk.occupier = town
        FlagWar.occupiedChunks.add(terrChunk.coord)
    }

    internal fun loadAttack(
        attacker: UUID,
        coord: Coord,
        flagBase: Block,
        progress: Long,
    ) {
        val skyBeaconColorBlocks: MutableList<Block> = mutableListOf()
        val skyBeaconWireframeBlocks: MutableList<Block> = mutableListOf()

        if (Config.flagBeaconEnabled) {
            FlagWar.createAttackBeacon(
                skyBeaconColorBlocks,
                skyBeaconWireframeBlocks,
                flagBase.world,
                coord,
                flagBase.y,
                true,
                true,
                Config.flagBeaconUpdateLighting,
            )
        }

        val attackerResident = Nodes.getResidentFromUUID(attacker) ?: return
        val attackingTown = attackerResident.town ?: return
        val chunk = Nodes.getTerritoryChunkFromCoord(coord) ?: return

        FlagWar.createAttack(
            attacker,
            attackingTown,
            chunk,
            flagBase,
            progress,
            skyBeaconColorBlocks,
            skyBeaconWireframeBlocks,
        )
    }

    internal fun cleanup() {
        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = null
        FlagWar.attackTickTask?.cancel()
        FlagWar.attackTickTask = null

        for (attack in FlagWar.chunkToAttacker.values) {
            attack.progressBar.removeAll()
        }

        if (Nodes.initialized && FlagWar.enabled) {
            WarSerializer.save(false)
        }

        FlagWar.enabled = false

        for ((coord, attack) in FlagWar.chunkToAttacker) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.cancelled = true
            FlagWar.cancelAttack(attack)
        }

        for (coord in FlagWar.occupiedChunks) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()
    }

    internal fun ensureAttackTickTask() {
        val plugin = Nodes.plugin ?: return
        if (attackTickTask != null && attackTickTask?.isCancelled == false) return

        attackTickTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            Runnable {
                val attacks = chunkToAttacker.values.toList()
                for (attack in attacks) {
                    if (!attack.cancelled) {
                        attackTick(attack)
                    }
                }
            },
            ATTACK_TICK,
            ATTACK_TICK,
        )
    }

    internal fun enable(canAnnexTerritories: Boolean, canOnlyAttackBorders: Boolean, destructionEnabled: Boolean) {
        FlagWar.enabled = true
        FlagWar.canAnnexTerritories = canAnnexTerritories
        FlagWar.canOnlyAttackBorders = canOnlyAttackBorders
        FlagWar.destructionEnabled = destructionEnabled

        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            Nodes.plugin!!,
            FlagWar.SaveLoop,
            FlagWar.saveTaskPeriod,
            FlagWar.saveTaskPeriod,
        )
        FlagWar.ensureAttackTickTask()
    }

    internal fun disable() {
        FlagWar.enabled = false
        FlagWar.canAnnexTerritories = false

        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = null
        FlagWar.attackTickTask?.cancel()
        FlagWar.attackTickTask = null

        for ((coord, attack) in FlagWar.chunkToAttacker) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.cancelled = true
            FlagWar.cancelAttack(attack)
        }

        for (coord in FlagWar.occupiedChunks) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()
        phonon.nodes.util.DiscordRelay.war("**[War]** Nodes war **disabled**")

        WarSerializer.save(true)
    }

    private fun checkFlagPlacementAndLimits(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: Block,
        world: World,
        flagBaseX: Int,
        flagBaseY: Int,
        flagBaseZ: Int,
        skipEdgeCheck: Boolean = false,
    ): Result<Unit> {
        if (chunk.attacker !== null) {
            return Result.failure(ErrorAlreadyUnderAttack)
        }
        if (!skipEdgeCheck && FlagWar.chunkIsAtEdge(chunk, attackingTown) == false) {
            return Result.failure(ErrorChunkNotEdge)
        }
        if (flagBaseY >= 253) {
            return Result.failure(ErrorFlagTooHigh)
        }
        for (y in flagBaseY + 1..255) {
            if (!world.getBlockAt(flagBaseX, y, flagBaseZ).isEmpty()) {
                return Result.failure(ErrorSkyBlocked)
            }
        }
        var currentAttacks = FlagWar.attackers[attacker]
        if (currentAttacks == null) {
            currentAttacks = ArrayList(Config.maxPlayerChunkAttacks)
            FlagWar.attackers[attacker] = currentAttacks
        } else if (currentAttacks.size >= Config.maxPlayerChunkAttacks) {
            return Result.failure(ErrorTooManyAttacks)
        }
        if (Config.maxConcurrentAttacks > 0 && FlagWar.chunkToAttacker.size >= Config.maxConcurrentAttacks) {
            return Result.failure(ErrorTooManyAttacks)
        }
        if (Config.maxAttacksPerTown > 0) {
            val townCount = FlagWar.chunkToAttacker.values.count { it.town.uuid == attackingTown.uuid }
            if (townCount >= Config.maxAttacksPerTown) {
                return Result.failure(ErrorTooManyAttacks)
            }
        }
        return Result.success(Unit)
    }

    internal fun beginAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: Block,
    ): Result<Attack> {
        val world = flagBase.world
        val flagBaseX = flagBase.x
        val flagBaseY = flagBase.y
        val flagBaseZ = flagBase.z
        val territory = chunk.territory
        val territoryTown = territory.town

        if (territoryTown === null) {
            if (!Config.allowUnownedAttack) {
                return Result.failure(ErrorNotEnemy)
            }
            val place = checkFlagPlacementAndLimits(
                attacker,
                attackingTown,
                chunk,
                flagBase,
                world,
                flagBaseX,
                flagBaseY,
                flagBaseZ,
            )
            if (place.isFailure) {
                return Result.failure(place.exceptionOrNull()!!)
            }
            val event = WarAttackStartEvent(attacker, attackingTown, territory, flagBase)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) {
                return Result.failure(ErrorAttackCustomCancel)
            }
            val attack = createAttack(attacker, attackingTown, chunk, flagBase, 0L)
            FlagWar.needsSave = true
            return Result.success(attack)
        }

        val town: Town = territoryTown

        if (Config.warUseBlacklist && Config.warBlacklist.contains(town.uuid)) {
            return Result.failure(ErrorTownBlacklisted)
        }

        if (Config.warUseWhitelist) {
            if (!Config.warWhitelist.contains(town.uuid) ||
                (Config.onlyWhitelistCanClaim && !Config.warWhitelist.contains(attackingTown.uuid))
            ) {
                return Result.failure(ErrorTownNotWhitelisted)
            }
        }

        if (chunkAlreadyCaptured(chunk, territory, attackingTown)) {
            return Result.failure(ErrorAlreadyCaptured)
        }

        val isWarzoneTarget = Warzone.isAttackableWarzoneTerritory(town)
        val liveRaid = Raid.getLive(territory)

        var raidRetake = false
        if (liveRaid != null) {
            if (!Raid.canRaidAttack(attackingTown, territory)) {
                if (!Raid.canRaidRetake(attackingTown, chunk)) {
                    return Result.failure(ErrorNotEnemy)
                }
                raidRetake = true
            }
        } else {
            if (isWarzoneTarget) {
                if (!Warzone.canAttackWarzone(attackingTown, town)) {
                    return Result.failure(ErrorNotEnemy)
                }
            }
            val enemyOk = isWarzoneTarget || chunkIsEnemy(chunk, territory, attackingTown)
            if (!enemyOk) {
                return Result.failure(ErrorNotEnemy)
            }
        }

        if (liveRaid == null &&
            FlagWar.canOnlyAttackBorders &&
            !FlagWar.isBorderTerritory(territory)
        ) {
            return Result.failure(ErrorNotBorderTerritory)
        }

        val place = checkFlagPlacementAndLimits(
            attacker,
            attackingTown,
            chunk,
            flagBase,
            world,
            flagBaseX,
            flagBaseY,
            flagBaseZ,
            skipEdgeCheck = raidRetake,
        )
        if (place.isFailure) {
            return Result.failure(place.exceptionOrNull()!!)
        }

        val event = WarAttackStartEvent(attacker, attackingTown, territory, flagBase)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) {
            return Result.failure(ErrorAttackCustomCancel)
        }

        val attack = createAttack(attacker, attackingTown, chunk, flagBase, 0L)
        FlagWar.needsSave = true
        return Result.success(attack)
    }

    internal fun createAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: Block,
        progress: Long,
        skyBeaconColorBlocksInput: MutableList<Block>? = null,
        skyBeaconWireframeBlocksInput: MutableList<Block>? = null,
    ): Attack {
        val world = flagBase.world
        val flagBaseX = flagBase.x
        val flagBaseY = flagBase.y
        val flagBaseZ = flagBase.z
        val territory = chunk.territory

        val flagBlock = world.getBlockAt(flagBaseX, flagBaseY + 1, flagBaseZ)
        val flagTorch = world.getBlockAt(flagBaseX, flagBaseY + 2, flagBaseZ)

        var attackTime = Config.chunkAttackTime.toDouble()
        if (territory.bordersWilderness) {
            attackTime *= Config.chunkAttackFromWastelandMultiplier
        }
        val terrTown = territory.town
        if (terrTown !== null) {
            if (territory.id == terrTown.home) {
                attackTime *= Config.chunkAttackHomeMultiplier
            }
            if (terrTown.uuid == attackingTown.uuid || terrTown.allies.contains(attackingTown)) {
                attackTime *= territory.defenderTimeMultiplier
            } else {
                attackTime *= territory.attackerTimeMultiplier
                if (!SupplyLines.isFriendly(terrTown, attackingTown)) {
                    // supply-cut speedup only for land the owner still holds: an occupied
                    // territory counts as cut off, but taking it shouldn't get faster for that
                    if (territory.occupier == null && SupplyLines.isCutOff(terrTown, territory.id)) {
                        attackTime *= SupplyLines.captureTimeMultiplier()
                    }
                    attackTime *= Fortifications.captureMultiplier(territory)
                }
            }
        }

        if (Config.playersInChunkMultiplier) {
            val bukkitChunk = flagBase.chunk
            var playersInChunk = 0
            for (p in flagBase.world.players) {
                if (p.location.chunk.x == bukkitChunk.x && p.location.chunk.z == bukkitChunk.z) {
                    playersInChunk++
                }
            }
            if (playersInChunk > 1) {
                val factor = (1.0 - (playersInChunk - 1) * Config.playersInChunkMultiplierPerPlayer)
                    .coerceAtLeast(Config.playersInChunkMultiplierMin)
                attackTime *= factor
            }
        }

        val defenderName = terrTown?.name ?: "Wilderness"
        val initialSeconds = (attackTime.toLong() / 20L).coerceAtLeast(0L)
        val initMin = initialSeconds / 60L
        val initSec = initialSeconds % 60L
        val initTime = "$initMin:${initSec.toString().padStart(2, '0')}"

        val progressBar = Bukkit.getServer().createBossBar(
            "Attacking $defenderName at ($flagBaseX, $flagBaseY, $flagBaseZ) [$initTime]",
            BarColor.YELLOW,
            BarStyle.SOLID,
        )

        val skyBeaconColorBlocks: MutableList<Block> = if (skyBeaconColorBlocksInput === null) {
            mutableListOf()
        } else {
            skyBeaconColorBlocksInput
        }
        val skyBeaconWireframeBlocks: MutableList<Block> = if (skyBeaconWireframeBlocksInput === null) {
            mutableListOf()
        } else {
            skyBeaconWireframeBlocksInput
        }

        if (Config.flagBeaconEnabled &&
            (skyBeaconColorBlocksInput === null || skyBeaconWireframeBlocksInput === null)
        ) {
            FlagWar.createAttackBeacon(
                skyBeaconColorBlocks,
                skyBeaconWireframeBlocks,
                world,
                chunk.coord,
                flagBaseY,
                true,
                true,
                Config.flagBeaconUpdateLighting,
            )
        }

        if (!Config.flagMaterials.contains(flagBase.type)) {
            flagBase.setType(Config.flagMaterialDefault)
        }

        if (Config.staticFlagblock) {
            flagBlock.setType(Config.staticFlagblockMaterial)
        } else {
            flagBlock.setType(Material.DEEPSLATE)
        }
        flagTorch.setType(Material.TORCH)

        val attack = Attack(
            attacker,
            attackingTown,
            chunk.coord,
            flagBase,
            flagBlock,
            flagTorch,
            skyBeaconColorBlocks.toList(),
            skyBeaconWireframeBlocks.toList(),
            progressBar,
            attackTime.toLong(),
            progress,
        )

        chunk.attacker = attackingTown

        Bukkit.getPlayer(attacker)?.let { player ->
            attack.progressBar.addPlayer(player)
        }

        var currentAttacks = FlagWar.attackers[attacker]
        if (currentAttacks == null) {
            currentAttacks = ArrayList(Config.maxPlayerChunkAttacks)
            FlagWar.attackers[attacker] = currentAttacks
        }
        currentAttacks.add(attack)

        FlagWar.chunkToAttacker[chunk.coord] = attack
        FlagWar.blockToAttacker[flagBlock] = attack

        val defenderTown = territory.town
        if (defenderTown != null) {
            val attackerName = Bukkit.getOfflinePlayer(attacker).name ?: "Someone"
            val alert =
                "${ChatColor.DARK_RED}${ChatColor.BOLD}$attackerName${ChatColor.RED} is attacking your land in ${ChatColor.YELLOW}${territory.id}${ChatColor.RED} at ${ChatColor.WHITE}($flagBaseX, $flagBaseY, $flagBaseZ)"

            val plugin = Nodes.plugin!!
            var ticksLeft = 12
            Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable {
                    if (ticksLeft <= 0) return@Runnable
                    ticksLeft--
                    for (resident in defenderTown.residents) {
                        val p = resident.player() ?: continue
                        if (!p.isOnline) continue
                        try {
                            p.sendActionBar(
                                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                    .legacySection()
                                    .deserialize(alert),
                            )
                        } catch (_: Exception) {
                            @Suppress("DEPRECATION")
                            p.spigot().sendMessage(
                                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                                *net.md_5.bungee.api.chat.TextComponent.fromLegacyText(alert),
                            )
                        }
                    }
                },
                0L,
                20L,
            ).also { task ->
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { task.cancel() }, 20L * 8)
            }
        }
        val attackerName = Bukkit.getOfflinePlayer(attacker).name ?: "Someone"
        val defName = territory.town?.name ?: "Wilderness"
        phonon.nodes.util.DiscordRelay.flagWar(
            "**[Flag]** $attackerName (${attackingTown.name}) started a capture on **$defName** " +
                "node `${territory.id}` at ($flagBaseX, $flagBaseY, $flagBaseZ)",
        )

        FlagWar.ensureAttackTickTask()
        return attack
    }

    internal fun isBorderTerritory(territory: Territory): Boolean {
        val territoryTown = territory.town
        if (territoryTown !== null && territoryTown.home == territory.id) {
            return false
        }
        if (territory.bordersWilderness) {
            return true
        }
        for (neighborTerritoryId in territory.neighbors) {
            val neighborTerritory = Nodes.territories[neighborTerritoryId]
            if (neighborTerritory !== null && neighborTerritory.town !== territoryTown) {
                return true
            }
        }
        return false
    }

    internal fun chunkAlreadyCaptured(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        val territoryOccupier = territory.occupier
        val chunkOccupier = chunk.occupier

        if (territoryOccupier === attackingTown || attackingTown.allies.contains(territoryOccupier)) {
            if (!attackingTown.enemies.contains(chunkOccupier)) {
                return true
            }
        }
        if (chunkOccupier !== null) {
            if (chunkOccupier === attackingTown || attackingTown.allies.contains(chunkOccupier)) {
                return true
            }
        }
        return false
    }

    internal fun chunkIsEnemy(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        if (attackingTown.enemies.contains(territory.town)) {
            return true
        }

        val attackingNation = attackingTown.nation
        val territoryNation = territory.town?.nation

        if ((territory.town === attackingTown) ||
            (attackingNation !== null && attackingNation === territoryNation) ||
            (attackingTown.allies.contains(territory.town))
        ) {
            if (attackingTown.enemies.contains(territory.occupier)) {
                return true
            }
            if (attackingTown.enemies.contains(chunk.occupier)) {
                return true
            }
        }

        val occupier = territory.occupier
        val occupierNation = occupier?.nation
        if (occupier === attackingTown ||
            (attackingNation !== null && attackingNation === occupierNation) ||
            attackingTown.allies.contains(occupier)
        ) {
            if (attackingTown.enemies.contains(chunk.occupier)) {
                return true
            }
        }

        return false
    }

    internal fun chunkIsAtEdge(chunk: TerritoryChunk, attackingTown: Town): Boolean {
        val coord = chunk.coord
        val chunkNorth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z - 1))
        val chunkSouth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z + 1))
        val chunkWest = Nodes.getTerritoryChunkFromCoord(Coord(coord.x - 1, coord.z))
        val chunkEast = Nodes.getTerritoryChunkFromCoord(Coord(coord.x + 1, coord.z))

        return canAttackFromNeighborChunk(chunkNorth, attackingTown) ||
            canAttackFromNeighborChunk(chunkSouth, attackingTown) ||
            canAttackFromNeighborChunk(chunkWest, attackingTown) ||
            canAttackFromNeighborChunk(chunkEast, attackingTown)
    }

    internal fun canAttackFromNeighborChunk(neighborChunk: TerritoryChunk?, attacker: Town): Boolean {
        if (neighborChunk === null) {
            return true
        }
        if (neighborChunk.territory.town === null) {
            if (FlagWar.canOnlyAttackBorders && Config.allowBorderSkirmishFromWilderness) {
                return true
            }
            return true
        }

        val attackerNation = attacker.nation
        val neighborTerritory = neighborChunk.territory
        val neighborTown = neighborTerritory.town
        val neighborTerritoryOccupier = neighborTerritory.occupier
        val neighborChunkOccupier = neighborChunk.occupier

        if (neighborTown === null) {
            if (FlagWar.canOnlyAttackBorders && !Config.allowBorderSkirmishFromWilderness) {
                return false
            }
            return true
        }

        if (neighborTown === attacker) {
            if (neighborTerritoryOccupier === null) {
                return true
            } else if (attacker.allies.contains(neighborTerritoryOccupier)) {
                return true
            }
        }

        if (neighborTerritoryOccupier === attacker || attacker.allies.contains(neighborTerritoryOccupier)) {
            return true
        }

        if (neighborChunkOccupier === attacker || attacker.allies.contains(neighborChunkOccupier) == true) {
            return true
        }

        if (attackerNation !== null) {
            val neighborNation = neighborTown.nation
            val neighborTerritoryOccupierNation = neighborTerritoryOccupier?.nation
            val neighborChunkOccupierNation = neighborChunk.occupier?.nation

            if (neighborTown === attacker && neighborNation === neighborTerritoryOccupierNation) {
                return true
            }
            if (attackerNation === neighborNation) {
                if (neighborTerritoryOccupier === null) {
                    return true
                } else if (attacker.allies.contains(neighborTerritoryOccupier)) {
                    return true
                }
            }
            if (attackerNation === neighborTerritoryOccupierNation) {
                return true
            }
            if (attackerNation === neighborChunkOccupierNation) {
                return true
            }
        }

        return false
    }

    internal fun createAttackBeacon(
        skyBeaconColorBlocks: MutableList<Block>,
        skyBeaconWireframeBlocks: MutableList<Block>,
        world: World,
        coord: Coord,
        flagBaseY: Int,
        createFrame: Boolean,
        createColor: Boolean,
        updateLighting: Boolean,
    ) {
        val edit = FastBlockEditSession(world)
        val size = FlagWar.skyBeaconSize
        val startPositionInChunk: Int = (16 - size) / 2
        val x0: Int = coord.x * 16 + startPositionInChunk
        val z0: Int = coord.z * 16 + startPositionInChunk
        val y0: Int = Math.max(flagBaseY + Config.flagBeaconSkyLevel, Config.flagBeaconMinSkyLevel)
        val xEnd: Int = x0 + size - 1
        val zEnd: Int = z0 + size - 1
        val yEnd: Int = Math.min(255, y0 + size)

        for (y in y0..yEnd) {
            for (x in x0..xEnd) {
                for (z in z0..zEnd) {
                    val block = world.getBlockAt(x, y, z)
                    val mat = block.getType()
                    if (mat == Material.AIR || SKY_BEACON_MATERIALS.contains(mat)) {
                        if (((y == y0 || y == yEnd) && (x == x0 || x == xEnd || z == z0 || z == zEnd)) ||
                            ((x == x0 || x == xEnd) && (z == z0 || z == zEnd))
                        ) {
                            skyBeaconWireframeBlocks.add(block)
                            if (createFrame) {
                                edit.setBlock(x, y, z, SKY_BEACON_FRAME_BLOCK)
                            }
                        } else {
                            skyBeaconColorBlocks.add(block)
                            if (createColor) {
                                edit.setBlock(x, y, z, SKY_BEACON_BLOCK)
                            }
                        }
                    }
                }
            }
        }

        if (createFrame || createColor) {
            edit.execute(updateLighting)
        }
    }

    internal fun cancelAttack(attack: Attack) {
        attack.cancelled = true

        val chunk = Nodes.getTerritoryChunkFromCoord(attack.coord)
        chunk?.attacker = null

        attack.progressBar.removeAll()

        attack.flagTorch.setType(Material.AIR)
        attack.flagBlock.setType(Material.AIR)
        attack.flagBase.setType(Material.AIR)

        for (block in attack.skyBeaconWireframeBlocks) {
            block.setType(Material.AIR)
        }
        for (block in attack.skyBeaconColorBlocks) {
            block.setType(Material.AIR)
        }

        try {
            attack.armorstand?.remove()
        } catch (e: Exception) {
            Nodes.logger?.warning("Failed to remove attack hologram: ${e.message}")
        }

        FlagWar.attackers[attack.attacker]?.remove(attack)
        FlagWar.chunkToAttacker.remove(attack.coord)
        FlagWar.blockToAttacker.remove(attack.flagBlock)

        FlagWar.needsSave = true

        if (chunk !== null) {
            val event = WarAttackCancelEvent(
                attack.attacker,
                attack.town,
                chunk.territory,
                attack.flagBase,
            )
            Bukkit.getPluginManager().callEvent(event)
        }

        if (FlagWar.chunkToAttacker.isEmpty()) {
            FlagWar.attackTickTask?.cancel()
            FlagWar.attackTickTask = null
        }
        val name = Bukkit.getOfflinePlayer(attack.attacker).name ?: "Someone"
        phonon.nodes.util.DiscordRelay.flagWar(
            "**[Flag]** Capture by $name at (${attack.flagBase.x}, ${attack.flagBase.y}, ${attack.flagBase.z}) was **defended/cancelled**",
        )
    }

    /** Cancels capture attacks between [town] and towns of [nation]; used when [town] surrenders into [nation]. */
    internal fun cancelAttacksForSurrender(town: Town, nation: Nation) {
        for (attack in FlagWar.chunkToAttacker.values.toList()) {
            if (attack.cancelled) continue
            val defender = Nodes.getTerritoryChunkFromCoord(attack.coord)?.territory?.town ?: continue
            val attackingTown = attack.town
            val involved = (attackingTown === town && defender.nation === nation) ||
                (defender === town && attackingTown.nation === nation)
            if (involved) {
                FlagWar.cancelAttack(attack)
            }
        }
    }

    internal fun finishAttack(attack: Attack) {
        attack.progressBar.removeAll()

        attack.flagTorch.setType(Material.AIR)
        attack.flagBlock.setType(Material.AIR)
        attack.flagBase.setType(Material.AIR)

        for (block in attack.skyBeaconWireframeBlocks) {
            block.setType(Material.AIR)
        }
        for (block in attack.skyBeaconColorBlocks) {
            block.setType(Material.AIR)
        }

        try {
            attack.armorstand?.remove()
        } catch (e: Exception) {
            Nodes.logger?.warning("Failed to remove attack hologram: ${e.message}")
        }

        FlagWar.attackers[attack.attacker]?.remove(attack)
        FlagWar.chunkToAttacker.remove(attack.coord)
        FlagWar.blockToAttacker.remove(attack.flagBlock)

        FlagWar.needsSave = true

        val chunk = Nodes.getTerritoryChunkFromCoord(attack.coord)
        if (chunk == null) {
            Nodes.logger?.severe("finishAttack(): TerritoryChunk at ${attack.coord} is null")
            return
        }

        val event = WarAttackFinishEvent(
            attack.attacker,
            attack.town,
            chunk.territory,
            attack.flagBase,
        )
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) {
            chunk.attacker = null
            return
        }

        if (chunk.coord == chunk.territory.core) {
            val territory = chunk.territory
            val territoryTown = territory.town
            val attacker = Nodes.getResidentFromUUID(attack.attacker)
            val attackerTown = attack.town
            val attackerNation = attackerTown.nation

            for (coord in territory.chunks) {
                val territoryChunk = Nodes.getTerritoryChunkFromCoord(coord)
                if (territoryChunk != null) {
                    FlagWar.chunkToAttacker[territoryChunk.coord]?.cancel()
                    territoryChunk.attacker = null
                    territoryChunk.occupier = null
                    FlagWar.occupiedChunks.remove(territoryChunk.coord)
                }
            }

            // Raid: attacker wins the raid, defender keeps the node
            val liveRaid = Raid.getLive(territory)
            if (liveRaid != null && attackerTown === liveRaid.attacker) {
                if (territory.occupier != null) {
                    Nodes.releaseTerritory(territory)
                }
                Message.broadcast(
                    "${ChatColor.DARK_RED}[Raid] ${attackerTown.name} captured the raid node " +
                        "(id=${territory.id}) — territory remains ${liveRaid.defender.name}'s!",
                )
                Raid.onCoreCaptured(attackerTown, territory)
                Nodes.renderMinimaps()
                FlagWar.needsSave = true
            } else if (territoryTown === attackerTown ||
                (attackerNation !== null && attackerNation === territoryTown?.nation) ||
                attackerTown.allies.contains(territoryTown)
            ) {
                val occupier = territory.occupier
                Nodes.releaseTerritory(territory)
                Message.broadcast(
                    "${ChatColor.DARK_RED}[War] ${attacker?.name} liberated territory (id=${territory.id}) from ${occupier?.name}!",
                )
                phonon.nodes.util.DiscordRelay.flagWar(
                    "**[Flag]** ${attacker?.name ?: "Someone"} liberated territory `${territory.id}`",
                )
                Nodes.renderMinimaps()
                FlagWar.needsSave = true
            } else {
                val defenderTown = territoryTown
                val wasHome = defenderTown != null && territory.id == defenderTown.home

                Nodes.captureTerritory(attackerTown, territory)
                Message.broadcast(
                    "${ChatColor.DARK_RED}[War] ${attacker?.name} captured territory (id=${territory.id}) from ${defenderTown?.name}!",
                )
                phonon.nodes.util.DiscordRelay.flagWar(
                    "**[Flag]** ${attacker?.name} captured territory `${territory.id}` from ${defenderTown?.name}",
                )

                if (wasHome &&
                    defenderTown != null &&
                    FlagWar.canAnnexTerritories &&
                    !Config.annexDisabled
                ) {
                    Nodes.annexTerritory(attackerTown, territory)
                }
                Nodes.renderMinimaps()
                FlagWar.needsSave = true
            }
        } else {
            val town = chunk.territory.town
            val occupier = chunk.territory.occupier
            val attacker = Nodes.getResidentFromUUID(attack.attacker)

            chunk.attacker = null

            val liveRaid = Raid.getLive(chunk.territory)
            if (liveRaid != null &&
                Raid.isDefenderSide(attack.town, liveRaid) &&
                chunk.occupier === liveRaid.attacker
            ) {
                chunk.occupier = null
                FlagWar.occupiedChunks.remove(chunk.coord)
                FlagWar.needsSave = true
                Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${liveRaid.attacker.name}!")
                Nodes.renderMinimaps()
                if (FlagWar.chunkToAttacker.isEmpty()) {
                    FlagWar.attackTickTask?.cancel()
                    FlagWar.attackTickTask = null
                }
                return
            }

            if (town === attack.town) {
                if (occupier !== null) {
                    chunk.occupier = town
                    FlagWar.occupiedChunks.add(chunk.coord)
                    Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} liberated chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${occupier.name}!")
                    phonon.nodes.util.DiscordRelay.flagWar(
                        "**[Flag]** ${attacker?.name ?: "Someone"} liberated chunk (${chunk.coord.x}, ${chunk.coord.z})",
                    )
                } else {
                    val chunkOccupier = chunk.occupier
                    chunk.occupier = null
                    FlagWar.occupiedChunks.remove(chunk.coord)
                    if (chunkOccupier !== null) {
                        Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunkOccupier.name}!")
                    }
                }
            } else if (occupier === attack.town && chunk.occupier !== null) {
                val previousOccupier = chunk.occupier
                chunk.occupier = null
                FlagWar.occupiedChunks.remove(chunk.coord)
                Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${previousOccupier?.name}!")
            } else {
                chunk.occupier = attack.town
                FlagWar.occupiedChunks.add(chunk.coord)
                Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} captured chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${chunk.territory.town?.name}!")
            }
            phonon.nodes.util.DiscordRelay.flagWar(
                "**[Flag]** ${attacker?.name} captured chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${chunk.territory.town?.name}",
            )

            Nodes.renderMinimaps()
        }

        if (FlagWar.chunkToAttacker.isEmpty()) {
            FlagWar.attackTickTask?.cancel()
            FlagWar.attackTickTask = null
        }
    }

    internal fun shouldShowBossBar(player: Player, flagBlock: Block): Boolean {
        val max = Config.warBossBarMaxDistanceChunks
        if (max <= 0) return true
        if (player.world != flagBlock.world) return false
        val pc = player.location.chunk
        val fc = flagBlock.chunk
        val dx = kotlin.math.abs(pc.x - fc.x)
        val dz = kotlin.math.abs(pc.z - fc.z)
        return dx <= max && dz <= max
    }

    internal fun attackTick(attack: Attack) {
        if (attack.cancelled) return

        val progress = attack.progress + FlagWar.ATTACK_TICK

        if (progress >= attack.attackTime) {
            attack.cancelled = true
            Bukkit.getScheduler().runTask(
                Nodes.plugin!!,
                Runnable {
                    FlagWar.finishAttack(attack)
                },
            )
        } else {
            attack.progress = progress

            val progressNormalized: Double = progress.toDouble() / attack.attackTime.toDouble()
            attack.progressBar.setProgress(progressNormalized)

            val remainingTicks = (attack.attackTime - attack.progress).coerceAtLeast(0L)
            val remainingSec = remainingTicks / 20L
            val m = remainingSec / 60L
            val s = remainingSec % 60L
            val timeStr = "$m:${s.toString().padStart(2, '0')}"
            val x = attack.flagBase.x
            val y = attack.flagBase.y
            val z = attack.flagBase.z
            val currentTitle = attack.progressBar.title
            val baseTitle = if (currentTitle.contains("[")) {
                currentTitle.substringBeforeLast("[").trimEnd()
            } else {
                "Attacking at ($x, $y, $z)"
            }
            attack.progressBar.setTitle("$baseTitle [$timeStr]")

            val stand = attack.armorstand
            if (stand != null && Config.flagHologramsEnabled) {
                if (stand.isValid()) {
                    try {
                        stand.sendPackets()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    Bukkit.getScheduler().runTask(
                        Nodes.plugin!!,
                        Runnable { stand.respawn() },
                    )
                }
            }
        }
    }

    public fun sendWarProgressBarToPlayer(player: Player) {
        val uuid = player.uniqueId
        val currentAttacks = FlagWar.attackers[uuid] ?: return
        for (attack in currentAttacks) {
            attack.progressBar.addPlayer(player)
        }
    }
}
