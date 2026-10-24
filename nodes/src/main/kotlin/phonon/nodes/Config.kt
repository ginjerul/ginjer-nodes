/**
 * Config
 *
 * Contains global config state variables read in from
 * plugin config.yml file
 */

package phonon.nodes

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.EntityType
import phonon.nodes.fortify.FortLevel
import phonon.nodes.fortify.Fortifications
import phonon.nodes.objects.OreDeposit
import phonon.nodes.objects.TerritoryResources
import java.nio.file.Paths
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

public object Config {

    // ===================================
    // engine configs
    // ===================================
    // main plugin path for config and saves
    public var pathPlugin = "plugins/nodes"

    public var pathBackup = Paths.get("plugins/nodes/backup").normalize()

    public var pathWorld = Paths.get(pathPlugin, "world.json").normalize()
    public var pathTowns = Paths.get(pathPlugin, "towns.json").normalize()
    public var pathWar = Paths.get(pathPlugin, "war.json").normalize()
    public var pathTruce = Paths.get(pathPlugin, "truce.json").normalize()
    public var pathPorts = Paths.get(pathPlugin, "ports.json").normalize()
    public var pathLastBackupTime = Paths.get(pathPlugin, "lastBackupTime.txt").normalize()
    public var pathLastIncomeTime = Paths.get(pathPlugin, "lastIncomeTime.txt").normalize()

    // disable world when nodes world.json or towns.json fails due to errors
    public var disableWorldWhenLoadFails = true

    public var savePeriod: Long = 600L

    public var backupPeriod: Long = 3600000L // 1 hour

    // main tick period check for backup, income, town + resident cooldown counters
    public var mainPeriodicTick: Long = 1200L

    public var overMaxClaimsReminderPeriod: Long = 24000L

    public var nametagUpdatePeriod: Long = 80L
    public var nametagPipelineTicks: Int = 16

    public var dynmapCopyTowns: Boolean = false

    // NodesMap web map export (see nodesmap/ in the repo)
    public var mapExportEnabled: Boolean = true
    public var mapExportAlways: Boolean = false // export even if NodesMap isn't installed (external web server)
    public var mapExportPath: String = "plugins/NodesMap/web/data/nodes"
    public var mapExportStatePeriodTicks: Long = 100L
    public var mapExportLivePeriodTicks: Long = 40L
    public var mapExportSupplyLines: Boolean = true

    // ===================================
    // nametag configs
    // ===================================
    public var useNametags: Boolean = true

    // ===================================
    // resource configs
    // ===================================
    // territory do income enabled and income time
    public var incomeEnabled: Boolean = true
    public var incomePeriod: Long = 3600000L

    // enable income rate scaling based on percent of town claim power used
    // e.g. if 100 claim power is needed for all territories
    // and town players have total of 60 claim power
    // then income rate is scaled by,
    //     r = (sum of player claim power) / (sum of territory claim)
    //       = 60 / 100
    //       = 0.6
    public var incomeScaleByClaimPower: Boolean = true
    public var incomeScaleMin: Double = 0.1 // minimum income scaling factor
    public var incomeScaleMax: Double = 1.0 // maximum income scaling factor, >1.0 allowed

    // global resource node in all territories
    public var globalResources = TerritoryResources()

    public var oreBlocks = EnumSet.of(
        Material.STONE,
        Material.ANDESITE,
        Material.GRANITE,
        Material.DIORITE,
    )

    // Crops affected by occupied territory tax

    public var cropTypes = EnumSet.of(
        Material.BEETROOTS,
        Material.CACTUS,
        Material.CARROTS,
        Material.KELP,
        Material.COCOA,
        Material.MELON,
        Material.NETHER_WART,
        Material.POTATOES,
        Material.PUMPKIN,
        Material.SUGAR_CANE,
        Material.SWEET_BERRY_BUSH,
        Material.WHEAT,
    )

    // map block type -> alternative editor name
    public var cropAlternativeNames: EnumMap<Material, Material> = {
        val alternativeNames: EnumMap<Material, Material> = EnumMap<Material, Material>(Material::class.java)

        alternativeNames.put(Material.MELON_STEM, Material.MELON)
        alternativeNames.put(Material.PUMPKIN_STEM, Material.PUMPKIN)
        alternativeNames.put(Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES)

        alternativeNames
    }()

    // allow mining/harvesting/breeding in unowned territories
    public var allowOreInWilderness: Boolean = false
    public var allowCropsInWilderness: Boolean = false
    public var allowBreedingInWilderness: Boolean = false

    public var allowOreInCaptured: Boolean = true

    public var allowOreInNationTowns: Boolean = true

    // required sky light for crops and animal breeding (set = 0 to disable)
    public var cropsMinSkyLight: Int = 14
    public var breedingMinSkyLight: Int = 14

    // min/max y levels allowed for crop growth and animal breeding
    public var cropsMinYHeight: Int = 10
    public var cropsMaxYHeight: Int = 255
    public var breedingMinYHeight: Int = 10
    public var breedingMaxYHeight: Int = 255

    // ===================================
    // afk kick time
    // ===================================
    // time in milliseconds for afk time before player kicked
    // adjusts claims progress as penalty
    // 10 min = 600000 ms
    // 20 min = 1200000 ms
    public var afkKickTime: Long = 900000L

    // ===================================
    // permissions
    // ===================================
    // interact in area with NO TERRITORIES (build, destroy, etc...)
    public var canInteractInEmpty: Boolean = false

    // interact in territory without town (build, destroy, etc...)
    public var canInteractInUnclaimed: Boolean = true

