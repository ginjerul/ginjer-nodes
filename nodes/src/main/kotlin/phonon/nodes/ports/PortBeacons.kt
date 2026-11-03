/*
 * Beacon pillars that mark ports. A column of blocks is placed at the port's
 * x/z in the main world, topped with a light block. Off unless portBeacon.enabled
 * is set in config.yml.
 */

package phonon.nodes.ports

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import phonon.nodes.Nodes
import phonon.nodes.objects.Port

object PortBeacons {
    var enabled = false
    private var pillar: Material = Material.BEDROCK
    private var pillarHeight = 20
    private var startY = 50 // used for ports without a stored request height
    private var light: Material = Material.BEACON
    private var lightHeight = 1

    fun loadConfig(config: FileConfiguration) {
        val sec = config.getConfigurationSection("portBeacon")
        if (sec == null) {
            enabled = false
            return
        }
        enabled = sec.getBoolean("enabled", false)
        pillar = block(sec.getString("pillarMaterial"), Material.BEDROCK)
        light = block(sec.getString("lightMaterial"), Material.BEACON)
        pillarHeight = sec.getInt("pillarHeight", 20).coerceIn(1, 200)
        lightHeight = sec.getInt("lightHeight", 1).coerceIn(0, 20)
        startY = sec.getInt("startY", 50).coerceIn(-64, 300)
    }

    private fun block(name: String?, fallback: Material): Material {
        val material = name?.let { Material.matchMaterial(it) }
        if (material == null || !material.isBlock || material.isAir) {
            if (name != null) {
                Nodes.logger?.warning("[Ports] Invalid beacon block '$name', using $fallback")
            }
            return fallback
        }
        return material
    }

    private fun column(port: Port, action: (org.bukkit.block.Block, Material) -> Unit) {
        val world = Bukkit.getWorlds().firstOrNull() ?: return
        val maxY = world.maxHeight - 1
        val bottom = (port.beaconY ?: startY).coerceIn(world.minHeight, maxY)
        val top = bottom + pillarHeight
        for (y in bottom until minOf(top, maxY + 1)) {
            action(world.getBlockAt(port.locX, y, port.locZ), pillar)
        }
        for (y in top until minOf(top + lightHeight, maxY + 1)) {
            action(world.getBlockAt(port.locX, y, port.locZ), light)
        }
    }

    fun place(port: Port) {
        if (!enabled) {
            return
        }
        column(port) { block, material ->
            if (block.type != material) {
                block.setType(material, false)
            }
        }
    }

    // only clears blocks that are still the pillar or light, so other builds are left alone
    fun remove(port: Port) {
        if (!enabled) {
            return
        }
        column(port) { block, material ->
            if (block.type == material) {
                block.setType(Material.AIR, false)
            }
        }
    }

    fun placeAll() {
        if (!enabled) {
            return
        }
        for (port in Nodes.ports.values) {
            place(port)
        }
    }
}
