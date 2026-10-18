package dev.kinesis.editor.render

import android.opengl.GLES30
import android.util.Log

/**
 * Recent pictures of layers that use TimeSlice, by (layer, frame): the strips that look into the past read them back
 * instead of decoding the video again. Frames are stored as they were rendered (the layer with the effects placed before
 * TimeSlice), at the size of the output they were rendered for. Memory is bounded: the least recently used go first.
 */
class SliceCache {
    private data class Key(val owner: String, val frame: Int, val width: Int, val height: Int)

    /** [use] marks the pass that last read or wrote it: those are not evicted while that pass still draws from them. */
    private class Entry(val target: RenderTarget, var use: Int)

    private val frames = LinkedHashMap<Key, Entry>(64, 0.75f, true)
    private var bytes = 0L
    private var pass = 0
    var budget = DEFAULT_BYTES

    val size: Int get() = frames.size

    /** Starts a pass (one TimeSlice effect of one frame): what it reads or stores stays until the next pass. */
    fun beginPass() {
        pass++
    }

    fun get(owner: String, frame: Int, width: Int, height: Int): RenderTarget? =
        frames[Key(owner, frame, width, height)]?.also { it.use = pass }?.target

    fun contains(owner: String, frame: Int, width: Int, height: Int): Boolean = Key(owner, frame, width, height) in frames

    /** The stored frame of [owner] nearest to [frame] (preferring older ones), to stand in for one that is missing. */
    fun nearest(owner: String, frame: Int, width: Int, height: Int, within: Int): RenderTarget? {
        for (d in 1..within) {
            frames[Key(owner, frame - d, width, height)]?.let { it.use = pass; return it.target }
            frames[Key(owner, frame + d, width, height)]?.let { it.use = pass; return it.target }
        }
        return null
    }

    /** Keeps a copy of [source] as frame [frame]. Returns false if there is no room for even one frame. */
    fun put(owner: String, frame: Int, source: RenderTarget): Boolean {
        val key = Key(owner, frame, source.width, source.height)
        frames[key]?.let { it.use = pass; return true }
        val cost = source.width.toLong() * source.height * 4
        if (cost > budget) return false
        while (bytes + cost > budget) if (!evictEldest()) return false
        val copy = try {
            RenderTarget(source.width, source.height, withDepth = false)
        } catch (e: Throwable) {
            Log.w("Kinesis", "time slice cache is full of GPU memory", e)
            clear()
            return false
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, source.framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, copy.framebuffer)
        GLES30.glBlitFramebuffer(0, 0, source.width, source.height, 0, 0, copy.width, copy.height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        frames[key] = Entry(copy, pass)
        bytes += cost
        return true
    }

    /** Frees the least recently used frame not in use by the current pass; false when there is none. */
    private fun evictEldest(): Boolean {
        val it = frames.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.use == pass) continue
            it.remove()
            bytes -= e.value.target.width.toLong() * e.value.target.height * 4
            e.value.target.release()
            return true
        }
        return false
    }

    fun clear() {
        frames.values.forEach { it.target.release() }
        frames.clear()
        bytes = 0
    }

    companion object {
        const val DEFAULT_BYTES = 96L * 1024 * 1024
    }
}
