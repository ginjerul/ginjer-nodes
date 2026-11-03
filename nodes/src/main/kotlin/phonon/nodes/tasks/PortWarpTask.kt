package phonon.nodes.tasks

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.commands.progressBar
import phonon.nodes.commands.warpToPort
import phonon.nodes.economy.Economy
import phonon.nodes.economy.EconomyException
import phonon.nodes.economy.EconomyExtras
import phonon.nodes.objects.Port
import phonon.nodes.objects.Town

public class PortWarpTask(
    val player: Player,
    val destination: Port,
    val playersToWarp: List<Player>,
    val entitiesToWarp: List<Entity>,
    val initialLoc: Location,
    val timeWarp: Double,
    val tick: Double,
    val fee: Long = 0L,
    val feeOwner: Town? = null,
) : BukkitRunnable() {
    private val locX = initialLoc.getBlockX()
    private val locY = initialLoc.getBlockY()
    private val locZ = initialLoc.getBlockZ()

    private var time = timeWarp

    override fun run() {
        val location = player.location
        if (locX != location.getBlockX() || locY != location.getBlockY() || locZ != location.getBlockZ()) {
            Message.announcement(player, "${ChatColor.RED}Moved! Stopped warping...")
            this.cancel()

            Bukkit.getScheduler().runTask(
                Nodes.plugin!!,
                object : Runnable {
                    public override fun run() {
                        Nodes.playerWarpTasks.remove(player.getUniqueId())
                    }
                },
            )

            return
        }

        time -= tick

        if (time <= 0.0) {
            this.cancel()

            Bukkit.getScheduler().runTask(
                Nodes.plugin!!,
                object : Runnable {
                    public override fun run() {
                        Nodes.playerWarpTasks.remove(player.getUniqueId())

                        // the fee is only charged when the warp actually happens
                        if (fee > 0 && feeOwner !== null) {
                            try {
                                EconomyExtras.payPortFee(player, feeOwner, fee, destination.name)
                            } catch (e: EconomyException) {
                                Message.error(player, e.message ?: "Could not pay the port fee")
                                return
                            }
                            Message.print(player, "Paid ${Economy.show(fee)} port fee to ${feeOwner.name}")
                        }

                        warpToPort(
                            destination,
                            playersToWarp,
                            entitiesToWarp,
                        )

                        Message.announcement(player, "${ChatColor.GREEN}Warped to ${destination.name}")
                    }
                },
            )
        } else {
            val progress = 1.0 - (time / timeWarp)
            Message.announcement(player, "Warping ${ChatColor.GREEN}${progressBar(progress)}")
        }
    }
}
