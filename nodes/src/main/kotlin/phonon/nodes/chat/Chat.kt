/**
 * Basic chat pub/sub channels manager.
 */

package phonon.nodes.chat

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerChatEvent
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Resident
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

public enum class ChatMode {
    GLOBAL,
    TOWN,
    NATION,
    ALLY,
    LOCAL,
    RP,
}

public object Chat {

    val playersMuteGlobal: HashSet<Player> = hashSetOf()

    // admins with /nda socialspy on: they also see town, nation and ally chat
    val spies: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // sends a private copy of a channel message to every spy who did not already get it
    private fun spy(event: AsyncPlayerChatEvent, channel: String) {
        if (spies.isEmpty()) {
            return
        }
        val text = "${ChatColor.DARK_GRAY}[Spy|$channel] ${ChatColor.GRAY}${event.player.name}: ${event.message}"
        for (uuid in spies.toList()) {
            val spyPlayer = Bukkit.getPlayer(uuid)
            if (spyPlayer == null || spyPlayer === event.player || event.recipients.contains(spyPlayer)) {
                continue
            }
            if (!spyPlayer.hasPermission("nodes.admin")) {
                spies.remove(uuid)
                continue
            }
            spyPlayer.sendMessage(text)
        }
    }

    // toggles social spy for a player; returns true when it is now on
    fun toggleSpy(player: Player): Boolean = if (spies.remove(player.uniqueId)) {
        false
    } else {
        spies.add(player.uniqueId)
        true
    }

    var colorDefault = ChatColor.WHITE
    val colorGreen = ChatColor.GREEN

    var colorTown = ChatColor.DARK_AQUA
    var colorNation = ChatColor.GOLD
    var colorAlly = ChatColor.GREEN

    var colorPlayerTownless = ChatColor.GRAY
    var colorPlayerOp = ChatColor.DARK_RED
    var colorPlayerTownOfficer = ChatColor.WHITE
    var colorPlayerTownLeader = ChatColor.BOLD
    var colorPlayerNationLeader = "${ChatColor.GOLD}${ChatColor.BOLD}"

    public fun process(event: AsyncPlayerChatEvent) {
        val msg = event.getMessage()
        if (msg.get(0) == '>') {
            event.setMessage("${colorGreen}$msg")
        }

        val player = event.getPlayer()
        val fetchResident = Nodes.getResident(player)
        val resident: Resident = if (fetchResident != null) {
            fetchResident
        } else {
            return
        }

        val chatMode = resident.chatMode

        when (chatMode) {
            ChatMode.GLOBAL -> {
                val recipients = event.getRecipients()
                for (p in Chat.playersMuteGlobal) {
                    recipients.remove(p)
                }
                event.setFormat(formatMsgGlobal(resident))
            }
            ChatMode.TOWN -> {
                val town = resident.town
                if (town == null) {
                    event.setCancelled(true)
                    return
                }
                event.getRecipients().clear()
                event.getRecipients().addAll(town.playersOnline)
                event.setFormat(formatMsgTown(resident))
                spy(event, "Town: ${town.name}")
            }
            ChatMode.NATION -> {
                val nation = resident.nation
                if (nation == null) {
                    event.setCancelled(true)
                    return
                }
                event.getRecipients().clear()
                event.getRecipients().addAll(nation.playersOnline)
                event.setFormat(formatMsgNation(resident))
                spy(event, "Nation: ${nation.name}")
            }
            ChatMode.LOCAL -> {
                setNearbyRecipients(event, Config.localChatDistance)
                event.setFormat("${ChatColor.YELLOW}[Local] ${formatResidentName(resident)}${ChatColor.YELLOW}: %2\$s")
            }
            ChatMode.RP -> {
                setNearbyRecipients(event, Config.rpChatDistance)
                event.setFormat("${ChatColor.LIGHT_PURPLE}(RP) ${formatResidentName(resident)}${ChatColor.LIGHT_PURPLE}: %2\$s")
            }
            ChatMode.ALLY -> {
                val town = resident.town
                if (town == null) {
                    event.setCancelled(true)
                    return
                }
                event.getRecipients().clear()
                event.getRecipients().addAll(town.playersOnline)
                for (allyTown in town.allies) {
                    event.getRecipients().addAll(allyTown.playersOnline)
                }
                event.setFormat(formatMsgAlly(resident))
                spy(event, "Ally: ${town.name}")
            }
        }
    }

    // only players in the same world within `distance` blocks hear the message
    private fun setNearbyRecipients(event: AsyncPlayerChatEvent, distance: Double) {
        val from = event.player.location
        val limit = distance * distance
        val near = Bukkit.getOnlinePlayers().filter { it.world == from.world && it.location.distanceSquared(from) <= limit }
        event.recipients.clear()
        event.recipients.addAll(near)
        event.recipients.add(event.player)
    }

    public fun enableGlobalChat(player: Player) {
        Chat.playersMuteGlobal.remove(player)
    }

    public fun disableGlobalChat(player: Player) {
        Chat.playersMuteGlobal.add(player)
    }

    public fun isMuted(player: Player): Boolean {
        // TODO: hook into essentials, check muted player
        return false
    }

    public fun formatResidentName(resident: Resident): String {
        val town = resident.town
        val nation = resident.nation

        val color = if (resident.player()?.isOp() == true) {
            colorPlayerOp
        } else if (town == null) {
            colorPlayerTownless
        } else {
            if (resident === nation?.capital?.leader) {
                colorPlayerNationLeader
            } else if (resident.uuid == town.leader?.uuid) {
                colorPlayerTownLeader
            } else if (town.officers.contains(resident)) {
                colorPlayerTownOfficer
            } else {
                colorDefault
            }
        }

        if (resident.prefix != "" && resident.suffix != "") {
            return "${color}${resident.prefix} $color%1\$s ${color}${resident.suffix}"
        } else if (resident.prefix != "") {
            return "${color}${resident.prefix} $color%1\$s"
        } else if (resident.suffix != "") {
            return "$color%1\$s ${resident.suffix}"
        } else {
            return "$color%1\$s"
        }
    }

    public fun formatMsgGlobal(resident: Resident): String {
        val formattedResidentName = formatResidentName(resident)

        val formattedResidentAllegience = if (Config.chatPrefixMode == 2) {
            ""
        } else if (Config.chatPrefixMode == 1 && resident.town != null) {
            "[${colorTown}${resident.town?.name}$colorDefault] "
        } else if (Config.chatPrefixMode == 1) {
            ""
        } else if (resident.town != null && resident.nation != null) {
            "[${colorNation}${resident.nation?.name}$colorDefault|${colorTown}${resident.town?.name}$colorDefault] "
        } else if (resident.town != null) {
            "[${colorTown}${resident.town?.name}$colorDefault] "
        } else {
            ""
        }

        return "${formattedResidentAllegience}${formattedResidentName}$colorDefault: %2\$s"
    }

    public fun formatMsgTown(resident: Resident): String {
        val formattedResidentName = formatResidentName(resident)

        return "$colorTown[Town] ${formattedResidentName}$colorTown: %2\$s"
    }

    public fun formatMsgNation(resident: Resident): String {
        val formattedResidentName = formatResidentName(resident)

        return "$colorNation[Nation] ${formattedResidentName}$colorNation: %2\$s"
    }

    public fun formatMsgAlly(resident: Resident): String {
        val formattedResidentName = formatResidentName(resident)

        return "$colorAlly[Ally] ${formattedResidentName}$colorAlly: %2\$s"
    }
}
