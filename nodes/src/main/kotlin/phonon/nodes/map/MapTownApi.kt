/**
 * Town and nation editing for the NodesMap web editor (its Towns tab).
 *
 * NodesMap calls these static methods by reflection, like MapEditApi:
 *
 *   townsJson()          every town and nation with what the editor shows
 *   act(json, actor)     run one change, e.g. {"op":"town.rename","town":"A","name":"B"}
 *
 * Every change goes through the same Nodes functions the /nodesadmin town
 * and /nodesadmin nation commands use, so it applies live and is saved with
 * the normal autosave. Changes run on the main thread.
 *
 * Land changes (claiming, capturing, deleting a town) are refused while flag
 * attacks or raids are running, because those hold references to the owners.
 */

package phonon.nodes.map

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import phonon.nodes.Nodes
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.utils.sanitizeString
import phonon.nodes.utils.stringInputIsValid
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Raid
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

public object MapTownApi {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private class Fail(message: String) : Exception(message)

    @JvmStatic
    public fun townsJson(): String = onMain { gson.toJson(snapshot()) }

    @JvmStatic
    public fun act(json: String, actor: String): String {
        val req = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return reply(false, "Could not read the request: ${e.message}")
        }
        return onMain {
            try {
                val msg = run(req)
                Nodes.plugin?.logger?.info("[Nodes] $actor (web editor): $msg")
                MapExporter.stateChanged()
                reply(true, msg)
            } catch (e: Fail) {
                reply(false, e.message ?: "Failed")
            } catch (e: Exception) {
                reply(false, "Something went wrong: $e")
            }
        }
    }

    // ------------------------------------------------------------------

    private fun onMain(f: () -> String): String {
        if (Bukkit.isPrimaryThread()) return f()
        val plugin = Nodes.plugin ?: return reply(false, "Nodes is not enabled")
        return try {
            Bukkit.getScheduler().callSyncMethod(plugin, Callable { f() }).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            reply(false, "Server did not respond in time: ${e.message}")
        }
    }

    private fun reply(ok: Boolean, message: String): String {
        val o = JsonObject()
        o.addProperty("ok", ok)
        o.addProperty("message", message)
        if (Nodes.plugin != null && Bukkit.isPrimaryThread()) o.add("data", snapshot())
        return gson.toJson(o)
    }

    private fun hex(r: Int, g: Int, b: Int) = String.format("#%02x%02x%02x", r and 255, g and 255, b and 255)

    private fun ids(set: Collection<TerritoryId>): JsonArray {
        val a = JsonArray()
        for (id in set.map { it.toInt() }.sorted()) a.add(id)
        return a
    }

    private fun names(list: Collection<String>): JsonArray {
        val a = JsonArray()
        for (n in list.sortedBy { it.lowercase() }) a.add(n)
        return a
    }

    private fun snapshot(): JsonObject {
        val root = JsonObject()
        val towns = JsonArray()
        for (t in Nodes.towns.values) {
            val o = JsonObject()
            o.addProperty("name", t.name)
            o.addProperty("color", hex(t.color.r, t.color.g, t.color.b))
            o.addProperty("nation", t.nation?.name)
            o.addProperty("capital", t.nation?.capital === t)
            o.addProperty("leader", t.leader?.name)
            o.add("officers", names(t.officers.map { it.name }))
            o.add("residents", names(t.residents.map { it.name }))
            o.addProperty("home", t.home.toInt())
            o.add("territories", ids(t.territories))
            o.add("captured", ids(t.captured))
            o.addProperty("open", t.isOpen)
            o.addProperty("claimsUsed", t.claimsUsed)
            o.addProperty("claimsMax", t.claimsMax)
            towns.add(o)
        }
        val nations = JsonArray()
        for (n in Nodes.nations.values) {
            val o = JsonObject()
            o.addProperty("name", n.name)
            o.addProperty("color", hex(n.color.r, n.color.g, n.color.b))
            o.addProperty("capital", n.capital.name)
            o.add("towns", names(n.towns.map { it.name }))
            nations.add(o)
        }
        root.add("towns", towns)
        root.add("nations", nations)
        root.add("players", names(Nodes.residents.values.map { it.name }))
        root.addProperty("warBusy", FlagWar.chunkToAttacker.isNotEmpty() || Raid.byTerritory.isNotEmpty())
        return root
    }

    // ------------------------------------------------------------------
    // request helpers

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun town(req: JsonObject, k: String = "town"): Town {
        val n = req.str(k) ?: throw Fail("No town given")
        return Nodes.towns[n] ?: throw Fail("Town \"$n\" doesn't exist")
    }

    private fun nation(req: JsonObject, k: String = "nation"): Nation {
        val n = req.str(k) ?: throw Fail("No nation given")
        return Nodes.nations[n] ?: throw Fail("Nation \"$n\" doesn't exist")
    }

    private fun player(req: JsonObject): Resident {
        val n = req.str("player") ?: throw Fail("No player given")
        return Nodes.getResidentFromName(n) ?: throw Fail("\"$n\" has never joined the server")
    }

    private fun territories(req: JsonObject): List<Territory> {
        val arr = req.get("territories")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw Fail("No territories given")
        val out = ArrayList<Territory>()
        for (e in arr) {
            val id = e.asInt
            out.add(Nodes.territories[TerritoryId(id)] ?: throw Fail("Territory #$id isn't on the server yet. Apply your land changes first."))
        }
        if (out.isEmpty()) throw Fail("Select some territories on the map first")
        return out
    }

    private fun newName(req: JsonObject, what: String): String {
        val raw = req.str("name") ?: throw Fail("Give the $what a name")
        if (!stringInputIsValid(raw) || raw.contains(" ")) throw Fail("$what names can't have spaces, quotes or { }, and are at most 32 letters")
        return sanitizeString(raw)
    }

    private fun color(req: JsonObject): Triple<Int, Int, Int> {
        val c = req.str("color")?.removePrefix("#") ?: throw Fail("No color given")
        if (c.length != 6) throw Fail("Colors look like #a1b2c3")
        val v = c.toIntOrNull(16) ?: throw Fail("Colors look like #a1b2c3")
        return Triple((v shr 16) and 255, (v shr 8) and 255, v and 255)
    }

    private fun noWar(what: String) {
        if (FlagWar.chunkToAttacker.isNotEmpty()) throw Fail("Flag attacks are in progress. $what after they finish.")
        if (Raid.byTerritory.isNotEmpty()) throw Fail("A raid is running. $what after it ends.")
    }

    /** take a player out of their current town, clearing leadership there first */
    private fun leaveTown(r: Resident) {
        val old = r.town ?: return
        if (old.leader === r) Nodes.townSetLeader(old, null)
        Nodes.removeResidentFromTown(old, r)
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    // ------------------------------------------------------------------

    private fun run(req: JsonObject): String {
        when (val op = req.str("op") ?: throw Fail("No action given")) {
            "town.create" -> {
                noWar("Create the town")
                val name = newName(req, "Town")
                if (Nodes.towns.containsKey(name)) throw Fail("A town called $name already exists")
                val terrs = territories(req)
                val taken = terrs.filter { it.town != null }
                if (taken.size == terrs.size) throw Fail("All selected territories already belong to a town")
                val free = terrs.filter { it.town == null }
                val leader = req.str("player")?.let { player(req) }
                if (leader != null) leaveTown(leader)
                val town = Nodes.createTown(name, free[0], leader).getOrElse { throw Fail("Couldn't create the town: ${it.message}") }
                for (t in free.drop(1)) Nodes.addTerritoryToTown(town, t)
                town.claimsMax = Nodes.calculateMaxClaims(town)
                val skipped = if (taken.isNotEmpty()) " (skipped ${plural(taken.size, "territory")} that already had a town)" else ""
                return "created town $name with ${plural(free.size, "territory")}$skipped"
            }

            "town.delete" -> {
                noWar("Delete the town")
                val t = town(req)
                Nodes.destroyTown(t)
                return "deleted town ${t.name}"
            }

            "town.rename" -> {
                val t = town(req)
                val old = t.name
                val name = newName(req, "Town")
                if (name == old) return "nothing to change"
                if (!Nodes.renameTown(t, name)) throw Fail("A town called $name already exists")
                return "renamed town $old to $name"
            }

            "town.color" -> {
                val t = town(req)
                val (r, g, b) = color(req)
                Nodes.setTownColor(t, r, g, b)
                return "changed the color of ${t.name}"
            }

            "town.open" -> {
                val t = town(req)
                val open = req.get("open")?.asBoolean ?: !t.isOpen
                Nodes.setTownOpen(t, open)
                return "${t.name} is now ${if (open) "open" else "closed"} to join"
            }

            "town.home" -> {
                val t = town(req)
                val terr = territories(req).first()
                if (terr.town !== t) throw Fail("Territory #${terr.id.toInt()} doesn't belong to ${t.name}")
                if (t.home == terr.id) return "#${terr.id.toInt()} is already the home of ${t.name}"
                Nodes.setTownHomeTerritory(t, terr)
                t.moveHomeCooldown = 0L
                return "moved the home of ${t.name} to #${terr.id.toInt()}"
            }

            "town.addTerritories" -> {
                noWar("Change land")
                val t = town(req)
                var added = 0
                var skipped = 0
                for (terr in territories(req)) {
                    if (terr.town != null) {
                        skipped++
                        continue
                    }
                    if (terr.occupier === t) Nodes.releaseTerritory(terr)
                    Nodes.addTerritoryToTown(t, terr)
                    added++
                }
                if (added == 0) throw Fail("Those territories already belong to a town. Remove them from it first.")
                t.claimsMax = Nodes.calculateMaxClaims(t)
                return "added ${plural(added, "territory")} to ${t.name}" + if (skipped > 0) " (skipped $skipped already owned)" else ""
            }

            "town.removeTerritories" -> {
                noWar("Change land")
                var removed = 0
                var homes = 0
                for (terr in territories(req)) {
                    val owner = terr.town ?: continue
                    if (owner.home == terr.id) {
                        homes++
                        continue
                    }
                    val penaltyBefore = owner.claimsPenalty
                    Nodes.unclaimTerritory(owner, terr)
                    // an admin edit isn't the town giving up land: no claims penalty
                    owner.claimsPenalty = penaltyBefore
                    owner.claimsMax = Nodes.calculateMaxClaims(owner)
                    removed++
                }
                if (removed == 0 && homes > 0) throw Fail("That's a town's home territory. Move the home first, or delete the town.")
                if (removed == 0) throw Fail("None of those territories belong to a town")
                return "removed ${plural(removed, "territory")} from their towns" + if (homes > 0) " (kept $homes home territory)" else ""
            }

            "town.capture" -> {
                noWar("Change land")
                val t = town(req)
                var n = 0
                for (terr in territories(req)) {
                    if (terr.town === t) continue
                    Nodes.captureTerritory(t, terr)
                    n++
                }
                if (n == 0) throw Fail("${t.name} already owns those territories")
                return "${t.name} now occupies ${plural(n, "territory")}"
            }

            "town.release" -> {
                noWar("Change land")
                var n = 0
                for (terr in territories(req)) {
                    if (terr.occupier == null) continue
                    Nodes.releaseTerritory(terr)
                    n++
                }
                if (n == 0) throw Fail("None of those territories are occupied")
                return "freed ${plural(n, "occupied territory")}"
            }

            "town.addPlayer" -> {
                val t = town(req)
                val r = player(req)
                if (r.town === t) return "${r.name} is already in ${t.name}"
                val from = r.town?.name
                leaveTown(r)
                if (!Nodes.addResidentToTown(t, r)) throw Fail("${r.name} can't join: the nation is at its player cap")
                return "added ${r.name} to ${t.name}" + if (from != null) " (left $from)" else ""
            }

            "town.removePlayer" -> {
                val t = town(req)
                val r = player(req)
                if (r.town !== t) throw Fail("${r.name} isn't in ${t.name}")
                leaveTown(r)
                return "removed ${r.name} from ${t.name}"
            }

            "town.rank" -> {
                val t = town(req)
                val r = player(req)
                if (r.town !== t) throw Fail("${r.name} isn't in ${t.name}")
                when (req.str("rank")) {
                    "leader" -> {
                        Nodes.townSetLeader(t, r)
                        return "${r.name} now leads ${t.name}"
                    }

                    "officer" -> {
                        if (t.leader === r) Nodes.townSetLeader(t, null)
                        Nodes.townAddOfficer(t, r)
                        return "${r.name} is now an officer of ${t.name}"
                    }

                    "member" -> {
                        if (t.leader === r) Nodes.townSetLeader(t, null)
                        Nodes.townRemoveOfficer(t, r)
                        return "${r.name} is now a regular member of ${t.name}"
                    }

                    else -> throw Fail("Rank must be leader, officer or member")
                }
            }

            // join a nation by name, creating it if it doesn't exist; empty name leaves
            "town.setNation" -> {
                val t = town(req)
                val target = req.str("nation")
                val current = t.nation
                if (target == current?.name) return "nothing to change"
                if (current != null) Nodes.removeTownFromNation(current, t)
                if (target == null) return "${t.name} left ${current?.name}"
                val existing = Nodes.nations[target]
                if (existing != null) {
                    Nodes.addTownToNation(existing, t).getOrElse { throw Fail("Couldn't join ${existing.name}: ${it.message}") }
                    return "${t.name} joined ${existing.name}"
                }
                req.addProperty("name", target)
                val name = newName(req, "Nation")
                val n = Nodes.createNation(name, t, t.leader).getOrElse { throw Fail("Couldn't create the nation: ${it.message}") }
                return "created nation ${n.name} with ${t.name} as its capital"
            }

            "nation.rename" -> {
                val n = nation(req)
                val old = n.name
                val name = newName(req, "Nation")
                if (name == old) return "nothing to change"
                if (!Nodes.renameNation(n, name)) throw Fail("A nation called $name already exists")
                return "renamed nation $old to $name"
            }

            "nation.color" -> {
                val n = nation(req)
                val (r, g, b) = color(req)
                Nodes.setNationColor(n, r, g, b)
                return "changed the color of ${n.name}"
            }

            "nation.capital" -> {
                val n = nation(req)
                val t = town(req)
                if (t.nation !== n) throw Fail("${t.name} isn't in ${n.name}")
                Nodes.setNationCapital(n, t)
                return "${t.name} is now the capital of ${n.name}"
            }

            "nation.delete" -> {
                val n = nation(req)
                Nodes.destroyNation(n)
                return "deleted nation ${n.name} (its towns are kept)"
            }

            else -> throw Fail("Unknown action $op")
        }
    }
}
