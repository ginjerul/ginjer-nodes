package phonon.nodesmap.render

import org.bukkit.HeightMap
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.StructureGrowEvent

/**
 * Marks chunks dirty when something visible from above changes.
 * Changes more than a couple of blocks below the surface (mining, caves)
 * are ignored, so underground activity never causes re-renders.
 * Each handler is a heightmap lookup + a set insert.
 */
class ChangeListener(private val scheduler: RenderScheduler) : Listener {
    private val world = scheduler.world

    private fun touch(b: Block) {
        if (b.world !== world) return
        val top = world.getHighestBlockYAt(b.x, b.z, HeightMap.WORLD_SURFACE)
        if (b.y < top - 2) return
        scheduler.markDirty(b.x shr 4, b.z shr 4)
    }

    private fun touchAll(blocks: List<Block>) {
        var lastCx = Int.MIN_VALUE
        var lastCz = Int.MIN_VALUE
        for (b in blocks) {
            val cx = b.x shr 4
            val cz = b.z shr 4
            if (cx == lastCx && cz == lastCz) continue
            touch(b)
            lastCx = cx
            lastCz = cz
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(e: BlockBurnEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(e: BlockFadeEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(e: BlockFormEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpread(e: BlockSpreadEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGrow(e: BlockGrowEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeaves(e: LeavesDecayEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFlow(e: BlockFromToEvent) = touch(e.toBlock)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChange(e: EntityChangeBlockEvent) = touch(e.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonOut(e: BlockPistonExtendEvent) = touchAll(e.blocks)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonIn(e: BlockPistonRetractEvent) = touchAll(e.blocks)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(e: BlockExplodeEvent) = touchAll(e.blockList())

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(e: EntityExplodeEvent) = touchAll(e.blockList())

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTreeGrow(e: StructureGrowEvent) {
        if (e.world !== world) return
        val first = e.blocks.firstOrNull() ?: return
        scheduler.markDirty(first.x shr 4, first.z shr 4)
        // trees can straddle chunk borders
        val l = e.location
        scheduler.markDirty(l.blockX shr 4, l.blockZ shr 4)
    }

    /** freshly generated chunks get drawn as players explore */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(e: ChunkLoadEvent) {
        if (e.isNewChunk && e.world === world) scheduler.markDirty(e.chunk.x, e.chunk.z)
    }
}