    public var requireSheepNodeToShear: Boolean = true

    // ===================================
    // town cooldowns
    // ===================================
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    public var townCreateCooldown: Long = 172800000L

    public var townMoveHomeCooldown: Long = 172800000L

    // ===================================
    // town claim configs
    // ===================================
    // territory cost = base + scale * chunks
    public var territoryCostBase: Int = 10
    public var territoryCostScale: Double = 0.25

    // Claims given on town creation
    public var townInitialClaims: Int = 25

    // penalty scale factor when over initial claim allowed:
    // penalty = scale * (territory.cost - initialAllowed) if cost > allowed
    public var initialOverClaimsAmountScale: Int = 2

    public var townClaimsBase: Int = 20

    // max claim power per town (-1 for unlimited)
    public var townClaimsMax: Int = -1

    public var townPenaltyDecay: Int = 2

    public var playerClaimsInitial: Int = 0 // initial player claims on town join
    public var playerClaimsMax: Int = 20 // max player claims for town
    public var playerClaimsIncrease: Int = 1 // claims increase per tick period

    // time periods for town claims penalty decay and player power gain, milliseconds
    // 1 hour = 3600000 ms
    // 2 hour = 7200000 ms
    public var townClaimsPenaltyDecayPeriod: Long = 3600000L
    public var playerClaimsIncreasePeriod: Long = 3600000L

    // flag for enforcing over max claims
    // for now, this affects all systems (income, hidden ore, crops,
    // breeding, etc...)
    // TODO: make fine grained over claims penalty for each system
    public var overClaimsPenalty: Boolean = true

    // reduced resource rate when over max claim (runs Math.random() < rate)
    public var overClaimsMaxPenalty: Double = 0.5

    public var overClaimsAllowClaim: Boolean = false

    // annexation settings
    // only allow annexing during war time
    public var canOnlyAnnexDuringWar: Boolean = true

    // ===================================
    // town settings
    // ===================================
    // town spawn timer in seconds (converted to ticks by num * 20)
    public var townSpawnTime: Int = 10

