/*
 * Skill experience per player, saved in plugins/nodes/skills.json. Changes are marked dirty and
 * written every minute and on shutdown.
 */

package phonon.nodes.skills

import com.google.gson.GsonBuilder
import org.bukkit.Material
import phonon.nodes.Nodes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

enum class Skill(val label: String, val icon: Material) {
    MINING("Mining", Material.IRON_PICKAXE),
    FARMING("Farming", Material.IRON_HOE),
    WOODCUTTING("Woodcutting", Material.IRON_AXE),
    HUNTING("Hunting", Material.BOW),
    ;

    companion object {
        fun parse(text: String): Skill? = values().firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

class SkillProgress(
    var name: String = "",
    var xp: MutableMap<String, Long> = mutableMapOf(),
)

private class SkillFile(
    var players: MutableMap<String, SkillProgress> = mutableMapOf(),
)

object SkillData {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/skills.json")
    private var file = SkillFile()
    private var dirty = false

    fun load() {
        file = SkillFile()
        dirty = false
        if (!Files.exists(path)) {
            return
        }
        try {
            gson.fromJson(String(Files.readAllBytes(path), StandardCharsets.UTF_8), SkillFile::class.java)?.let { file = it }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Skills] Could not read skills.json: ${e.message}")
        }
    }

    fun flush() {
        if (!dirty) {
            return
        }
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("skills.tmp")
            Files.write(tmp, gson.toJson(file).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            dirty = false
        } catch (e: Exception) {
            Nodes.logger?.severe("[Skills] Could not save skills.json: ${e.message}")
        }
    }

    fun xp(uuid: String, skill: Skill): Long = file.players[uuid]?.xp?.get(skill.name) ?: 0L

    fun setXp(uuid: String, name: String, skill: Skill, xp: Long) {
        val progress = file.players.getOrPut(uuid) { SkillProgress() }
        progress.name = name
        progress.xp[skill.name] = xp.coerceAtLeast(0L)
        dirty = true
    }

    fun reset(uuid: String): Boolean {
        val removed = file.players.remove(uuid) != null
        if (removed) {
            dirty = true
        }
        return removed
    }

    // top players of a skill: name to xp
    fun top(skill: Skill, count: Int): List<Pair<String, Long>> {
        val all = file.players.values.mapNotNull { p -> p.xp[skill.name]?.takeIf { it > 0 }?.let { Pair(p.name, it) } }
        return all.sortedByDescending { it.second }.take(count)
    }
}
