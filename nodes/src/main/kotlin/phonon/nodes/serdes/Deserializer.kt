/**
 * Deserializer
 *
 */

package phonon.nodes.serdes

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import phonon.nodes.Nodes
import phonon.nodes.Nodes.createPortGroup
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Port
import phonon.nodes.objects.PortGroup
import phonon.nodes.objects.Town
import phonon.nodes.utils.Color
import java.io.FileReader
import java.nio.file.Path
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

public data class WorldJsonState(
    val resources: JsonObject?,
    val territories: JsonObject?,
)

public object Deserializer {

    public fun worldFromJson(path: Path): WorldJsonState {
        val json = JsonParser.parseReader(FileReader(path.toString()))
        val jsonObj = json.getAsJsonObject()

        val jsonNodes = jsonObj.get("nodes")?.getAsJsonObject()
        val jsonTerritories = jsonObj.get("territories")?.getAsJsonObject()

        return WorldJsonState(jsonNodes, jsonTerritories)
    }

    public fun townsFromJson(path: Path) {
        val towns: ArrayList<Town> = ArrayList()
        val townAllies: ArrayList<ArrayList<String>> = ArrayList()
        val townEnemies: ArrayList<ArrayList<String>> = ArrayList()
        val nations: ArrayList<Nation> = ArrayList()
        val nationAllies: ArrayList<ArrayList<String>> = ArrayList()
        val nationEnemies: ArrayList<ArrayList<String>> = ArrayList()

        val json = JsonParser.parseReader(FileReader(path.toString()))
        val jsonObj = json.getAsJsonObject()

        // ===============================
        // Residents
        // ===============================
        val jsonResidents = jsonObj.get("residents")?.getAsJsonObject()
        if (jsonResidents !== null) {
            jsonResidents.keySet().forEach { uuid ->
                val resident = jsonResidents[uuid].getAsJsonObject()

                var claims: Int = 0
                var claimsTime: Long = 0
                val claimsJson = resident.get("claims")?.getAsJsonArray()
                if (claimsJson !== null && claimsJson.size() == 2) {
                    claims = claimsJson[0].getAsInt()
                    claimsTime = claimsJson[1].getAsLong()
                }

                val prefix = resident.get("prefix")?.getAsString() ?: ""
                val suffix = resident.get("suffix")?.getAsString() ?: ""

                val trusted = resident.get("trust")?.getAsBoolean() ?: false

                val townCreateCooldown: Long = resident.get("townCool")?.getAsLong() ?: 0L

                val leftNationJson = resident.get("leftNation")
                val leftNation: String? = if (leftNationJson != null && !leftNationJson.isJsonNull) leftNationJson.getAsString() else null
                val leftNationAt: Long = resident.get("leftNationAt")?.getAsLong() ?: 0L

                Nodes.loadResident(
                    UUID.fromString(uuid),
                    claims,
                    claimsTime,
                    prefix,
                    suffix,
                    trusted,
                    townCreateCooldown,
                    leftNation,
                    leftNationAt,
                )
            }
        }

        // ===============================
        // Towns
        // ===============================
        val jsonTowns = jsonObj.get("towns")?.getAsJsonObject()
        if (jsonTowns !== null) {
            jsonTowns.keySet().forEach { name ->
                val town = jsonTowns[name].getAsJsonObject()

                val uuidJson = town.get("uuid")
                val uuid: UUID = if (uuidJson !== null) {
                    UUID.fromString(uuidJson.getAsString())
                } else {
                    UUID.randomUUID()
                }

                // Skip towns with no home
                val homeId = town.get("home")?.getAsInt()
                if (homeId == null) {
                    System.err.println("Cannot create $name: no home")
                    return@forEach
                }

                val leaderJson = town.get("leader")
                val leader: UUID? = if (leaderJson == null || leaderJson.isJsonNull()) {
                    null
                } else {
                    UUID.fromString(leaderJson.getAsString())
                }

                val spawnLocArray = town.get("spawn")?.getAsJsonArray()
                var spawn = if (spawnLocArray !== null && spawnLocArray.size() == 3) {
                    Location(Bukkit.getWorlds().get(0), spawnLocArray[0].getAsDouble(), spawnLocArray[1].getAsDouble(), spawnLocArray[2].getAsDouble())
                } else {
                    null
                }

                val colorArray = town.get("color")?.getAsJsonArray()
                var color = if (colorArray !== null && colorArray.size() == 3) {
                    Color(colorArray[0].getAsInt(), colorArray[1].getAsInt(), colorArray[2].getAsInt())
                } else {
                    null
                }

                var claimsBonus = town.get("claimsBonus")?.getAsInt() ?: 0

                var claimsAnnexed = town.get("claimsAnnex")?.getAsInt() ?: 0

                var claimsPenalty: Int = 0
                var claimsPenaltyTime: Long = 0
                val claimsPenaltyJson = town.get("claimsPenalty")?.getAsJsonArray()
                if (claimsPenaltyJson !== null && claimsPenaltyJson.size() == 2) {
                    claimsPenalty = claimsPenaltyJson[0].getAsInt()
                    claimsPenaltyTime = claimsPenaltyJson[1].getAsLong()
                }

                val residentsUUID: ArrayList<UUID> = ArrayList()
                val residentsArray = town.get("residents")?.getAsJsonArray()
                if (residentsArray !== null) {
                    residentsArray.forEach { uuid ->
                        residentsUUID.add(UUID.fromString(uuid.getAsString()))
                    }
                }

                val officersUUID: ArrayList<UUID> = ArrayList()
                val officersArray = town.get("officers")?.getAsJsonArray()
                if (officersArray !== null) {
                    officersArray.forEach { uuid ->
                        officersUUID.add(UUID.fromString(uuid.getAsString()))
                    }
                }

                val territoryIds: ArrayList<Int> = ArrayList()
                val territoryArray = town.get("territories")?.getAsJsonArray()
                if (territoryArray !== null) {
                    territoryArray.forEach { id ->
                        territoryIds.add(id.getAsInt())
                    }
                }

                val capturedIds: ArrayList<Int> = ArrayList()
                val capturedTerrArray = town.get("captured")?.getAsJsonArray()
                if (capturedTerrArray !== null) {
                    capturedTerrArray.forEach { id ->
                        capturedIds.add(id.getAsInt())
                    }
                }

                val annexedIds: ArrayList<Int> = ArrayList()
                val annexedTerrArray = town.get("annexed")?.getAsJsonArray()
                if (annexedTerrArray !== null) {
                    annexedTerrArray.forEach { id ->
                        annexedIds.add(id.getAsInt())
                    }
                }

                val income: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)
                val townIncomeJson = town.get("income")?.getAsJsonObject()
                if (townIncomeJson !== null) {
                    townIncomeJson.keySet().forEach { type ->
                        val material = Material.matchMaterial(type)
                        if (material !== null) {
                            income.put(material, townIncomeJson.get(type).getAsInt())
                        }
                    }
                }

                val incomeSpawnEgg: EnumMap<EntityType, Int> = EnumMap<EntityType, Int>(EntityType::class.java)
                val townIncomeSpawnEggJson = town.get("incomeEgg")?.getAsJsonObject()
                if (townIncomeSpawnEggJson !== null) {
                    townIncomeSpawnEggJson.keySet().forEach { type ->
                        val entityType = EntityType.valueOf(type)
                        if (entityType !== null) {
                            incomeSpawnEgg.put(entityType, townIncomeSpawnEggJson.get(type).getAsInt())
                        }
                    }
                }

                val allies: ArrayList<String> = ArrayList()
                val alliesArray = town.get("allies")?.getAsJsonArray()
                if (alliesArray !== null) {
                    alliesArray.forEach { name ->
                        allies.add(name.getAsString())
                    }
                }

                val enemies: ArrayList<String> = ArrayList()
                val enemiesArray = town.get("enemies")?.getAsJsonArray()
                if (enemiesArray !== null) {
                    enemiesArray.forEach { name ->
                        enemies.add(name.getAsString())
                    }
                }

                val permissions: EnumMap<TownPermissions, EnumSet<PermissionsGroup>> = enumValues<TownPermissions>().toList().associateWithTo(
                    EnumMap<TownPermissions, EnumSet<PermissionsGroup>>(TownPermissions::class.java),
                    { _ -> EnumSet.noneOf(PermissionsGroup::class.java) },
                )
                val permissionsJson = town.get("perms")?.getAsJsonObject()
                if (permissionsJson !== null) {
                    permissionsJson.keySet().forEach { type ->
                        try {
                            val permType = TownPermissions.valueOf(type)
                            val permGroupList = permissionsJson.get(type)?.getAsJsonArray()
                            if (permGroupList !== null) {
                                for (group in permGroupList) {
                                    permissions.get(permType)!!.add(PermissionsGroup.values[group.getAsInt()])
                                }
                            }
                        } catch (err: IllegalArgumentException) {
                            System.err.println("Invalid town permission: $type")
                        }
                    }
                }

                val isOpen: Boolean = town.get("open")?.getAsBoolean() ?: false

                val protectedBlocks: HashSet<Block> = hashSetOf()
                val protectedBlocksJsonArray = town.get("protect")?.getAsJsonArray()
                if (protectedBlocksJsonArray !== null) {
                    val world = Bukkit.getWorlds().get(0)
                    for (item in protectedBlocksJsonArray) {
                        val blockArray = item.getAsJsonArray()
                        if (blockArray !== null && blockArray.size() == 3) {
                            val x = blockArray[0].getAsInt()
                            val y = blockArray[1].getAsInt()
                            val z = blockArray[2].getAsInt()
                            val block = world.getBlockAt(x, y, z)
                            protectedBlocks.add(block)
                        }
                    }
                }

                val moveHomeCooldown: Long = town.get("homeCool")?.getAsLong() ?: 0L

                // parse town outposts "[terr.id, spawn.x, spawn.y, spawn.z]"
                val outposts: HashMap<String, Pair<Int, Location>> = hashMapOf()
                val outpostsJson = town.get("outpost")?.getAsJsonObject()
                if (outpostsJson !== null) {
                    outpostsJson.keySet().forEach { name ->
                        val dataArray = outpostsJson.get(name)?.getAsJsonArray()
                        if (dataArray !== null && dataArray.size() == 4) {
                            val terrId = dataArray[0].getAsInt()
                            val spawnX = dataArray[1].getAsDouble()
                            val spawnY = dataArray[2].getAsDouble()
                            val spawnZ = dataArray[3].getAsDouble()
                            val location = Location(Bukkit.getWorlds().get(0), spawnX, spawnY, spawnZ)
                            outposts.put(name, Pair(terrId, location))
                        }
                    }
                }

                val townObject: Town? = Nodes.loadTown(
                    uuid,
                    name,
                    leader,
                    homeId,
                    spawn,
                    color,
                    residentsUUID,
                    officersUUID,
                    territoryIds,
                    capturedIds,
                    annexedIds,
                    claimsBonus,
                    claimsAnnexed,
                    claimsPenalty,
                    claimsPenaltyTime,
                    income,
                    incomeSpawnEgg,
                    permissions,
                    isOpen,
                    protectedBlocks,
                    moveHomeCooldown,
                    outposts,
                )

                if (townObject !== null) {
                    towns.add(townObject)
                    townAllies.add(allies)
                    townEnemies.add(enemies)
                }
            }
        }

