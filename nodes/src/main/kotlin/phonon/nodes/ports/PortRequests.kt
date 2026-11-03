/*
 * Port requests. A town leader or officer asks for a port at their position
 * with /port request. Admins review them with /nda port requests: the menu
 * teleports to a request, and approve/deny finish it. Approving creates the
 * port, owned by whoever holds the territory (same rule as every other port).
 */

package phonon.nodes.ports

import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Coord
import phonon.nodes.objects.PortGroup
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

class PortRequest(
    var name: String = "",
    var x: Int = 0,
    var z: Int = 0,
    var town: String = "",
    var townName: String = "",
    var requester: String = "",
    var requesterName: String = "",
    var groups: MutableList<String> = ArrayList(),
    var created: Long = 0L,
    // height the player stood at; the port's beacon pillar starts here (null on old requests)
    var y: Int? = null,
)

object PortRequests {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/portrequests.json")
    private val requests: LinkedHashMap<String, PortRequest> = LinkedHashMap()
    private val namePattern = Regex("[a-z0-9_-]{2,24}")

    // ===================================
    // persistence
    // ===================================
    fun load() {
        requests.clear()
        if (!Files.exists(path)) {
            return
        }
        try {
            val loaded = gson.fromJson(String(Files.readAllBytes(path), StandardCharsets.UTF_8), Array<PortRequest>::class.java)
            loaded?.forEach { requests[it.name] = it }
            Nodes.logger?.info("[Ports] Loaded ${requests.size} port requests")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Ports] Could not read portrequests.json: ${e.message}")
        }
    }

    // writes through a temp file so a crash never leaves a half-written list
    private fun save() {
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("portrequests.tmp")
            Files.write(tmp, gson.toJson(requests.values.toTypedArray()).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Nodes.logger?.severe("[Ports] Could not save portrequests.json: ${e.message}")
        }
    }

    fun all(): List<PortRequest> = requests.values.toList()

    fun get(name: String): PortRequest? = requests[name.lowercase()]

    // ===================================
    // players
    // ===================================
    // returns an error message, or null when the request was filed
    fun submit(player: Player, rawName: String, groupNames: List<String>): String? {
        val resident = Nodes.getResident(player) ?: return "Nodes has not loaded you yet"
        val town = resident.town ?: return "Join a town first"
        if (town.leader !== resident && !town.officers.contains(resident)) {
            return "Only the town leader or an officer can request a port"
        }
        val name = rawName.lowercase()
        if (!namePattern.matches(name)) {
            return "Port names use 2-24 lowercase letters, numbers, underscores or hyphens"
        }
        if (Nodes.ports.containsKey(name) || requests.containsKey(name)) {
            return "That port name is taken"
        }
        if (requests.values.any { it.town == town.uuid.toString() }) {
            return "Your town already has a pending port request. Use /port request cancel first"
        }
        if (player.world != Bukkit.getWorlds().first()) {
            return "Ports can only be requested in the main world"
        }
        val x = player.location.blockX
        val z = player.location.blockZ
        val cx = Math.floorDiv(x, 16)
        val cz = Math.floorDiv(z, 16)
        val chunk = Nodes.getTerritoryChunkFromCoord(Coord(cx, cz)) ?: return "You are not inside any territory"
        val holder = chunk.occupier ?: chunk.territory.town
        if (holder !== town) {
            return "The port has to be inside territory held by your town"
        }
        if (Nodes.chunkToPort.containsKey(listOf(cx, cz)) || requests.values.any { Math.floorDiv(it.x, 16) == cx && Math.floorDiv(it.z, 16) == cz }) {
            return "There is already a port or request in this chunk"
        }
        val groups = mutableListOf<String>()
        for (g in groupNames) {
            val group = Nodes.getPortGroupFromName(g.lowercase()) ?: return "Unknown port group '$g'. Ask an admin which groups exist"
            groups.add(group.name)
        }
        requests[name] = PortRequest(
            name = name,
            x = x,
            z = z,
            town = town.uuid.toString(),
            townName = town.name,
            requester = player.uniqueId.toString(),
            requesterName = player.name,
            groups = groups,
            created = System.currentTimeMillis(),
            y = player.location.blockY,
        )
        save()
        for (admin in Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("nodes.admin")) {
                Message.print(admin, "${ChatColor.AQUA}[Ports] ${town.name} requested port \"$name\" at ($x, $z). Review with /nda port requests")
            }
        }
        return null
    }

    // cancels the requester's town's pending request; an error message or null
    fun cancel(player: Player): String? {
        val town = Nodes.getResident(player)?.town ?: return "Join a town first"
        val request = requests.values.firstOrNull { it.town == town.uuid.toString() } ?: return "Your town has no pending port request"
        requests.remove(request.name)
        save()
        return null
    }

    // ===================================
    // admins
    // ===================================
    fun teleport(admin: Player, request: PortRequest): String? {
        val world = Bukkit.getWorlds().first()
        val y = world.getHighestBlockYAt(request.x, request.z) + 1
        admin.teleport(Location(world, request.x + 0.5, y.toDouble(), request.z + 0.5))
        return null
    }

    private fun notify(request: PortRequest, text: String) {
        runCatching { UUID.fromString(request.requester) }.getOrNull()?.let { Bukkit.getPlayer(it) }?.let { Message.print(it, text) }
    }

    // returns an error message, or null when the port was created
    fun approve(name: String, by: CommandSender): String? {
        val request = get(name) ?: return "No request named $name"
        if (Nodes.ports.containsKey(request.name)) {
            requests.remove(request.name)
            save()
            return "A port named ${request.name} already exists; the request was removed"
        }
        val groups = HashSet<PortGroup>()
        request.groups.forEach { Nodes.getPortGroupFromName(it)?.let { group -> groups.add(group) } }
        Nodes.createPort(request.name, request.x, request.z, groups, false, request.y).getOrElse { return "Could not create the port: ${it.message}" }
        requests.remove(request.name)
        save()
        notify(request, "${ChatColor.GREEN}Your port request \"${request.name}\" was approved by ${by.name}")
        return null
    }

    fun deny(name: String, reason: String, by: CommandSender): String? {
        val request = get(name) ?: return "No request named $name"
        requests.remove(request.name)
        save()
        val why = if (reason.isBlank()) "" else ": $reason"
        notify(request, "${ChatColor.RED}Your port request \"${request.name}\" was denied by ${by.name}$why")
        return null
    }
}

