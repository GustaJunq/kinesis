package dev.kinesis.core.edit

/**
 * Snapshot-based undo/redo. States are immutable, so keeping previous versions is cheap (structural sharing).
 * Consecutive pushes with the same non-null [coalesceKey] (e.g. one drag gesture) collapse into one step.
 */
class History<T>(initial: T, private val limit: Int = 200) {
    var present: T = initial
        private set

    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var lastKey: String? = null

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val undoDepth: Int get() = past.size

    fun push(state: T, coalesceKey: String? = null) {
        if (state == present) return
        if (coalesceKey == null || coalesceKey != lastKey) {
            past.addLast(present)
            while (past.size > limit) past.removeFirst()
        }
        present = state
        future.clear()
        lastKey = coalesceKey
    }

    /** Ends the current coalescing run so the next push becomes a separate undo step. */
    fun commit() {
        lastKey = null
    }

    fun undo(): T? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(present)
        present = prev
        lastKey = null
        return present
    }

    fun redo(): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(present)
        present = next
        lastKey = null
        return present
    }

    /** Replaces the present state without recording an undo step (e.g. after loading). */
    fun reset(state: T) {
        present = state
        past.clear()
        future.clear()
        lastKey = null
    }
}
