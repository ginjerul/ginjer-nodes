import com.google.gson.JsonParser
import phonon.nodes.shardsync.FolderSyncStore
import phonon.nodes.shardsync.StateMerge
import phonon.nodes.shardsync.SyncEngine
import phonon.nodes.shardsync.SyncStore
import phonon.nodes.shardsync.Snapshot
import java.nio.file.Files

var failed = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("ok   $name") else { println("FAIL $name"); failed++ }
}
fun j(s: String) = JsonParser.parseString(s).asJsonObject

fun world(residents: String = "{}", towns: String = "{}", nations: String = "{}") =
    j("""{"meta":{"type":"towns"},"residents":$residents,"towns":$towns,"nations":$nations}""")

fun main() {
    val T1 = """{"leader":"u1","residents":["u1"],"territories":[1],"claimsBonus":0}"""
    val base = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""", """{"A":$T1}""")

    // changed only on one side
    run {
        val local = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]},"u2":{"town":null,"nation":null}}""", """{"A":$T1}""")
        val remote = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""",
            """{"A":{"leader":"u1","residents":["u1"],"territories":[1,2],"claimsBonus":0}}""")
        val r = StateMerge.merge(base, local, remote)
        val m = r.merged
        check("keeps our new resident", m["residents"].asJsonObject.has("u2"))
        check("takes their town change", m["towns"].asJsonObject["A"].asJsonObject["territories"].asJsonArray.size() == 2)
        check("no conflicts", r.conflicts.isEmpty())
        check("local must apply town A only", r.changedFromLocal == listOf("towns/A"))
        check("remote must be sent our resident only", r.changedFromRemote == listOf("residents/u2"))
    }

    // both changed same town: ours wins and it is reported
    run {
        val local = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""",
            """{"A":{"leader":"u1","residents":["u1"],"territories":[1],"claimsBonus":5}}""")
        val remote = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""",
            """{"A":{"leader":"u1","residents":["u1"],"territories":[1],"claimsBonus":9}}""")
        val r = StateMerge.merge(base, local, remote)
        check("conflict reported", r.conflicts == listOf("towns/A"))
        check("ours wins", r.merged["towns"].asJsonObject["A"].asJsonObject["claimsBonus"].asInt == 5)
    }

    // deletion on one side, untouched on the other
    run {
        val local = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""", "{}")
        val remote = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""", """{"A":$T1}""")
        val r = StateMerge.merge(base, local, remote)
        check("our delete wins over unchanged", !r.merged["towns"].asJsonObject.has("A"))
        val r2 = StateMerge.merge(base, remote, local)
        check("their delete is taken", !r2.merged["towns"].asJsonObject.has("A"))
        check("their delete must be applied locally", r2.changedFromLocal == listOf("towns/A"))
    }

    // delete vs change: changed side kept (ours = changed)
    run {
        val local = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""",
            """{"A":{"leader":"u1","residents":["u1"],"territories":[1],"claimsBonus":3}}""")
        val remote = world("""{"u1":{"town":"A","nation":null,"claims":[0,0]}}""", "{}")
        val r = StateMerge.merge(base, local, remote)
        check("change beats delete", r.merged["towns"].asJsonObject.has("A"))
    }

    // no base (first sync): everything from both, ours wins on difference
    run {
        val local = world("""{"u1":{"town":null,"nation":null}}""")
        val remote = world("""{"u2":{"town":null,"nation":null}}""")
        val r = StateMerge.merge(null, local, remote)
        check("first sync unions entities", r.merged["residents"].asJsonObject.keySet() == setOf("u1", "u2"))
    }

    // identical: nothing to do
    run {
        val r = StateMerge.merge(base, base, base)
        check("identical -> no work", r.conflicts.isEmpty() && r.changedFromLocal.isEmpty() && r.changedFromRemote.isEmpty())
    }

    // issues
    run {
        val w = world(
            """{"u1":{"town":"GONE","nation":null},"u2":{"town":"A","nation":"N"}}""",
            """{"A":{"residents":["u2","u9"],"territories":[1,2]},"B":{"residents":[],"territories":[2]}}""",
            """{"N":{"capital":"A","towns":["A","Z"]}}""",
        )
        val issues = StateMerge.findIssues(w)
        check("finds resident in missing town", issues.any { it.contains("u1") && it.contains("GONE") })
        check("finds missing town resident", issues.any { it.contains("u9") })
        check("finds double claimed territory", issues.any { it.contains("territory 2") })
        check("finds nation with missing town", issues.any { it.contains("missing town Z") })
        check("clean world has no issues", StateMerge.findIssues(base).isEmpty())
    }

    // store
    run {
        val dir = Files.createTempDirectory("sync")
        val a = FolderSyncStore(dir)
        val b = FolderSyncStore(dir)
        check("empty store", a.read() == null && a.version() == 0L)
        check("first write", a.writeIfUnchanged(0, """{"x":1}""") == 1L)
        check("stale write refused", b.writeIfUnchanged(0, """{"x":2}""") == null)
        check("read back", b.read()!!.json == """{"x":1}""" && b.read()!!.version == 1L)
        check("second write", b.writeIfUnchanged(1, """{"x":3}""") == 2L)

        // two threads racing for the same version: exactly one wins
        val dir2 = Files.createTempDirectory("sync2")
        var wins = 0
        val results = java.util.Collections.synchronizedList(ArrayList<Long?>())
        val ts = (1..8).map { i ->
            Thread { results.add(FolderSyncStore(dir2).writeIfUnchanged(0, """{"w":$i}""")) }
        }
        ts.forEach { it.start() }
        ts.forEach { it.join() }
        wins = results.count { it != null }
        check("exactly one racing writer wins", wins == 1)
    }


    // two shards syncing through one store
    run {
        val dir = Files.createTempDirectory("engines")
        val tA = """{"leader":"u1","residents":["u1"],"territories":[1]}"""
        val tB = """{"leader":"u2","residents":["u2"],"territories":[2]}"""
        val a = SyncEngine(FolderSyncStore(dir))
        val b = SyncEngine(FolderSyncStore(dir))

        val aState = world("""{"u1":{"town":"A","nation":null}}""", """{"A":$tA}""")
        val o1 = a.sync(aState.toString())
        check("A publishes first", o1.wrote && o1.version == 1L)

        val bState = world("""{"u2":{"town":"B","nation":null}}""", """{"B":$tB}""")
        val o2 = b.sync(bState.toString())
        check("B publishes and merges A in", o2.wrote && o2.version == 2L)
        check("B sees A's entities as pending", o2.pendingFromOthers.toSet() == setOf("residents/u1", "towns/A"))

        val o3 = a.sync(aState.toString())
        check("A writes nothing, sees B pending", !o3.wrote && o3.pendingFromOthers.toSet() == setOf("residents/u2", "towns/B"))
        check("A needs no more rounds", !a.needsRound(aState.toString(), aState.toString()))

        // both change different things at the same time
        val aState2 = world("""{"u1":{"town":"A","nation":null,"claims":[5,0]}}""", """{"A":$tA}""")
        val bState2 = world("""{"u2":{"town":"B","nation":null}}""",
            """{"B":{"leader":"u2","residents":["u2"],"territories":[2,3]}}""")
        a.sync(aState2.toString())
        b.sync(bState2.toString())
        val finalWorld = JsonParser.parseString(FolderSyncStore(dir).read()!!.json).asJsonObject
        check("both changes survive", finalWorld["residents"].asJsonObject["u1"].asJsonObject["claims"].asJsonArray[0].asInt == 5
            && finalWorld["towns"].asJsonObject["B"].asJsonObject["territories"].asJsonArray.size() == 2)

        // a write sneaks in between our read and our write: engine reads again and still publishes
        val real = FolderSyncStore(dir)
        var injected = false
        val sneaky = object : SyncStore {
            override fun read(): Snapshot? = real.read()
            override fun version(): Long = real.version()
            override fun writeIfUnchanged(expectedVersion: Long, json: String): Long? {
                if (!injected) {
                    injected = true
                    val w = JsonParser.parseString(real.read()!!.json).asJsonObject
                    w["residents"].asJsonObject.add("u7", j("""{"town":null,"nation":null}"""))
                    real.writeIfUnchanged(real.version(), w.toString())
                }
                return real.writeIfUnchanged(expectedVersion, json)
            }
        }
        val c = SyncEngine(sneaky, a.base, a.baseVersion)
        val cState = world("""{"u1":{"town":"A","nation":null,"claims":[9,0]}}""", """{"A":$tA}""")
        val o4 = c.sync(cState.toString())
        val after = JsonParser.parseString(real.read()!!.json).asJsonObject
        check("retry after concurrent write", o4.wrote && injected)
        check("concurrent writer's resident kept, ours too", after["residents"].asJsonObject.has("u7")
            && after["residents"].asJsonObject["u1"].asJsonObject["claims"].asJsonArray[0].asInt == 9)
    }

    println(if (failed == 0) "ALL PASSED" else "$failed FAILED")
    if (failed > 0) System.exit(1)
}
