/*
 * Nodes Engine/API
 */

package phonon.nodes

import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.chat.ChatMode
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.constants.ErrorAlreadyAllies
import phonon.nodes.constants.ErrorAlreadyEnemies
import phonon.nodes.constants.ErrorAlreadyTruce
import phonon.nodes.constants.ErrorNationDoesNotHaveTown
import phonon.nodes.constants.ErrorNationExists
import phonon.nodes.constants.ErrorNotAllies
import phonon.nodes.constants.ErrorPlayerHasNation
import phonon.nodes.constants.ErrorPlayerHasTown
import phonon.nodes.constants.ErrorPlayerNotInTown
import phonon.nodes.constants.ErrorPortExists
import phonon.nodes.constants.ErrorPortInGroup
import phonon.nodes.constants.ErrorTerritoryHasClaim
import phonon.nodes.constants.ErrorTerritoryIsTownHome
import phonon.nodes.constants.ErrorTerritoryNotConnected
import phonon.nodes.constants.ErrorTerritoryNotInTown
import phonon.nodes.constants.ErrorTerritoryOwned
import phonon.nodes.constants.ErrorTooManyClaims
import phonon.nodes.constants.ErrorTownDoesNotExist
import phonon.nodes.constants.ErrorTownExists
import phonon.nodes.constants.ErrorTownHasNation
import phonon.nodes.constants.ErrorWarAllyOrTruce
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.event.AllianceCreatedEvent
import phonon.nodes.event.NodesTerritoriesLoadedEvent
import phonon.nodes.event.NodesWorldLoadedEvent
import phonon.nodes.event.TruceExpiredEvent
import phonon.nodes.fortify.Fortifications
import phonon.nodes.objects.Coord
import phonon.nodes.objects.DefaultResourceAttributeLoader
import phonon.nodes.objects.Nametag
import phonon.nodes.objects.Nation
import phonon.nodes.objects.NationPair
import phonon.nodes.objects.OreBlockCache
import phonon.nodes.objects.OreSampler
import phonon.nodes.objects.Port
import phonon.nodes.objects.PortGroup
import phonon.nodes.objects.Resident
import phonon.nodes.objects.ResourceNode
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.TerritoryPreprocessing
import phonon.nodes.objects.TerritoryResources
import phonon.nodes.objects.Town
import phonon.nodes.objects.TownOutpost
import phonon.nodes.objects.TownPair
import phonon.nodes.ports.PortBeacons
import phonon.nodes.serdes.Deserializer
import phonon.nodes.supply.SupplyLines
import phonon.nodes.tasks.OverMaxClaimsReminder
import phonon.nodes.tasks.SaveManager
import phonon.nodes.tasks.TaskCopyToDynmap
import phonon.nodes.tasks.TaskSaveBackup
import phonon.nodes.tasks.TaskSaveDynmapClaimsConfig
import phonon.nodes.tasks.TaskSavePorts
import phonon.nodes.tasks.TaskSaveWorld
import phonon.nodes.utils.Color
import phonon.nodes.utils.sanitizeString
import phonon.nodes.utils.saveStringToFile
import phonon.nodes.vassal.Vassals
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Truce
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Logger
import kotlin.system.measureNanoTime

public object Nodes {

    internal val version: String = "v0.0.14"

    internal val resourceNodes: HashMap<String, ResourceNode> = hashMapOf()

    internal val territoryChunks: HashMap<Coord, TerritoryChunk> = hashMapOf()

    internal val territories: HashMap<TerritoryId, Territory> = hashMapOf()

    internal val towns: LinkedHashMap<String, Town> = LinkedHashMap()

    internal val nations: LinkedHashMap<String, Nation> = LinkedHashMap()

    internal val residents: LinkedHashMap<UUID, Resident> = LinkedHashMap()

    internal val ports: LinkedHashMap<String, Port> = LinkedHashMap()
    internal val portGroups: LinkedHashMap<String, PortGroup> = LinkedHashMap()

    public var playerWarpTasks: HashMap<UUID, BukkitTask> = hashMapOf()

    // chunk coords -> port (one port per chunk)
    public var chunkToPort: HashMap<List<Int>, Port> = hashMapOf()

    // Accessed async
    internal var lastBackupTime: Long = 0

    // Accessed async
    internal var lastIncomeTime: Long = 0

    public val war = FlagWar

    internal var needsSave: Boolean = false

    internal val hiddenOreInvalidBlocks: OreBlockCache = OreBlockCache(2000)

    internal var dynmap: Boolean = false
    internal val DYNMAP_DIR: Path = Paths.get("plugins/dynmap/web/nodes")
    internal val DYNMAP_PATH_NODES_CONFIG: Path = Paths.get("plugins/dynmap/web/nodes/config.json")
    internal val DYNMAP_PATH_WORLD: Path = Paths.get("plugins/dynmap/web/nodes/world.json")
    internal val DYNMAP_PATH_TOWNS: Path = Paths.get("plugins/dynmap/web/nodes/towns.json")
    internal val DYNMAP_PATH_PORTS: Path = Paths.get("plugins/dynmap/web/nodes/ports.json")

    internal var plugin: Plugin? = null
    internal var logger: Logger? = null

    internal var initialized: Boolean = false

    internal var enabled: Boolean = false

    internal fun initialize(plugin: Plugin) {
        Nodes.plugin = plugin
        Nodes.logger = plugin.getLogger()
    }

    // sections whose entries are chosen by the admin (blocks, levels, titles...): if the section
    // exists, entries the admin removed on purpose are not put back
    private val adminKeyedSections = listOf(
        "globalResources.income", "globalResources.ore", "globalResources.crops", "globalResources.animals",
        "raid.attackerReward", "raid.defenderReward", "outpostTeleportCost", "nationTownTeleportCost",
        "tabList.staffPermissions", "poi.milestones", "skills.titles", "fortify.levels",
    )

    private val skillXpPath = Regex("^skills\\.[a-z]+\\.xp(\\..*)?$")

    private fun isAdminKeyed(path: String): Boolean = adminKeyedSections.any { path == it || path.startsWith("$it.") } || skillXpPath.matches(path)

