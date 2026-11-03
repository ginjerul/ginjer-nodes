/**
 * Main listener for Nodes world:
 * - town permissions and protections
 * - flag war events
 * - hidden ore
 * - ore, crop harvest taxation
 *
 * Anti-blockglitch: only on denied BUILD (place / bucket), not interact.
 */

package phonon.nodes.listeners

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.Event.Result
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.Nodes.getRelationshipOfPlayerToTown
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.constants.ErrorAlreadyCaptured
import phonon.nodes.constants.ErrorAlreadyUnderAttack
import phonon.nodes.constants.ErrorAttackCustomCancel
import phonon.nodes.constants.ErrorChunkNotEdge
import phonon.nodes.constants.ErrorFlagTooHigh
import phonon.nodes.constants.ErrorNoTerritory
import phonon.nodes.constants.ErrorNotBorderTerritory
import phonon.nodes.constants.ErrorNotEnemy
import phonon.nodes.constants.ErrorSkyBlocked
import phonon.nodes.constants.ErrorTooManyAttacks
import phonon.nodes.constants.ErrorTownBlacklisted
import phonon.nodes.constants.ErrorTownNotWhitelisted
import phonon.nodes.constants.INTERACTIVE_BLOCKS
import phonon.nodes.constants.PROTECTED_BLOCKS
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.objects.PlotAction
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.Town
import phonon.nodes.plot.Plots
import phonon.nodes.util.AntiBlockGlitch
import phonon.nodes.war.Attack
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Warzone
import java.util.concurrent.ThreadLocalRandom

