/*
 * Implement bukkit plugin interface
 */

package phonon.nodes

import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import phonon.nodes.commands.AllyChatCommand
import phonon.nodes.commands.AllyCommand
import phonon.nodes.commands.GlobalChatCommand
import phonon.nodes.commands.KillShopCommand
import phonon.nodes.commands.MiningTowerCommand
import phonon.nodes.commands.NationChatCommand
import phonon.nodes.commands.NationCommand
import phonon.nodes.commands.NodesAdminCommand
import phonon.nodes.commands.NodesCommand
import phonon.nodes.commands.PeaceCommand
import phonon.nodes.commands.PlayerCommand
import phonon.nodes.commands.PlotCommand
import phonon.nodes.commands.PortCommand
import phonon.nodes.commands.RaidCommand
import phonon.nodes.commands.RefineryCommand
import phonon.nodes.commands.StatsCommand
import phonon.nodes.commands.SupplyDropCommand
import phonon.nodes.commands.TerritoryCommand
import phonon.nodes.commands.TownChatCommand
import phonon.nodes.commands.TownCommand
import phonon.nodes.commands.TruceCommand
import phonon.nodes.commands.UnallyCommand
import phonon.nodes.commands.VassalCommand
import phonon.nodes.commands.WarCommand
import phonon.nodes.commands.WarzoneCommand
import phonon.nodes.fortify.FortTreasuryListener
import phonon.nodes.fortify.Fortifications
import phonon.nodes.killshop.KillShop
import phonon.nodes.killshop.KillShopGuiListener
import phonon.nodes.listeners.AntiBlockGlitchListener
import phonon.nodes.listeners.DisabledWorldListener
import phonon.nodes.listeners.MiningTowerListener
import phonon.nodes.listeners.NodesBlockGrowListener
import phonon.nodes.listeners.NodesChatListener
import phonon.nodes.listeners.NodesChestProtectionDestroyListener
import phonon.nodes.listeners.NodesChestProtectionListener
import phonon.nodes.listeners.NodesDiplomacyAllianceListener
import phonon.nodes.listeners.NodesDiplomacyTruceExpiredListener
import phonon.nodes.listeners.NodesEntityBreedListener
import phonon.nodes.listeners.NodesGuiListener
import phonon.nodes.listeners.NodesIncomeInventoryListener
import phonon.nodes.listeners.NodesNametagListener
import phonon.nodes.listeners.NodesPlayerAFKKickListener
import phonon.nodes.listeners.NodesPlayerDamageListener
import phonon.nodes.listeners.NodesPlayerJoinQuitListener
import phonon.nodes.listeners.NodesPlayerMoveListener
import phonon.nodes.listeners.NodesSheepShearListener
import phonon.nodes.listeners.NodesWarFlagArmorStandListener
import phonon.nodes.listeners.NodesWorldListener
import phonon.nodes.listeners.NodesXcDamageListener
import phonon.nodes.listeners.PlotSelectListener
import phonon.nodes.listeners.RefineryListener
import phonon.nodes.listeners.StatsListener
import phonon.nodes.listeners.WarzoneBossBarListener
import phonon.nodes.mining.MiningBoost
import phonon.nodes.mining.MiningTowers
import phonon.nodes.plot.Plots
import phonon.nodes.refinery.Refineries
import phonon.nodes.refinery.RefineryGuiListener
import phonon.nodes.stats.PlayerStats
import phonon.nodes.supply.SupplyDropListener
import phonon.nodes.supply.SupplyDrops
import phonon.nodes.utils.loadLongFromFile
import phonon.nodes.vassal.Vassals
import phonon.nodes.war.Raid
import phonon.nodes.war.Warzone

public class NodesPlugin : JavaPlugin() {

