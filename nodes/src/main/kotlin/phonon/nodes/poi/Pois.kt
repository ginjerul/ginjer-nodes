/*
 * Discovery: players find POIs by walking into their radius. A find shows a title and gives the
 * POI's reward once; counts of finds earn milestone titles. Settings are under `poi:` in the config.
 */

package phonon.nodes.poi

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.economy.Economy
import phonon.nodes.refinery.Refineries
import java.text.SimpleDateFormat
import java.util.Date

object Pois {
    private var enabled = true
    private var checkTicks = 40L
    internal var defaultRadius = 12.0
    internal var activationDelayMs = 120_000L
    private var announceFirst = true
    internal var showTotal = true
    internal var defaultMoney: Map<PoiType, Long> = emptyMap()
    internal var milestones: Map<Int, String> = emptyMap()
    private var task: BukkitTask? = null

    fun loadConfig(config: ConfigurationSection) {
        val sec = config.getConfigurationSection("poi")
        enabled = sec?.getBoolean("enabled", true) ?: true
        checkTicks = (sec?.getLong("checkTicks", 40L) ?: 40L).coerceIn(10L, 600L)
        defaultRadius = (sec?.getDouble("defaultRadius", 12.0) ?: 12.0).coerceIn(2.0, 200.0)
        activationDelayMs = (sec?.getLong("activationDelaySeconds", 120L) ?: 120L).coerceIn(0L, 86400L) * 1000L
        announceFirst = sec?.getBoolean("announceFirstDiscovery", true) ?: true
        showTotal = sec?.getBoolean("showTotal", true) ?: true
        val money = HashMap<PoiType, Long>()
        for (type in PoiType.values()) {
            money[type] = Economy.optionalMoney(sec?.get("defaultMoney.${type.name.lowercase()}"), "poi.defaultMoney.${type.name.lowercase()}")
        }
        defaultMoney = money
        val ms = LinkedHashMap<Int, String>()
        val msSec = sec?.getConfigurationSection("milestones")
        for (key in msSec?.getKeys(false) ?: emptySet()) {
            val n = key.toIntOrNull() ?: continue
            val title = msSec?.getString(key) ?: continue
            if (n > 0 && title.isNotBlank()) {
                ms[n] = title
            }
        }
        milestones = ms.toSortedMap()
    }

    fun isEnabled(): Boolean = enabled

    fun start(plugin: Plugin) {
        task?.cancel()
        task = null
        PoiData.load()
        if (!enabled) {
            return
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { check() }, 100L, checkTicks)
    }

    private fun check() {
        val pois = PoiData.pois()
        if (pois.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        try {
            for (player in Bukkit.getOnlinePlayers()) {
                if (player.gameMode == GameMode.SPECTATOR) {
                    continue
                }
                val uuid = player.uniqueId.toString()
                val loc = player.location
                for (poi in pois) {
                    if (poi.activeAt > now || poi.world != loc.world?.name || PoiData.hasFound(uuid, poi.id)) {
                        continue
                    }
                    val dx = loc.x - poi.x
                    val dy = loc.y - poi.y
                    val dz = loc.z - poi.z
                    if (dx * dx + dy * dy + dz * dz <= poi.radius * poi.radius) {
                        discover(player, poi)
                    }
                }
            }
        } catch (e: Exception) {
            Nodes.logger?.warning("[POI] Discovery check stopped for one cycle: ${e.message}")
        }
    }

    private fun discover(player: Player, poi: Poi) {
        val progress = PoiData.progress(player.uniqueId.toString())
        progress.found["${poi.id}"] = System.currentTimeMillis()

        val first = poi.firstBy == null
        if (first) {
            poi.firstBy = player.uniqueId.toString()
            poi.firstByName = player.name
            poi.firstAt = System.currentTimeMillis()
            PoiData.savePois()
        }

        player.sendTitle("${ChatColor.GOLD}${poi.name}", "${ChatColor.YELLOW}${poi.poiType().label} discovered", 10, 60, 20)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
        Message.print(player, "${ChatColor.GOLD}You discovered ${ChatColor.WHITE}${poi.name}${ChatColor.GOLD}!${if (poi.description.isNotBlank()) " ${ChatColor.GRAY}${poi.description}" else ""}")
        if (announceFirst && first) {
            Message.broadcast("${ChatColor.GOLD}[Explorer] ${player.name} was the first to discover ${poi.name}!")
        }

        giveRewards(player, poi, progress)

        val count = PoiData.foundCount(player.uniqueId.toString())
        for ((needed, title) in milestones) {
            if (count >= needed && !progress.titles.contains(title)) {
                progress.titles.add(title)
                Message.print(player, "${ChatColor.LIGHT_PURPLE}Title earned: ${colour(title)} ${ChatColor.GRAY}(/map title)")
            }
        }
        PoiData.saveDiscoveries()
    }

    private fun giveRewards(player: Player, poi: Poi, progress: PoiProgress) {
        if (poi.money > 0 && Economy.enabled) {
            try {
                Economy.transaction {
                    val wallet = "wallet-${player.uniqueId}"
                    Economy.credit(wallet, poi.money)
                    Economy.log("poi-discovery", "issuance", wallet, poi.money)
                }
                Message.print(player, "${ChatColor.GREEN}Reward: ${Economy.show(poi.money)} paid to your wallet")
            } catch (e: Exception) {
                Nodes.logger?.warning("[POI] Money reward for ${player.name} failed: ${e.message}")
            }
        }
        for (entry in poi.items) {
            val parts = entry.split(":")
            val material = Material.matchMaterial(parts[0]) ?: continue
            val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
            val leftover = player.inventory.addItem(ItemStack(material, amount))
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            Message.print(player, "${ChatColor.GREEN}Reward: $amount x ${material.name.lowercase().replace('_', ' ')}")
        }
        for (data in poi.customItems) {
            val item = Refineries.itemFromBase64(data) ?: continue
            val name = item.itemMeta?.takeIf { it.hasDisplayName() }?.displayName ?: item.type.name.lowercase().replace('_', ' ')
            val leftover = player.inventory.addItem(item)
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            Message.print(player, "${ChatColor.GREEN}Reward: ${item.amount} x $name")
        }
        val title = poi.title
        if (title != null && !progress.titles.contains(title)) {
            progress.titles.add(title)
            Message.print(player, "${ChatColor.LIGHT_PURPLE}Title earned: ${colour(title)} ${ChatColor.GRAY}(/map title)")
        }
    }

    fun colour(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd").format(Date(ms))
}