        // ===============================
        // Nations
        // ===============================
        val jsonNations = jsonObj.get("nations")?.getAsJsonObject()
        if (jsonNations !== null) {
            jsonNations.keySet().forEach { name ->
                val nation = jsonNations[name].getAsJsonObject()

                val uuidJson = nation.get("uuid")
                val uuid: UUID = if (uuidJson !== null) {
                    UUID.fromString(uuidJson.getAsString())
                } else {
                    UUID.randomUUID()
                }

                val colorArray = nation.get("color")?.getAsJsonArray()
                var color = if (colorArray !== null && colorArray.size() == 3) {
                    Color(colorArray[0].getAsInt(), colorArray[1].getAsInt(), colorArray[2].getAsInt())
                } else {
                    null
                }

                val towns: ArrayList<String> = arrayListOf()
                val townsArray = nation.get("towns")?.getAsJsonArray()
                if (townsArray !== null) {
                    townsArray.forEach { townName ->
                        towns.add(townName.getAsString())
                    }
                }

                var capitalName = nation.get("capital")?.getAsString()
                if (capitalName == null) {
                    System.err.println("Capital for: $name not found, setting it to ${towns[0]}")
                    capitalName = towns[0]
                }

                val allies: ArrayList<String> = ArrayList()
                val alliesArray = nation.get("allies")?.getAsJsonArray()
                if (alliesArray !== null) {
                    alliesArray.forEach { name ->
                        allies.add(name.getAsString())
                    }
                }

                val enemies: ArrayList<String> = ArrayList()
                val enemiesArray = nation.get("enemies")?.getAsJsonArray()
                if (enemiesArray !== null) {
                    enemiesArray.forEach { name ->
                        enemies.add(name.getAsString())
                    }
                }

                val nationObject = Nodes.loadNation(
                    uuid,
                    name,
                    capitalName,
                    color,
                    towns,
                )

                nations.add(nationObject)
                nationAllies.add(allies)
                nationEnemies.add(enemies)
            }
        }

