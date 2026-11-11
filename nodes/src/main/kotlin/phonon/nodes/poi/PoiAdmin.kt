/*
 * /nda poi: place and manage points of interest. Permission nodes.admin.poi or
 * nodes.admin.poi.<action>.
 */

package phonon.nodes.poi

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.economy.Economy
import phonon.nodes.utils.AdminPerms

object PoiAdmin {
    private val ACTIONS = listOf("add", "list", "show", "remove", "tp", "move", "name", "type", "desc", "radius", "reward", "reset")

    fun complete(args: Array<String>): List<String> {
        fun starts(options: List<String>, text: String) = options.filter { it.startsWith(text, ignoreCase = true) }
        return when {
            args.size == 2 -> starts(ACTIONS, args[1])
            args.size == 3 && args[1].equals("add", true) -> starts(PoiType.values().map { it.name.lowercase() }, args[2])
            args.size == 3 && args[1].lowercase() in listOf("show", "remove", "tp", "move", "name", "type", "desc", "radius", "reward") -> starts(PoiData.pois().map { "${it.id}" }, args[2])
            args.size == 3 && args[1].equals("reset", true) -> starts(Bukkit.getOnlinePlayers().map { it.name }, args[2])
            args.size == 4 && args[1].equals("type", true) -> starts(PoiType.values().map { it.name.lowercase() }, args[3])
            args.size == 4 && args[1].equals("reward", true) -> starts(listOf("money", "item", "title", "clearitems"), args[3])
            args.size == 5 && args[1].equals("reward", true) && args[3].equals("item", true) -> starts(Material.values().filter { it.isItem }.map { it.name.lowercase() }, args[4])
            else -> emptyList()
        }
    }

    fun handle(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(1)?.lowercase()
        if (sub == null) {
            help(sender)
            return
        }
        if (!AdminPerms.has(sender, "poi", sub)) {
            Message.error(sender, "You do not have permission for /nodesadmin poi $sub")
            return
        }
        when (sub) {
            "add" -> add(sender, args)
            "list" -> PoiData.pois().also { if (it.isEmpty()) Message.print(sender, "No POIs yet") }.forEach { line(sender, it) }
            "show" -> find(sender, args)?.let { detail(sender, it) }
            "remove" -> find(sender, args)?.let {
                PoiData.remove(it.id)
                Message.print(sender, "${ChatColor.GREEN}Removed #${it.id} ${it.name}")
            }
            "tp" -> tp(sender, args)
            "move" -> move(sender, args)
            "name" -> edit(sender, args) { poi, value ->
                poi.name = value
                true
            }
            "desc" -> edit(sender, args) { poi, value ->
                poi.description = if (value.equals("none", true)) "" else value
                true
            }
            "type" -> edit(sender, args) { poi, value ->
                val type = PoiType.parse(value)
                if (type == null) {
                    Message.error(sender, "Types: ${PoiType.values().joinToString(", ") { it.name.lowercase() }}")
                    false
                } else {
                    poi.type = type.name
                    true
                }
            }
            "radius" -> edit(sender, args) { poi, value ->
                val r = value.toDoubleOrNull()
                if (r == null || r < 2.0 || r > 200.0) {
                    Message.error(sender, "Radius is 2 to 200 blocks")
                    false
                } else {
                    poi.radius = r
                    true
                }
            }
            "reward" -> reward(sender, args)
            "reset" -> reset(sender, args)
            else -> help(sender)
        }
    }

