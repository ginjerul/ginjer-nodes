/**
 * Map exporter for NodesMap (the nodesmap/ plugin in this repo).
 *
 * Writes four small JSON layers + a version file into NodesMap's web folder:
 *   world.json     territories, chunks, resource nodes        (checked every 60s)
 *   state.json     towns, nations, forts, supply lines        (every mapExport.statePeriodTicks)
 *   features.json  ports, mining towers, refineries, drops    (every 30s)
 *   live.json      war, flags, occupation, warzones, raids    (every mapExport.livePeriodTicks)
 *   version.json   one hash per layer; browsers poll only this
 *
 * Main thread: copies plain data out of Nodes objects (fast, no IO, no JSON).
 * Background thread: builds JSON, hashes it, and only writes files that changed.
 */

package phonon.nodes.map

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.fortify.Fortifications
import phonon.nodes.mining.MiningTowers
import phonon.nodes.objects.Territory
import phonon.nodes.objects.Town
import phonon.nodes.refinery.Refineries
import phonon.nodes.supply.SupplyDrops
import phonon.nodes.supply.SupplyLines
import phonon.nodes.vassal.Vassals
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Raid
import phonon.nodes.war.WarTimer
import phonon.nodes.war.Warzone
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.CRC32

public object MapExporter {
    private var plugin: Plugin? = null
    private var dir: Path = Paths.get("plugins/NodesMap/web/data/nodes")
    private var writer: ExecutorService? = null
    private val tasks = ArrayList<BukkitTask>()

    // only touched on the writer thread
    private val hashes = HashMap<String, String>()

    // main thread only
    private var lastWorldRefs: List<Territory> = emptyList()
    private val supplyCache = HashMap<UUID, IntArray>()
    private var supplyCursor = 0

    /** Called from NodesPlugin.onEnable after the world is loaded. */
    public fun start(plugin: Plugin) {
        stop()
        if (!Config.mapExportEnabled) return
        val nodesMapPresent = Bukkit.getPluginManager().getPlugin("NodesMap") != null
        if (!nodesMapPresent && !Config.mapExportAlways) return

        this.plugin = plugin
        this.dir = Paths.get(Config.mapExportPath).normalize()
        this.writer = Executors.newSingleThreadExecutor { r -> Thread(r, "Nodes-MapExport").apply { isDaemon = true } }
        hashes.clear()
        lastWorldRefs = emptyList()
        supplyCache.clear()

        val s = Bukkit.getScheduler()
        tasks += s.runTaskTimer(plugin, Runnable { exportWorld() }, 1L, 1200L)
        tasks += s.runTaskTimer(plugin, Runnable { exportFeatures() }, 2L, 600L)
        tasks += s.runTaskTimer(plugin, Runnable { exportState() }, 3L, Config.mapExportStatePeriodTicks)
        tasks += s.runTaskTimer(plugin, Runnable { exportLive() }, 4L, Config.mapExportLivePeriodTicks)
        if (Config.mapExportSupplyLines && Config.supplyLinesEnabled) {
            // refresh supply connectivity for one town per tick: spreads the BFS cost out
            tasks += s.runTaskTimer(plugin, Runnable { supplyStep() }, 5L, 1L)
        }
        plugin.logger.info("[Nodes] map export -> $dir")
    }

    public fun stop() {
        for (t in tasks) t.cancel()
        tasks.clear()
        writer?.shutdown()
        writer = null
    }

    /** Force a world re-export on the next check (e.g. after /nda reload world). */
    public fun worldChanged() {
        lastWorldRefs = emptyList()
    }

    // ------------------------------------------------------------------
    // world

