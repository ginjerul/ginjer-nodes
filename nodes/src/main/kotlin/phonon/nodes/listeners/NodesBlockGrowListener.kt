package phonon.nodes.listeners

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockGrowEvent
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import java.util.concurrent.ThreadLocalRandom

/**
 * Crops only grow in territories with that crop resource, at the territory's rate.
 *
 * Performance: this runs for every crop growth attempt on the server, and most
 * attempts get cancelled. Checks are ordered cheapest-first so cancelled
 * attempts exit early; the sky light lookup (the most expensive check) only
 * runs for the few attempts that would otherwise be allowed. The result is the
 * same as checking in any order.
 */
public class NodesBlockGrowListener : Listener {

    @EventHandler(ignoreCancelled = true)
    public fun onBlockGrow(event: BlockGrowEvent) {
        val block = event.block
        val blockY = block.y

        if (blockY < Config.cropsMinYHeight || blockY > Config.cropsMaxYHeight) {
            event.setCancelled(true)
            return
        }

        // territory lookup by chunk coords: a hash map lookup, no Chunk object needed
        val territory: Territory? = Nodes.getTerritoryFromChunkCoords(block.x shr 4, block.z shr 4)

        if (territory === null || territory.town === null) {
            if (!Config.allowCropsInWilderness) {
                event.setCancelled(true)
                return
            }
        }

        if (territory === null || !territory.cropsCanGrow) {
            event.setCancelled(true)
            return
        }

        // Use the new state: for spreading plants (sugarcane, vines) event.block is still AIR
        var plant: Material = event.getNewState().type

        if (!territory.crops.containsKey(plant)) {
            val altName = Config.cropAlternativeNames.get(plant)
            if (altName === null || !territory.crops.containsKey(altName)) {
                event.setCancelled(true)
                return
            }

            plant = altName
        }

        val growRate = territory.crops.get(plant) ?: 0.0
        if (ThreadLocalRandom.current().nextDouble() > growRate) {
            event.setCancelled(true)
            return
        }

        if (block.lightFromSky < Config.cropsMinSkyLight) {
            event.setCancelled(true)
            return
        }
    }
}