    override fun onEnable() {
        val timeStart = System.currentTimeMillis()

        val logger = this.getLogger()
        val pluginManager = this.getServer().getPluginManager()

        Nodes.initialize(this)

        val dynmap = pluginManager.getPlugin("dynmap")
        if (dynmap !== null) {
            Nodes.hookDynmap()
            logger.info("Using Dynmap v${dynmap.getDescription().getVersion()}")
        }

        Nodes.reloadConfig()

        Nodes.war.initialize(Config.flagMaterials)

        val pluginPath = Config.pathPlugin
        logger.info("Loading world from: $pluginPath")
        try {
            if (Nodes.loadWorld() == true) {
                logger.info("- Resource Nodes: ${Nodes.getResourceNodeCount()}")
                logger.info("- Territories: ${Nodes.getTerritoryCount()}")
                logger.info("- Residents: ${Nodes.getResidentCount()}")
                logger.info("- Towns: ${Nodes.getTownCount()}")
                logger.info("- Nations: ${Nodes.getNationCount()}")
            } else {
                logger.severe("Error loading world: Invalid world file at $pluginPath/${Config.pathWorld}")
                if (Config.disableWorldWhenLoadFails) {
                    logger.severe("Disabling world interactions due to world load error")
                    pluginManager.registerEvents(DisabledWorldListener(), this)
                    return
                }
            }
        } catch (err: Exception) {
            err.printStackTrace()
            logger.severe("Error loading world: $err")
            if (Config.disableWorldWhenLoadFails) {
                logger.severe("Disabling world interactions due to world load error")
                pluginManager.registerEvents(DisabledWorldListener(), this)
                return
            }
        }

        pluginManager.registerEvents(NodesBlockGrowListener(), this)
        pluginManager.registerEvents(NodesChatListener(), this)
        pluginManager.registerEvents(NodesChestProtectionListener(), this)
        pluginManager.registerEvents(NodesChestProtectionDestroyListener(), this)
        pluginManager.registerEvents(NodesDiplomacyAllianceListener(), this)
        pluginManager.registerEvents(NodesDiplomacyTruceExpiredListener(), this)
        pluginManager.registerEvents(NodesEntityBreedListener(), this)
        pluginManager.registerEvents(NodesGuiListener(), this)
        pluginManager.registerEvents(NodesIncomeInventoryListener(), this)
        pluginManager.registerEvents(NodesWorldListener(), this)
        pluginManager.registerEvents(NodesPlayerAFKKickListener(), this)
        pluginManager.registerEvents(NodesPlayerJoinQuitListener(), this)
        pluginManager.registerEvents(NodesPlayerMoveListener(), this)
        pluginManager.registerEvents(NodesSheepShearListener(), this)
        pluginManager.registerEvents(NodesNametagListener(), this)
        pluginManager.registerEvents(NodesWarFlagArmorStandListener(), this)
        pluginManager.registerEvents(NodesPlayerDamageListener(), this)
        pluginManager.registerEvents(StatsListener(), this)
        pluginManager.registerEvents(RefineryListener(), this)
        pluginManager.registerEvents(RefineryGuiListener(), this)
        pluginManager.registerEvents(MiningTowerListener(), this)
        pluginManager.registerEvents(AntiBlockGlitchListener(), this)
        pluginManager.registerEvents(WarzoneBossBarListener(), this)
        NodesXcDamageListener.register(this)
        pluginManager.registerEvents(PlotSelectListener(), this)
        pluginManager.registerEvents(KillShopGuiListener(), this)
        pluginManager.registerEvents(SupplyDropListener(), this)
        pluginManager.registerEvents(FortTreasuryListener(), this)

        this.getCommand("town")?.setExecutor(TownCommand())
        this.getCommand("nation")?.setExecutor(NationCommand())
        this.getCommand("nodes")?.setExecutor(NodesCommand())
        this.getCommand("nodesadmin")?.setExecutor(NodesAdminCommand())
        this.getCommand("ally")?.setExecutor(AllyCommand())
        this.getCommand("unally")?.setExecutor(UnallyCommand())
        this.getCommand("war")?.setExecutor(WarCommand())
        this.getCommand("warzone")?.setExecutor(WarzoneCommand())
        this.getCommand("warzone")?.setTabCompleter(WarzoneCommand())
        val raidCmd = RaidCommand()
        this.getCommand("raid")?.setExecutor(raidCmd)
        this.getCommand("raid")?.setTabCompleter(raidCmd)
        this.getCommand("peace")?.setExecutor(PeaceCommand())
        this.getCommand("truce")?.setExecutor(TruceCommand())
        this.getCommand("globalchat")?.setExecutor(GlobalChatCommand())
        this.getCommand("townchat")?.setExecutor(TownChatCommand())
        this.getCommand("nationchat")?.setExecutor(NationChatCommand())
        this.getCommand("allychat")?.setExecutor(AllyChatCommand())
        this.getCommand("player")?.setExecutor(PlayerCommand())
        this.getCommand("territory")?.setExecutor(TerritoryCommand())
        this.getCommand("port")?.setExecutor(PortCommand())
        this.getCommand("miningtower")?.setExecutor(MiningTowerCommand())
        this.getCommand("miningtower")?.setTabCompleter(MiningTowerCommand())
        val refineryCmd = RefineryCommand()
        this.getCommand("refinery")?.setExecutor(refineryCmd)
        this.getCommand("refinery")?.setTabCompleter(refineryCmd)
        val statsCmd = StatsCommand()
        this.getCommand("stats")?.setExecutor(statsCmd)
        this.getCommand("stats")?.setTabCompleter(statsCmd)
        this.getCommand("leaderboard")?.setExecutor(statsCmd)
        this.getCommand("leaderboard")?.setTabCompleter(statsCmd)
        val plotCmd = PlotCommand()
        this.getCommand("plot")?.setExecutor(plotCmd)
        this.getCommand("plot")?.setTabCompleter(plotCmd)
        this.getCommand("p")?.setExecutor(plotCmd)
        this.getCommand("p")?.setTabCompleter(plotCmd)
        val ks = KillShopCommand()
        this.getCommand("killshop")?.setExecutor(ks)
        this.getCommand("killshop")?.setTabCompleter(ks)
        val supplyDropCmd = SupplyDropCommand()
        this.getCommand("supplydrop")?.setExecutor(supplyDropCmd)
        this.getCommand("supplydrop")?.setTabCompleter(supplyDropCmd)
        val vassalCmd = VassalCommand()
        this.getCommand("vassal")?.setExecutor(vassalCmd)
        this.getCommand("vassal")?.setTabCompleter(vassalCmd)
        this.getCommand("t")?.setTabCompleter(this.getCommand("town")?.getExecutor() as TabCompleter)
        this.getCommand("n")?.setTabCompleter(this.getCommand("nation")?.getExecutor() as TabCompleter)
        this.getCommand("nd")?.setTabCompleter(this.getCommand("nodes")?.getExecutor() as TabCompleter)
        this.getCommand("nda")?.setTabCompleter(this.getCommand("nodesadmin")?.getExecutor() as TabCompleter)
        this.getCommand("gc")?.setTabCompleter(this.getCommand("globalchat")?.getExecutor() as TabCompleter)
        this.getCommand("miningtower")?.setExecutor(MiningTowerCommand())
        this.getCommand("miningtower")?.setTabCompleter(MiningTowerCommand())

        val currTime = System.currentTimeMillis()
        Nodes.lastBackupTime = loadLongFromFile(Config.pathLastBackupTime) ?: currTime
        Nodes.lastIncomeTime = loadLongFromFile(Config.pathLastIncomeTime) ?: currTime

        Nodes.saveWorldToDynmap(async = true)
        Nodes.reloadManagers()

        Nodes.initializeOnlinePlayers()

        Nodes.initialized = true
        Nodes.enabled = true
        Warzone.load()
        Raid.load()
        MiningBoost.load()
        MiningTowers.load()
        PlayerStats.load()
        Refineries.load()
        Plots.load()
        RallyCap.load()
        KillShop.load()
        SupplyDrops.load()
        Vassals.load()
        Fortifications.load()

        // web map export (after every system above has loaded its state)
        phonon.nodes.map.MapExporter.start(this)

        // a timed war keeps counting down across restarts (boss bar + auto end)
        server.scheduler.runTask(
            this,
            Runnable {
                phonon.nodes.stats.WarStats.load()
                phonon.nodes.war.WarTimer.restore()
            },
        )

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        logger.info("Enabled in ${timeLoad}ms")

        logger.info("ginjer is the goat")
    }

    override fun onDisable() {
        logger.info("ginjer is the goat but disabled")
        phonon.nodes.map.MapExporter.stop()
        Warzone.shutdown()
        Raid.shutdown()
        MiningBoost.shutdown()
        MiningTowers.shutdown()
        PlayerStats.shutdown()
        phonon.nodes.stats.WarStats.shutdown()
        Refineries.shutdown()
        Plots.shutdown()
        RallyCap.save()
        KillShop.shutdown()
        SupplyDrops.shutdown()
        Vassals.save()
        Fortifications.flushOpenTreasuries()
        Fortifications.save()

        Nodes.enabled = false

        Nodes.cleanup()
        // Don't save if loading failed, or empty data overwrites the save
        if (Nodes.initialized) {
            Nodes.saveWorld(checkIfNeedsSave = false, async = false)
            Nodes.saveTruce(async = false)
        }
    }
}