public class NodesWorldListener : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public fun onBlockBreak(event: BlockBreakEvent) {
        val player: Player = event.player
        val block: Block = event.block
        if (phonon.nodes.refinery.Refineries.getByBlock(block) != null) {
            return
        }
        if (phonon.nodes.mining.MiningTowers.isTowerBlock(block)) {
            return
        }
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)

        if (territoryChunk?.attacker !== null) {
            val attack = FlagWar.chunkToAttacker.get(territoryChunk.coord)!!

            if (blockInWarFlagNoBuildRegion(block, attack)) {
                if (attack.flagBlock == block) {
                    event.isCancelled = true

                    if (!Config.allowBreakingAlliesFlags) {
                        if (player.uniqueId != attack.attacker) {
                            val relationship = getRelationshipOfPlayerToTown(player, attack.town)
                            if (relationship in setOf(
                                    DiplomaticRelationship.NATION,
                                    DiplomaticRelationship.ALLY,
                                    DiplomaticRelationship.TOWN,
                                )
                            ) {
                                Message.error(player, "[War] Cannot break ally war flags")
                                return
                            }
                        }
                    }
                    attack.cancel()
                    Message.broadcast(
                        "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) defeated by ${player.name}",
                    )
                    return
                }

                if (player.isOp) {
                    return
                }
                event.isCancelled = true
                Message.error(
                    player,
                    "[War] Cannot break blocks within ${Config.flagNoBuildDistance} blocks of war flags",
                )
                return
            }
        }

        if (player.isOp) {
            return
        }

        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val town: Town? = territory?.town
        val resident = Nodes.getResident(player)

        val plotDestroy = Plots.allows(player, block, PlotAction.DESTROY)
        if (plotDestroy != null) {
            if (!plotDestroy) {
                event.isCancelled = true
                Message.error(player, "You cannot destroy in this plot!")
                return
            }
            return
        }

        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }
            event.isCancelled = true
            Message.error(player, "You cannot destroy here!")
            return
        }

        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.DESTROY, town, resident)) {
                return
            }
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.DESTROY, town, occupier, resident)) {
                return
            }
            if (territoryChunk != null && hasWarPermissions(resident, territory, territoryChunk)) {
                return
            }
        }

        event.isCancelled = true
        Message.error(player, "You cannot destroy here!")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onBlockBreakSuccess(event: BlockBreakEvent) {
        if (event.isCancelled) {
            return
        }

        val player = event.player
        val block = event.block
        val blockMaterial = block.type

        if (Config.oreBlocks.contains(blockMaterial)) {
            if (!Nodes.hiddenOreInvalidBlocks.contains(block)) {
                handleHiddenOre(player, block)
                Nodes.hiddenOreInvalidBlocks.add(block)
            }
        } else if (Config.cropTypes.contains(blockMaterial)) {
            handleCropHarvest(block)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public fun onBlockPlace(event: BlockPlaceEvent) {
        val block: Block = event.block
        val player: Player = event.player

        val territoryChunkForWar = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        val warzoneTown = territoryChunkForWar?.territory?.town
        val warzoneAttack =
            warzoneTown != null && Warzone.isAttackableWarzoneTerritory(warzoneTown)
        val raidLive = territoryChunkForWar?.territory?.let {
            phonon.nodes.war.Raid.isLiveTerritory(it)
        } == true

        if (Nodes.war.enabled || warzoneAttack || raidLive) {
            val territoryChunk = territoryChunkForWar
            if (territoryChunk !== null) {
                if (territoryChunk.attacker !== null) {
                    if (player.isOp) {
                        return
                    }
                    val attack = FlagWar.chunkToAttacker[territoryChunk.coord]
                    if (attack != null && blockInWarFlagNoBuildRegion(block, attack)) {
                        event.isCancelled = true
                        Message.error(
                            player,
                            "[War] Cannot build within ${Config.flagNoBuildDistance} blocks of war flags",
                        )
                        return
                    }
                } else if (FlagWar.flagMaterials.contains(block.type)) {
                    val resident = Nodes.getResident(player)
                    if (resident !== null) {
                        val town = resident.town
                        if (town !== null) {
                            val result = FlagWar.beginAttack(player.uniqueId, town, territoryChunk, block)
                            if (result.isSuccess) {
                                val townAttacked = territoryChunk.territory.town!!
                                if (townAttacked === town) {
                                    Message.broadcast(
                                        "${ChatColor.DARK_RED}[War] ${event.player.name} is liberating ${townAttacked.name} at (${block.x}, ${block.y}, ${block.z})",
                                    )
                                } else {
                                    Message.broadcast(
                                        "${ChatColor.DARK_RED}[War] ${event.player.name} is attacking ${townAttacked.name} at (${block.x}, ${block.y}, ${block.z})",
                                    )
                                }
                            } else {
                                when (result.exceptionOrNull()) {
                                    ErrorNoTerritory -> Message.error(player, "[War] There is no territory here")
                                    ErrorAlreadyUnderAttack -> Message.error(player, "[War] Chunk already under attack")
                                    ErrorAlreadyCaptured -> Message.error(player, "[War] Chunk already captured by town or allies")
                                    ErrorTownBlacklisted -> Message.error(player, "[War] Cannot attack this town (blacklisted)")
                                    ErrorTownNotWhitelisted -> Message.error(player, "[War] Cannot attack this town (not whitelisted)")
                                    ErrorNotEnemy -> if (raidLive) {
                                        Message.error(player, "[Raid] Not an enemy chunk")
                                    } else {
                                        Message.error(player, "[War] Not an enemy")
                                    }
                                    ErrorNotBorderTerritory -> Message.error(player, "[War] You can only attack border territories")
                                    ErrorChunkNotEdge -> Message.error(player, "[War] Must attack from territory edge or from captured chunk")
                                    ErrorFlagTooHigh -> Message.error(player, "[War] Flag placement too high, cannot create flag")
                                    ErrorSkyBlocked -> Message.error(player, "[War] Flag must see the sky")
                                    ErrorTooManyAttacks -> Message.error(player, "[War] You cannot attack any more chunks at the same time")
                                    ErrorAttackCustomCancel -> Message.error(player, "[War] Attack cancelled by another plugin")
                                    phonon.nodes.constants.ErrorNotEnoughPlaytime -> Message.error(player, "[War] You need ${Config.hoursRequiredToJoinWars} hours of playtime before you can attack")
                                }
                                event.isCancelled = true
                                return
                            }
                        } else {
                            Message.error(player, "[War] Cannot claim unless you are part of a town")
                            event.isCancelled = true
                        }
                    } else {
                        event.isCancelled = true
                    }
                }
            }
        }

        if (player.isOp) {
            return
        }

        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        val resident = Nodes.getResident(player)
        val town: Town? = territory?.town

        val plotBuild = Plots.allows(player, block, PlotAction.BUILD)
        if (plotBuild != null) {
            if (!plotBuild) {
                event.isCancelled = true
                Message.error(player, "You cannot build in this plot!")
                AntiBlockGlitch.recordDenied(player)
                return
            }
            return
        }

        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }
            event.isCancelled = true
            Message.error(player, "You cannot build here!")
            AntiBlockGlitch.recordDenied(player)
            return
        }

        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.BUILD, town, resident)) {
                return
            }
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.BUILD, town, occupier, resident)) {
                return
            }
            if (territoryChunk != null && hasWarPermissions(resident, territory, territoryChunk)) {
                return
            }
            val placeOnWarzone = Warzone.isAttackableWarzoneTerritory(town)
            val placeOnRaid = phonon.nodes.war.Raid.isLiveTerritory(territory)
            if ((Nodes.war.enabled || placeOnWarzone || placeOnRaid) &&
                Config.flagMaterials.contains(block.type)
            ) {
                return
            }
        }

        event.isCancelled = true
        Message.error(player, "You cannot build here!")
        AntiBlockGlitch.recordDenied(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onBlockPlaceSuccess(event: BlockPlaceEvent) {
        if (event.isCancelled) {
            return
        }
        val block = event.block
        if (Config.oreBlocks.contains(block.type)) {
            Nodes.hiddenOreInvalidBlocks.add(block)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public fun onPlayerBucketEmpty(event: PlayerBucketEmptyEvent) {
        val block = event.blockClicked.getRelative(event.blockFace)
        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        val player: Player = event.player
        val resident = Nodes.getResident(player)
        val town: Town? = territory?.town

        if (player.isOp) {
            return
        }

        val plotBucket = Plots.allows(player, block, PlotAction.BUILD)
        if (plotBucket != null) {
            if (!plotBucket) {
                event.isCancelled = true
                Message.error(player, "You cannot build in this plot!")
                AntiBlockGlitch.recordDenied(player)
                return
            }
            return
        }

        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }
            event.isCancelled = true
            Message.error(player, "You cannot use buckets here")
            AntiBlockGlitch.recordDenied(player)
            return
        }

        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.BUILD, town, resident)) {
                return
            }
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.BUILD, town, occupier, resident)) {
                return
            }
            if (territoryChunk != null && hasWarPermissions(resident, territory, territoryChunk)) {
                return
            }
        }

        event.isCancelled = true
        Message.error(player, "You cannot use buckets here")
        AntiBlockGlitch.recordDenied(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onPlayerBucketEmptySuccess(event: PlayerBucketEmptyEvent) {
        if (event.isCancelled) {
            return
        }
        val block = event.blockClicked.getRelative(event.blockFace)
        if (Config.cropTypes.contains(block.type)) {
            handleCropHarvest(block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onBlockFromToSuccess(event: BlockFromToEvent) {
        if (event.isCancelled) {
            return
        }
        val block = event.toBlock
        if (Config.cropTypes.contains(block.type)) {
            handleCropHarvest(block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onPistonExtendSuccess(event: BlockPistonExtendEvent) {
        if (event.isCancelled) {
            return
        }
        for (block in event.blocks) {
            if (Config.cropTypes.contains(block.type)) {
                handleCropHarvest(block)
            }
        }
    }

    @EventHandler
    public fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.clickedBlock === null) {
            return
        }

        val player = event.player
        val action = event.action

        if (player.isOp) {
            return
        }

        val block = event.clickedBlock ?: return
        if (phonon.nodes.supply.SupplyDrops.isDropBlock(block)) {
            return
        }

        if (FlagWar.blockToAttacker.containsKey(block)) {
            return
        }

        val plotInteract = Plots.allows(player, block, PlotAction.INTERACT)
        if (plotInteract != null) {
            if (!plotInteract) {
                event.setUseInteractedBlock(Result.DENY)
                if (event.hasItem() && event.isBlockInHand) {
                    event.setUseItemInHand(Result.DENY)
                } else {
                    event.setUseItemInHand(Result.DEFAULT)
                }
                if (action == Action.RIGHT_CLICK_BLOCK) {
                    Message.error(player, "You cannot interact in this plot!")
                }
                return
            }
            return
        }

        val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
        val resident = Nodes.getResident(player)
        val town: Town? = territory?.town

        if (territory === null || town === null) {
            return
        }

        if (resident !== null) {
            if (Nodes.war.enabled &&
                Config.flagMaterials.contains(event.material) &&
                action == Action.RIGHT_CLICK_BLOCK
            ) {
                val clickedBlock = event.clickedBlock
                if (clickedBlock !== null && INTERACTIVE_BLOCKS.contains(clickedBlock.type)) {
                    if (Config.allowAllInteractsInFlaggedChunks &&
                        FlagWar.chunkToAttacker.containsKey(territoryChunk?.coord)
                    ) {
                        return
                    }
                    Message.error(player, "You cannot interact here!")
                    event.setUseInteractedBlock(Result.DENY)
                }
                return
            }

            if (PROTECTED_BLOCKS.contains(block.type)) {
                if (territoryChunk != null && hasWarPermissions(resident, territory, territoryChunk)) {
                    return
                }
                if (hasTownPermissions(TownPermissions.CHESTS, town, resident)) {
                    if (town.protectedBlocks.contains(block) && !hasTownProtectedChestPermissions(town, resident)) {
                        event.isCancelled = true
                        Message.error(player, "This chest is for trusted residents only")
                    }
                    return
                }
                if (phonon.nodes.mining.MiningTowers.isTowerBlock(block)) {
                    return
                }
                event.isCancelled = true
                Message.error(player, "You cannot use chests here!")
                return
            }

            if (hasTownPermissions(TownPermissions.INTERACT, town, resident)) {
                return
            }
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.INTERACT, town, occupier, resident)) {
                return
            }
            if (territoryChunk != null && hasWarPermissions(resident, territory, territoryChunk)) {
                return
            }
        }

        val item = event.item
        val warzoneFlag = Warzone.isAttackableWarzoneTerritory(town)
        if (item != null &&
            Config.flagMaterials.contains(item.type) &&
            (Nodes.war.enabled || warzoneFlag || phonon.nodes.war.Raid.isLiveTerritory(territory))
        ) {
            return
        }

        // Refineries and mining towers are public
        if (phonon.nodes.refinery.Refineries.getByBlock(block) != null) {
            return
        }
        if (phonon.nodes.mining.MiningTowers.isTowerBlock(block)) {
            return
        }

        event.setUseInteractedBlock(Result.DENY)
        if (event.hasItem() && event.isBlockInHand) {
            event.setUseItemInHand(Result.DENY)
        } else {
            event.setUseItemInHand(Result.DEFAULT)
        }

        if (action == Action.RIGHT_CLICK_BLOCK) {
            Message.error(player, "You cannot interact here!")
        }
    }

    @EventHandler
    public fun onEntityInteract(event: EntityInteractEvent) {
        val entity = event.entity
        var player: Player? = null
        for (p in entity.passengers) {
            if (p is Player) {
                player = p
                break
            }
        }

        if (player !== null) {
            if (player.isOp) {
                return
            }
            val block: Block = event.block
            val territory: Territory? = Nodes.getTerritoryFromChunk(block.chunk)
            val territoryChunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
            val resident = Nodes.getResident(player)
            val town: Town? = territory?.town

            if (territory === null || town === null) {
                return
            }

            if (resident !== null) {
                if (hasTownPermissions(TownPermissions.INTERACT, town, resident)) {
                    return
                }
                val occupier: Town? = territory.occupier
                if (occupier !== null && hasOccupierPermissions(TownPermissions.INTERACT, town, occupier, resident)) {
                    return
                }
                if (territoryChunk != null && hasWarPermissions(resident, territory, territoryChunk)) {
                    return
                }
            }
        }

        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW)
    public fun onEntityExplode(event: EntityExplodeEvent) {
        sparePlayerStorage(event.blockList())
        if (Config.restrictExplosions) {
            if (!Nodes.war.enabled) {
                if (Config.onlyAllowExplosionsDuringWar) {
                    event.isCancelled = true
                    return
                }
            } else {
                if (Config.warUseWhitelist) {
                    val chunk = event.entity.location.chunk
                    val town = Nodes.getTownAtChunkCoord(chunk.x, chunk.z)
                    if (town !== null && !Config.warWhitelist.contains(town.uuid)) {
                        event.isCancelled = true
                        return
                    }
                }
                if (Config.warUseBlacklist) {
                    val chunk = event.entity.location.chunk
                    val town = Nodes.getTownAtChunkCoord(chunk.x, chunk.z)
                    if (town !== null && Config.warBlacklist.contains(town.uuid)) {
                        event.isCancelled = true
                        return
                    }
                }
            }
        }

        if (Nodes.war.enabled) {
            for (block in event.blockList()) {
                val chunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
                if (chunk?.attacker !== null) {
                    var attack = FlagWar.blockToAttacker[block]
                    if (attack === null) {
                        attack = FlagWar.blockToAttacker[block.getRelative(0, 1, 0)]
                    }
                    if (attack !== null) {
                        attack.cancel()
                        Message.broadcast(
                            "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) stopped by an explosion",
                        )
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public fun onBlockExplode(event: BlockExplodeEvent) {
        sparePlayerStorage(event.blockList())
        if (Config.restrictExplosions) {
            if (!Nodes.war.enabled) {
                if (Config.onlyAllowExplosionsDuringWar) {
                    event.isCancelled = true
                    return
                }
            } else {
                if (Config.warUseWhitelist) {
                    val chunk = event.block.chunk
                    val town = Nodes.getTownAtChunkCoord(chunk.x, chunk.z)
                    if (town !== null && !Config.warWhitelist.contains(town.uuid)) {
                        event.isCancelled = true
                        return
                    }
                }
                if (Config.warUseBlacklist) {
                    val chunk = event.block.chunk
                    val town = Nodes.getTownAtChunkCoord(chunk.x, chunk.z)
                    if (town !== null && Config.warBlacklist.contains(town.uuid)) {
                        event.isCancelled = true
                        return
                    }
                }
            }
        }

        if (Nodes.war.enabled) {
            for (block in event.blockList()) {
                val chunk = Nodes.getTerritoryChunkFromBlock(block.x, block.z)
                if (chunk?.attacker !== null) {
                    var attack = FlagWar.blockToAttacker[block]
                    if (attack === null) {
                        attack = FlagWar.blockToAttacker[block.getRelative(0, 1, 0)]
                    }
                    if (attack !== null) {
                        attack.cancel()
                        Message.broadcast(
                            "${ChatColor.GOLD}[War] Attack at (${block.x}, ${block.y}, ${block.z}) stopped by an explosion",
                        )
                    }
                }
            }
        }
    }
}

private fun hasWildernessPermissions(territory: Territory?): Boolean {
    if (territory !== null && Config.canInteractInUnclaimed) {
        return true
    } else if (Config.canInteractInEmpty) {
        return true
    }
    return false
}

private fun hasTownPermissions(perms: TownPermissions, town: Town, player: Resident): Boolean {
    if (town.permissions[perms].contains(PermissionsGroup.TOWN) && player.town === town) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.TRUSTED) && player.town === town && player.trusted) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.NATION) && town.nation !== null && player.nation === town.nation) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.ALLY) && (town.allies.contains(player.town) == true)) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.OUTSIDER)) {
        return true
    }
    return false
}