    private fun exportWorld() {
        val refs = Nodes.territories.values.toList()
        if (refs.size == lastWorldRefs.size && refs.indices.all { refs[it] === lastWorldRefs[it] }) return
        lastWorldRefs = refs
        // territories are immutable after load, so they can be read off-thread
        val resources = Nodes.resourceNodes.values.map { it.name to it.icon }
        val costBase = Config.territoryCostBase
        val costScale = Config.territoryCostScale
        submit("world") {
            val j = Json(256 * refs.size + 1024)
            j.obj {
                key("territories").arr {
                    for (t in refs.sortedBy { it.id.toInt() }) {
                        obj {
                            key("id").num(t.id.toInt())
                            key("name").str(t.name)
                            key("core").arr {
                                num(t.core.x)
                                num(t.core.z)
                            }
                            key("chunks").arr {
                                for (c in t.chunks) {
                                    num(c.x)
                                    num(c.z)
                                }
                            }
                            key("neighbors").arr { for (n in t.neighbors.toIntArray()) num(n) }
                            key("resources").arr { for (r in t.resourceNodes) str(r) }
                            key("cost").num(t.cost)
                            key("wild").bool(t.bordersWilderness)
                            if (t.attackerTimeMultiplier != 1.0) key("atk").num(t.attackerTimeMultiplier)
                        }
                    }
                }
                key("costConfig").obj {
                    key("constant").num(costBase)
                    key("scale").num(costScale)
                }
                key("resources").obj {
                    for ((name, icon) in resources) key(name).obj { key("icon").strOrNull(icon) }
                }
            }
            j.toString()
        }
    }

    // ------------------------------------------------------------------
    // state

    private class OutpostSnap(val name: String, val x: Int, val z: Int, val territory: Int)

    private class TownSnap(
        val name: String,
        val color: String,
        val home: Int,
        val leader: String?,
        val officers: List<String>,
        val residents: List<String>,
        val territories: IntArray,
        val annexed: IntArray,
        val captured: IntArray,
        val outposts: List<OutpostSnap>,
        val nation: String?,
        val allies: List<String>,
        val enemies: List<String>,
        val claimsUsed: Int,
        val claimsMax: Int,
        val open: Boolean,
        val overlord: String?,
        val vassals: List<String>,
        val supplied: IntArray?,
    )

    private class NationSnap(val name: String, val color: String, val capital: String, val towns: List<String>, val allies: List<String>, val enemies: List<String>)

    private fun hex(r: Int, g: Int, b: Int) = String.format("#%02x%02x%02x", r and 255, g and 255, b and 255)

    private fun exportState() {
        val supplyOn = Config.mapExportSupplyLines && Config.supplyLinesEnabled
        val towns = Nodes.towns.values.map { t ->
            TownSnap(
                name = t.name,
                color = hex(t.color.r, t.color.g, t.color.b),
                home = t.home.toInt(),
                leader = t.leader?.name,
                officers = t.officers.map { it.name },
                residents = t.residents.map { it.name },
                territories = t.territories.map { it.toInt() }.toIntArray(),
                annexed = t.annexed.map { it.toInt() }.toIntArray(),
                captured = t.captured.map { it.toInt() }.toIntArray(),
                outposts = t.outposts.values.map { OutpostSnap(it.name, it.spawn.blockX, it.spawn.blockZ, it.territory.toInt()) },
                nation = t.nation?.name,
                allies = t.allies.map { it.name },
                enemies = t.enemies.map { it.name },
                claimsUsed = t.claimsUsed,
                claimsMax = t.claimsMax,
                open = t.isOpen,
                overlord = safe { Vassals.overlordOf(t)?.name },
                vassals = safe { Vassals.vassalsOf(t).map { it.name } } ?: emptyList(),
                supplied = if (supplyOn) supplyCache[t.uuid] else null,
            )
        }
        val nations = Nodes.nations.values.map { n ->
            NationSnap(n.name, hex(n.color.r, n.color.g, n.color.b), n.capital.name, n.towns.map { it.name }, n.allies.map { it.name }, n.enemies.map { it.name })
        }
        val forts = safe { Fortifications.forts.values.map { it.territoryId to it.level } } ?: emptyList()

        submit("state") {
            val j = Json(64 * 1024)
            j.obj {
                key("towns").arr {
                    for (t in towns) {
                        obj {
                            key("name").str(t.name)
                            key("color").str(t.color)
                            key("home").num(t.home)
                            key("leader").strOrNull(t.leader)
                            key("officers").strs(t.officers)
                            key("residents").strs(t.residents)
                            key("territories").ints(t.territories)
                            key("annexed").ints(t.annexed)
                            key("captured").ints(t.captured)
                            key("outposts").arr {
                                for (o in t.outposts) {
                                    obj {
                                        key("name").str(o.name)
                                        key("x").num(o.x)
                                        key("z").num(o.z)
                                        key("territory").num(o.territory)
                                    }
                                }
                            }
                            key("nation").strOrNull(t.nation)
                            key("allies").strs(t.allies)
                            key("enemies").strs(t.enemies)
                            key("claims").arr {
                                num(t.claimsUsed)
                                num(t.claimsMax)
                            }
                            key("open").bool(t.open)
                            key("overlord").strOrNull(t.overlord)
                            key("vassals").strs(t.vassals)
                            key("supplied")
                            if (t.supplied != null) ints(t.supplied) else raw("null")
                        }
                    }
                }
                key("nations").arr {
                    for (n in nations) {
                        obj {
                            key("name").str(n.name)
                            key("color").str(n.color)
                            key("capital").str(n.capital)
                            key("towns").strs(n.towns)
                            key("allies").strs(n.allies)
                            key("enemies").strs(n.enemies)
                        }
                    }
                }
                key("forts").obj { for ((id, lvl) in forts) key(id.toString()).num(lvl) }
            }
            j.toString()
        }
    }