    private fun help(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] POI admin commands:")
        Message.print(sender, "/nda poi add <type> <name...>${ChatColor.WHITE}: Create a place at your position (types: ${PoiType.values().joinToString(", ") { it.name.lowercase() }})")
        Message.print(sender, "/nda poi list${ChatColor.WHITE}: List places")
        Message.print(sender, "/nda poi show <id>${ChatColor.WHITE}: Show one place")
        Message.print(sender, "/nda poi remove <id>${ChatColor.WHITE}: Delete a place")
        Message.print(sender, "/nda poi tp <id>${ChatColor.WHITE}: Teleport to a place")
        Message.print(sender, "/nda poi move <id>${ChatColor.WHITE}: Move a place to your position")
        Message.print(sender, "/nda poi name|desc|type|radius <id> <value...>${ChatColor.WHITE}: Edit a place")
        Message.print(sender, "/nda poi reward <id> money <amount> | item <MATERIAL> [amount] | title <text|none> | clearitems${ChatColor.WHITE}: Set the discovery reward")
        Message.print(sender, "/nda poi reset <player> [id]${ChatColor.WHITE}: Clear a player's discoveries")
    }

    private fun find(sender: CommandSender, args: Array<String>): Poi? {
        val poi = args.getOrNull(2)?.toIntOrNull()?.let { PoiData.get(it) }
        if (poi == null) {
            Message.error(sender, "No POI with that id (see /nda poi list)")
        }
        return poi
    }

    private fun line(sender: CommandSender, p: Poi) {
        Message.print(sender, "#${p.id} ${ChatColor.GOLD}${p.name} ${ChatColor.GRAY}${p.poiType().label} ${p.world} ${p.x.toInt()},${p.y.toInt()},${p.z.toInt()} r=${p.radius.toInt()}")
    }

    private fun detail(sender: CommandSender, p: Poi) {
        line(sender, p)
        if (p.description.isNotBlank()) {
            Message.print(sender, "${ChatColor.GRAY}  ${p.description}")
        }
        val rewards = mutableListOf<String>()
        if (p.money > 0) rewards.add(Economy.show(p.money))
        rewards.addAll(p.items)
        p.title?.let { rewards.add("title " + Pois.colour(it) + ChatColor.GRAY) }
        Message.print(sender, "${ChatColor.GRAY}  reward: ${if (rewards.isEmpty()) "none" else rewards.joinToString(", ")}")
        if (p.firstByName != null) {
            Message.print(sender, "${ChatColor.GRAY}  first found by ${p.firstByName} on ${Pois.date(p.firstAt)}")
        }
    }

    private fun add(sender: CommandSender, args: Array<String>) {
        if (sender !is Player) {
            Message.error(sender, "Run this in game, the POI is placed where you stand")
            return
        }
        val type = args.getOrNull(2)?.let { PoiType.parse(it) }
        if (type == null || args.size < 4) {
            Message.error(sender, "Usage: /nda poi add <type> <name...> (types: ${PoiType.values().joinToString(", ") { it.name.lowercase() }})")
            return
        }
        val loc = sender.location
        val poi = PoiData.add(
            Poi(
                name = args.drop(3).joinToString(" "),
                type = type.name,
                world = loc.world?.name ?: "",
                x = loc.x,
                y = loc.y,
                z = loc.z,
                radius = Pois.defaultRadius,
                money = Pois.defaultMoney[type] ?: 0L,
            ),
        )
        Message.print(sender, "${ChatColor.GREEN}Added POI #${poi.id}")
        detail(sender, poi)
    }

    private fun tp(sender: CommandSender, args: Array<String>) {
        if (sender !is Player) {
            return
        }
        val poi = find(sender, args) ?: return
        val world = Bukkit.getWorld(poi.world)
        if (world == null) {
            Message.error(sender, "World ${poi.world} is not loaded")
            return
        }
        sender.teleport(Location(world, poi.x, poi.y, poi.z))
    }

    private fun move(sender: CommandSender, args: Array<String>) {
        if (sender !is Player) {
            return
        }
        val poi = find(sender, args) ?: return
        val loc = sender.location
        poi.world = loc.world?.name ?: poi.world
        poi.x = loc.x
        poi.y = loc.y
        poi.z = loc.z
        PoiData.savePois()
        Message.print(sender, "${ChatColor.GREEN}Moved #${poi.id} to your position")
    }

    private fun edit(sender: CommandSender, args: Array<String>, change: (Poi, String) -> Boolean) {
        val poi = find(sender, args) ?: return
        if (args.size < 4) {
            Message.error(sender, "Give a value")
            return
        }
        if (change(poi, args.drop(3).joinToString(" "))) {
            PoiData.savePois()
            Message.print(sender, "${ChatColor.GREEN}Updated #${poi.id}")
        }
    }

    private fun reward(sender: CommandSender, args: Array<String>) {
        val poi = find(sender, args) ?: return
        when (args.getOrNull(3)?.lowercase()) {
            "money" -> {
                val text = args.getOrNull(4)
                val cents = if (text == null) -1L else Economy.optionalMoney(text, "poi reward")
                if (text == null || (cents <= 0 && text.trim() != "0")) {
                    Message.error(sender, "Usage: /nda poi reward ${poi.id} money <amount> (0 = none)")
                    return
                }
                poi.money = cents.coerceAtLeast(0L)
            }
            "item" -> {
                val material = args.getOrNull(4)?.let { Material.matchMaterial(it) }
                if (material == null || !material.isItem) {
                    Message.error(sender, "Usage: /nda poi reward ${poi.id} item <MATERIAL> [amount]")
                    return
                }
                val amount = args.getOrNull(5)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                poi.items.add("${material.name}:$amount")
            }
            "title" -> {
                val text = args.drop(4).joinToString(" ")
                if (text.isBlank()) {
                    Message.error(sender, "Usage: /nda poi reward ${poi.id} title <text|none>")
                    return
                }
                poi.title = if (text.equals("none", true)) null else text
            }
            "clearitems" -> poi.items.clear()
            else -> {
                Message.error(sender, "Usage: /nda poi reward <id> money|item|title|clearitems ...")
                return
            }
        }
        PoiData.savePois()
        Message.print(sender, "${ChatColor.GREEN}Updated #${poi.id}")
        detail(sender, poi)
    }

    private fun reset(sender: CommandSender, args: Array<String>) {
        val name = args.getOrNull(2)
        if (name == null) {
            Message.error(sender, "Usage: /nda poi reset <player> [id]")
            return
        }
        val uuid = Bukkit.getOfflinePlayer(name).uniqueId.toString()
        val id = args.getOrNull(3)?.toIntOrNull()
        if (PoiData.reset(uuid, id)) {
            Message.print(sender, "${ChatColor.GREEN}Reset ${if (id == null) "all discoveries" else "POI #$id"} for $name")
        } else {
            Message.error(sender, "Nothing to reset for $name")
        }
    }
}