    // copies every option (at any depth) the bundled config has and the file lacks, with comments
    private fun mergeMissing(defaults: org.bukkit.configuration.ConfigurationSection, config: YamlConfiguration, prefix: String, force: Boolean = false): Int {
        var added = 0
        for (key in defaults.getKeys(false)) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val exists = config.contains(path, true)
            if (defaults.isConfigurationSection(key)) {
                if (!exists) {
                    config.createSection(path)
                    config.setComments(path, defaults.getComments(key))
                    config.setInlineComments(path, defaults.getInlineComments(key))
                    added += 1 + mergeMissing(defaults.getConfigurationSection(key)!!, config, path, true)
                } else if (force || !isAdminKeyed(path)) {
                    added += mergeMissing(defaults.getConfigurationSection(key)!!, config, path, force)
                }
                continue
            }
            if (exists) continue
            config.set(path, defaults.get(key))
            config.setComments(path, defaults.getComments(key))
            config.setInlineComments(path, defaults.getInlineComments(key))
            added++
        }
        return added
    }

    /**
     * Plugin updates never touch an existing config.yml, so on every start copy in whatever the
     * bundled config.yml has and this file lacks, with its comments. Existing values are never
     * changed, and entries inside admin-keyed sections that were removed on purpose stay removed.
     */
    private fun addMissingConfigOptions(plugin: org.bukkit.plugin.Plugin, config: YamlConfiguration, file: File) {
        try {
            val stream = plugin.getResource("config.yml") ?: return
            val defaults = stream.use {
                YamlConfiguration.loadConfiguration(java.io.InputStreamReader(it, Charsets.UTF_8))
            }
            val added = mergeMissing(defaults, config, "")
            if (added > 0) {
                config.save(file)
                plugin.getLogger().info("config.yml: added $added missing option(s) from this version (existing values kept)")
            }
        } catch (e: Exception) {
            plugin.getLogger().warning("Could not add new options to config.yml: ${e.message}")
        }
    }

    internal fun reloadConfig() {
        val plugin = Nodes.plugin
        if (plugin === null) {
            return
        }

        val logger = plugin.getLogger()

        val configFile = File(plugin.getDataFolder().getPath(), "config.yml")
        if (!configFile.exists()) {
            logger.info("No config found: generating default config.yml")
            plugin.saveDefaultConfig()
        }

        val config = YamlConfiguration.loadConfiguration(configFile)
        if (config !== null) {
            addMissingConfigOptions(plugin, config, configFile)
            Config.load(config)
        }
    }

    internal fun reloadManagers() {
        SaveManager.stop()
        PeriodicTickManager.stop()
        OverMaxClaimsReminder.stop()
        Nametag.stop()

        val plugin = Nodes.plugin
        if (plugin === null) {
            return
        }

        // saving and timed logic (income, cooldowns) belong to the leader shard only
        if (!phonon.nodes.shard.Shards.follower) {
            SaveManager.start(plugin, Config.savePeriod)
            PeriodicTickManager.start(plugin, Config.mainPeriodicTick)
        }
        OverMaxClaimsReminder.start(plugin, Config.overMaxClaimsReminderPeriod)
        Nametag.start(plugin, Config.nametagUpdatePeriod)
    }

    // Needed to mark online players correctly after a plugin reload
    internal fun initializeOnlinePlayers() {
        for (player in Bukkit.getOnlinePlayers()) {
            Nodes.createResident(player)

            val resident = Nodes.getResident(player)!!
            Nodes.setResidentOnline(resident, player)

            if (resident.town !== null && Config.useNametags) {
                Nametag.create(player)
            }
        }
    }

    internal fun cleanup() {
        for (r in Nodes.residents.values) {
            r.destroyMinimap()
        }

        for (town in Nodes.towns.values) {
            val result = town.income.pushToStorage(true)
            if (result == true) {
                town.needsUpdate()
            }
        }

        if (Nodes.war.enabled) {
            Nodes.war.cleanup()
        }

        if (Config.useNametags) {
            Nametag.clear()
        }

        val currTimeString = System.currentTimeMillis().toString()
        saveStringToFile(currTimeString, Config.pathLastBackupTime)
        saveStringToFile(currTimeString, Config.pathLastIncomeTime)
    }

    /**
     * Load resource nodes from json files and insert them into
     * the global `Nodes.resourceNodes` map.
     */
    internal fun loadResources(json: JsonObject) {
        // TODO: generalize to multipler loaders
        Nodes.resourceNodes.putAll(DefaultResourceAttributeLoader.load(HashMap(0), json))
    }

    /**
     * Load territories from json and insert into the global
     * `Nodes.territories` map. Raises exceptions if any territory ids
     * do not exist.
     */
    internal fun loadTerritories(json: JsonObject, ids: List<TerritoryId>? = null) {
        val territoryPreprocessing: List<TerritoryPreprocessing> = TerritoryPreprocessing.loadFromJson(json, ids)

        // first create intermediary resource graph before creating
        // final compiled territories.
        // - if ids == null, we are re-creating all territories, so this
        // will be populated with resources from all territories
        // - if ids != null, we are re-creating only a subset of territories,
        // so we need to also pre-populate this with resources from existing
        // territory neighbors AND NEIGHBOR's NEIGHBORS. This is because we cannot
        // know if neighbor's neighbors also have neighbor modifiers, so this
        // must pre-emptively load neighbor's neighbors resources.
        val terrResourceGraph: HashMap<TerritoryId, TerritoryResources> = HashMap(0)

        if (ids != null) {
            val neighborResourcesToLoad = HashSet<TerritoryId>()

            for (id in ids) {
                val currTerr = Nodes.territories[id]
                if (currTerr != null) {
                    for (neighborId in currTerr.neighbors) {
                        val neighborTerr = Nodes.territories[neighborId]
                        if (neighborTerr != null) {
                            neighborResourcesToLoad.add(neighborId)
                            for (nnId in neighborTerr.neighbors) {
                                neighborResourcesToLoad.add(nnId)
                            }
                        } else {
                            Nodes.logger!!.warning("`loadTerritories()` Territory $id neighbor $neighborId does not exist")
                        }
                    }
                } else {
                    Nodes.logger!!.warning("`loadTerritories()` reloading territory $id does not exist")
                }
            }

            for (id in neighborResourcesToLoad) {
                val neighborTerr = Nodes.territories[id]
                if (neighborTerr != null) {
                    val resources = neighborTerr.resourceNodes
                        .map { name -> Nodes.resourceNodes[name] ?: throw Exception("Resource node '$name' does not exist (for territory id=${neighborTerr.id})") }
                        .sortedBy { r -> r.priority }

                    terrResourceGraph[id] = resources.fold(Config.globalResources.copy(), { terr, r -> r.apply(terr) })
                } else {
                    Nodes.logger!!.warning("`loadTerritories()` neighbor territory $id does not exist")
                }
            }
        }

        for (terr in territoryPreprocessing) {
            val resources = terr.resourceNodes
                .map { name -> Nodes.resourceNodes[name] ?: throw Exception("Resource node '$name' does not exist (for territory id=${terr.id})") }
                .sortedBy { r -> r.priority }

            terrResourceGraph[terr.id] = resources.fold(Config.globalResources.copy(), { tr, r -> r.apply(tr) })
        }

        // Determine final territories to be built.
        // If ids == null, then all territories in preprocessing phase are built.
        // If ids != null, then all territories in preprocessing AND
        //    their neighbors IF territory has neighbor modifiers.
        val territoriesToBuild: List<TerritoryPreprocessing> = if (ids == null) {
            territoryPreprocessing
        } else {
            val neighborIdsToRebuild = HashSet<TerritoryId>()
            for (terr in territoryPreprocessing) {
                if (terrResourceGraph[terr.id]!!.hasNeighborModifier) {
                    for (neighborId in terr.neighbors) {
                        neighborIdsToRebuild.add(neighborId)
                    }
                }
            }

            // remove main reloaded territory ids to avoid double-counting
            for (terr in territoryPreprocessing) {
                neighborIdsToRebuild.remove(terr.id)
            }

            val terrNeighborsToRebuild = neighborIdsToRebuild
                .map { id -> Nodes.territories.get(id)?.toPreprocessing() }
                .filterNotNull()

            territoryPreprocessing + terrNeighborsToRebuild
        }

        for (terr in territoriesToBuild) {
            val terrResources = terrResourceGraph[terr.id]
            if (terrResources != null) {
                var terrAfterNeighborModifiers: TerritoryResources = terrResources
                for (neighborId in terr.neighbors) {
                    terrResourceGraph[neighborId]?.let { neighborResources ->
                        if (neighborResources.hasNeighborModifier) {
                            terrAfterNeighborModifiers = terrAfterNeighborModifiers.accumulateNeighborModifiers(neighborResources)
                        }
                    }
                }
                terrResourceGraph[terr.id] = terrAfterNeighborModifiers
            } else {
                Nodes.logger?.warning("`loadTerritories()` Invalid territory id: ${terr.id}")
            }
        }

        for (t in territoriesToBuild) {
            if (!t.chunks.contains(t.core)) {
                Nodes.logger?.warning("[Nodes] Territory ${t.id} chunk does not contain core")
                return
            }

            val resources = terrResourceGraph[t.id]!!.applyNeighborModifiers()

            val resourceNamesSorted = t.resourceNodes.sortedBy { name -> Nodes.resourceNodes[name]!!.priority }

            val ores = OreSampler(ArrayList(resources.ores.values))

            val cost = Nodes.calculateTerritoryCost(t.chunks.size, resourceNamesSorted)

            val territory = Territory(
                id = t.id,
                name = t.name,
                color = t.color,
                core = t.core,
                chunks = t.chunks,
                bordersWilderness = t.bordersWilderness,
                neighbors = t.neighbors,
                resourceNodes = resourceNamesSorted,
                cost = cost,
                income = resources.income,
                incomeSpawnEgg = resources.incomeSpawnEgg,
                ores = ores,
                crops = resources.crops,
                animals = resources.animals,
                customProperties = resources.customProperties,
                attackerTimeMultiplier = resources.attackerTimeMultiplier,
                defenderTimeMultiplier = resources.defenderTimeMultiplier,
            )

            // Carry over in-game state from the old territory
            Nodes.territories[t.id]?.let { oldTerritory ->
                oldTerritory.chunks.forEach { c -> Nodes.territoryChunks.remove(c) }
                territory.town = oldTerritory.town
                territory.occupier = oldTerritory.occupier
            }

            Nodes.territories.put(t.id, territory)

            t.chunks.forEach { c ->
                Nodes.territoryChunks.put(c, TerritoryChunk(c, territory))
            }
        }

        Bukkit.getPluginManager().callEvent(NodesTerritoriesLoadedEvent())
    }

    /**
     * Wrapper for reloading resources or territories.
     * Returns boolean if reload was successful.
     */
    internal fun reloadWorldJson(
        reloadResources: Boolean,
        reloadTerritories: Boolean,
        territoryIds: List<TerritoryId>? = null,
    ): Boolean {
        if (Files.exists(Config.pathWorld)) {
            val (jsonResources, jsonTerritories) = Deserializer.worldFromJson(Config.pathWorld)

            // if resources are reloaded, ALL territories must be updated (ignore ids input)
            if (reloadResources && jsonResources != null) {
                Nodes.loadResources(jsonResources)
                if (jsonTerritories != null) Nodes.loadTerritories(jsonTerritories)
            } else if (reloadTerritories && jsonTerritories != null) {
                Nodes.loadTerritories(jsonTerritories, territoryIds)
            }

            if (Nodes.dynmap == true || Config.dynmapCopyTowns) {
                TaskCopyToDynmap(Config.pathWorld, DYNMAP_DIR, DYNMAP_PATH_WORLD).run()
            }

            return true
        }

        return false
    }

    internal fun loadWorld(): Boolean {
        Nodes.resourceNodes.clear()
        Nodes.territoryChunks.clear()
        Nodes.territories.clear()
        Nodes.towns.clear()
        Nodes.nations.clear()
        Nodes.residents.clear()
        Nodes.ports.clear()

        if (Files.exists(Config.pathWorld)) {
            val (jsonResources, jsonTerritories) = Deserializer.worldFromJson(Config.pathWorld)
            if (jsonResources != null) Nodes.loadResources(jsonResources)
            if (jsonTerritories != null) Nodes.loadTerritories(jsonTerritories)

            if (Nodes.dynmap == true || Config.dynmapCopyTowns) {
                TaskCopyToDynmap(Config.pathWorld, Nodes.DYNMAP_DIR, Nodes.DYNMAP_PATH_WORLD).run()
            }

            if (Files.exists(Config.pathTowns)) {
                Deserializer.townsFromJson(Config.pathTowns)

                // Pre-generate json strings to speed up the first save (a follower shard never saves)
                if (!phonon.nodes.shard.Shards.follower) {
                    for (resident in Nodes.residents.values) {
                        resident.getSaveState()
                    }
                    for (town in Nodes.towns.values) {
                        town.getSaveState()
                    }
                    for (nation in Nodes.nations.values) {
                        nation.getSaveState()
                    }
                }

                if (Nodes.dynmap) {
                    Files.copy(Config.pathTowns, Nodes.DYNMAP_PATH_TOWNS, StandardCopyOption.REPLACE_EXISTING)
                }

                Nodes.war.load()
            } else {
                System.err.println("No towns found: ${Config.pathTowns}")
                return true
            }

            if (Files.exists(Config.pathPorts)) {
                Deserializer.portsFromJson(Config.pathPorts)

                // Pre-generate json strings to speed up the first save (a follower shard never saves)
                if (!phonon.nodes.shard.Shards.follower) {
                    for (port in Nodes.ports.values) {
                        port.getSaveState()
                    }
                    for (portGroup in Nodes.portGroups.values) {
                        portGroup.getSaveState()
                    }
                }

                if (Nodes.dynmap) {
                    Files.copy(Config.pathPorts, Nodes.DYNMAP_PATH_PORTS, StandardCopyOption.REPLACE_EXISTING)
                }
            } else {
                System.err.println("No ports found: ${Config.pathPorts}")
                return true
            }

            Bukkit.getPluginManager().callEvent(NodesWorldLoadedEvent())
        } else {
            System.err.println("Failed to load world: ${Config.pathWorld}")
            return false
        }

        return true
    }

    internal fun saveWorld(
        checkIfNeedsSave: Boolean = true, // set to false to force save
        async: Boolean = false, // run serialization and file write asynchronously
    ) {
        // if we reached backup time interval, generate a backup millis
        // timestamp for save task, which will be used to create a
        // timestamped backup file
        // a follower shard mirrors the leader and must never write the shared state
        if (phonon.nodes.shard.Shards.follower) {
            return
        }
        val currTime = System.currentTimeMillis()
        val backup = currTime > Nodes.lastBackupTime + Config.backupPeriod
        val backupTimestamp = if (backup) {
            Nodes.lastBackupTime = currTime
            currTime
        } else {
            -1
        }

        if (Nodes.needsSave == true || checkIfNeedsSave == false) {
            Nodes.saveWorldPreprocess()

            val timeUpdate = measureNanoTime {
                // create a snapshot of world objects state
                // always do synchronously on main thread to keep world consistent
                val residentsSnapshot = Nodes.residents.values.map { it.getSaveState() }
                val townsSnapshot = Nodes.towns.values.map { it.getSaveState() }
                val nationsSnapshot = Nodes.nations.values.map { it.getSaveState() }

                val copyToDynmap = Config.dynmapCopyTowns

                // save task manages:
                // 1. serialize individual objects into json strings and combine into a full json string
                // 2. write file
                // 3. copy to dynmap folder when save done
                // 4. save backup if reached backup interval
                val taskSave = TaskSaveWorld(
                    residentsSnapshot,
                    townsSnapshot,
                    nationsSnapshot,
                    Config.pathTowns,
                    DYNMAP_DIR,
                    DYNMAP_PATH_TOWNS,
                    Config.pathBackup,
                    Config.pathLastBackupTime,
                    copyToDynmap,
                    backupTimestamp,
                )

                if (async) {
                    Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, taskSave)
                } else {
                    taskSave.run()
                }

                Nodes.needsSave = false
            }

            Nodes.logger?.info("[Nodes] Saving world: ${timeUpdate}ns")

            val portGroupsSnapshot = Nodes.portGroups.values.map { it.getSaveState() }
            val portsSnapshot = Nodes.ports.values.map { it.getSaveState() }

            val copyToDynmap = Nodes.dynmap || Config.dynmapCopyTowns

            val taskSavePorts = TaskSavePorts(
                portsSnapshot,
                portGroupsSnapshot,
                Config.pathPorts,
                DYNMAP_DIR,
                DYNMAP_PATH_PORTS,
                copyToDynmap,
            )

            if (async) {
                Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, taskSavePorts)
            } else {
                taskSavePorts.run()
            }
        }
        // no new save needed...just do backup if we reached backup interval
        else if (backup) {
            val taskBackup =
                TaskSaveBackup(backupTimestamp, Config.pathTowns, Config.pathBackup, Config.pathLastBackupTime)
            if (async) {
                Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, taskBackup)
            } else {
                taskBackup.run()
            }
        }
    }

    /**
     * Handle any pre-processing or finishing town/nation modifications before
     * saving to json.
     */
    internal fun saveWorldPreprocess() {
        for (town in Nodes.towns.values) {
            val result = town.income.pushToStorage(false)
            if (result == true) {
                town.needsUpdate()
            }
        }
    }

    /**
     * Save config and world state to dynmap folder.
     * Should run when plugin enabled.
     */
    internal fun saveWorldToDynmap(async: Boolean) {
        if (!Config.dynmapCopyTowns) {
            return
        }

        val taskSaveClaimsConfig = TaskSaveDynmapClaimsConfig(
            Config.territoryCostBase,
            Config.territoryCostScale,
            DYNMAP_PATH_NODES_CONFIG,
        )
        val taskSaveTowns = TaskCopyToDynmap(
            Config.pathTowns,
            Nodes.DYNMAP_DIR,
            Nodes.DYNMAP_PATH_TOWNS,
        )
        val taskSavePorts = TaskCopyToDynmap(
            Config.pathPorts,
            Nodes.DYNMAP_DIR,
            Nodes.DYNMAP_PATH_PORTS,
        )

        if (async) {
            Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, taskSaveClaimsConfig)
            Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, taskSaveTowns)
            Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, taskSavePorts)
        } else {
            taskSaveClaimsConfig.run()
            taskSaveTowns.run()
            taskSavePorts.run()
        }
    }

    // initialization function,
    // loads diplomatic relations (allies, enemies, truce)
    // requires all towns, nations already be created
    internal fun loadDiplomacy(
        towns: ArrayList<Town>,
        townAllies: ArrayList<ArrayList<String>>,
        townEnemies: ArrayList<ArrayList<String>>,
        nations: ArrayList<Nation>,
        nationAllies: ArrayList<ArrayList<String>>,
        nationEnemies: ArrayList<ArrayList<String>>,
    ) {
        // perform fixes on diplomacy during loading:
        // 1. if towns are in nation, ignore pairs between towns
        //    in nation. instead add nations to nationAlliancePairs
        // 2. if either town NOT in nation, add to townAlliancePairs

        val townAlliancePairs: HashSet<TownPair> = hashSetOf()
        val nationAlliancePairs: HashSet<NationPair> = hashSetOf()

        val townEnemyPairs: HashSet<TownPair> = hashSetOf()
        val nationEnemyPairs: HashSet<NationPair> = hashSetOf()

        for ((i, town) in towns.withIndex()) {
            val allies = townAllies[i]
            val enemies = townEnemies[i]

            val townNation = town.nation

            for (name in allies) {
                val other = Nodes.towns.get(name)
                if (other !== null) {
                    val otherNation = other.nation

                    if (townNation === null || otherNation === null) {
                        townAlliancePairs.add(TownPair(town, other))
                    } else if (town === townNation.capital && other === otherNation.capital) {
                        nationAlliancePairs.add(NationPair(townNation, otherNation))
                    }
                }
            }

            for (name in enemies) {
                val other = Nodes.towns.get(name)
                if (other !== null) {
                    val otherNation = other.nation

                    if (townNation === null || otherNation === null) {
                        townEnemyPairs.add(TownPair(town, other))
                    } else if (town === townNation.capital && other === otherNation.capital) {
                        nationEnemyPairs.add(NationPair(townNation, otherNation))
                    }
                }
            }
        }

        for (townPair in townAlliancePairs) {
            val town1 = townPair.town1
            val town2 = townPair.town2

            town1.allies.add(town2)
            town2.allies.add(town1)
        }

        for (nationPair in nationAlliancePairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.allies.add(nation2)
            nation2.allies.add(nation1)

            for (town1 in nation1.towns) {
                for (town2 in nation2.towns) {
                    town1.allies.add(town2)
                    town2.allies.add(town1)
                }
            }
        }

        for (townPair in townEnemyPairs) {
            val town1 = townPair.town1
            val town2 = townPair.town2

            town1.enemies.add(town2)
            town2.enemies.add(town1)
        }

        for (nationPair in nationEnemyPairs) {
            val nation1 = nationPair.nation1
            val nation2 = nationPair.nation2

            nation1.enemies.add(nation2)
            nation2.enemies.add(nation1)

            for (town1 in nation1.towns) {
                for (town2 in nation2.towns) {
                    town1.enemies.add(town2)
                    town2.enemies.add(town1)
                }
            }
        }

        if (Files.exists(Config.pathTruce)) {
            try {
                val truceString = String(Files.readAllBytes(Config.pathTruce))
                Truce.fromJsonString(truceString)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    // ==============================================
    // Resource Node functions
    // ==============================================

    public fun getResourceNodeCount(): Int = Nodes.resourceNodes.size

    // ==============================================
    // Territory Chunk functions
    // ==============================================
    public fun getTerritoryChunkFromBlock(blockX: Int, blockZ: Int): TerritoryChunk? {
        val coord = Coord.fromBlockCoords(blockX, blockZ)
        return Nodes.territoryChunks.get(coord)
    }

    public fun getTerritoryChunkFromCoord(coord: Coord): TerritoryChunk? = Nodes.territoryChunks.get(coord)

    // ==============================================
    // Territory functions
    // ==============================================

    public fun calculateTerritoryCost(size: Int, resourceNodes: List<String>): Int {
        var costConstant = Config.territoryCostBase
        var costScale = Config.territoryCostScale

        for (type in resourceNodes) {
            val resource = Nodes.resourceNodes.get(type)
            if (resource != null) {
                costConstant += resource.costConstant
                costScale *= resource.costScale
            }
        }

        val cost = costConstant + Math.round(costScale * size.toDouble()).toInt()

        return cost
    }

    public fun getTerritoryCount(): Int = Nodes.territories.size

    public fun getTerritoryFromId(id: TerritoryId): Territory? = Nodes.territories.get(id)

    public fun getTerritoryFromBlock(blockX: Int, blockZ: Int): Territory? {
        val coord = Coord.fromBlockCoords(blockX, blockZ)
        return Nodes.territoryChunks.get(coord)?.territory
    }

    public fun getTerritoryFromPlayer(player: Player): Territory? {
        val loc = player.getLocation()
        val coord = Coord.fromBlockCoords(loc.x.toInt(), loc.z.toInt())
        return Nodes.territoryChunks.get(coord)?.territory
    }

    public fun getTerritoryFromCoord(coord: Coord): Territory? = Nodes.territoryChunks.get(coord)?.territory

    public fun getTerritoryFromChunk(chunk: Chunk): Territory? {
        val coord = Coord(chunk.x, chunk.z)
        return Nodes.territoryChunks.get(coord)?.territory
    }

    public fun getTerritoryFromChunkCoords(cx: Int, cz: Int): Territory? {
        val coord = Coord(cx, cz)
        return Nodes.territoryChunks.get(coord)?.territory
    }

    public fun iterTerritories(): Iterable<kotlin.collections.Map.Entry<TerritoryId, Territory>> = Nodes.territories.asIterable()

    public fun getChunkFromCoord(coord: Coord, world: World): Chunk? = Bukkit.getWorld(world.name)?.getChunkAt(coord.x, coord.z)

    // Default spawn: ~center of the home core chunk, at the first air block
    public fun getDefaultSpawnLocation(territory: Territory): Location {
        val homeChunk = territory.core
        val x = homeChunk.x * 16 + 8
        val z = homeChunk.z * 16 + 8

        val world = Bukkit.getWorlds().get(0)
        var y = 255
        while (y > 0) {
            if (!world.getBlockAt(x, y, z).isEmpty()) {
                break
            }
            y -= 1
        }

        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    // ==============================================
    // Resident functions
    // ==============================================
    public fun createResident(player: Player) {
        val uuid = player.getUniqueId()
        if (!residents.containsKey(uuid)) {
            val resident = Resident(uuid, player.name)
            residents.put(uuid, resident)

            Nodes.needsSave = true
        }
    }

    // Used when deserializing towns.json
    public fun loadResident(
        uuid: UUID,
        claims: Int,
        claimsTime: Long,
        prefix: String,
        suffix: String,
        trusted: Boolean,
        townCreateCooldown: Long,
        leftNation: String? = null,
        leftNationAt: Long = 0L,
        savedName: String? = null,
    ) {
        var playerName: String? = Bukkit.getPlayer(uuid)?.let { player ->
            player.name
        } ?: run {
            Bukkit.getOfflinePlayer(uuid)?.getName()
        }
        // on shards a player may never have joined this server, so it does not know their name:
        // use the name the leader saved instead of dropping them from their town
        if (playerName == null && phonon.nodes.shard.Shards.enabled && !savedName.isNullOrBlank()) {
            playerName = savedName
        }

        if (playerName == null) {
            return
        }

        val resident = Resident(uuid, playerName)
        resident.prefix = prefix
        resident.suffix = suffix
        resident.claims = Math.min(claims, Config.playerClaimsMax) // in case config changed
        resident.claimsTime = claimsTime

        resident.trusted = trusted

        resident.needsUpdate()

        resident.townCreateCooldown = townCreateCooldown
        resident.leftNation = leftNation
        resident.leftNationAt = leftNationAt

        residents.put(uuid, resident)
    }

    public fun getResidentCount(): Int = Nodes.residents.size

    public fun getResident(player: Player): Resident? = Nodes.residents.get(player.getUniqueId())

    public fun getResidentFromName(name: String): Resident? {
        val player = Bukkit.getPlayer(name)
        if (player != null) {
            return Nodes.residents.get(player.getUniqueId())
        } else {
            val playerNameLowercase = name.lowercase()
            for (r in Nodes.residents.values) {
                if (r.name.lowercase() == playerNameLowercase) {
                    return r
                }
            }
        }

        return null
    }

    public fun getResidentFromUUID(uuid: UUID): Resident? = Nodes.residents.get(uuid)

    public fun setResidentPrefix(resident: Resident, s: String) {
        resident.prefix = sanitizeString(s)
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setResidentSuffix(resident: Resident, s: String) {
        resident.suffix = sanitizeString(s)
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setResidentOnline(resident: Resident, player: Player) {
        val town = resident.town
        if (town != null) {
            town.playersOnline.add(player)

            val nation = town.nation
            if (nation != null) {
                nation.playersOnline.add(player)
            }
        }
    }

    // Takes the player explicitly since resident.player() is null once offline
    public fun setResidentOffline(resident: Resident, player: Player) {
        val town = resident.town
        if (town != null) {
            town.playersOnline.remove(player)

            val nation = town.nation
            if (nation != null) {
                nation.playersOnline.remove(player)
            }
        }
    }

    // toggle chat mode:
    // if already in mode, return to default (global)
    // else, set to new mode
    // return chatmode this is set to
    public fun toggleChatMode(resident: Resident, mode: ChatMode): ChatMode {
        if (resident.chatMode == mode) {
            resident.chatMode = ChatMode.GLOBAL
        } else {
            resident.chatMode = mode
        }

        return resident.chatMode
    }

    public fun setResidentTownCreateCooldown(resident: Resident, cooldown: Long) {
        resident.townCreateCooldown = cooldown
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setResidentClaimTimer(resident: Resident, time: Long) {
        resident.claimsTime = time
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    public fun refreshPlayersOnline() {
        for (nation in Nodes.nations.values) {
            nation.playersOnline.clear()
        }

        for (town in Nodes.towns.values) {
            town.playersOnline.clear()
            for (r in town.residents) {
                val player = r.player()
                if (player !== null) {
                    town.playersOnline.add(player)

                    val nation = r.nation
                    if (nation !== null) {
                        nation.playersOnline.add(player)
                    }
                }
            }
        }
    }

    public fun renderMinimaps() {
        for (player in Bukkit.getOnlinePlayers()) {
            val resident = Nodes.getResident(player)
            if (resident?.minimap != null) {
                val loc = player.getLocation()
                val coordX = kotlin.math.floor(loc.x).toInt()
                val coordZ = kotlin.math.floor(loc.z).toInt()
                val coord = Coord.fromBlockCoords(coordX, coordZ)
                resident.updateMinimap(coord)
            }
        }
    }

    // ==============================================
    // Town functions
    // ==============================================

    // create town at player location:
    // - player becomes town leader
    // - territory at player's location becomes town home
    public fun createTown(name: String, territory: Territory, leader: Resident?): Result<Town> {
        // on a shard leader, a town founded from another shard starts at where its founder stands there
        val leaderPlayer = leader?.let { phonon.nodes.shard.Shards.commandPlayer(it.uuid) ?: it.player() }
        var spawnpoint = if (leaderPlayer != null) {
            leaderPlayer.location
        } else {
            Nodes.getDefaultSpawnLocation(territory)
        }

        if (Nodes.getTownFromName(name) != null) {
            return Result.failure(ErrorTownExists)
        }

        if (territory.town != null) {
            return Result.failure(ErrorTerritoryOwned)
        }

        if (leader?.town != null) {
            return Result.failure(ErrorPlayerHasTown)
        }

        val town = Town(UUID.randomUUID(), name, territory.id, leader, spawnpoint)
        town.claimsUsed = territory.cost

        territory.town = town

        if (leader != null) {
            leader.town = town

            val overClaimsPenalty: Int = Math.max(0, Config.initialOverClaimsAmountScale * (territory.cost - Config.townInitialClaims))

            leader.claims = Math.max(0, Math.max(Config.playerClaimsMax, Config.townInitialClaims) - overClaimsPenalty)
            leader.claimsTime = 0L

            if (leaderPlayer !== null) {
                Nametag.create(leaderPlayer)
            }

            leader.needsUpdate()
        }

        town.claimsMax = Nodes.calculateMaxClaims(town)

        Nodes.towns.put(name, town)
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(town)
    }

    // Used when deserializing towns.json
    public fun loadTown(
        uuid: UUID,
        name: String,
        leader: UUID?,
        homeId: Int,
        spawn: Location?,
        color: Color?,
        residents: ArrayList<UUID>,
        officers: ArrayList<UUID>,
        territoryIds: ArrayList<Int>,
        capturedTerritoryIds: ArrayList<Int>,
        annexedTerritoryIds: ArrayList<Int>,
        claimsBonus: Int,
        claimsAnnexed: Int,
        claimsPenalty: Int,
        claimsPenaltyTime: Long,
        income: EnumMap<Material, Int>,
        incomeSpawnEgg: EnumMap<EntityType, Int>,
        permissions: EnumMap<TownPermissions, EnumSet<PermissionsGroup>>,
        isOpen: Boolean,
        protectedBlocks: HashSet<Block>,
        moveHomeCooldown: Long,
        outposts: HashMap<String, Pair<Int, Location>>,
    ): Town? {
        val leaderAsResident = if (leader != null) {
            Nodes.getResidentFromUUID(leader)
        } else {
            null
        }

        val home = Nodes.getTerritoryFromId(TerritoryId(homeId))
        if (home == null) {
            System.err.println("Failed to create town $name with home (id = $homeId)")
            return null
        }

        // get spawn point — treat null or 0,0,0 as unset (editor default)
        val spawnIsUnset = spawn == null ||
            (
                spawn.blockX == 0 && spawn.blockZ == 0 && spawn.blockY == 0
                )

        val spawnpoint = if (!spawnIsUnset) {
            spawn!!
        } else {
            val homeTerritory = Nodes.territories.get(TerritoryId(homeId))
            if (homeTerritory != null) {
                Nodes.getDefaultSpawnLocation(homeTerritory)
            } else {
                Location(Bukkit.getWorlds().get(0), 0.0, 255.0, 0.0)
            }
        }

        val town = Town(uuid, name, home.id, leaderAsResident, spawnpoint)
        leaderAsResident?.town = town

        for (id in residents) {
            Nodes.getResidentFromUUID(id)?.let { r ->
                town.residents.add(r)
                r.town = town
                r.needsUpdate()
            }
        }

        for (id in officers) {
            Nodes.getResidentFromUUID(id)?.let { r ->
                town.officers.add(r)
            }
        }

        for (id in territoryIds) {
            val terrId = TerritoryId(id)
            Nodes.getTerritoryFromId(terrId)?.let { terr ->
                town.territories.add(terrId)
                terr.town = town
                town.claimsUsed += terr.cost
            }
        }

        // Already in territoryIds, so only add the ids
        for (id in annexedTerritoryIds) {
            val terrId = TerritoryId(id)
            Nodes.getTerritoryFromId(terrId)?.let { terr ->
                if (town.territories.contains(terrId)) {
                    town.annexed.add(terrId)
                    town.claimsUsed -= terr.cost
                }
            }
        }

        for (id in capturedTerritoryIds) {
            val terrId = TerritoryId(id)
            Nodes.getTerritoryFromId(terrId)?.let { terr ->
                // Shouldn't happen, but clear any existing occupier
                val currentOccupier: Town? = terr.occupier
                if (currentOccupier != null) {
                    currentOccupier.captured.remove(terrId)
                }

                town.captured.add(terrId)
                terr.occupier = town
            }
        }

        town.income.storage.putAll(income)
        town.income.storageSpawnEgg.putAll(incomeSpawnEgg)

        if (color != null) {
            town.color = color
        }

        for ((type, groups) in permissions) {
            town.permissions[type].clear()
            town.permissions[type].addAll(groups)
        }

        town.claimsBonus = claimsBonus
        town.claimsAnnexed = claimsAnnexed
        town.claimsPenalty = claimsPenalty
        town.claimsPenaltyTime = claimsPenaltyTime
        town.claimsMax = Nodes.calculateMaxClaims(town)

        town.isOpen = isOpen

        town.protectedBlocks.addAll(protectedBlocks)

        town.moveHomeCooldown = moveHomeCooldown

        for ((name, outpostData) in outposts) {
            val terrId = TerritoryId(outpostData.first)
            val spawn = outpostData.second
            val terr = Nodes.getTerritoryFromId(terrId)
            if (terr !== null && town.territories.contains(terrId)) {
                town.outposts.put(
                    name,
                    TownOutpost(
                        name,
                        terrId,
                        spawn,
                    ),
                )
            }
        }

        towns.put(name, town)

        town.needsUpdate()

        return town
    }

    public fun destroyTown(town: Town) {
        val nation = town.nation
        if (nation !== null) {
            if (nation.towns.size == 1) {
                Nodes.destroyNation(nation)
            } else {
                Nodes.removeTownFromNation(nation, town)
            }
        }

        town.territories.forEach { terrId ->
            Nodes.getTerritoryFromId(terrId)?.town = null
        }

        town.captured.forEach { terrId ->
            Nodes.getTerritoryFromId(terrId)?.occupier = null
        }

        town.residents.forEach { r ->
            r.town = null
            r.nation = null
            r.needsUpdate()

            val player = r.player()
            if (player !== null) {
                Nametag.destroy(player)

                if (nation !== null) {
                    nation.playersOnline.remove(player)
                }
            }
        }

        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }

        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }

        Nodes.towns.remove(town.name)

        Nodes.needsSave = true

        Nodes.renderMinimaps()
    }

    public fun getTownCount(): Int = Nodes.towns.size

    public fun getTownFromName(name: String): Town? = towns.get(name)

    public fun getTownFromPlayer(player: Player): Town? {
        val resident = Nodes.getResident(player)
        if (resident !== null) {
            return resident.town
        }

        return null
    }

    /**
     * If input is "*", return all towns. Otherwise, return list of
     * single town.
     */
    public fun matchTowns(name: String): List<Town> {
        val matchedTowns = ArrayList<Town>()

        if (name == "*") {
            matchedTowns.addAll(towns.values)
        } else {
            val town = getTownFromName(name)
            if (town !== null) {
                matchedTowns.add(town)
            }
        }

        return matchedTowns
    }

    public fun getTownAtChunkCoord(cx: Int, cz: Int): Town? {
        val terr = Nodes.getTerritoryFromChunkCoords(cx, cz)
        if (terr !== null) {
            return terr.town
        }

        return null
    }

    public fun setClaimsBonus(town: Town, num: Int) {
        town.claimsBonus = num
        town.claimsMax = Nodes.calculateMaxClaims(town)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setClaimsPenalty(town: Town, num: Int) {
        town.claimsPenalty = num
        town.claimsMax = Nodes.calculateMaxClaims(town)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setClaimsAnnexed(town: Town, num: Int) {
        town.claimsAnnexed = num
        town.claimsMax = Nodes.calculateMaxClaims(town)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    // Doesn't set town.claimsMax; callers assign the result
    public fun calculateMaxClaims(town: Town): Int {
        val numResidents = town.residents.size

        var maxClaims = Config.townClaimsBase - town.claimsPenalty
        for (r in town.residents) {
            maxClaims += r.claims
        }

        maxClaims += town.claimsBonus

        maxClaims -= town.claimsAnnexed

        if (Config.townClaimsMax > 0) {
            maxClaims = Math.min(Config.townClaimsMax, maxClaims)
        }

        town.isOverClaimsMax = town.claimsUsed > maxClaims

        return maxClaims
    }

    // reduces claim penalty from all towns by 1
    // scheduled by ClaimPenaltyDecayManager
    public fun claimsPenaltyDecay(dt: Long) {
        for (town in Nodes.towns.values) {
            if (town.claimsPenalty > 0) {
                val elapsedTime = town.claimsPenaltyTime + dt
                if (elapsedTime >= Config.townClaimsPenaltyDecayPeriod) {
                    town.claimsPenaltyTime = 0L
                    town.claimsPenalty = Math.max(0, town.claimsPenalty - Config.townPenaltyDecay)
                    town.claimsMax = Nodes.calculateMaxClaims(town)
                    town.needsUpdate()
                    Nodes.needsSave = true
                } else {
                    town.claimsPenaltyTime = elapsedTime
                    town.needsUpdate()
                    Nodes.needsSave = true
                }
            }
        }
    }

    // tick player claim power contributions and
    // re-calculate town max claims
    // -> apply only for online players
    public fun claimsPowerRamp(dt: Long) {
        for (player in Bukkit.getOnlinePlayers()) {
            val resident = Nodes.getResident(player)
            val town = resident?.town
            if (town !== null && resident.claims < Config.playerClaimsMax) {
                val elapsedTime = resident.claimsTime + dt
                if (elapsedTime >= Config.playerClaimsIncreasePeriod) {
                    resident.claimsTime = 0L
                    resident.claims = Math.min(Config.playerClaimsMax, resident.claims + Config.playerClaimsIncrease)
                    town.claimsMax = Nodes.calculateMaxClaims(town)
                    resident.needsUpdate()
                    town.needsUpdate()
                    Nodes.needsSave = true
                } else {
                    resident.claimsTime = elapsedTime
                    resident.needsUpdate()
                    Nodes.needsSave = true
                }
            }
        }
    }

    /**
     * Runs in bukkit task periodically, sends message to town
     * players if their town is over max claims
     */
    public fun overMaxClaimsReminder() {
        if (!Config.overClaimsPenalty) {
            return
        }

        val resourcePenalty = "${(Config.overClaimsMaxPenalty * 100).toInt()}% resource penalty"

        for (town in Nodes.towns.values) {
            if (town.isOverClaimsMax) {
                val msg = "${ChatColor.DARK_RED}Your town is over max claims: ${town.claimsUsed}/${town.claimsMax}, you have a $resourcePenalty"
                for (r in town.residents) {
                    val player = r.onlinePlayer()
                    if (player !== null) {
                        Message.print(player, msg)
                    }
                }
            }
        }
    }

    /**
     * Claim territory for a town. Returns result with either Territory
     * if successful, or an TerritoryClaim error status.
     */
    public fun claimTerritory(town: Town, territory: Territory): Result<Territory> {
        if (territory.town != null) {
            return Result.failure(ErrorTerritoryHasClaim)
        }

        var isNeighbor = false
        for (neighborId in territory.neighbors) {
            if (Nodes.getTerritoryFromId(neighborId)?.town === town) {
                isNeighbor = true
                break
            }
        }
        if (!isNeighbor) {
            return Result.failure(ErrorTerritoryNotConnected)
        }

        if (!Config.overClaimsAllowClaim && territory.cost > town.claimsMax - town.claimsUsed) {
            return Result.failure(ErrorTooManyClaims)
        }

        town.territories.add(territory.id)
        territory.town = town

        town.claimsUsed += territory.cost

        town.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(territory)
    }

    public fun unclaimTerritory(town: Town, territory: Territory): Result<Territory> {
        if (!town.territories.contains(territory.id)) {
            return Result.failure(ErrorTerritoryNotInTown)
        }

        if (town.home == territory.id) {
            return Result.failure(ErrorTerritoryIsTownHome)
        }

        town.territories.remove(territory.id)
        territory.town = null

        town.outposts.entries.removeAll({ (name, outpost) -> outpost.territory == territory.id })

        // if territory was not annexed, remove territory cost
        // from claims used, add to penalty until it decays
        if (!town.annexed.contains(territory.id)) {
            town.claimsUsed -= territory.cost
            town.claimsPenalty += territory.cost
            town.claimsMax = Nodes.calculateMaxClaims(town)
        } else {
            town.annexed.remove(territory.id)
        }

        town.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(territory)
    }

    // adds a territory to town and bypasses standard claim checks
    // (e.g. territory must be connected, town has claims available, ...)
    // if successful, returns added territory
    public fun addTerritoryToTown(town: Town, territory: Territory): Result<Territory> {
        if (territory.town != null) {
            return Result.failure(ErrorTerritoryOwned)
        }

        town.territories.add(territory.id)
        territory.town = town

        town.claimsUsed += territory.cost

        town.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(territory)
    }

    public fun captureTerritory(town: Town, territory: Territory) {
        val currentOccupier: Town? = territory.occupier
        if (currentOccupier != null) {
            currentOccupier.captured.remove(territory.id)
            territory.occupier = null

            currentOccupier.needsUpdate()
        }

        if (territory.town != town) {
            town.captured.add(territory.id)
            territory.occupier = town
        }

        town.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()
    }

    public fun releaseTerritory(territory: Territory) {
        val currentOccupier: Town? = territory.occupier
        if (currentOccupier != null) {
            currentOccupier.captured.remove(territory.id)
            territory.occupier = null
            currentOccupier.needsUpdate()
        }

        for (coord in territory.chunks) {
            val tc = Nodes.getTerritoryChunkFromCoord(coord) ?: continue
            tc.occupier = null
            tc.attacker = null
            try {
                phonon.nodes.war.FlagWar.chunkToAttacker[tc.coord]?.cancel()
                phonon.nodes.war.FlagWar.occupiedChunks.remove(tc.coord)
            } catch (_: Exception) {
            }
        }

        Nodes.needsSave = true
        Nodes.renderMinimaps()
    }

    /**
     * Town annexes a territory:
     * - add to town's territories and town's annexed territories
     * - does not cost claim power (no need to recalculate max claims)
     * Returns boolean on success
     */
    public fun annexTerritory(town: Town, territory: Territory): Boolean {
        val occupier: Town? = territory.occupier
        if (occupier !== town) {
            return false
        }

        val oldTown = territory.town
        if (oldTown === town) {
            return false
        }

        if (oldTown !== null) {
            if (territory.id == oldTown.home && !phonon.nodes.war.Warzone.isWarzoneTown(oldTown)) { // Capturing home annexes the entire town: transfer all territories, then destroy the town.
                val territoriesToTransfer = oldTown.territories.toList()

                for (terrId in territoriesToTransfer) {
                    val t = Nodes.getTerritoryFromId(terrId) ?: continue
                    if (t.town !== oldTown) continue

                    oldTown.territories.remove(terrId)
                    oldTown.annexed.remove(terrId)
                    oldTown.captured.remove(terrId)

                    town.territories.add(terrId)
                    town.annexed.add(terrId)
                    town.captured.remove(terrId)

                    t.town = town
                    t.occupier = null
                }
                town.claimsMax = Nodes.calculateMaxClaims(town)
// Plunder: move the defeated town's income to the annexer
                oldTown.income.pushToStorage(force = true) // flush GUI into storage maps if open
                for ((mat, amount) in oldTown.income.storage.toMap()) {
                    if (amount > 0) {
                        town.income.add(mat, amount)
                    }
                }
                for ((egg, amount) in oldTown.income.storageSpawnEgg.toMap()) {
                    if (amount > 0) {
                        town.income.storageSpawnEgg[egg] =
                            (town.income.storageSpawnEgg[egg] ?: 0) + amount
                    }
                }
                oldTown.income.storage.clear()
                oldTown.income.storageSpawnEgg.clear()

                Message.broadcast(
                    "${ChatColor.GOLD}[War] ${town.name} plundered ${oldTown.name}'s town!",
                )

                Nodes.destroyTown(oldTown)

                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Town ${oldTown.name} was completely annexed by ${town.name}")
            } else {
                if (oldTown.annexed.contains(territory.id)) {
                    oldTown.annexed.remove(territory.id)
                } else {
                    oldTown.claimsUsed -= territory.cost
                    oldTown.claimsAnnexed += territory.cost
                }

                oldTown.territories.remove(territory.id)
                oldTown.claimsMax = Nodes.calculateMaxClaims(town)

                oldTown.needsUpdate()
            }
        }

        town.territories.add(territory.id)
        town.annexed.add(territory.id)
        town.captured.remove(territory.id)

        territory.town = town
        territory.occupier = null

        town.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return true
    }

    public fun addToIncome(town: Town, material: Material, amount: Int, meta: Int = 0) {
        town.income.add(material, amount, meta)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Removes items from town's income. If Material is null, removes all
     * items. If amount < 0, removes all items of that type.
     */
    public fun townIncomeRemove(
        town: Town,
        material: Material?,
        amount: Int = -1,
    ) {
        town.income.pushToStorage(force = true) // push items to storage before removing

        if (material !== null) {
            if (amount >= 0) {
                val currAmount = town.income.storage.get(material) ?: 0
                if (currAmount > amount) {
                    town.income.storage.put(material, currAmount - amount)
                } else {
                    town.income.storage.remove(material)
                }
            } else {
                town.income.storage.remove(material)
            }
        } else {
            town.income.storage.clear()
        }
        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setTownColor(town: Town, r: Int, g: Int, b: Int) {
        town.color = Color(r, g, b)
        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setTownSpawn(town: Town, spawnpoint: Location): Boolean {
        val territory = Nodes.getTerritoryFromChunk(spawnpoint.chunk)
        if (territory === null || territory.id != town.home) {
            return false
        }

        town.spawnpoint = spawnpoint
        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    /**
     * Remember that this resident left (or was kicked from) a town in a nation, so
     * Config.nationJoinCooldown can stop them hopping straight into another nation.
     */
    public fun markResidentLeftNation(resident: Resident, town: Town) {
        val nation = town.nation ?: return
        resident.leftNation = nation.name
        resident.leftNationAt = System.currentTimeMillis()
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    /** Milliseconds left before this resident may join a town of [nation], 0 if allowed. */
    public fun nationJoinCooldownLeft(resident: Resident, nation: Nation): Long {
        val cooldown = Config.nationJoinCooldown
        val left = resident.leftNation
        if (cooldown <= 0L || left == null || left == nation.name) {
            return 0L
        }
        return Math.max(0L, resident.leftNationAt + cooldown - System.currentTimeMillis())
    }

    public fun addResidentToTown(town: Town, resident: Resident, ignoreCooldown: Boolean = false): Boolean {
        val nation = town.nation
        val player = resident.player()
        if (nation != null && !ignoreCooldown) {
            val wait = nationJoinCooldownLeft(resident, nation)
            if (wait > 0L) {
                val target = player ?: resident.onlinePlayer()
                if (target != null) {
                    val totalMin = (wait + 59_999L) / 60_000L
                    val d = totalMin / 1440
                    val h = (totalMin % 1440) / 60
                    val m = totalMin % 60
                    val left = when {
                        d > 0 -> "${d}d ${h}h"
                        h > 0 -> "${h}h ${m}m"
                        else -> "${m}m"
                    }
                    Message.error(target, "You left a nation recently and cannot join another nation's town for $left")
                }
                return false
            }
        }
        if (nation != null && player != null) {
            if (!RallyCap.canJoinNationTown(nation, player)) {
                Message.error(player, RallyCap.denyMessage(nation))
                return false
            }
        }

        town.residents.add(resident)
        resident.town = town

        resident.claims = Config.playerClaimsInitial
        resident.claimsTime = 0L
        town.claimsMax = Nodes.calculateMaxClaims(town)

        resident.trusted = false

        if (player !== null) {
            town.playersOnline.add(player)
        }

        if (nation !== null) {
            resident.nation = nation
            nation.residents.add(resident)

            if (player !== null) {
                nation.playersOnline.add(player)
            }
        }

        town.needsUpdate()
        resident.needsUpdate()
        Nodes.needsSave = true

        if (player !== null) {
            Nametag.create(player)
        }
        return true
    }

    public fun removeResidentFromTown(town: Town, resident: Resident) {
        if (town.officers.contains(resident)) {
            town.officers.remove(resident)
        }
        town.residents.remove(resident)
        resident.town = null
        phonon.nodes.plot.Plots.onResidentLeave(resident.uuid)

        val player = resident.player()

        val nation = town.nation
        if (nation !== null) {
            resident.nation = null
            nation.residents.remove(resident)

            if (player !== null) {
                nation.playersOnline.remove(player)
            }
        }

        town.claimsMax = Nodes.calculateMaxClaims(town)

        if (player !== null) {
            town.playersOnline.remove(player)
        }

        town.needsUpdate()
        resident.needsUpdate()
        Nodes.needsSave = true

        if (player !== null) {
            Nametag.destroy(player)
        }
    }

    public fun townAddOfficer(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }

        if (town.officers.contains(resident)) {
            return true
        }

        town.officers.add(resident)

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    public fun townRemoveOfficer(town: Town, resident: Resident): Boolean {
        if (resident.town !== town) {
            return false
        }

        town.officers.remove(resident)

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    public fun playerIsOfficer(town: Town, player: Player): Boolean {
        val resident = Nodes.getResident(player)
        if (resident === town.leader || town.officers.contains(resident)) {
            return true
        }
        return false
    }

    /**
     * Set town's leader. If input resident is null, try to remove
     * current town leader.
     */
    public fun townSetLeader(town: Town, resident: Resident?) {
        if (resident !== null) {
            if (resident.town !== town) {
                return
            }

            if (town.leader === resident) {
                return
            }

            town.officers.remove(resident)

            town.leader = resident
            town.needsUpdate()
            Nodes.needsSave = true
        } else {
            if (town.leader === null) {
                return
            }

            town.leader = null
            town.needsUpdate()
            Nodes.needsSave = true
        }
    }

    public fun renameTown(town: Town, s: String): Boolean {
        if (Nodes.towns.contains(s)) {
            return false
        }

        Nodes.towns.remove(town.name)
        town.name = s
        town.updateNametags()

        Nodes.towns.put(s, town)
        town.needsUpdate()

        town.nation?.needsUpdate()
        for (r in town.residents) {
            r.needsUpdate()
        }

        for (t in town.enemies) {
            t.needsUpdate()
        }
        for (t in town.allies) {
            t.needsUpdate()
        }

        Nodes.needsSave = true

        return true
    }

    public fun getTownIncomeInventory(town: Town): Inventory {
        // mark dirty if inventory not empty (player could take items)
        if (!town.income.empty()) {
            town.needsUpdate()
        }
        return town.income.getInventory()
    }

    public fun setTownPermissions(town: Town, perm: TownPermissions, group: PermissionsGroup, flag: Boolean) {
        if (flag == true) {
            town.permissions[perm].add(group)
        } else {
            town.permissions[perm].remove(group)
        }

        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setTownHomeTerritory(town: Town, territory: Territory) {
        if (town !== territory.town) {
            return
        }
        if (town.home == territory.id) {
            return
        }

        town.home = territory.id

        town.spawnpoint = Nodes.getDefaultSpawnLocation(territory)

        town.moveHomeCooldown = Config.townMoveHomeCooldown

        Nodes.renderMinimaps()

        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun setTownHomeMoveCooldown(town: Town, time: Long) {
        town.moveHomeCooldown = time
        town.needsUpdate()
        Nodes.needsSave = true
    }

    public fun createTownOutpost(town: Town, name: String, territory: Territory): Boolean {
        if (!town.territories.contains(territory.id)) {
            return false
        }

        if (town.outposts.contains(name)) {
            return false
        }

        val spawn = Nodes.getDefaultSpawnLocation(territory)

        town.outposts.put(
            name,
            TownOutpost(
                name,
                territory.id,
                spawn,
            ),
        )

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    public fun destroyTownOutpost(town: Town, name: String): Boolean {
        val outpost = town.outposts.remove(name)
        if (outpost === null) {
            return false
        }

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    public fun setOutpostSpawn(town: Town, outpost: TownOutpost, spawn: Location): Boolean {
        if (Nodes.getTerritoryFromChunk(spawn.chunk)?.id != outpost.territory) {
            return false
        }

        outpost.spawn = spawn

        town.needsUpdate()
        Nodes.needsSave = true

        return true
    }

    // Items may have been moved, so require a save
    internal fun onTownIncomeInventoryClose() {
        Nodes.needsSave = true
    }

    internal fun setTownOpen(town: Town, isOpen: Boolean) {
        town.isOpen = isOpen
        town.needsUpdate()
        Nodes.needsSave = true
    }

    internal fun townMoveHomeCooldownTick(dt: Long) {
        for (town in Nodes.towns.values) {
            if (town.moveHomeCooldown > 0) {
                town.moveHomeCooldown = Math.max(0, town.moveHomeCooldown - dt)

                town.needsUpdate()
                Nodes.needsSave = true
            }
        }
    }

    internal fun residentTownCreateCooldownTick(dt: Long) {
        for (resident in Nodes.residents.values) {
            if (resident.townCreateCooldown > 0) {
                resident.townCreateCooldown = Math.max(0, resident.townCreateCooldown - dt)

                resident.needsUpdate()
                Nodes.needsSave = true
            }
        }
    }

    // ==============================================
    // Nation functions
    // ==============================================
    public fun createNation(name: String, town: Town, leader: Resident? = null): Result<Nation> {
        if (town.nation != null) {
            return Result.failure(ErrorTownHasNation)
        }

        if (leader?.nation != null) {
            return Result.failure(ErrorPlayerHasNation)
        }

        if (leader != null && !town.residents.contains(leader)) {
            return Result.failure(ErrorPlayerNotInTown)
        }

        if (Nodes.getNationFromName(name) != null) {
            return Result.failure(ErrorNationExists)
        }

        val nation = Nation(UUID.randomUUID(), name, town)
        nations.put(name, nation)

        nation.towns.add(town)
        town.nation = nation

        // remove all pre-existing town alliances, but do not change enemies
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        town.allies.clear()

        for (r in town.residents) {
            r.nation = nation
            r.needsUpdate()
        }

        town.needsUpdate()
        nation.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(nation)
    }

    // Used when deserializing towns.json
    public fun loadNation(
        uuid: UUID,
        name: String,
        capitalName: String,
        color: Color?,
        towns: ArrayList<String>,
    ): Nation {
        val capital = Nodes.getTownFromName(capitalName)
        if (capital == null) {
            throw ErrorTownDoesNotExist
        }

        val nation = Nation(uuid, name, capital)

        if (color != null) {
            nation.color = color
        }

        for (townName in towns) {
            val town = Nodes.getTownFromName(townName)
            if (town != null) {
                nation.towns.add(town)
                town.nation = nation
                town.needsUpdate()

                for (r in town.residents) {
                    r.nation = nation
                    nation.residents.add(r)
                    r.needsUpdate()
                }
            }
        }

        nation.needsUpdate()

        nations.put(name, nation)

        return nation
    }

    public fun destroyNation(nation: Nation) {
        for (ally in nation.allies) {
            ally.allies.remove(nation)
            ally.needsUpdate()
        }
        for (enemy in nation.enemies) {
            enemy.enemies.remove(nation)
            enemy.needsUpdate()
        }

        for (town in nation.towns) {
            for (r in town.residents) {
                r.nation = null
                r.needsUpdate()
            }

            for (ally in town.allies) {
                ally.allies.remove(town)
                ally.needsUpdate()
            }
            town.allies.clear()

            for (enemy in town.enemies) {
                enemy.enemies.remove(town)
                enemy.needsUpdate()
            }
            town.enemies.clear()

            town.nation = null
            town.needsUpdate()
        }

        Nodes.nations.remove(nation.name)

        Nodes.needsSave = true

        Nodes.renderMinimaps()
    }

    public fun getNationCount(): Int = Nodes.nations.size

    public fun getNationFromName(name: String): Nation? = nations.get(name)

    public fun addTownToNation(nation: Nation, town: Town): Result<Town> {
        if (town.nation != null) {
            return Result.failure(ErrorTownHasNation)
        }

        nation.towns.add(town)
        town.nation = nation
        town.needsUpdate()

        for (r in town.residents) {
            r.nation = nation
            nation.residents.add(r)
            val player = r.player()
            if (player !== null) {
                nation.playersOnline.add(player)
            }
            r.needsUpdate()
        }

        // remove current town alliances and enemies, set equal to nation capital
        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }

        town.allies.clear()
        town.enemies.clear()

        for (ally in nation.capital.allies) {
            town.allies.add(ally)
            ally.allies.add(town)
            ally.needsUpdate()
        }
        for (enemy in nation.capital.enemies) {
            town.enemies.add(enemy)
            enemy.enemies.add(town)
            enemy.needsUpdate()
        }

        nation.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(town)
    }

    public fun removeTownFromNation(nation: Nation, town: Town): Result<Town> {
        if (town.nation !== nation) {
            return Result.failure(ErrorNationDoesNotHaveTown)
        }

        nation.towns.remove(town)
        town.nation = null

        for (r in town.residents) {
            r.nation = null
            nation.residents.remove(r)
            r.needsUpdate()
        }

        // if nation has no more towns, destroy nation
        // -> cleans up allies, enemies, etc... and global references
        if (nation.towns.size == 0) {
            Nodes.destroyNation(nation)
        } else {
            if (town === nation.capital) {
                val newCapital: Town = nation.towns.first()
                nation.capital = newCapital
                for (r in newCapital.residents) {
                    val player = r.onlinePlayer()
                    if (player !== null) {
                        Message.print(player, "Your town is now the capital of ${nation.name}")
                    }
                }
            }
        }

        for (ally in town.allies) {
            ally.allies.remove(town)
            ally.needsUpdate()
        }
        town.allies.clear()

        for (enemy in town.enemies) {
            enemy.enemies.remove(town)
            enemy.needsUpdate()
        }
        town.enemies.clear()

        town.needsUpdate()
        nation.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(town)
    }

    public fun setNationColor(nation: Nation, r: Int, g: Int, b: Int) {
        nation.color = Color(r, g, b)
        nation.needsUpdate()
        Nodes.needsSave = true
    }

    public fun renameNation(nation: Nation, s: String): Boolean {
        if (Nodes.nations.contains(s)) {
            return false
        }

        Nodes.nations.remove(nation.name)
        nation.name = s
        Nodes.nations.put(s, nation)
        nation.needsUpdate()

        for (town in nation.towns) {
            town.needsUpdate()
            for (r in town.residents) {
                r.needsUpdate()
            }
        }

        for (n in nation.enemies) {
            n.needsUpdate()
        }
        for (n in nation.allies) {
            n.needsUpdate()
        }

        Nodes.needsSave = true

        return true
    }

    /**
     * Set nation capital to a new town. Town must be in the nation already
     */
    public fun setNationCapital(nation: Nation, town: Town) {
        if (town.nation !== nation || nation.capital === town) {
            return
        }

        nation.capital = town

        nation.needsUpdate()
        Nodes.needsSave = true
    }

    // ==============================================
    // Territory income cycle functions
    // ==============================================

    /**
     * System to run income from all town territories and deposit items into
     * a town's income inventory chest. For a town's occupied territories,
     * it gives the income to the occupier town. This must also handle
     * adjusting net income rates based on a town's over max claims penalty.
     * Strategy for income:
     *     for each town:
     *         // 1. construct hashmap of each town name mapped to an enum map of
     *         //    material mapped to net income to be given
     *         townIncomes = HashMap<Town, EnumMap<Material, Double>>
     *
     *         // 2. accumulate net income from each territory into the town
     *         //    incomes hashmap. for occupied territories, create a new
     *         //    town entry if it doesn't exist.
     *         for territory in town:
     *             doTownIncomeLogic(territory)
     *
     *         // 3. apply over max claims penalty, other modifiers, etc.
     *         //    to each town's income to be given
     *         townIncomes.forEach { town, income -> doTownIncomeModifiers(town, income) }
     *
     *         // 4. add incomes to each town's income chests
     *         townIncomes.forEach { town, income -> addTownIncomeToChest(town, income) }
     */
    public fun runIncome() {
        /**
         * Helper to convert an income item rate Double to a item count as Int.
         * The rate must be >0.0 but can have fractional parts which allow for
         * random rolls for item amount. Examples for handling rates:
         * - rate = 2.0 : 2 items
         * - rate = 2.5 : split into 2.0 + 0.5
         *      - 2.0 -> 2 items are guaranteed
         *      - 0.5 -> do random roll, if roll < 0.5, add 1 item (50% chance)
         */
        fun rateToAmount(rate: Double): Int {
            if (rate <= 0.0) {
                return 0
            }

            val intPart = kotlin.math.floor(rate)
            val fracPart = kotlin.math.max(0.0, rate - intPart)

            val fracAmount = if (fracPart > 0.0) {
                val roll = ThreadLocalRandom.current().nextDouble()
                if (roll < fracPart) {
                    1
                } else {
                    0
                }
            } else {
                0
            }

            return intPart.toInt() + fracAmount
        }

        val taxRate = Config.taxIncomeRate.coerceIn(0.0, 1.0)
        val keptRate = 1.0 - taxRate

        for (town in Nodes.towns.values) {
            try {
                val thisTownIncome = EnumMap<Material, Double>(Material::class.java)
                val townIncomes = HashMap<Town, EnumMap<Material, Double>>()

                // inject this town, to unify claims penalty logic later
                townIncomes[town] = thisTownIncome

                val cutOff = SupplyLines.cutOff(town)
                val cutOffSet = cutOff.toHashSet()
                val cutMultiplier = SupplyLines.incomeMultiplier()

                for (terrId in town.territories) {
                    val territory = Nodes.getTerritoryFromId(terrId)
                    if (territory === null) {
                        continue
                    }

                    val supply = if (terrId in cutOffSet) cutMultiplier else 1.0

                    val occupier = territory.occupier
                    if (occupier != null) {
                        val occupierIncome = townIncomes.getOrPut(occupier) { EnumMap<Material, Double>(Material::class.java) }

                        for ((material, amount) in territory.income) {
                            occupierIncome[material] = (occupierIncome[material] ?: 0.0) + (amount * taxRate)
                            thisTownIncome[material] = (thisTownIncome[material] ?: 0.0) + (amount * keptRate * supply)
                        }
                    } else {
                        for ((material, amount) in territory.income) {
                            thisTownIncome[material] = (thisTownIncome[material] ?: 0.0) + (amount * supply)
                        }
                    }
                }

                SupplyLines.notifyCutOff(town, cutOff)

                for ((townForIncome, income) in townIncomes) {
                    var incomeModifier = 1.0

                    if (Config.overClaimsPenalty && townForIncome.isOverClaimsMax == true) {
                        incomeModifier *= (1.0 - Config.overClaimsMaxPenalty)
                    }

                    if (Config.incomeScaleByClaimPower) {
                        val claimsUsedRatio = townForIncome.claimsMax.toDouble() / townForIncome.claimsUsed.toDouble()
                        val incomeClaimsPowerScale = claimsUsedRatio.coerceIn(Config.incomeScaleMin, Config.incomeScaleMax)
                        incomeModifier *= incomeClaimsPowerScale
                    }

                    val overlord = if (townForIncome === town) Vassals.overlordOf(town) else null
                    val tribute = if (overlord != null) Vassals.tributeRate(town) else 0.0

                    for ((material, amount) in income) {
                        if (overlord != null && tribute > 0.0) {
                            val toOverlord = rateToAmount(amount * incomeModifier * tribute)
                            if (toOverlord > 0) {
                                Nodes.addToIncome(overlord, material, toOverlord)
                            }
                            val kept = rateToAmount(amount * incomeModifier * (1.0 - tribute))
                            if (kept > 0) {
                                Nodes.addToIncome(town, material, kept)
                            }
                            continue
                        }
                        val amountInt = rateToAmount(amount * incomeModifier)
                        if (amountInt > 0) {
                            Nodes.addToIncome(town, material, amountInt)
                        }
                    }
                }

                Fortifications.chargeUpkeep(town)
            } catch (err: Exception) {
                Nodes.logger?.severe("Error running income for town ${town.name}")
                err.printStackTrace()
            }
        }

        Message.broadcast("Towns have collected income (use \"/t income\" to get)")
    }

    // ==============================================
    // Handle war and diplomatic relations
    //
    // Rules for declaring war:
    // 1. who can declare war:
    //    - town without nation: town
    //    - town with nation:
    //       - capital town declare war against other towns
    //       - internal town declare war on other towns in nation
    //    - cannot war an ally or town/nation with truce
    // 2. war on enemy town:
    //    - if enemy town has no nation, only war against town
    //    - if enemy town has nation, war defaults against enemy nation
    //    - if enemy town is in your nation, civil war boogaloo
    // 3. war on enemy nation:
    //    - all towns in enemy nation become enemies of all
    //      towns in your nation
    //
    // Same rules apply for making allies.
    // ==============================================

    public fun enableWar(
        canAnnexTerritories: Boolean,
        canOnlyAttackBorders: Boolean,
        destructionEnabled: Boolean,
    ) {
        Nodes.war.enable(canAnnexTerritories, canOnlyAttackBorders, destructionEnabled)
    }

    public fun disableWar() {
        Nodes.war.disable()

        Nodes.renderMinimaps()
    }

    /**
     * Set two towns as enemies and their nations if needed, order does not matter.
     * This should be the main function used.
     */
    public fun addEnemy(town: Town, enemy: Town): Result<Boolean> {
        if (town.allies.contains(enemy) || Truce.contains(town, enemy)) {
            return Result.failure(ErrorWarAllyOrTruce)
        }

        if (town.enemies.contains(enemy) || enemy.enemies.contains(town)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        val townNation = town.nation
        val enemyNation = enemy.nation

        if (townNation !== null) {
            if (enemyNation !== null) {
                if (enemyNation === townNation) {
                    town.enemies.add(enemy)
                    enemy.enemies.add(town)

                    town.allies.remove(enemy)
                    enemy.allies.remove(town)
                } else {
                    if (town === townNation.capital) { // only nation leading town declare war
                        return nationAddEnemy(townNation, enemyNation)
                    }
                }
            } else {
                for (t in townNation.towns) {
                    t.enemies.add(enemy)
                    enemy.enemies.add(t)

                    t.needsUpdate()
                }
            }
        } else {
            if (enemyNation !== null) {
                for (t in enemyNation.towns) {
                    t.enemies.add(town)
                    town.enemies.add(t)

                    t.needsUpdate()
                }
            } else {
                town.enemies.add(enemy)
                enemy.enemies.add(town)
            }
        }

        town.needsUpdate()
        enemy.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    /**
     * Removes enemy status between two towns (and nations if needed)
     * Order does not matter.
     */
    public fun removeEnemy(town: Town, enemy: Town): Result<Boolean> {
        val townNation = town.nation
        val enemyNation = enemy.nation

        if (townNation !== null) {
            if (enemyNation !== null) {
                if (enemyNation === townNation) {
                    town.enemies.remove(enemy)
                    enemy.enemies.remove(town)

                    town.allies.add(enemy)
                    enemy.allies.add(town)
                } else {
                    return nationRemoveEnemy(townNation, enemyNation)
                }
            } else {
                for (t in townNation.towns) {
                    t.enemies.remove(enemy)
                    enemy.enemies.remove(t)

                    t.needsUpdate()
                }
            }
        } else {
            if (enemyNation !== null) {
                for (t in enemyNation.towns) {
                    t.enemies.remove(town)
                    town.enemies.remove(t)

                    t.needsUpdate()
                }
            } else {
                town.enemies.remove(enemy)
                enemy.enemies.remove(town)
            }
        }

        town.needsUpdate()
        enemy.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    /**
     * Set two towns as allies (bidirectional), order does not matter.
     * Handle town-town, town-nation, nation-town, nation-nation cases
     */
    public fun addAlly(town: Town, other: Town): Result<Boolean> {
        val pm = Bukkit.getPluginManager()

        if (town.allies.contains(other) && other.allies.contains(town)) {
            return Result.failure(ErrorAlreadyAllies)
        }

        if (town.enemies.contains(other) || other.enemies.contains(town)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            if (otherNation !== null && townNation !== otherNation) {
                Nodes.nationAddAlly(townNation, otherNation)
            } else {
                for (t in townNation.towns) {
                    t.allies.add(other)
                    other.allies.add(town)

                    t.needsUpdate()
                    pm.callEvent(AllianceCreatedEvent(t, other))
                }
            }
        } else {
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    t.allies.add(town)
                    town.allies.add(t)

                    t.needsUpdate()
                    pm.callEvent(AllianceCreatedEvent(town, t))
                }
            } else {
                town.allies.add(other)
                other.allies.add(town)
                pm.callEvent(AllianceCreatedEvent(town, other))
            }
        }

        town.needsUpdate()
        other.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    public fun removeAlly(town: Town, other: Town): Result<Boolean> {
        if (!town.allies.contains(other) || !other.allies.contains(town)) {
            return Result.failure(ErrorNotAllies)
        }

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            if (otherNation !== null && townNation !== otherNation) {
                Nodes.nationRemoveAlly(townNation, otherNation)
            } else {
                for (t in townNation.towns) {
                    t.allies.remove(other)
                    other.allies.remove(town)

                    t.needsUpdate()
                }
            }
        } else {
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    t.allies.remove(town)
                    town.allies.remove(t)

                    t.needsUpdate()
                }
            } else {
                town.allies.remove(other)
                other.allies.remove(town)
            }
        }

        town.needsUpdate()
        other.needsUpdate()
        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    public fun addTruce(town: Town, other: Town): Result<Boolean> {
        if (Truce.contains(town, other)) {
            return Result.failure(ErrorAlreadyTruce)
        }

        val time = System.currentTimeMillis()

        val townNation = town.nation
        val otherNation = other.nation

        if (townNation !== null) {
            if (otherNation !== null && townNation !== otherNation) {
                for (t in townNation.towns) {
                    for (other in otherNation.towns) {
                        Truce.create(t, other, time)
                    }
                }
            } else {
                for (t in townNation.towns) {
                    Truce.create(t, other, time)
                }
            }
        } else {
            if (otherNation !== null) {
                for (t in otherNation.towns) {
                    Truce.create(town, t, time)
                }
            } else {
                Truce.create(town, other, time)
            }
        }

        return Result.success(true)
    }

    /**
     * Truces all handled between town pairs, so removeTruce should only
     * affect towns involved and not nations.
     *
     * addTruce creates truces for all town pairs in nations,
     * truce expiration tick will call this removeTruce for all those
     * town pairs individually.
     */
    public fun removeTruce(town: Town, other: Town): Result<Boolean> {
        Truce.remove(town, other)

        Bukkit.getPluginManager().callEvent(TruceExpiredEvent(town, other))

        return Result.success(true)
    }

    // set two nations as enemies (bidirectional), order does not matter
    // -> sets all towns in each nation as enemies
    // returns true on success
    private fun nationAddEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
        if (nation.allies.contains(enemy) || Truce.contains(nation.capital, enemy.capital)) {
            return Result.failure(ErrorWarAllyOrTruce)
        }

        if (nation.enemies.contains(enemy) && enemy.enemies.contains(nation)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        nation.enemies.add(enemy)
        enemy.enemies.add(nation)

        for (nationTown in nation.towns) {
            for (enemyTown in enemy.towns) {
                nationTown.enemies.add(enemyTown)
                enemyTown.enemies.add(nationTown)

                enemyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    private fun nationRemoveEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
        nation.enemies.remove(enemy)
        enemy.enemies.remove(nation)

        for (nationTown in nation.towns) {
            for (enemyTown in enemy.towns) {
                nationTown.enemies.remove(enemyTown)
                enemyTown.enemies.remove(nationTown)

                enemyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    private fun nationAddAlly(nation: Nation, ally: Nation): Result<Boolean> {
        val pm = Bukkit.getPluginManager()

        if (nation.enemies.contains(ally) || ally.enemies.contains(nation)) {
            return Result.failure(ErrorAlreadyEnemies)
        }

        if (nation.allies.contains(ally) && ally.allies.contains(nation)) {
            return Result.failure(ErrorAlreadyAllies)
        }

        nation.allies.add(ally)
        ally.allies.add(nation)

        for (nationTown in nation.towns) {
            for (allyTown in ally.towns) {
                nationTown.allies.add(allyTown)
                allyTown.allies.add(nationTown)

                allyTown.needsUpdate()

                pm.callEvent(AllianceCreatedEvent(nationTown, allyTown))
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    private fun nationRemoveAlly(nation: Nation, ally: Nation): Result<Boolean> {
        nation.allies.remove(ally)
        ally.allies.remove(nation)

        for (nationTown in nation.towns) {
            for (allyTown in ally.towns) {
                nationTown.allies.remove(allyTown)
                allyTown.allies.remove(nationTown)

                allyTown.needsUpdate()
            }
            nationTown.needsUpdate()
        }

        Nodes.needsSave = true

        Nodes.renderMinimaps()

        return Result.success(true)
    }

    public fun truceTick() {
        val time = System.currentTimeMillis()

        val expired: List<TownPair> = Truce.truces.asSequence()
            .filter({ (towns, startTime) ->
                (time - startTime) > Config.trucePeriod
            })
            .map({ (towns, startTime) ->
                towns
            })
            .toList()

        for (towns in expired) {
            Nodes.removeTruce(towns.town1, towns.town2)
        }

        if (Truce.needsUpdate == true) {
            Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, Truce.saveTask())
            Truce.needsUpdate = false
        }
    }

    /**
     * Save truces to json file. This is separate from world state save
     * because truces only need to be saved infrequently on a separate
     * schedule than regular world save.
     */
    public fun saveTruce(async: Boolean = false) {
        if (async) {
            Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, Truce.saveTask())
        } else {
            Truce.saveTask().run()
        }
    }

    public fun getRelationshipOfTownToTown(playerTown: Town?, otherTown: Town?): DiplomaticRelationship {
        if (playerTown !== null && otherTown !== null) {
            if (playerTown === otherTown) {
                return DiplomaticRelationship.TOWN
            }

            val playerNation = playerTown.nation
            val otherNation = otherTown.nation
            if (playerNation !== null && playerNation === otherNation) {
                return DiplomaticRelationship.NATION
            }

            if (playerTown.allies.contains(otherTown)) {
                return DiplomaticRelationship.ALLY
            }

            if (playerTown.enemies.contains(otherTown)) {
                return DiplomaticRelationship.ENEMY
            }
        }

        return DiplomaticRelationship.NEUTRAL
    }

    public fun getRelationshipOfPlayerToTown(player: Player, otherTown: Town): DiplomaticRelationship {
        val playerTown = Nodes.getTownFromPlayer(player)
        return getRelationshipOfTownToTown(playerTown, otherTown)
    }

    public fun getRelationshipOfPlayerToPlayer(player: Player, other: Player): DiplomaticRelationship {
        val playerTown = Nodes.getTownFromPlayer(player)
        val otherTown = Nodes.getTownFromPlayer(other)
        return getRelationshipOfTownToTown(playerTown, otherTown)
    }

    // ==============================================
    // Chest protection functions
    // ==============================================

    internal fun setResidentTrust(resident: Resident, trust: Boolean) {
        resident.trusted = trust
        resident.needsUpdate()
        Nodes.needsSave = true
    }

    internal fun startProtectingChests(resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        val town = resident.town
        if (town === null) {
            return
        }

        if (resident !== town.leader && !town.officers.contains(resident)) {
            return
        }

        resident.isProtectingChests = true
    }

    internal fun stopProtectingChests(resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        resident.isProtectingChests = false
    }

    /**
     * Mark town chest as protected by town
     * Handles checking for connected chests.
     * Protect: true/false setting for protecting or unprotecting
     */
    internal fun protectTownChest(town: Town, block: Block, protect: Boolean) {
        fun getConnectedBlocks(block: Block): List<Block> {
            val type = block.type
            if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                val blockState = block.getState()
                if (blockState is Chest) {
                    val chest = blockState as Chest
                    val inventory = chest.getInventory()
                    if (inventory is DoubleChestInventory) {
                        val doubleChest = inventory.getHolder() as DoubleChest

                        val leftSide: Block = (doubleChest.getLeftSide() as Chest).block
                        val rightSide: Block = (doubleChest.getRightSide() as Chest).block

                        return listOf(leftSide, rightSide)
                    }
                }
            }

            return listOf(block)
        }

        val blocks: List<Block> = if (protect == true) {
            getConnectedBlocks(block)
        } else {
            listOf(block)
        }

        if (protect == true) {
            for (block in blocks) {
                town.protectedBlocks.add(block)
            }
        } else {
            for (block in blocks) {
                town.protectedBlocks.remove(block)
            }
        }

        town.needsUpdate()
        Nodes.needsSave = true
    }

    internal fun showProtectedChests(town: Town, resident: Resident) {
        val player = resident.player()
        if (player === null) {
            return
        }

        val protectedBlocks = town.protectedBlocks

        val task = object : BukkitRunnable() {
            private val particle = Particle.HAPPY_VILLAGER
            private val particleCount = 3
            private val randomOffsetXZ = 0.05
            private val randomOffsetY = 0.1

            public val MAX_RUNS = 10
            public var runCount = 0

            public override fun run() {
                for (block in protectedBlocks) {
                    val location1 = Location(block.world, block.x.toDouble() + 0.1, block.y.toDouble() + 0.5, block.z.toDouble() + 0.1)
                    val location2 = Location(block.world, block.x.toDouble() + 0.1, block.y.toDouble() + 0.5, block.z.toDouble() + 0.9)
                    val location3 = Location(block.world, block.x.toDouble() + 0.9, block.y.toDouble() + 0.5, block.z.toDouble() + 0.1)
                    val location4 = Location(block.world, block.x.toDouble() + 0.9, block.y.toDouble() + 0.5, block.z.toDouble() + 0.9)

                    val location5 = Location(block.world, block.x.toDouble() + 0.5, block.y.toDouble() + 0.5, block.z.toDouble())
                    val location6 = Location(block.world, block.x.toDouble(), block.y.toDouble() + 0.5, block.z.toDouble() + 0.5)
                    val location7 = Location(block.world, block.x.toDouble() + 0.5, block.y.toDouble() + 0.5, block.z.toDouble() + 1.0)
                    val location8 = Location(block.world, block.x.toDouble() + 1.0, block.y.toDouble() + 0.5, block.z.toDouble() + 0.5)

                    player.spawnParticle(particle, location1, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                    player.spawnParticle(particle, location2, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                    player.spawnParticle(particle, location3, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                    player.spawnParticle(particle, location4, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                    player.spawnParticle(particle, location5, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                    player.spawnParticle(particle, location6, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                    player.spawnParticle(particle, location7, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                    player.spawnParticle(particle, location8, particleCount, randomOffsetXZ, randomOffsetY, randomOffsetXZ)
                }

                runCount += 1
                if (runCount > MAX_RUNS) {
                    Bukkit.getScheduler().cancelTask(this.getTaskId())
                    return
                }
            }
        }

        task.runTaskTimer(Nodes.plugin!!, 0, 20)
    }

    // ==============================================
    // Port functions
    // ==============================================
    public fun loadPort(
        name: String,
        locX: Int,
        locZ: Int,
        groups: HashSet<PortGroup>,
        isPublic: Boolean,
    ): Port? {
        val port = Port(name, locX, locZ, groups, isPublic)

        ports.put(name, port)

        val chunk = listOf(Math.floorDiv(locX, 16), Math.floorDiv(locZ, 16))
        chunkToPort.put(chunk, port)

        port.needsUpdate()

        return port
    }

    public fun destroyPort(port: Port) {
        PortBeacons.remove(port)
        Nodes.ports.remove(port.name)

        val chunk = listOf(Math.floorDiv(port.locX, 16), Math.floorDiv(port.locZ, 16))
        chunkToPort.remove(chunk)

        Nodes.needsSave = true
    }

    public fun getPortFromName(name: String): Port? = ports.get(name)

    public fun getPortGroupFromName(name: String): PortGroup? = portGroups.get(name)

    public fun createPortGroup(name: String): Result<PortGroup> {
        if (portGroups.containsKey(name)) {
            return Result.failure(ErrorPortExists)
        }

        val portGroup = PortGroup(name)
        portGroups.put(name, portGroup)

        return Result.success(portGroup)
    }

    public fun destroyPortGroup(portGroup: PortGroup) {
        Nodes.portGroups.remove(portGroup.name)

        Nodes.needsSave = true
    }

    public fun createPort(
        name: String,
        locX: Int,
        locZ: Int,
        groups: HashSet<PortGroup>,
        isPublic: Boolean,
        beaconY: Int? = null,
    ): Result<Port> {
        if (ports.containsKey(name)) {
            return Result.failure(ErrorPortExists)
        }

        val port = Port(name, locX, locZ, groups, isPublic)
        port.beaconY = beaconY

        ports.put(name, port)

        val chunk = listOf(Math.floorDiv(locX, 16), Math.floorDiv(locZ, 16))
        chunkToPort.put(chunk, port)

        port.needsUpdate()
        needsSave = true
        PortBeacons.place(port)

        return Result.success(port)
    }

    public fun addPortToGroup(port: Port, group: PortGroup): Result<Port> {
        if (port.groups.contains(group)) {
            return Result.failure(ErrorPortInGroup)
        }

        port.groups.add(group)
        port.needsUpdate()
        Nodes.needsSave = true

        return Result.success(port)
    }

    public fun removePortFromGroup(port: Port, group: PortGroup) {
        if (!port.groups.contains(group)) {
            return
        }

        port.groups.remove(group)
        port.needsUpdate()
        Nodes.needsSave = true
    }

    /**
     * Get port owner based on who owns chunk
     * If no owner or if port is public, return null
     * If chunk is occupied, return occupier
     * Else, return territory town (may be null)
     */
    public fun getPortOwner(port: Port): Town? {
        if (port.isPublic) {
            return null
        }

        val chunk = getTerritoryChunkFromCoord(Coord(port.chunkX, port.chunkZ))
        if (chunk === null) {
            return null
        }

        val occupier = chunk.occupier
        if (occupier !== null) {
            return occupier
        }

        return chunk.territory.town
    }

    fun sharePortGroups(port1: Port, port2: Port): Boolean = port1.groups.any { it in port2.groups }

    // ==============================================
    // Hooks to external functions
    // ==============================================
    internal fun hookDynmap() {
        Nodes.dynmap = true
    }
}