private fun hasOccupierPermissions(perms: TownPermissions, town: Town, occupier: Town, player: Resident): Boolean = if (Config.allowControlInOccupiedTownList.contains(town.uuid)) {
    hasTownPermissions(perms, occupier, player)
} else {
    false
}

private fun hasTownProtectedChestPermissions(town: Town, player: Resident): Boolean {
    if (player.town === town) {
        if (player === town.leader) {
            return true
        } else if (town.officers.contains(player)) {
            return true
        } else {
            return player.trusted
        }
    }
    return false
}

private fun hasWarPermissions(resident: Resident, territory: Territory, territoryChunk: TerritoryChunk): Boolean {
    val warzoneActive = Warzone.isAttackableWarzoneTerritory(territory.town)
    val raidActive = phonon.nodes.war.Raid.isLiveTerritory(territory)

    if (Nodes.war.enabled || warzoneActive || raidActive) {
        val residentTown = resident.town
        val territoryTown = territory.town

        if (residentTown !== null) {
            if (Config.warPermissions) {
                val residentNation = residentTown.nation
                if (territory.occupier === residentTown ||
                    residentTown.allies.contains(territory.occupier) ||
                    territoryChunk.occupier === residentTown ||
                    territoryChunk.attacker === residentTown ||
                    residentTown.allies.contains(territoryTown) ||
                    residentTown.allies.contains(territoryChunk.occupier) ||
                    residentTown.allies.contains(territoryChunk.attacker)
                ) {
                    return true
                }
                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            } else {
                if (territory.occupier === residentTown ||
                    territoryChunk.occupier === residentTown ||
                    territoryChunk.attacker === residentTown
                ) {
                    return true
                }
                val residentNation = residentTown.nation
                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
        }
    }
    return false
}

private fun handleCropHarvest(block: Block) {
    val blockX = block.location.blockX
    val blockZ = block.location.blockZ
    val territory = Nodes.getTerritoryFromBlock(blockX, blockZ) ?: return
    val random = ThreadLocalRandom.current()

    val territoryOccupier = territory.occupier
    if (territoryOccupier !== null && random.nextDouble() <= Config.taxFarmRate) {
        for (itemStack in block.getDrops()) {
            Nodes.addToIncome(territoryOccupier, itemStack.type, itemStack.amount)
        }
        block.type = Material.AIR
    } else if (Config.overClaimsPenalty && territory.town?.isOverClaimsMax == true) {
        if (random.nextDouble() < Config.overClaimsMaxPenalty) {
            block.type = Material.AIR
        }
    }
}

// explodeChests: false keeps chests, barrels and shulker boxes out of explosions
private fun sparePlayerStorage(blocks: MutableList<Block>) {
    if (Config.explodeChests) {
        return
    }
    val storage = setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL)
    blocks.removeIf { storage.contains(it.type) || Tag.SHULKER_BOXES.isTagged(it.type) }
}

