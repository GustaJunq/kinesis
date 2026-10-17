package dev.kinesis.core.edit

import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.Marker

/** Timeline markers: named time references that live on the ruler, independent of the layers. */
object Markers {
    /** Two markers closer than this (seconds) are the same point: adding at an occupied time returns the existing one. */
    const val MERGE_WINDOW = 0.01

    /** Playhead moves smaller than this (seconds) do not count as "already there" when jumping to the next point. */
    const val EPS = 1e-4

    fun sorted(project: KnsProject): List<Marker> = project.markers.sortedBy { it.time }

    fun find(project: KnsProject, id: String): Marker? = project.markers.firstOrNull { it.id == id }

    /** The marker within [window] seconds of [time], nearest first. */
    fun at(project: KnsProject, time: Double, window: Double = MERGE_WINDOW): Marker? =
        project.markers.filter { kotlin.math.abs(it.time - time) <= window }.minByOrNull { kotlin.math.abs(it.time - time) }

    /** Adds a marker at [time] (clamped to the composition). Returns the project and the id of the marker now there. */
    fun add(project: KnsProject, time: Double, name: String = ""): Pair<KnsProject, String> {
        val t = clamp(project, time)
        at(project, t)?.let { return project to it.id }
        val taken = project.markers.mapTo(HashSet()) { it.id } + LayerTree.allIds(project)
        val id = Ids.newId("marker", taken)
        return project.copy(markers = project.markers + Marker(id, t, name.trim())) to id
    }

    fun move(project: KnsProject, id: String, time: Double): KnsProject =
        update(project, id) { it.copy(time = clamp(project, time)) }

    fun rename(project: KnsProject, id: String, name: String): KnsProject = update(project, id) { it.copy(name = name.trim()) }

    fun recolor(project: KnsProject, id: String, color: String?): KnsProject = update(project, id) { it.copy(color = color) }

    fun remove(project: KnsProject, id: String): KnsProject = project.copy(markers = project.markers.filterNot { it.id == id })

    /** Latest point before [time] among the markers and [extra] times (e.g. keyframes of the selected layer). */
    fun previous(project: KnsProject, time: Double, extra: List<Double> = emptyList()): Double? =
        (project.markers.map { it.time } + extra).filter { it < time - EPS }.maxOrNull()

    /** Earliest point after [time] among the markers and [extra] times. */
    fun next(project: KnsProject, time: Double, extra: List<Double> = emptyList()): Double? =
        (project.markers.map { it.time } + extra).filter { it > time + EPS }.minOrNull()

    private fun clamp(project: KnsProject, t: Double) = t.coerceIn(0.0, project.composition.duration)

    private fun update(project: KnsProject, id: String, f: (Marker) -> Marker): KnsProject =
        project.copy(markers = project.markers.map { if (it.id == id) f(it) else it })
}