        Nodes.loadDiplomacy(
            towns,
            townAllies,
            townEnemies,
            nations,
            nationAllies,
            nationEnemies,
        )
    }

    public fun portsFromJson(path: Path) {
        val json = JsonParser.parseReader(FileReader(path.toString()))
        val jsonObj = json.getAsJsonObject()

        val groups = jsonObj.getAsJsonArray("groups")
        if (groups !== null) {
            for (group in groups) {
                createPortGroup(group.asString)
            }
        }

        val jsonPorts = jsonObj.get("ports")?.getAsJsonObject()
        if (jsonPorts !== null) {
            jsonPorts.keySet().forEach { name ->
                val port = jsonPorts[name].getAsJsonObject()

                val x = port.get("x").asInt
                val z = port.get("z").asInt

                val groupsArray = port.get("groups").getAsJsonArray()

                val groups: HashSet<PortGroup> = hashSetOf()
                for (groupElement in groupsArray) {
                    val groupName = groupElement.asString
                    val group = Nodes.getPortGroupFromName(groupName)
                    if (group !== null) {
                        groups.add(group)
                    }
                }

                val isPublic: Boolean = port.get("isPublic")?.getAsBoolean() ?: false

                val portObject: Port? = Nodes.loadPort(
                    name,
                    x,
                    z,
                    groups,
                    isPublic,
                )
                if (portObject !== null) {
                    portObject.beaconY = port.get("beaconY")?.asInt
                    portObject.needsUpdate()
                }
            }
        }
    }
}