private fun handleHiddenOre(player: Player, block: Block) {
    if (!Config.hiddenOreMechanic) {
        return
    }
    val inMainHand: ItemStack? = player.inventory.itemInMainHand
    if (!Config.hiddenOreSilktouch && inMainHand?.hasItemMeta() == true && inMainHand.enchantments.containsKey(Enchantment.SILK_TOUCH)) {
        return
    }

    val blockX = block.location.blockX
    val blockZ = block.location.blockZ
    val blockY = block.location.blockY
    val blockWorld = block.world
    val territory = Nodes.getTerritoryFromBlock(blockX, blockZ) ?: return
    val random = ThreadLocalRandom.current()

    val territoryTown = territory.town
    val territoryNation = territoryTown?.nation
    val playerTown = Nodes.getTownFromPlayer(player)
    val playerNation = playerTown?.nation

    if ((Config.allowOreInWilderness && territoryTown === null) ||
        (territoryTown !== null && territoryTown === playerTown) ||
        (Config.allowOreInNationTowns && territoryNation !== null && territoryNation === playerNation) ||
        (Config.allowOreInCaptured && territory.occupier === playerTown)
    ) {
        val baseDrops = territory.ores.sample(blockY)
        val itemDrops = phonon.nodes.mining.MiningBoost.applyToDrops(player, baseDrops) {
            territory.ores.sample(blockY)
        }

        val territoryOccupier = territory.occupier
        if (territoryOccupier !== null && random.nextDouble() <= Config.taxMineRate) {
            for (itemStack in itemDrops) {
                Nodes.addToIncome(territoryOccupier, itemStack.type, itemStack.amount)
            }
        } else {
            if (Config.overClaimsPenalty && playerTown?.isOverClaimsMax == true) {
                if (random.nextDouble() < Config.overClaimsMaxPenalty) {
                    return
                }
            }
            for (itemStack in itemDrops) {
                blockWorld.dropItem(block.location, itemStack)
            }
        }
    }
}

private fun blockInWarFlagNoBuildRegion(block: Block, attack: Attack): Boolean {
    val x = block.x
    val y = block.y
    val z = block.z
    if (x < attack.noBuildXMin || x > attack.noBuildXMax) return false
    if (y < attack.noBuildYMin || y > attack.noBuildYMax) return false
    if (z < attack.noBuildZMin || z > attack.noBuildZMax) return false
    return true
}