    public val outpostTeleportCost: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)

    public var outpostTeleportCostString: String = ""

    // ===================================
    // nation settings
    // ===================================
    // allow spawning in nation towns
    public var allowNationTownSpawn: Boolean = false

    public val nationTownTeleportCost: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)

    public var nationTownTeleportCostString: String = ""

    public var allowTownFriendlyFire: Boolean = false

    public var allowNationFriendlyFire: Boolean = false

    // ===================================
    // alliance settings
    // ===================================
    // allow allied nations to attack each other
    public var allowAllyFriendlyFire: Boolean = false

    // ===================================
    // captured territory tax rates:
    // (taxation is theft)
    // ===================================
    // fraction of territory income that goes to occupier
    public var taxIncomeRate: Double = 0.2

    // probability that a hidden ore event resources go to occupier
    public var taxMineRate: Double = 0.2

    // probability that all of a crop's harvest items go to occupier
    public var taxFarmRate: Double = 0.2

    // probability that a new animal goes to occupier (as a spawn egg item)
    public var taxAnimalRate: Double = 0.2

    // ===================================
    // war configs
    // ===================================
    // Nodes internal explosion block damage restrictions
    public var restrictExplosions: Boolean = true
    public var onlyAllowExplosionsDuringWar: Boolean = true

    public var flagMaterialDefault: Material = Material.OAK_FENCE

    public var flagMaterials: EnumSet<Material> = EnumSet.of(
        Material.ACACIA_FENCE,
        Material.BIRCH_FENCE,
        Material.BAMBOO_FENCE,
        Material.CHERRY_FENCE,
        Material.DARK_OAK_FENCE,
        Material.JUNGLE_FENCE,
        Material.MANGROVE_FENCE,
        Material.OAK_FENCE,
        Material.SPRUCE_FENCE,
    )

    // disable building within this distance of flag (square range)
    public var flagNoBuildDistance: Int = 1

    // disable building for y > flag base block + flagNoBuildYOffset
    public var flagNoBuildYOffset: Int = -1

    // ticks required to capture chunk
    public var chunkAttackTime: Long = 200

    public var chunkAttackFromWastelandMultiplier: Double = 2.0 // territory next to wilderness
    public var chunkAttackHomeMultiplier: Double = 2.0 // in home territory

    public var maxPlayerChunkAttacks: Int = 1

    public var allowBreakingAlliesFlags = false

    public var flagBeaconSize: Int = 6 // be in range [2, 16]
    public var flagBeaconMinSkyLevel: Int = 100 // minimum height level in sky
    public var flagBeaconSkyLevel: Int = 50 // height level above blocks

    /** If false, skip sky beacon create/restore (big lag win under many caps). */
    public var flagBeaconEnabled: Boolean = false

    /** Lighting updates when placing beacons (expensive). Keep false. */
    public var flagBeaconUpdateLighting: Boolean = false

    /** If false, no armor-stand cap holograms (boss bar only). */
    public var flagHologramsEnabled: Boolean = true

    /** Hard cap on simultaneous attacks server-wide (0 = unlimited). */
    public var maxConcurrentAttacks: Int = 0

    /** Max active attacks started by one town (0 = unlimited). */
    public var maxAttacksPerTown: Int = 0

    // --- Fork: war extras ---
    /** When true, more players in flagged chunk shortens attack time. Default off. */
    public var playersInChunkMultiplier: Boolean = false
    public var playersInChunkMultiplierPerPlayer: Double = 0.05
    public var playersInChunkMultiplierMin: Double = 0.5

    /**
     * When true, cancel PvP only between players in the SAME town.
     * Nation members and allies can still friendly-fire.
     */
    public var noFriendlyFire: Boolean = false

    public var allowUnownedAttack: Boolean = true
    public var allowBorderSkirmishFromWilderness: Boolean = true
    public var allowAllInteractsInFlaggedChunks: Boolean = true

    public var staticFlagblock: Boolean = true
    public var staticFlagblockMaterial: Material = Material.DEEPSLATE

    /** Denied interact/place attempts before poison+darkness. 0 = off. */
    public var antiBlockGlitchAttempts: Int = 4
    public var antiBlockGlitchDurationTicks: Int = 60

    /**
     * When true, only players in towns listed in [warPlayerWhiteListTowns] may stay online.
     * Default off.
     */
    public var warPlayerWhiteList: Boolean = false
    public var warPlayerWhiteListTowns: HashSet<UUID> = hashSetOf()
    public var whitelistDenyCommand: String = "kick %player% Not on the war whitelist"

    /** 0 = always skulls, 1 = no skulls during war, 2 = never. */
    public var skullsGUIToggle: Int = 1

    /**
     * Cooldown (ms) before joining a town in a different nation after leave/kick.
     * -1 = disabled.
     */
    public var nationJoinCooldown: Long = -1L

    /**
     * Only players within this many chunks of the flag get the attack boss bar.
     * 0 = all online players (old behavior).
     */
    public var warBossBarMaxDistanceChunks: Int = 6

    // allow war permissions during skirmish mode
    public var allowDestructionDuringSkirmish: Boolean = false

    // bypass permissions and allow extended ally interactions in towns
    public var warPermissions: Boolean = true

    public var canLeaveTownDuringWar: Boolean = true

    public var canCreateTownDuringWar: Boolean = false
    public var canDestroyTownDuringWar: Boolean = false
    public var canLeaveNationDuringWar: Boolean = false

    public var annexDisabled: Boolean = false

    // use whitelist/blacklist for war (derived from list.size > 0 for lists below)
    public var warUseWhitelist: Boolean = false
    public var warUseBlacklist: Boolean = false

    // war whitelist: only allow attacking these town UUIDs
    public var warWhitelist: HashSet<UUID> = hashSetOf()

    // war blacklist: disable attacking these town UUIDs
    public var warBlacklist: HashSet<UUID> = hashSetOf()

    // annex blacklist: cannot annex these towns, only occupy
    public var annexBlacklist: HashSet<UUID> = hashSetOf()
    public var useAnnexBlacklist: Boolean = false

    // only towns in whitelist can annex territories (from other whitelist territories)
    public var onlyWhitelistCanAnnex: Boolean = true
    public var onlyWhitelistCanClaim: Boolean = true

    public var occupiedHomeTeleportMultiplier: Double = 12.0

    // List of town UUIDs to allow building in occupied territory.
    // War whitelist often used to create AI towns that can be attacked
    // by anyone. People want to build in occupied territory from these
    // towns during non-war time. This list allows building/interacting
    // in these occupied towns.
    public var allowControlInOccupiedTownList: HashSet<UUID> = hashSetOf()

    // Raid
    public var raidEnabled: Boolean = true
    public var raidPrepMs: Long = 15L * 60L * 1000L
    public var raidLiveMs: Long = 30L * 60L * 1000L
    public var raidCancelDefenderWinWindowMs: Long = 5L * 60L * 1000L
    public var raidCooldownMs: Long = 2L * 60L * 60L * 1000L
    public var raidAttackerReward: Map<org.bukkit.Material, Int> = emptyMap()
    public var raidDefenderReward: Map<org.bukkit.Material, Int> = emptyMap()
    public var raidMinDefenderOnline: Int = 1

    // Supply lines
    public var supplyLinesEnabled: Boolean = true
    public var supplyLinesCutIncomeMultiplier: Double = 0.25
    public var supplyLinesCutCaptureTimeMultiplier: Double = 0.5
    public var supplyLinesChunkBlockFraction: Double = 0.5
    public var supplyLinesWastelandRoutes: Boolean = true

    // Vassals
    public var vassalsEnabled: Boolean = true
    public var vassalTributeRate: Double = 0.2
    public var vassalRevoltCooldownMs: Long = 72L * 60L * 60L * 1000L
    public var vassalOfferTimeoutMs: Long = 120L * 1000L

    // Fortified nodes
    public var fortifyEnabled: Boolean = true
    public var fortifyLevels: List<FortLevel> = Fortifications.defaultLevels

    // Supply drops
    public var supplyDropsEnabled: Boolean = true
    public var supplyDropsIntervalMs: Long = 180L * 60L * 1000L
    public var supplyDropsJitterMs: Long = 30L * 60L * 1000L
    public var supplyDropsWarningMs: Long = 5L * 60L * 1000L
    public var supplyDropsDespawnMs: Long = 20L * 60L * 1000L
    public var supplyDropsChannelMs: Long = 60L * 1000L
    public var supplyDropsChannelRadius: Double = 4.0
    public var supplyDropsFallHeight: Double = 60.0
    public var supplyDropsItemsPerDrop: Int = 3
    public var supplyDropsMinPlayersOnline: Int = 5
    public var supplyDropsDiscord: Boolean = true

    // ===================================
    // truce configs
    // ===================================

    // truce period in milliseconds
    // (truce counter uses System.currentTimeMillis)
    // 24 hour = 86400000 ms
    // 48 hour = 172800000 ms
    // 72 hour = 259200000 ms
    public var trucePeriod: Long = 172800000L

    // ===================================
    // port configs
    // ===================================
    public var seaLevel: Double = 62.0
    public var portWarpTime: Double = 5.0 * 20.0 // in ticks
    public var allowPortWarpWithoutBoat: Boolean = false

    // ===================================
    // Discord webhooks (empty = disabled)
    // ===================================
    public var discordWebhookFlagWar: String = ""
    public var discordWebhookWar: String = ""
    public var discordRelayFlagWar: Boolean = true
    public var discordRelayWar: Boolean = true
    public var discordWebhookRaids: String = ""
    public var discordWebhookWarzones: String = ""
    public var discordRelayRaids: Boolean = true
    public var discordRelayWarzones: Boolean = true

    // ===================================================
    // Load config
    // ===================================================
    public fun load(config: FileConfiguration) {
        Config.disableWorldWhenLoadFails = config.getBoolean("disableWorldWhenLoadFails", Config.disableWorldWhenLoadFails)
        Config.savePeriod = config.getLong("savePeriod", Config.savePeriod)
        Config.backupPeriod = config.getLong("backupPeriod", Config.backupPeriod)
        Config.mainPeriodicTick = config.getLong("mainPeriodicTick", Config.mainPeriodicTick)
        Config.overMaxClaimsReminderPeriod = config.getLong("overMaxClaimsReminderPeriod", Config.overMaxClaimsReminderPeriod)
        Config.nametagUpdatePeriod = config.getLong("nametagUpdatePeriod", Config.nametagUpdatePeriod)
        Config.nametagPipelineTicks = config.getInt("nametagPipelineTicks", Config.nametagPipelineTicks)
        Config.dynmapCopyTowns = config.getBoolean("dynmapCopyTowns", Config.dynmapCopyTowns)

        val mapExportSec = config.getConfigurationSection("mapExport")
        if (mapExportSec != null) {
            Config.mapExportEnabled = mapExportSec.getBoolean("enabled", Config.mapExportEnabled)
            Config.mapExportAlways = mapExportSec.getBoolean("always", Config.mapExportAlways)
            Config.mapExportPath = mapExportSec.getString("path", Config.mapExportPath) ?: Config.mapExportPath
            Config.mapExportStatePeriodTicks = mapExportSec.getLong("statePeriodTicks", Config.mapExportStatePeriodTicks).coerceIn(20L, 12000L)
            Config.mapExportLivePeriodTicks = mapExportSec.getLong("livePeriodTicks", Config.mapExportLivePeriodTicks).coerceIn(10L, 1200L)
            Config.mapExportSupplyLines = mapExportSec.getBoolean("supplyLines", Config.mapExportSupplyLines)
        }

        Config.useNametags = config.getBoolean("useNametags", Config.useNametags)

        Config.afkKickTime = config.getLong("afkKickTime", Config.afkKickTime)

        Config.canInteractInEmpty = config.getBoolean("canInteractInEmpty", Config.canInteractInEmpty)
        Config.canInteractInUnclaimed = config.getBoolean("canInteractInUnclaimed", Config.canInteractInUnclaimed)
        Config.requireSheepNodeToShear = config.getBoolean("requireSheepNodeToShear", Config.requireSheepNodeToShear)

        Config.townCreateCooldown = config.getLong("townCreateCooldown", Config.townCreateCooldown)
        Config.townMoveHomeCooldown = config.getLong("townMoveHomeCooldown", Config.townMoveHomeCooldown)

        Config.incomeEnabled = config.getBoolean("incomeEnabled", Config.incomeEnabled)
        Config.incomePeriod = config.getLong("incomePeriod", Config.incomePeriod)
        Config.incomeScaleByClaimPower = config.getBoolean("incomeScaleByClaimPower", Config.incomeScaleByClaimPower)
        Config.incomeScaleMin = config.getDouble("incomeScaleMin", Config.incomeScaleMin)
        Config.incomeScaleMax = config.getDouble("incomeScaleMax", Config.incomeScaleMax)
        Config.allowOreInWilderness = config.getBoolean("allowOreInWilderness", Config.allowOreInWilderness)
        Config.allowCropsInWilderness = config.getBoolean("allowCropsInWilderness", Config.allowCropsInWilderness)
        Config.allowBreedingInWilderness = config.getBoolean("allowBreedingInWilderness", Config.allowBreedingInWilderness)
        Config.allowOreInCaptured = config.getBoolean("allowOreInCaptured", Config.allowOreInCaptured)
        Config.allowOreInNationTowns = config.getBoolean("allowOreInNationTowns", Config.allowOreInNationTowns)
        Config.cropsMinSkyLight = config.getInt("cropsMinSkyLight", Config.cropsMinSkyLight)
        Config.breedingMinSkyLight = config.getInt("breedingMinSkyLight", Config.breedingMinSkyLight)
        Config.cropsMinYHeight = config.getInt("cropsMinYHeight", Config.cropsMinYHeight)
        Config.cropsMaxYHeight = config.getInt("cropsMaxYHeight", Config.cropsMaxYHeight)
        Config.breedingMinYHeight = config.getInt("breedingMinYHeight", Config.breedingMinYHeight)
        Config.breedingMaxYHeight = config.getInt("breedingMaxYHeight", Config.breedingMaxYHeight)

        val globalResourcesSection = config.getConfigurationSection("globalResources")
        if (globalResourcesSection !== null) {
            Config.globalResources = parseGlobalResources(globalResourcesSection)
        }

        Config.territoryCostBase = config.getInt("territoryCostBase", Config.territoryCostBase)
        Config.territoryCostScale = config.getDouble("territoryCostScale", Config.territoryCostScale)
        Config.townInitialClaims = config.getInt("townInitialClaims", Config.townInitialClaims)
        Config.initialOverClaimsAmountScale = config.getInt("initialOverClaimsAmountScale", Config.initialOverClaimsAmountScale)
        Config.townClaimsBase = config.getInt("townClaimsBase", Config.townClaimsBase)
        Config.townClaimsMax = config.getInt("townClaimsMax", Config.townClaimsMax)

        Config.playerClaimsInitial = config.getInt("playerClaimsInitial", Config.playerClaimsInitial)
        Config.playerClaimsMax = config.getInt("playerClaimsMax", Config.playerClaimsMax)
        Config.playerClaimsIncrease = config.getInt("playerClaimsIncrease", Config.playerClaimsIncrease)

        Config.townPenaltyDecay = config.getInt("townPenaltyDecay", Config.townPenaltyDecay)
        Config.townClaimsPenaltyDecayPeriod = config.getLong("townClaimsPenaltyDecayPeriod", Config.townClaimsPenaltyDecayPeriod)
        Config.playerClaimsIncreasePeriod = config.getLong("playerClaimsIncreasePeriod", Config.playerClaimsIncreasePeriod)
        Config.overClaimsPenalty = config.getBoolean("overClaimsPenalty", Config.overClaimsPenalty)
        Config.overClaimsMaxPenalty = config.getDouble("overClaimsMaxPenalty", Config.overClaimsMaxPenalty)
        Config.overClaimsAllowClaim = config.getBoolean("overClaimsAllowClaim", Config.overClaimsAllowClaim)

        Config.canOnlyAnnexDuringWar = config.getBoolean("canOnlyAnnexDuringWar", Config.canOnlyAnnexDuringWar)

        Config.townSpawnTime = config.getInt("townSpawnTime", Config.townSpawnTime)
        val outpostTeleportCostSection = config.getConfigurationSection("outpostTeleportCost")
        if (outpostTeleportCostSection !== null) {
            Config.outpostTeleportCost.putAll(parseTeleportCost(outpostTeleportCostSection))
            Config.outpostTeleportCostString = teleportCostToString(Config.outpostTeleportCost)
        }

        Config.allowNationTownSpawn = config.getBoolean("allowNationTownSpawn", Config.allowNationTownSpawn)
        val nationTeleportCostSection = config.getConfigurationSection("nationTownTeleportCost")
        if (nationTeleportCostSection !== null) {
            Config.nationTownTeleportCost.putAll(parseTeleportCost(nationTeleportCostSection))
            Config.nationTownTeleportCostString = teleportCostToString(Config.nationTownTeleportCost)
        }
        Config.allowNationFriendlyFire = config.getBoolean("allowNationFriendlyFire", Config.allowNationFriendlyFire)

        Config.allowAllyFriendlyFire = config.getBoolean("allowAllyFriendlyFire", Config.allowAllyFriendlyFire)

        Config.taxIncomeRate = config.getDouble("taxIncomeRate", Config.taxIncomeRate)
        Config.taxMineRate = config.getDouble("taxMineRate", Config.taxMineRate)
        Config.taxFarmRate = config.getDouble("taxFarmRate", Config.taxFarmRate)
        Config.taxAnimalRate = config.getDouble("taxAnimalRate", Config.taxAnimalRate)

        // ======================
        // war
        // ======================
        Config.restrictExplosions = config.getBoolean("restrictExplosions", Config.restrictExplosions)
        Config.onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", Config.onlyAllowExplosionsDuringWar)
        Config.flagNoBuildDistance = config.getInt("flagNoBuildDistance", Config.flagNoBuildDistance)
        Config.flagNoBuildYOffset = config.getInt("flagNoBuildYOffset", Config.flagNoBuildYOffset)
        Config.chunkAttackTime = config.getLong("chunkAttackTime", Config.chunkAttackTime)
        Config.chunkAttackFromWastelandMultiplier = config.getDouble("chunkAttackFromWastelandMultiplier", Config.chunkAttackFromWastelandMultiplier)
        Config.chunkAttackHomeMultiplier = config.getDouble("chunkAttackHomeMultiplier", Config.chunkAttackHomeMultiplier)
        Config.maxPlayerChunkAttacks = config.getInt("maxPlayerChunkAttacks", Config.maxPlayerChunkAttacks)
        Config.allowBreakingAlliesFlags = config.getBoolean("allowBreakingAlliesFlags", Config.allowBreakingAlliesFlags)
        Config.flagBeaconSize = config.getInt("flagBeaconSize", Config.flagBeaconSize)
        Config.flagBeaconMinSkyLevel = config.getInt("flagBeaconMinSkyLevel", Config.flagBeaconMinSkyLevel)
        Config.flagBeaconSkyLevel = config.getInt("flagBeaconSkyLevel", Config.flagBeaconSkyLevel)
        Config.flagBeaconEnabled = config.getBoolean("flagBeaconEnabled", Config.flagBeaconEnabled)
        Config.flagBeaconUpdateLighting = config.getBoolean("flagBeaconUpdateLighting", Config.flagBeaconUpdateLighting)
        Config.flagHologramsEnabled = config.getBoolean("flagHologramsEnabled", Config.flagHologramsEnabled)
        Config.maxConcurrentAttacks = config.getInt("maxConcurrentAttacks", Config.maxConcurrentAttacks)
        Config.maxAttacksPerTown = config.getInt("maxAttacksPerTown", Config.maxAttacksPerTown)
        Config.warBossBarMaxDistanceChunks = config.getInt("warBossBarMaxDistanceChunks", Config.warBossBarMaxDistanceChunks)
        Config.onlyAllowExplosionsDuringWar = config.getBoolean("onlyAllowExplosionsDuringWar", Config.onlyAllowExplosionsDuringWar)
        Config.warPermissions = config.getBoolean("warPermissions", Config.warPermissions)
        Config.flagBeaconEnabled = config.getBoolean("flagBeaconEnabled", Config.flagBeaconEnabled)
        Config.flagBeaconUpdateLighting = config.getBoolean("flagBeaconUpdateLighting", Config.flagBeaconUpdateLighting)
        Config.flagHologramsEnabled = config.getBoolean("flagHologramsEnabled", Config.flagHologramsEnabled)
        Config.maxConcurrentAttacks = config.getInt("maxConcurrentAttacks", Config.maxConcurrentAttacks)
        Config.maxAttacksPerTown = config.getInt("maxAttacksPerTown", Config.maxAttacksPerTown)
        Config.warBossBarMaxDistanceChunks = config.getInt("warBossBarMaxDistanceChunks", Config.warBossBarMaxDistanceChunks)

        // Fork: war extras
        Config.playersInChunkMultiplier = config.getBoolean("playersInChunkMultiplier", Config.playersInChunkMultiplier)
        Config.playersInChunkMultiplierPerPlayer = config.getDouble("playersInChunkMultiplierPerPlayer", Config.playersInChunkMultiplierPerPlayer)
        Config.playersInChunkMultiplierMin = config.getDouble("playersInChunkMultiplierMin", Config.playersInChunkMultiplierMin)

        Config.noFriendlyFire = config.getBoolean("noFriendlyFire", Config.noFriendlyFire)

        Config.allowUnownedAttack = config.getBoolean("allowUnownedAttack", Config.allowUnownedAttack)
        Config.allowBorderSkirmishFromWilderness = config.getBoolean("allowBorderSkirmishFromWilderness", Config.allowBorderSkirmishFromWilderness)
        Config.allowAllInteractsInFlaggedChunks = config.getBoolean("allowAllInteractsInFlaggedChunks", Config.allowAllInteractsInFlaggedChunks)

        Config.staticFlagblock = config.getBoolean("staticFlagblock", Config.staticFlagblock)
        val flagMatName = config.getString("staticFlagblockMaterial", Config.staticFlagblockMaterial.name)
        val flagMat = flagMatName?.let { Material.matchMaterial(it) }
        if (flagMat != null) {
            Config.staticFlagblockMaterial = flagMat
        }

        Config.antiBlockGlitchAttempts = config.getInt("antiBlockGlitchAttempts", Config.antiBlockGlitchAttempts)
        Config.antiBlockGlitchDurationTicks = config.getInt("antiBlockGlitchDurationTicks", Config.antiBlockGlitchDurationTicks)

        Config.warPlayerWhiteList = config.getBoolean("warPlayerWhiteList", Config.warPlayerWhiteList)
        Config.warPlayerWhiteListTowns = parseUUIDSet(config, "warPlayerWhiteListTowns")
        Config.whitelistDenyCommand = config.getString("whitelistDenyCommand", Config.whitelistDenyCommand)
            ?: Config.whitelistDenyCommand
        // ======================
        // Raid
        // ======================
        val raidSec = config.getConfigurationSection("raid")
        if (raidSec != null) {
            Config.raidEnabled = raidSec.getBoolean("enabled", Config.raidEnabled)
            Config.raidPrepMs = raidSec.getLong("prepMinutes", 15L) * 60L * 1000L
            Config.raidLiveMs = raidSec.getLong("liveMinutes", 30L) * 60L * 1000L
            Config.raidCancelDefenderWinWindowMs =
                raidSec.getLong("cancelDefenderWinWindowMinutes", 5L) * 60L * 1000L
            Config.raidCooldownMs = raidSec.getLong("cooldownMinutes", 120L) * 60L * 1000L
            Config.raidMinDefenderOnline =
                raidSec.getInt("minDefenderOnline", Config.raidMinDefenderOnline).coerceAtLeast(0)

            fun loadReward(key: String): Map<org.bukkit.Material, Int> {
                val sec = raidSec.getConfigurationSection(key) ?: return emptyMap()
                val map = mutableMapOf<org.bukkit.Material, Int>()
                for (k in sec.getKeys(false)) {
                    val mat = org.bukkit.Material.matchMaterial(k) ?: continue
                    map[mat] = sec.getInt(k)
                }
                return map
            }
            Config.raidAttackerReward = loadReward("attackerReward")
            Config.raidDefenderReward = loadReward("defenderReward")
        }

        val supplyLinesSec = config.getConfigurationSection("supplyLines")
        if (supplyLinesSec != null) {
            Config.supplyLinesEnabled = supplyLinesSec.getBoolean("enabled", Config.supplyLinesEnabled)
            Config.supplyLinesCutIncomeMultiplier =
                supplyLinesSec.getDouble("cutIncomeMultiplier", Config.supplyLinesCutIncomeMultiplier).coerceIn(0.0, 1.0)
            Config.supplyLinesCutCaptureTimeMultiplier =
                supplyLinesSec.getDouble("cutCaptureTimeMultiplier", Config.supplyLinesCutCaptureTimeMultiplier).coerceIn(0.01, 1.0)
            Config.supplyLinesChunkBlockFraction =
                supplyLinesSec.getDouble("chunkBlockFraction", Config.supplyLinesChunkBlockFraction).coerceIn(0.0, 1.0)
            Config.supplyLinesWastelandRoutes =
                supplyLinesSec.getBoolean("wastelandRoutes", Config.supplyLinesWastelandRoutes)
        }

        val vassalSec = config.getConfigurationSection("vassals")
        if (vassalSec != null) {
            Config.vassalsEnabled = vassalSec.getBoolean("enabled", Config.vassalsEnabled)
            Config.vassalTributeRate = vassalSec.getDouble("tributeRate", Config.vassalTributeRate).coerceIn(0.0, 1.0)
            Config.vassalRevoltCooldownMs = vassalSec.getLong("revoltCooldownHours", 72L).coerceAtLeast(0L) * 60L * 60L * 1000L
            Config.vassalOfferTimeoutMs = vassalSec.getLong("offerTimeoutSeconds", 120L).coerceAtLeast(10L) * 1000L
        }

        val fortifySec = config.getConfigurationSection("fortify")
        if (fortifySec != null) {
            Config.fortifyEnabled = fortifySec.getBoolean("enabled", Config.fortifyEnabled)
            val levelsSec = fortifySec.getConfigurationSection("levels")
            if (levelsSec != null) {
                val parsed = levelsSec.getKeys(false)
                    .mapNotNull { key -> key.toIntOrNull()?.let { it to levelsSec.getConfigurationSection(key) } }
                    .sortedBy { it.first }
                    .mapNotNull { (_, sec) ->
                        sec?.let {
                            FortLevel(
                                it.getDouble("captureTimeMultiplier", 1.0).coerceAtLeast(1.0),
                                it.getConfigurationSection("cost")?.let { c -> parseTeleportCost(c) } ?: emptyMap(),
                                it.getConfigurationSection("upkeep")?.let { u -> parseTeleportCost(u) } ?: emptyMap(),
                            )
                        }
                    }
                if (parsed.isNotEmpty()) {
                    Config.fortifyLevels = parsed
                }
            }
        }

        val supplyDropsSec = config.getConfigurationSection("supplyDrops")
        if (supplyDropsSec != null) {
            val minute = 60L * 1000L
            Config.supplyDropsEnabled = supplyDropsSec.getBoolean("enabled", Config.supplyDropsEnabled)
            Config.supplyDropsIntervalMs = supplyDropsSec.getLong("intervalMinutes", 180L).coerceAtLeast(1L) * minute
            Config.supplyDropsJitterMs = supplyDropsSec.getLong("jitterMinutes", 30L).coerceAtLeast(0L) * minute
            Config.supplyDropsWarningMs = supplyDropsSec.getLong("warningSeconds", 300L).coerceAtLeast(0L) * 1000L
            Config.supplyDropsDespawnMs = supplyDropsSec.getLong("despawnMinutes", 20L).coerceAtLeast(1L) * minute
            Config.supplyDropsChannelMs = supplyDropsSec.getLong("claimSeconds", 60L).coerceAtLeast(1L) * 1000L
            Config.supplyDropsChannelRadius = supplyDropsSec.getDouble("claimRadius", Config.supplyDropsChannelRadius)
            Config.supplyDropsFallHeight = supplyDropsSec.getDouble("fallHeight", Config.supplyDropsFallHeight).coerceAtLeast(0.0)
            Config.supplyDropsItemsPerDrop = supplyDropsSec.getInt("itemsPerDrop", Config.supplyDropsItemsPerDrop).coerceAtLeast(1)
            Config.supplyDropsMinPlayersOnline =
                supplyDropsSec.getInt("minPlayersOnline", Config.supplyDropsMinPlayersOnline).coerceAtLeast(0)
            Config.supplyDropsDiscord = supplyDropsSec.getBoolean("discord", Config.supplyDropsDiscord)
        }

        Config.skullsGUIToggle = config.getInt("skullsGUIToggle", Config.skullsGUIToggle)

        Config.nationJoinCooldown = config.getLong("nationJoinCooldown", Config.nationJoinCooldown)

        Config.allowDestructionDuringSkirmish = config.getBoolean("allowDestructionDuringSkirmish", Config.allowDestructionDuringSkirmish)
        Config.canLeaveTownDuringWar = config.getBoolean("canLeaveTownDuringWar", Config.canLeaveTownDuringWar)
        Config.canCreateTownDuringWar = config.getBoolean("canCreateTownDuringWar", Config.canCreateTownDuringWar)
        Config.canDestroyTownDuringWar = config.getBoolean("canDestroyTownDuringWar", Config.canDestroyTownDuringWar)
        Config.canLeaveNationDuringWar = config.getBoolean("canLeaveNationDuringWar", Config.canLeaveNationDuringWar)
        Config.annexDisabled = config.getBoolean("annexDisabled", Config.annexDisabled)

        Config.warWhitelist = parseUUIDSet(config, "warWhitelist")
        Config.warUseWhitelist = Config.warWhitelist.size > 0
        Config.warBlacklist = parseUUIDSet(config, "warBlacklist")
        Config.warUseBlacklist = Config.warBlacklist.size > 0
        Config.annexBlacklist = parseUUIDSet(config, "annexBlacklist")
        Config.useAnnexBlacklist = Config.annexBlacklist.size > 0

        Config.onlyWhitelistCanAnnex = config.getBoolean("onlyWhitelistCanAnnex", Config.onlyWhitelistCanAnnex)
        Config.onlyWhitelistCanClaim = config.getBoolean("onlyWhitelistCanClaim", Config.onlyWhitelistCanClaim)

        Config.occupiedHomeTeleportMultiplier = config.getDouble("occupiedHomeTeleportMultiplier", Config.occupiedHomeTeleportMultiplier)

        Config.allowControlInOccupiedTownList = parseUUIDSet(config, "allowControlInOccupiedTownList")

        Config.trucePeriod = config.getLong("trucePeriod", Config.trucePeriod)

        Config.seaLevel = config.getDouble("seaLevel", Config.seaLevel)
        Config.portWarpTime = config.getDouble("portWarpTime", Config.portWarpTime) * 20.0 // time is in seconds, convert to ticks
        Config.allowPortWarpWithoutBoat = config.getBoolean("allowPortWarpWithoutBoat", Config.allowPortWarpWithoutBoat)

        Config.discordWebhookFlagWar = config.getString("discordWebhookFlagWar", Config.discordWebhookFlagWar)?.trim() ?: ""
        Config.discordWebhookWar = config.getString("discordWebhookWar", Config.discordWebhookWar)?.trim() ?: ""
        Config.discordRelayFlagWar = config.getBoolean("discordRelayFlagWar", Config.discordRelayFlagWar)
        Config.discordRelayWar = config.getBoolean("discordRelayWar", Config.discordRelayWar)
        Config.discordWebhookRaids = config.getString("discordWebhookRaids", Config.discordWebhookRaids)?.trim() ?: ""
        Config.discordWebhookWarzones = config.getString("discordWebhookWarzones", Config.discordWebhookWarzones)?.trim() ?: ""
        Config.discordRelayRaids = config.getBoolean("discordRelayRaids", Config.discordRelayRaids)
        Config.discordRelayWarzones = config.getBoolean("discordRelayWarzones", Config.discordRelayWarzones)

        if (config.isString("nationJoinCooldown")) {
            val raw = config.getString("nationJoinCooldown")
            if (raw != null && raw.trim() == "-1") {
                Config.nationJoinCooldown = -1L
            } else {
            }
        }
    }
}

