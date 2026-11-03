/*
 * Port access and fees, one policy per town. The policy covers every port the
 * town owns, now and later (a captured or newly approved port follows its
 * owner's policy), so nobody has to set it port by port. A town starts with
 * "allies may warp for free; neutrals and enemies may not". The town's own
 * members and its nation always warp free. Saved in plugins/nodes/portpolicies.json.
 */

package phonon.nodes.ports

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import phonon.nodes.Nodes
import phonon.nodes.objects.Town
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class PortPolicy(
    var allowAlly: Boolean = true,
    var allowNeutral: Boolean = false,
    var allowEnemy: Boolean = false,
    var costAlly: Long = 0L,
    var costNeutral: Long = 0L,
    var costEnemy: Long = 0L,
)

object PortPolicies {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/portpolicies.json")
    private val policies: LinkedHashMap<String, PortPolicy> = LinkedHashMap()
    private val default = PortPolicy()

    fun load() {
        policies.clear()
        if (!Files.exists(path)) {
            return
        }
        try {
            val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            val type = object : TypeToken<LinkedHashMap<String, PortPolicy>>() {}.type
            val loaded: LinkedHashMap<String, PortPolicy>? = gson.fromJson(text, type)
            loaded?.let { policies.putAll(it) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Ports] Could not read portpolicies.json: ${e.message}")
        }
        Nodes.logger?.info("[Ports] Loaded port policies for ${policies.size} towns")
    }

    private fun save() {
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("portpolicies.tmp")
            Files.write(tmp, gson.toJson(policies).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Nodes.logger?.severe("[Ports] Could not save portpolicies.json: ${e.message}")
        }
    }

    // the town's policy, or the default one when it never changed anything (read only)
    fun of(town: Town): PortPolicy = policies[town.uuid.toString()] ?: default

    // changes the town's policy and saves it
    fun edit(town: Town, change: (PortPolicy) -> Unit) {
        val policy = policies.getOrPut(town.uuid.toString()) { PortPolicy() }
        change(policy)
        save()
    }
}
