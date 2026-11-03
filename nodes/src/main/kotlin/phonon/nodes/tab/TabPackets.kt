/*
 * Sends the tab list as packets (PacketEvents). Every viewer gets 80 fake entries (4 columns of
 * 20 rows) with fixed ids; real players are only marked as not listed, so they still render in
 * the world. Only entries that changed since the last update are sent again.
 * Never touch this class unless the PacketEvents plugin is present.
 */

package phonon.nodes.tab

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.UUID

internal object TabPackets {
    private const val SLOTS = TabList.COLUMNS * TabList.ROWS

    private class Shown(val text: Component, val head: UUID?)

    // what each viewer's client currently shows, per slot
    private val states = HashMap<UUID, Array<Shown?>>()

    private val slotIds: List<UUID> = (0 until SLOTS).map { UUID.nameUUIDFromBytes("nodes-tab-$it".toByteArray()) }

    fun forget(viewer: UUID) {
        states.remove(viewer)
    }

    fun show(viewer: Player, online: List<Player>, columns: List<List<TabCell?>>) {
        val api = PacketEvents.getAPI().playerManager

        // real players stay in the world but leave the tab list
        val hide = online.map {
            val info = PlayerInfo(it.uniqueId)
            info.isListed = false
            info
        }
        api.sendPacket(viewer, WrapperPlayServerPlayerInfoUpdate(Action.UPDATE_LISTED, hide))

        val first = !states.containsKey(viewer.uniqueId)
        val state = states.getOrPut(viewer.uniqueId) { arrayOfNulls<Shown>(SLOTS) }

        val adds = ArrayList<PlayerInfo>()
        val renames = ArrayList<PlayerInfo>()
        val removes = ArrayList<UUID>()
        for (column in 0 until TabList.COLUMNS) {
            for (row in 0 until TabList.ROWS) {
                val slot = column * TabList.ROWS + row
                val cell = columns.getOrNull(column)?.getOrNull(row)
                val text = cell?.text ?: Component.empty()
                val head = cell?.head
                val last = state[slot]
                if (last != null && last.text == text && last.head == head?.uniqueId) {
                    continue
                }
                state[slot] = Shown(text, head?.uniqueId)
                if (last == null || last.head != head?.uniqueId) {
                    if (last != null) {
                        removes.add(slotIds[slot])
                    }
                    adds.add(entry(slot, text, head, viewer))
                } else {
                    val info = PlayerInfo(slotIds[slot])
                    info.displayName = text
                    renames.add(info)
                }
            }
        }

        if (removes.isNotEmpty()) {
            api.sendPacket(viewer, WrapperPlayServerPlayerInfoRemove(removes))
        }
        if (adds.isNotEmpty()) {
            api.sendPacket(
                viewer,
                WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(
                        Action.ADD_PLAYER,
                        Action.UPDATE_GAME_MODE,
                        Action.UPDATE_LISTED,
                        Action.UPDATE_LATENCY,
                        Action.UPDATE_DISPLAY_NAME,
                        Action.UPDATE_LIST_ORDER,
                    ),
                    adds,
                ),
            )
        }
        if (renames.isNotEmpty() && !first) {
            api.sendPacket(viewer, WrapperPlayServerPlayerInfoUpdate(Action.UPDATE_DISPLAY_NAME, renames))
        }
    }

    // a fake entry; the name makes older clients sort the slots in order, the list order does it on new ones
    private fun entry(slot: Int, text: Component, head: Player?, viewer: Player): PlayerInfo {
        val textures = ArrayList<TextureProperty>()
        val property = head?.playerProfile?.properties?.firstOrNull { it.name == "textures" }
        if (property != null) {
            textures.add(TextureProperty(property.name, property.value, property.signature))
        }
        val profile = UserProfile(slotIds[slot], String.format("!%02d", slot), textures)
        val latency = if (TabList.pingEnabled() && head != null) head.ping else 0
        return PlayerInfo(profile, true, latency, GameMode.SURVIVAL, text, null, 1000 - slot)
    }
}
