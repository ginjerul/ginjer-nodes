/*
 * Vote rewards from NuVotifier / Votifier. The votifier event is looked up by
 * name when the plugin is present, so Nodes has no dependency on it. Votes
 * are paid on the main thread. Without a votifier plugin, staff or a vote
 * plugin can run /economyadmin vote <player> [service] instead.
 */

package phonon.nodes.economy

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin

internal object VoteHook {
    fun register(plugin: Plugin) {
        val votifier = Bukkit.getPluginManager().getPlugin("Votifier") ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            val eventClass = Class.forName("com.vexsoftware.votifier.model.VotifierEvent", true, votifier.javaClass.classLoader) as Class<out Event>
            val executor = EventExecutor { _, event ->
                try {
                    val vote = eventClass.getMethod("getVote").invoke(event)
                    val user = vote.javaClass.getMethod("getUsername").invoke(vote) as String
                    val service = vote.javaClass.getMethod("getServiceName").invoke(vote) as String
                    // the event can arrive off the main thread
                    Bukkit.getScheduler().runTask(
                        plugin,
                        Runnable {
                            try {
                                EconomyExtras.vote(user, service)
                            } catch (e: EconomyException) {
                                plugin.logger.warning("[Economy] Vote from $user not paid: ${e.message}")
                            } catch (e: Exception) {
                                plugin.logger.severe("[Economy] Vote from $user failed: $e")
                            }
                        },
                    )
                } catch (e: Exception) {
                    plugin.logger.severe("[Economy] Could not read a vote event: $e")
                }
            }
            Bukkit.getPluginManager().registerEvent(eventClass, object : Listener {}, EventPriority.NORMAL, executor, plugin)
            plugin.logger.info("[Economy] Listening for votes from Votifier")
        } catch (e: Exception) {
            plugin.logger.warning("[Economy] Votifier found but its vote event could not be hooked: $e")
        }
    }
}
