package phonon.nodes

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

public object Message {

    public val PREFIX = "[Nodes]"
    public val COL_MSG = ChatColor.AQUA
    public val COL_ERROR = ChatColor.RED

    public fun print(sender: CommandSender?, s: String) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text("${COL_MSG}$s")
        sender.sendMessage(msg)
    }

    public fun error(sender: CommandSender?, s: String) {
        if (sender === null) {
            System.out.println("$PREFIX Message called with null sender: $s")
            return
        }

        val msg = Component.text("${COL_ERROR}$s")
        sender.sendMessage(msg)
    }

    public fun broadcast(s: String) {
        val msg = Component.text("${COL_MSG}$s")
        Bukkit.broadcast(msg)
        // on shards, the leader's broadcasts are shown on every shard
        phonon.nodes.shard.Shards.relayBroadcast(msg)
    }

    /** Shows [s] in the player's action bar. */
    public fun announcement(player: Player, s: String) {
        player.sendActionBar(Component.text(s))
    }
}
