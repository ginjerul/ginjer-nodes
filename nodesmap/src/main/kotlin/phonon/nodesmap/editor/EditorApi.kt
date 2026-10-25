package phonon.nodesmap.editor

import org.bukkit.Server
import phonon.nodesmap.web.WebServer.ApiRequest
import phonon.nodesmap.web.WebServer.ApiResponse
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
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
) {
    class Session(val user: String, val uuid: UUID?, val expiresAt: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun createSession(user: String, uuid: UUID?): String {
        purge()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = Session(user, uuid, System.currentTimeMillis() + sessionMinutes * 60_000L)
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
        return before - sessions.size
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        sessions.values.removeIf { it.expiresAt < now }
    }

    private fun session(token: String?): Session? {
        if (token == null) return null
        val s = sessions[token] ?: return null
        if (s.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token)
            return null
        }
        return s
    }

    // ------------------------------------------------------------------
    // bridge to Nodes

    private class Bridge(val worldJson: Method, val apply: Method)

    @Volatile private var bridge: Bridge? = null

    private fun bridge(): Bridge? {
        bridge?.let { return it }
        val nodes = server.pluginManager.getPlugin("nodes") ?: return null
        if (!nodes.isEnabled) return null
        return try {
            val cls = Class.forName("phonon.nodes.map.MapEditApi", true, nodes.javaClass.classLoader)
            val b = Bridge(
                cls.getMethod("worldJson"),
                cls.getMethod("apply", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java),
            )
            bridge = b
            b
        } catch (e: Exception) {
            logger.warning("[NodesMap] this Nodes version has no map editor support: $e")
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
                "{\"user\":${q(s.user)},\"expiresAt\":${s.expiresAt},\"nodes\":${bridge() != null}}",
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
                    logger.info("[NodesMap] ${s.user} applied a world edit from the web editor")
                }
                ApiResponse(200, out)
            }

            else -> err(404, "unknown editor route")
        }
    }

    private fun noNodes() = err(503, "Nodes isn't running, or this Nodes version doesn't support the editor. You can still edit and download world.json.")

    private fun err(status: Int, msg: String) = ApiResponse(status, "{\"error\":${q(msg)}}")

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
