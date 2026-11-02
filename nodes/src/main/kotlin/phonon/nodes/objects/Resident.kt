/**
 * Resident
 * -----------------------------
 * Wrapper around Minecraft player, member
 * of a town.
 * Identified by name which equals unique Minecraft username.
 */

package phonon.nodes.objects

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.chat.ChatMode
import phonon.nodes.serdes.SaveState
import java.util.UUID

public class Resident(val uuid: UUID, val name: String) {
    var town: Town? = null
    var nation: Nation? = null

    var claims: Int = 0
    var claimsTime: Long = 0L

    var townCreateCooldown: Long = 0L

    // nation this resident last left/was kicked from (via a town) and when (epoch ms), for Config.nationJoinCooldown
    var leftNation: String? = null
    var leftNationAt: Long = 0L

    var trusted: Boolean = false

    var isProtectingChests: Boolean = false

    var chatMode: ChatMode = ChatMode.GLOBAL
    var prefix: String = ""
    var suffix: String = ""

    var teleportThread: BukkitTask? = null
    var isTeleportingToOutpost: Boolean = false // if true, gives outpost teleport cost refund if teleport fails
    var isTeleportingToNationTown: Boolean = false // if true, gives nation town teleport cost refund if teleport fails

    var invitingNation: Nation? = null
    var invitingTown: Town? = null
    var invitingPlayer: Player? = null
    var inviteThread: BukkitTask? = null

    var minimap: Minimap? = null

    private var saveState: ResidentSaveState

    @Suppress("PropertyName")
    private var _needsUpdate = false

    init {
        this.saveState = ResidentSaveState(this)
    }

    public override fun hashCode(): Int = this.uuid.hashCode()

    // null when offline
    public fun player(): Player? = Bukkit.getPlayer(this.uuid)

    public fun createMinimap(player: Player, size: Int) {
        this.destroyMinimap()

        this.minimap = Minimap(this, player, size)
    }

    public fun destroyMinimap() {
        val minimap = this.minimap
        if (minimap != null) {
            minimap.destroy()
            this.minimap = null
        }
    }

    public fun updateMinimap(coord: Coord) {
        this.minimap?.render(coord)
    }

    public fun printInfo(sender: CommandSender) {
        val town = this.town?.name ?: "${ChatColor.GRAY}None"
        val nation = this.nation?.name ?: "${ChatColor.GRAY}None"

        Message.print(sender, "${ChatColor.BOLD}Player ${this.name}:")
        Message.print(sender, "- Claim Power${ChatColor.WHITE}: ${this.claims}/${Config.playerClaimsMax}")
        Message.print(sender, "- Town${ChatColor.WHITE}: $town")
        Message.print(sender, "- Nation${ChatColor.WHITE}: $nation")
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    public class ResidentSaveState(r: Resident) : SaveState {
        public val uuid = r.uuid
        public val name = r.name
        public val town = r.town?.name
        public val nation = r.nation?.name
        public val claims = r.claims
        public val claimsTime = r.claimsTime
        public val prefix = r.prefix
        public val suffix = r.suffix
        public val trusted = r.trusted
        public val townCreateCooldown = r.townCreateCooldown
        public val leftNation = r.leftNation
        public val leftNationAt = r.leftNationAt

        public override var jsonString: String? = null

        public override fun createJsonString(): String {
            val jsonString = (
                "{" +
                    "\"name\":\"${this.name}\"," +
                    "\"town\":${ if (this.town !== null) "\"${this.town}\"" else null }," +
                    "\"nation\":${ if (this.nation !== null) "\"${this.nation}\"" else null }," +
                    "\"claims\":[${this.claims},${this.claimsTime}]," +
                    "\"prefix\":\"${this.prefix}\"," +
                    "\"suffix\":\"${this.suffix}\"," +
                    "\"trust\":${this.trusted}," +
                    "\"townCool\":${this.townCreateCooldown}," +
                    "\"leftNation\":${ if (this.leftNation !== null) "\"${this.leftNation}\"" else null }," +
                    "\"leftNationAt\":${this.leftNationAt}" +
                    "}"
                )
            return jsonString
        }
    }

    public fun needsUpdate() {
        this._needsUpdate = true
    }

    public fun getSaveState(): ResidentSaveState {
        if (this._needsUpdate) {
            this.saveState = ResidentSaveState(this)
            this._needsUpdate = false
        }
        return this.saveState
    }
}
