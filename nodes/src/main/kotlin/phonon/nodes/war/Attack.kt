/**
 * Instance for attacking a chunk
 * - holds state data of attack
 * - progress is driven by FlagWar.attackTickTask (single global ticker)
 */

package phonon.nodes.war

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.boss.BossBar
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Town
import java.util.UUID

public class Attack(
    val attacker: UUID,
    val town: Town,
    val coord: Coord,
    val flagBase: Block,
    val flagBlock: Block,
    val flagTorch: Block,
    val skyBeaconColorBlocks: List<Block>,
    val skyBeaconWireframeBlocks: List<Block>,
    val progressBar: BossBar,
    val attackTime: Long,
    var progress: Long,
) {
    val noBuildXMin: Int
    val noBuildXMax: Int
    val noBuildZMin: Int
    val noBuildZMax: Int
    val noBuildYMin: Int
    val noBuildYMax: Int = 255

    /** Set true when finishing/cancelling so global ticker skips this attack. */
    @Volatile
    var cancelled: Boolean = false

    val hologram: AttackHologram? =
        if (Config.flagHologramsEnabled) {
            AttackHologram(this, flagBase.world, flagBase.location.clone().add(0.5, 1.75, 0.5))
        } else {
            null
        }

    val jsonStringBase: StringBuilder
    val jsonString: StringBuilder

    init {
        val flagX = flagBase.x
        val flagY = flagBase.y
        val flagZ = flagBase.z

        this.noBuildXMin = flagX - Config.flagNoBuildDistance
        this.noBuildXMax = flagX + Config.flagNoBuildDistance
        this.noBuildZMin = flagZ - Config.flagNoBuildDistance
        this.noBuildZMax = flagZ + Config.flagNoBuildDistance
        this.noBuildYMin = flagY + Config.flagNoBuildYOffset

        val progressNormalized: Double = this.progress.toDouble() / this.attackTime.toDouble()
        this.progressBar.setProgress(progressNormalized)

        this.jsonStringBase = generateFixedJsonBase(
            this.attacker,
            this.coord,
            this.flagBase,
        )

        try {
            this.hologram?.update()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val jsonStringBufferSize = this.jsonStringBase.capacity() + 20
        this.jsonString = StringBuilder(jsonStringBufferSize)
    }

    public fun cancel() {
        if (this.cancelled) return
        this.cancelled = true

        val attack = this
        Bukkit.getScheduler().runTask(
            Nodes.plugin!!,
            Runnable {
                FlagWar.cancelAttack(attack)
            },
        )
    }

    public fun toJson(): StringBuilder {
        this.jsonString.setLength(0)
        this.jsonString.append(this.jsonStringBase)
        this.jsonString.append("\"p\":${this.progress}")
        this.jsonString.append("}")
        return this.jsonString
    }
}

private fun generateFixedJsonBase(
    attacker: UUID,
    coord: Coord,
    block: Block,
): StringBuilder {
    val s = StringBuilder()
    s.append("{")
    s.append("\"id\":\"$attacker\",")
    s.append("\"c\":[${coord.x},${coord.z}],")
    s.append("\"b\":[${block.x},${block.y},${block.z}],")
    return s
}

/**
 * Floating label above a capture flag: attacking town name and time left.
 * One TextDisplay entity per attack, text only changes once per second.
 */
public class AttackHologram(
    val attack: Attack,
    val world: World,
    val loc: Location,
) {
    var display: TextDisplay = createTextDisplay(world, loc)
    private var lastLabel: String = ""

    public fun remove() {
        try {
            this.display.remove()
        } catch (_: Exception) {
        }
    }

    public fun isValid(): Boolean = this.display.isValid

    public fun respawn() {
        this.remove()
        this.display = createTextDisplay(this.world, this.loc)
        this.lastLabel = ""
    }

    public fun update() {
        if (!this.world.isChunkLoaded(this.loc.chunk)) {
            return
        }

        val remainingSeconds = (this.attack.attackTime - this.attack.progress) / 20
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val timeText = "$minutes:${seconds.toString().padStart(2, '0')}"

        val label = "${this.attack.town.name}|$timeText"
        if (label == this.lastLabel) {
            return
        }
        this.lastLabel = label

        this.display.text(
            Component.text(this.attack.town.name, NamedTextColor.WHITE)
                .append(Component.newline())
                .append(Component.text("[$timeText]", NamedTextColor.GOLD)),
        )
    }
}

internal val NODES_ARMORSTAND_KEY = NamespacedKey("nodes", "armorstand")

private fun createTextDisplay(
    world: World,
    loc: Location,
): TextDisplay {
    val display = world.spawn(loc, TextDisplay::class.java)
    display.billboard = Display.Billboard.CENTER
    display.alignment = TextDisplay.TextAlignment.CENTER
    display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
    display.persistentDataContainer.set(NODES_ARMORSTAND_KEY, PersistentDataType.INTEGER, 0)
    return display
}
