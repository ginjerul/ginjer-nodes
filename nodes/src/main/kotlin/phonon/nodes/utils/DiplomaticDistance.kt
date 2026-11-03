/*
 * Distance between two diplomatic sides (a town, or every town of its nation), used
 * to limit alliances (allyProximity) and war declarations (warProximity).
 */

package phonon.nodes.utils

import phonon.nodes.Nodes
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Town

object DiplomaticDistance {
    // blocks between the closest pair of town homes of the two sides; null when a home is unknown
    fun blocks(town: Town, townNation: Nation?, other: Town, otherNation: Nation?): Double? {
        val mine = (townNation?.towns ?: setOf(town)).mapNotNull { homeChunk(it) }
        val theirs = (otherNation?.towns ?: setOf(other)).mapNotNull { homeChunk(it) }
        if (mine.isEmpty() || theirs.isEmpty()) {
            return null
        }
        var best = Double.MAX_VALUE
        for (a in mine) {
            for (b in theirs) {
                val dx = (a.x - b.x).toDouble()
                val dz = (a.z - b.z).toDouble()
                best = minOf(best, Math.sqrt(dx * dx + dz * dz) * 16.0)
            }
        }
        return best
    }

    private fun homeChunk(town: Town): Coord? = Nodes.territories[town.home]?.core
}
