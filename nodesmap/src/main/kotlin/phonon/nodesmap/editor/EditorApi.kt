package phonon.nodesmap.editor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Server
import phonon.nodesmap.web.WebServer.ApiRequest
import phonon.nodesmap.web.WebServer.ApiResponse
import java.io.File
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Web editor backend.
 *
 * Login: `/nodesmap editor` (permission nodesmap.editor) creates a random
 * 256-bit token and shows a link with it. The browser sends it as
 * `Authorization: Bearer <token>`; nothing works without one.
 *
 * World changes are validated and applied by Nodes itself
 * (phonon.nodes.map.MapEditApi), reached by reflection so the two plugins
 * stay independent jars.
 */
class EditorApi(
    private val server: Server,
    private val logger: Logger,
    @Volatile var sessionMinutes: Int,
    private val sessionFile: File? = null,
) {
    class Session(val user: String, val uuid: UUID?, val expiresAt: Long)

    // keyed by SHA-256 of the token, so the saved file can't be used to log in
    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    init {
        load()
    }

    private fun load() {
        val f = sessionFile ?: return
        if (!f.exists()) return
        try {
            val now = System.currentTimeMillis()
            f.reader().use { r ->
                for (el in JsonParser.parseReader(r).asJsonObject.getAsJsonArray("sessions")) {
                    val o = el.asJsonObject
                    val exp = o.get("expiresAt").asLong
                    if (exp < now) continue
                    val uuid = o.get("uuid")?.takeIf { !it.isJsonNull }?.asString?.let { UUID.fromString(it) }
                    sessions[o.get("hash").asString] = Session(o.get("user").asString, uuid, exp)
                }
            }
        } catch (e: Exception) {
            logger.warning("[NodesMap] could not read editor sessions: ${e.message}")
        }
    }

    private fun save() {
        val f = sessionFile ?: return
        try {
            val arr = JsonArray()
            for ((h, s) in sessions) {
                val o = JsonObject()
                o.addProperty("hash", h)
                o.addProperty("user", s.user)
                o.addProperty("uuid", s.uuid?.toString())
                o.addProperty("expiresAt", s.expiresAt)
                arr.add(o)
            }
            val root = JsonObject()
            root.add("sessions", arr)
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
        } catch (e: Exception) {
            logger.warning("[NodesMap] could not save editor sessions: ${e.message}")
        }
    }

    fun createSession(user: String, uuid: UUID?): String {
        purge()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[hash(token)] = Session(user, uuid, System.currentTimeMillis() + sessionMinutes * 60_000L)
        save()
        return token
    }

    /** Ends every session opened by this user (or all sessions if user is null). Returns how many. */
    fun revoke(user: String?): Int {
        val before = sessions.size
        if (user == null) {
            sessions.clear()
        } else {
            sessions.values.removeIf { it.user.equals(user, ignoreCase = true) }
        }
        save()
        return before - sessions.size
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        sessions.values.removeIf { it.expiresAt < now }
    }

    private fun session(token: String?): Session? {
        if (token == null) return null
        val key = hash(token)
        val s = sessions[key] ?: return null
        if (s.expiresAt < System.currentTimeMillis()) {
            sessions.remove(key)
            return null
        }
        return s
    }

    // ------------------------------------------------------------------
    // bridge to Nodes

    private class Bridge(val worldJson: Method, val apply: Method, val townsJson: Method?, val townAct: Method?)

    @Volatile private var bridge: Bridge? = null

    private fun bridge(): Bridge? {
        bridge?.let { return it }
        val nodes = server.pluginManager.getPlugin("nodes") ?: return null
        if (!nodes.isEnabled) return null
        return try {
            val cls = Class.forName("phonon.nodes.map.MapEditApi", true, nodes.javaClass.classLoader)
            // town editing arrived later: older Nodes jars just don't have it
            val towns = try {
                Class.forName("phonon.nodes.map.MapTownApi", true, nodes.javaClass.classLoader)
            } catch (e: ClassNotFoundException) {
                null
            }
            val b = Bridge(
                cls.getMethod("worldJson"),
                cls.getMethod("apply", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java),
                towns?.getMethod("townsJson"),
                towns?.getMethod("act", String::class.java, String::class.java),
            )
            bridge = b
            b
        } catch (e: Exception) {
            logger.warning("this Nodes version has no map editor support: $e")
            null
        }
    }

    /** Nodes was reloaded/replaced: look the methods up again next time. */
    fun resetBridge() {
        bridge = null
    }

    // ------------------------------------------------------------------
    // routes

    fun handle(req: ApiRequest): ApiResponse {
        val s = session(req.token) ?: return err(401, "Your editor session has expired. Run /nodesmap editor in game for a new link.")
        return when (req.path) {
            "/api/editor/session" -> ApiResponse(
                200,
                "{\"user\":${q(s.user)},\"expiresAt\":${s.expiresAt},\"nodes\":${bridge() != null},\"towns\":${bridge()?.townsJson != null}}",
            )

            "/api/editor/world" -> {
                val b = bridge() ?: return noNodes()
                ApiResponse(200, b.worldJson.invoke(null) as String)
            }

            "/api/editor/check", "/api/editor/apply" -> {
                if (req.method != "POST") return err(405, "use POST")
                val b = bridge() ?: return noNodes()
                val apply = req.path.endsWith("apply")
                val force = req.query["force"] == "1"
                val json = String(req.body, StandardCharsets.UTF_8)
                val out = b.apply.invoke(null, json, !apply, force, s.user) as String
                if (apply && out.contains("\"applied\":true")) {
                    logger.info("${s.user} applied a world edit from the web editor")
                }
                ApiResponse(200, out)
            }

            "/api/editor/towns" -> {
                val m = bridge()?.townsJson ?: return noTowns()
                ApiResponse(200, m.invoke(null) as String)
            }

            "/api/editor/town" -> {
                if (req.method != "POST") return err(405, "use POST")
                val m = bridge()?.townAct ?: return noTowns()
                ApiResponse(200, m.invoke(null, String(req.body, StandardCharsets.UTF_8), s.user) as String)
            }

            else -> err(404, "unknown editor route")
        }
    }

    private fun noNodes() = err(503, "Nodes isn't running, or this Nodes version doesn't support the editor. You can still edit and download world.json.")

    private fun noTowns() = err(503, "This Nodes version can't edit towns from the map. Update nodes.jar.")

    private fun err(status: Int, msg: String) = ApiResponse(status, "{\"error\":${q(msg)}}")

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