private fun parseGlobalResources(globalResourcesSection: ConfigurationSection): TerritoryResources {
    val income: EnumMap<Material, Double> = EnumMap<Material, Double>(Material::class.java)
    val incomeSpawnEgg: EnumMap<EntityType, Double> = EnumMap<EntityType, Double>(EntityType::class.java)
    val ores: EnumMap<Material, OreDeposit> = EnumMap<Material, OreDeposit>(Material::class.java)
    val crops: EnumMap<Material, Double> = EnumMap<Material, Double>(Material::class.java)
    val animals: EnumMap<EntityType, Double> = EnumMap<EntityType, Double>(EntityType::class.java)

    globalResourcesSection.getConfigurationSection("income")?.let { section ->
        for (item in section.getKeys(false)) {
            val itemName = item.uppercase()
            if (itemName.startsWith("SPAWN_EGG_")) {
                val entityType = EntityType.valueOf(itemName.replace("SPAWN_EGG_", ""))
                if (entityType !== null) {
                    incomeSpawnEgg.put(entityType, section.getDouble(item))
                }
            } else {
                val material = Material.matchMaterial(item)
                if (material !== null) {
                    income.put(material, section.getDouble(item))
                }
            }
        }
    }

    globalResourcesSection.getConfigurationSection("ore")?.let { section ->
        for (item in section.getKeys(false)) {
            val material = Material.matchMaterial(item)
            if (material !== null) {
                // list format [chance, min, max]
                if (section.isList(item)) {
                    val list = section.getDoubleList(item)
                    if (list.size === 3) {
                        val chance = list[0]
                        val min = list[1].toInt()
                        val max = list[2].toInt()
                        ores.put(material, OreDeposit(material, chance, min, max))
                    }
                }
                // single chance number (implicit min = max = 1)
                else {
                    val chance = section.getDouble(item)
                    ores.put(material, OreDeposit(material, chance, 1, 1))
                }
            }
        }
    }

    globalResourcesSection.getConfigurationSection("crops")?.let { section ->
        for (item in section.getKeys(false)) {
            val material = Material.matchMaterial(item)
            if (material !== null) {
                crops.put(material, section.getDouble(item))
            }
        }
    }

    globalResourcesSection.getConfigurationSection("animals")?.let { section ->
        for (item in section.getKeys(false)) {
            try {
                val entityType = EntityType.valueOf(item.uppercase())
                animals.put(entityType, section.getDouble(item))
            } catch (err: Exception) {
                err.printStackTrace()
            }
        }
    }

    return TerritoryResources(
        income = income,
        incomeSpawnEgg = incomeSpawnEgg,
        ores = ores,
        crops = crops,
        animals = animals,
    )
}

// Parses a list of `material: amount`
private fun parseTeleportCost(section: ConfigurationSection): EnumMap<Material, Int> {
    val materials: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)

    for (item in section.getKeys(false)) {
        val material = Material.matchMaterial(item)
        if (material !== null) {
            materials.put(material, section.getInt(item))
        }
    }

    return materials
}

private fun teleportCostToString(materials: EnumMap<Material, Int>): String {
    var s = ""

    var index = 0
    for ((mat, amount) in materials) {
        s += "$amount $mat"

        if (index < materials.size - 1) {
            s += ", "
        }

        index += 1
    }

    return s
}

private fun parseUUIDSet(config: ConfigurationSection, listName: String): HashSet<UUID> {
    val uuids: HashSet<UUID> = hashSetOf()

    if (config.isList(listName)) {
        val uuidList = config.getStringList(listName)
        if (uuidList !== null) {
            for (uuidString in uuidList) {
                val uuid = try {
                    UUID.fromString(uuidString)
                } catch (err: Exception) {
                    System.err.println("[Config] Invalid UUID: $uuidString")
                    continue
                }

                uuids.add(uuid)
            }
        }
    }

    return uuids
}