    /** One town per tick; results are picked up by the next state export. */
    private fun supplyStep() {
        val list = Nodes.towns.values
        if (list.isEmpty()) return
        if (supplyCursor >= list.size) {
            supplyCursor = 0
            // drop towns that no longer exist
            val alive = list.mapTo(HashSet()) { it.uuid }
            supplyCache.keys.retainAll(alive)
        }
        val town: Town = list.elementAtOrNull(supplyCursor++) ?: return
        val connected = safe { SupplyLines.connected(town) } ?: return
        supplyCache[town.uuid] = connected.map { it.toInt() }.toIntArray()
    }

    // ------------------------------------------------------------------
    // features

    private class Pt(val id: String, val x: Int, val y: Int, val z: Int)

    private fun exportFeatures() {
        val ports = Nodes.ports.values.map { p -> Triple(p, p.groups.map { it.name }, p.isPublic) }
        val towers = safe {
            MiningTowers.towers.values.map { t -> Pair(Pt(t.id, t.x, t.y, t.z), Pair(t.cooldownSeconds, t.rewards.map { it.material.name to it.amount })) }
        } ?: emptyList()
        val refineries = safe {
            Refineries.byId.values.map { r ->
                Triple(Pt(r.id, r.x, r.y, r.z), r.intervalMs, r.outputs.map { o -> (safe { o.toItemStack()?.type?.name } ?: "?") to o.amount })
            }
        } ?: emptyList()
        val drops = safe { SupplyDrops.points.values.map { Pt(it.id, it.x, it.y, it.z) } } ?: emptyList()

        submit("features") {
            val j = Json(8 * 1024)
            j.obj {
                key("ports").arr {
                    for ((p, groups, pub) in ports) {
                        obj {
                            key("name").str(p.name)
                            key("x").num(p.locX)
                            key("z").num(p.locZ)
                            key("groups").strs(groups)
                            key("public").bool(pub)
                        }
                    }
                }
                key("towers").arr {
                    for ((pt, info) in towers) {
                        obj {
                            key("id").str(pt.id)
                            key("x").num(pt.x)
                            key("y").num(pt.y)
                            key("z").num(pt.z)
                            key("cooldownSeconds").num(info.first)
                            key("rewards").arr {
                                for ((m, a) in info.second) {
                                    obj {
                                        key("material").str(m)
                                        key("amount").num(a)
                                    }
                                }
                            }
                        }
                    }
                }
                key("refineries").arr {
                    for ((pt, interval, outs) in refineries) {
                        obj {
                            key("id").str(pt.id)
                            key("x").num(pt.x)
                            key("y").num(pt.y)
                            key("z").num(pt.z)
                            key("intervalMs").num(interval)
                            key("outputs").arr {
                                for ((m, a) in outs) {
                                    obj {
                                        key("material").str(m)
                                        key("amount").num(a)
                                    }
                                }
                            }
                        }
                    }
                }
                key("dropPoints").arr {
                    for (d in drops) {
                        obj {
                            key("id").str(d.id)
                            key("x").num(d.x)
                            key("y").num(d.y)
                            key("z").num(d.z)
                        }
                    }
                }
            }
            j.toString()
        }
    }

