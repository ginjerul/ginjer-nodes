/*
 * Sharding phase 2: a stand-in for a player who is online on a follower shard, used on the leader
 * to run that player's Nodes command with the normal command code. It answers who the player is,
 * where they stand (as the follower reported it) and which Nodes permissions they have. Messages
 * sent to it are collected and sent back to the real player. Everything else returns an empty
 * value, so only commands that need nothing more are forwarded (see RemoteCommands.route).
 */

package phonon.nodes.shard

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

internal class CommandRequest(
    val id: String,
    val shard: String,
    val uuid: UUID,
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val op: Boolean,
    val permissions: Set<String>,
    val label: String,
    val args: List<String>,
    val sentAt: Long,
)

internal object RemotePlayer {
    /** A Player for [request]; every message sent to it goes to [sink]. */
    fun create(request: CommandRequest, sink: (Component) -> Unit): Player {
        val world = Bukkit.getWorld(request.world) ?: Bukkit.getWorlds()[0]
        val location = Location(world, request.x, request.y, request.z, request.yaw, request.pitch)
        var self: Player? = null

        val handler = InvocationHandler { _, method, rawArgs ->
            val args = rawArgs ?: emptyArray()
            when (method.name) {
                "getUniqueId" -> request.uuid
                "getName", "getDisplayName", "getPlayerListName" -> request.name
                "name", "displayName", "playerListName", "teamDisplayName" -> Component.text(request.name)
                "getLocation" -> {
                    val target = args.getOrNull(0) as? Location
                    if (target != null) {
                        target.world = location.world
                        target.x = location.x
                        target.y = location.y
                        target.z = location.z
                        target.yaw = location.yaw
                        target.pitch = location.pitch
                        target
                    } else {
                        location.clone()
                    }
                }
                "getWorld" -> world
                "getServer" -> Bukkit.getServer()
                "getPlayer" -> self
                "getGameMode" -> GameMode.SURVIVAL
                "isOnline", "isValid", "isConnected" -> true
                "isOp" -> request.op
                "hasPermission" -> hasPermission(request, args.getOrNull(0))
                "isPermissionSet" -> hasPermission(request, args.getOrNull(0))
                "sendMessage", "sendRichMessage", "sendPlainMessage", "sendRawMessage" -> {
                    for (arg in args) {
                        toComponents(arg).forEach(sink)
                    }
                    null
                }
                "equals" -> self === args.getOrNull(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "RemotePlayer{${request.name} on ${request.shard}}"
                else -> emptyValue(method)
            }
        }
        val proxy = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java), handler) as Player
        self = proxy
        return proxy
    }

    private fun hasPermission(request: CommandRequest, permission: Any?): Boolean {
        val name = when (permission) {
            is String -> permission
            is Permission -> permission.name
            else -> return false
        }
        return request.op || name.lowercase() in request.permissions
    }

    private fun toComponents(arg: Any?): List<Component> = when (arg) {
        is ComponentLike -> listOf(arg.asComponent())
        is String -> listOf(LegacyComponentSerializer.legacySection().deserialize(arg))
        is Array<*> -> arg.flatMap { toComponents(it) }
        else -> emptyList()
    }

    private fun emptyValue(method: Method): Any? = when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }
}