private class RequestHolder(val names: Array<String?>) : InventoryHolder {
    lateinit var inv: Inventory

    override fun getInventory(): Inventory = inv
}

object PortRequestMenu {
    fun open(admin: Player) {
        val list = PortRequests.all().take(54)
        val holder = RequestHolder(arrayOfNulls(54))
        val inv = Bukkit.createInventory(holder, 54, "Port requests (${list.size})")
        holder.inv = inv
        list.forEachIndexed { slot, request ->
            val item = ItemStack(Material.COMPASS)
            val meta = item.itemMeta
            meta?.setDisplayName("${ChatColor.AQUA}${request.name}")
            val minutes = (System.currentTimeMillis() - request.created) / 60_000L
            meta?.lore = listOf(
                "${ChatColor.GRAY}Town: ${request.townName}",
                "${ChatColor.GRAY}Requested by: ${request.requesterName}",
                "${ChatColor.GRAY}Location: (${request.x}, ${request.z})",
                "${ChatColor.GRAY}Groups: ${if (request.groups.isEmpty()) "none yet" else request.groups.joinToString(", ")}",
                "${ChatColor.GRAY}Waiting: ${minutes}m",
                "${ChatColor.GREEN}Click to teleport there",
                "${ChatColor.GRAY}/nda port requests ${request.name} approve|deny",
            )
            item.itemMeta = meta
            inv.setItem(slot, item)
            holder.names[slot] = request.name
        }
        admin.openInventory(inv)
    }
}

class PortRequestMenuListener : Listener {
    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is RequestHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? RequestHolder ?: return
        event.isCancelled = true
        val admin = event.whoClicked as? Player ?: return
        if (!admin.hasPermission("nodes.admin")) {
            admin.closeInventory()
            return
        }
        if (event.rawSlot < 0 || event.rawSlot >= event.view.topInventory.size) {
            return
        }
        val name = holder.names.getOrNull(event.rawSlot) ?: return
        val request = PortRequests.get(name)
        admin.closeInventory()
        if (request == null) {
            Message.error(admin, "That request no longer exists")
            return
        }
        PortRequests.teleport(admin, request)
        Message.print(admin, "${ChatColor.AQUA}Teleported to request \"${request.name}\" from ${request.townName}. Decide with /nda port requests ${request.name} approve|deny")
    }
}