    // ------------------------------------------------------------------
    // live

    private class AttackSnap(val cx: Int, val cz: Int, val town: String, val attacker: String?, val progress: Double, val remainingMs: Long, val fx: Int, val fy: Int, val fz: Int)
    private class ZoneSnap(val town: String, val enabled: Boolean, val endsAt: Long, val holder: String?, val scores: Map<String, Long>)
    private class RaidSnap(val territory: Int, val attacker: String, val defender: String, val phase: String, val prepEndsAt: Long, val liveEndsAt: Long)

    private fun exportLive() {
        val now = System.currentTimeMillis()
        val warOn = FlagWar.enabled
        val warEnds = safe { WarTimer.endsAt() } ?: 0L

        val attacks = FlagWar.chunkToAttacker.values.filter { !it.cancelled }.map { a ->
            val total = a.attackTime.coerceAtLeast(1L)
            AttackSnap(
                a.coord.x, a.coord.z, a.town.name, Nodes.getResidentFromUUID(a.attacker)?.name,
                (a.progress.toDouble() / total).coerceIn(0.0, 1.0),
                (total - a.progress).coerceAtLeast(0L) * 50L,
                a.flagBase.x, a.flagBase.y, a.flagBase.z,
            )
        }
        val occupied = ArrayList<Triple<Int, Int, String>>(FlagWar.occupiedChunks.size)
        for (c in FlagWar.occupiedChunks) {
            val occ = Nodes.getTerritoryChunkFromCoord(c)?.occupier ?: continue
            occupied.add(Triple(c.x, c.z, occ.name))
        }
        val zones = safe {
            Warzone.zones.values.map { z ->
                val town = Nodes.towns[z.townName]
                val holder = if (town != null && z.enabled) safe { Warzone.majorityHolder(town)?.name } else z.lastMajorityTown
                ZoneSnap(z.townName, z.enabled, z.endsAt, holder, HashMap(z.scores))
            }
        } ?: emptyList()
        val raids = safe {
            Raid.byTerritory.values.map { r -> RaidSnap(r.territoryId.toInt(), r.attacker.name, r.defender.name, r.phase.name, r.prepEndsAt, r.liveEndsAt) }
        } ?: emptyList()
        val drop = safe {
            val a = SupplyDrops.active
            Pair(SupplyDrops.nextDropAt, a?.let { listOf(it.point.id, it.point.x, it.point.z, it.landsAt, it.expiresAt, it.landed) })
        }

        submit("live") {
            val j = Json(4 * 1024 + occupied.size * 24)
            j.obj {
                // snapshot time, only while flags tick, so a quiet live.json stays byte-identical
                if (attacks.isNotEmpty()) key("at").num(now)
                key("war").obj {
                    key("enabled").bool(warOn)
                    key("endsAt").num(warEnds)
                }
                key("attacks").arr {
                    for (a in attacks) {
                        obj {
                            key("cx").num(a.cx)
                            key("cz").num(a.cz)
                            key("town").str(a.town)
                            key("attacker").strOrNull(a.attacker)
                            key("progress").num(a.progress)
                            key("remainingMs").num(a.remainingMs)
                            key("fx").num(a.fx)
                            key("fy").num(a.fy)
                            key("fz").num(a.fz)
                        }
                    }
                }
                key("occupied").arr {
                    for ((x, z, t) in occupied) {
                        arr {
                            num(x)
                            num(z)
                            str(t)
                        }
                    }
                }
                key("warzones").arr {
                    for (z in zones) {
                        obj {
                            key("town").str(z.town)
                            key("enabled").bool(z.enabled)
                            key("endsAt").num(z.endsAt)
                            key("holder").strOrNull(z.holder)
                            key("scores").obj { for ((n, ms) in z.scores) key(n).num(ms) }
                        }
                    }
                }
                key("raids").arr {
                    for (r in raids) {
                        obj {
                            key("territory").num(r.territory)
                            key("attacker").str(r.attacker)
                            key("defender").str(r.defender)
                            key("phase").str(r.phase)
                            key("prepEndsAt").num(r.prepEndsAt)
                            key("liveEndsAt").num(r.liveEndsAt)
                        }
                    }
                }
                key("supplyDrop")
                if (drop == null) {
                    raw("null")
                } else {
                    obj {
                        key("nextDropAt").num(drop.first)
                        key("active")
                        val a = drop.second
                        if (a == null) {
                            raw("null")
                        } else {
                            obj {
                                key("id").str(a[0] as String)
                                key("x").num(a[1] as Int)
                                key("z").num(a[2] as Int)
                                key("landsAt").num(a[3] as Long)
                                key("expiresAt").num(a[4] as Long)
                                key("landed").bool(a[5] as Boolean)
                            }
                        }
                    }
                }
            }
            j.toString()
        }
    }

