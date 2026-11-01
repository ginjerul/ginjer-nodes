/**
 * Town surrender: a town leaves its nation and joins another one.
 */

package phonon.nodes.surrender

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.mining.MiningTowers
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Town
import phonon.nodes.util.DiscordRelay
import phonon.nodes.vassal.Vassals
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Raid
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

const val SURRENDER_COOLDOWN_MS = 24L * 60 * 60 * 1000
private const val CONFIRM_TIMEOUT_MS = 60_000L

private data class PendingSurrender(val nationName: String, val expiresAt: Long)

object Surrender {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/surrender.json")

    // town uuid -> time the town can leave its nation or surrender again
    private val readyAt: HashMap<UUID, Long> = hashMapOf()

    // player uuid -> surrender waiting for confirmation
    private val pending: HashMap<UUID, PendingSurrender> = hashMapOf()

    fun cooldownMessage(town: Town): String? {
        val left = (readyAt[town.uuid] ?: 0L) - System.currentTimeMillis()
        if (left <= 0) {
            return null
        }
        return "${town.name} can leave or surrender again in ${MiningTowers.formatDuration(left / 1000)}"
    }

    private fun validate(town: Town, target: Nation): String? {
        if (Vassals.isVassal(town)) {
            return "Vassals cannot surrender"
        }
        if (town.nation === target) {
            return "${town.name} is already in ${target.name}"
        }
        return cooldownMessage(town)
    }

    fun request(player: Player, town: Town, target: Nation): String? {
        validate(town, target)?.let { return it }

        pending[player.uniqueId] = PendingSurrender(target.name, System.currentTimeMillis() + CONFIRM_TIMEOUT_MS)

        Message.print(
            player,
            "${town.name} will join ${target.name}. Territories, income and claims stay. " +
                "You inherit ${target.name}'s wars and alliances, as if invited. Cooldown: 1 day.",
        )
        player.sendMessage(
            Component.text("[Confirm]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/surrender confirm"))
                .append(Component.text("  "))
                .append(
                    Component.text("[Cancel]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/surrender cancel")),
                ),
        )
        return null
    }

    fun cancel(player: Player) {
        pending.remove(player.uniqueId)
        Message.print(player, "Surrender cancelled")
    }

    fun confirm(player: Player, town: Town): String? {
        val request = pending.remove(player.uniqueId) ?: return "No surrender to confirm"
        if (System.currentTimeMillis() > request.expiresAt) {
            return "Surrender confirmation expired"
        }
        val target = Nodes.getNationFromName(request.nationName) ?: return "${request.nationName} no longer exists"
        return surrender(town, target)
    }

    private fun surrender(town: Town, target: Nation): String? {
        validate(town, target)?.let { return it }

        // capture attacks and raids with the target nation end, since the town is joining it
        FlagWar.cancelAttacksForSurrender(town, target)
        Raid.cancelForSurrender(town, target)

        val oldNation = town.nation

        if (oldNation != null) {
            if (oldNation.capital === town) {
                val newCapital = oldNation.towns.filter { it !== town }.maxByOrNull { it.territories.size }
                if (newCapital != null) {
                    Nodes.setNationCapital(oldNation, newCapital)
                }
            }
            Nodes.removeTownFromNation(oldNation, town)
        }

        val joined = Nodes.addTownToNation(target, town)
        if (joined.isFailure) {
            return "Could not join ${target.name}: ${joined.exceptionOrNull()?.message}"
        }

        town.needsUpdate()
        target.needsUpdate()
        Nodes.needsSave = true

        readyAt[town.uuid] = System.currentTimeMillis() + SURRENDER_COOLDOWN_MS
        save()

        val from = oldNation?.name ?: "no nation"
        Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}[Surrender] ${town.name} surrendered to ${target.name}")
        DiscordRelay.war("**[Surrender]** ${town.name} left $from and joined ${target.name}")
        return null
    }

    fun load() {
        readyAt.clear()
        pending.clear()
        if (!Files.exists(path)) {
            return
        }
        try {
            val root = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            root.getAsJsonArray("cooldowns")?.forEach { el ->
                val o = el.asJsonObject
                val time = o.get("readyAt").asLong
                if (time > System.currentTimeMillis()) {
                    readyAt[UUID.fromString(o.get("town").asString)] = time
                }
            }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Surrender] load failed: ${e.message}")
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val arr = JsonArray()
            for ((uuid, time) in readyAt) {
                val o = JsonObject()
                o.addProperty("town", uuid.toString())
                o.addProperty("readyAt", time)
                arr.add(o)
            }
            root.add("cooldowns", arr)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Surrender] save failed: ${e.message}")
        }
    }
}
