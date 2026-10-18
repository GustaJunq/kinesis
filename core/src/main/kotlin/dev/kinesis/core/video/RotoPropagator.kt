package dev.kinesis.core.video

import dev.kinesis.core.model.RotoStroke

/** Asks the network for the object under some points of a frame; the union of what it finds for each point (0..1). */
fun interface ObjectSegmenter {
    fun segment(frame: IntArray, w: Int, h: Int, seeds: List<IntArray>): FloatArray
}

/**
 * Follows an object through a video from the frames the person painted. The network only answers "which object is under
 * this point"; the strokes are laid over its answer on painted frames, and on the following frames the points come from
 * the previous mask, so the object is followed by its own shape. If the answer does not look like the same object (it
 * changed size, moved off, or disappeared) the previous mask is kept and the frame is flagged, so it shows what needs a
 * correction instead of inventing something.
 */
class RotoPropagator(private val segmenter: ObjectSegmenter, val w: Int, val h: Int) {
    /** [confident] is false when the mask is a guess (kept from the previous frame). */
    class Result(val mask: FloatArray, val confident: Boolean)

    /** A frame the person painted. */
    fun painted(frame: IntArray, strokes: List<RotoStroke>): Result {
        val remove = RotoOps.paint(strokes, dev.kinesis.core.model.RotoMode.REMOVE, w, h)
        val seeds = RotoOps.seedsFromStrokes(strokes, w, h, remove)
        val model = if (seeds.isEmpty()) null else segmenter.segment(frame, w, h, seeds)
        val laid = RotoOps.applyStrokes(model, strokes, w, h)
        return Result(MaskTools.refine(laid), true)
    }

    /** The frame after (or before) one that already has [previous]. */
    fun next(frame: IntArray, previous: FloatArray): Result {
        val seeds = RotoOps.seedsFromMask(previous, w, h)
        if (seeds.isEmpty()) return Result(previous, false)
        val model = segmenter.segment(frame, w, h, seeds)
        val before = RotoOps.area(previous)
        val allowed = RotoOps.dilate(previous, w, h, RotoOps.motionAllowance(before, w, h))
        val candidate = FloatArray(model.size) { model[it] * allowed[it] }
        val now = RotoOps.area(candidate)
        val ratio = if (before == 0) 1f else now.toFloat() / before
        val looksSame = before > 0 && ratio in MIN_RATIO..MAX_RATIO && RotoOps.iou(candidate, previous) >= MIN_IOU
        if (!looksSame) return Result(previous, false)
        val refined = MaskTools.refine(candidate)
        return Result(MaskTools.smoothInTime(previous, refined, STEADINESS), true)
    }

    companion object {
        const val MIN_RATIO = 0.5f
        const val MAX_RATIO = 2.0f
        const val MIN_IOU = 0.4f
        const val STEADINESS = 0.5f
    }
}