    // ------------------------------------------------------------------
    // writing

    private inline fun <T> safe(f: () -> T): T? = try {
        f()
    } catch (e: Throwable) {
        null
    }

    private fun submit(layer: String, build: () -> String) {
        val w = writer ?: return
        w.execute {
            try {
                val json = build()
                val crc = CRC32().apply { update(json.toByteArray(Charsets.UTF_8)) }.value
                val hash = crc.toString(36) + "-" + json.length.toString(36)
                if (hashes[layer] == hash && Files.exists(dir.resolve("$layer.json"))) {
                    writeVersion(false)
                    return@execute
                }
                Files.createDirectories(dir)
                atomicWrite(dir.resolve("$layer.json"), json)
                hashes[layer] = hash
                writeVersion(true)
            } catch (e: Throwable) {
                plugin?.logger?.warning("[Nodes] map export ($layer) failed: $e")
            }
        }
    }

    /** version.json only changes when a layer changes, so idle polls are all 304s */
    private fun writeVersion(changed: Boolean) {
        if (!changed && Files.exists(dir.resolve("version.json"))) return
        val j = Json(256)
        j.obj {
            for (k in listOf("world", "state", "features", "live")) key(k).strOrNull(hashes[k])
        }
        atomicWrite(dir.resolve("version.json"), j.toString())
    }

    private fun atomicWrite(path: Path, text: String) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Tiny allocation-light JSON builder (no reflection, no intermediate trees). */
internal class Json(capacity: Int) {
    private val sb = StringBuilder(capacity)
    private val first = ArrayList<Boolean>(16)

    private fun sep() {
        val n = first.size
        if (n == 0) return
        if (first[n - 1]) first[n - 1] = false else sb.append(',')
    }

    inline fun obj(f: Json.() -> Unit): Json {
        beginObj()
        f()
        endObj()
        return this
    }

    inline fun arr(f: Json.() -> Unit): Json {
        beginArr()
        f()
        endArr()
        return this
    }

    fun beginObj() {
        valueStart()
        sb.append('{')
        first.add(true)
    }

    fun endObj() {
        first.removeAt(first.size - 1)
        sb.append('}')
    }

    fun beginArr() {
        valueStart()
        sb.append('[')
        first.add(true)
    }

    fun endArr() {
        first.removeAt(first.size - 1)
        sb.append(']')
    }

    private var afterKey = false

    private fun valueStart() {
        if (afterKey) afterKey = false else sep()
    }

    fun key(k: String): Json {
        sep()
        quote(k)
        sb.append(':')
        afterKey = true
        return this
    }

    fun str(s: String): Json {
        valueStart()
        quote(s)
        return this
    }

    fun strOrNull(s: String?): Json = if (s == null) raw("null") else str(s)

    fun num(n: Int): Json {
        valueStart()
        sb.append(n)
        return this
    }

    fun num(n: Long): Json {
        valueStart()
        sb.append(n)
        return this
    }

    fun num(n: Double): Json {
        valueStart()
        sb.append(if (n.isFinite()) String.format(java.util.Locale.ROOT, "%.4f", n).trimEnd('0').trimEnd('.') else "0")
        return this
    }

    fun bool(b: Boolean): Json {
        valueStart()
        sb.append(b)
        return this
    }

    fun raw(s: String): Json {
        valueStart()
        sb.append(s)
        return this
    }

    fun strs(l: List<String>): Json = arr { for (s in l) str(s) }

    fun ints(a: IntArray): Json = arr { for (i in a) num(i) }

    private fun quote(s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    override fun toString(): String = sb.toString()
}
