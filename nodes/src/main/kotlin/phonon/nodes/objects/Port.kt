/**
 * Port objects and data structures
 */

package phonon.nodes.objects

import phonon.nodes.serdes.SaveState

data class Port(
    val name: String,
    val locX: Int,
    val locZ: Int,
    val groups: HashSet<PortGroup> = hashSetOf(),
    val isPublic: Boolean,
) {
    val chunkX = Math.floorDiv(locX, 16)
    val chunkZ = Math.floorDiv(locZ, 16)

    // who may warp here when the port is owned, and what they pay (cents, 0 = free).
    // Owner town and nation members always warp free. Defaults match the old rule:
    // allies only.
    var allowAlly: Boolean = true
    var allowNeutral: Boolean = false
    var allowEnemy: Boolean = false
    var costAlly: Long = 0L
    var costNeutral: Long = 0L
    var costEnemy: Long = 0L

    private var saveState: PortSaveState

    @Suppress("PropertyName")
    private var _needsUpdate = false

    init {
        this.saveState = PortSaveState(this)
    }

    public class PortSaveState(p: Port) : SaveState {
        public val name = p.name
        public val locX = p.locX
        public val locZ = p.locZ
        public val groups = p.groups
        public val isPublic = p.isPublic
        public val allowAlly = p.allowAlly
        public val allowNeutral = p.allowNeutral
        public val allowEnemy = p.allowEnemy
        public val costAlly = p.costAlly
        public val costNeutral = p.costNeutral
        public val costEnemy = p.costEnemy

        public override var jsonString: String? = null

        public override fun createJsonString(): String {
            val groupsJson = groups.joinToString(",", "[", "]") { "\"${it.name}\"" }

            val jsonString = (
                "{" +
                    "\"name\":\"${this.name}\"," +
                    "\"x\":$locX," +
                    "\"z\":$locZ," +
                    "\"groups\":$groupsJson," +
                    "\"isPublic\":$isPublic," +
                    "\"allowAlly\":$allowAlly," +
                    "\"allowNeutral\":$allowNeutral," +
                    "\"allowEnemy\":$allowEnemy," +
                    "\"costAlly\":$costAlly," +
                    "\"costNeutral\":$costNeutral," +
                    "\"costEnemy\":$costEnemy" +
                    "}"
                )

            return jsonString
        }

        public override fun hashCode(): Int = this.name.hashCode()
    }

    public fun needsUpdate() {
        this._needsUpdate = true
    }

    public fun getSaveState(): PortSaveState {
        if (this._needsUpdate) {
            this.saveState = PortSaveState(this)
            this._needsUpdate = false
        }
        return this.saveState
    }
}

data class PortGroup(
    val name: String,
) {
    private var saveState: PortGroupSaveState

    @Suppress("PropertyName")
    private var _needsUpdate = false

    init {
        this.saveState = PortGroupSaveState(this)
    }

    public class PortGroupSaveState(p: PortGroup) : SaveState {
        public val name = p.name

        public override var jsonString: String? = null

        public override fun createJsonString(): String {
            val jsonString = (
                "{" +
                    "\"name\":\"${this.name}\"" +
                    "}"
                )

            return jsonString
        }

        public override fun hashCode(): Int = this.name.hashCode()
    }

    public fun needsUpdate() {
        this._needsUpdate = true
    }

    public fun getSaveState(): PortGroupSaveState {
        if (this._needsUpdate) {
            this.saveState = PortGroupSaveState(this)
            this._needsUpdate = false
        }
        return this.saveState
    }
}
