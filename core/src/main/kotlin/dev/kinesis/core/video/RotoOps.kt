package dev.kinesis.core.video

import dev.kinesis.core.model.RotoMode
import dev.kinesis.core.model.RotoStroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure helpers of the Roto Brush: painting strokes into masks, finding where to ask the network about the object,
 * deciding which frames a correction touches and drawing the three ways of looking at a cut-out. Masks are
 * [FloatArray]s of 0..1 values, row by row.
 */
object RotoOps {
    /** The three ways to look at a frame: the cut-out over a checkerboard, a tinted overlay, or the mask in black and white. */
    enum class View { FINAL, OVERLAY, MASK }

    /** Paints the strokes of [mode] into a new mask of [w]×[h] (1 where the brush went). */
    fun paint(strokes: List<RotoStroke>, mode: RotoMode, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val side = min(w, h).toFloat()
        for (s in strokes) {
            if (s.mode != mode || s.points.isEmpty()) continue
            val radius = max(0.5f, s.size * side / 2f)
            var px = s.points[0].x * (w - 1)
            var py = s.points[0].y * (h - 1)
            stamp(out, w, h, px, py, radius)
            for (i in 1 until s.points.size) {
                val x = s.points[i].x * (w - 1)
                val y = s.points[i].y * (h - 1)
                // Stamp along the segment so a fast stroke leaves no gaps.
                val steps = max(1, (hypot(x - px, y - py) / max(1f, radius / 2f)).toInt())
                for (k in 1..steps) stamp(out, w, h, px + (x - px) * k / steps, py + (y - py) * k / steps, radius)
                px = x; py = y
            }
        }
        return out
    }

    private fun stamp(out: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float) {
        val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt() + 1)
        val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt() + 1)
        val r2 = r * r
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2) out[y * w + x] = 1f
        }
    }

    /** What the network found, with the strokes laid over it: painted-add pixels are in, painted-remove pixels are out (remove wins). */
    fun applyStrokes(model: FloatArray?, strokes: List<RotoStroke>, w: Int, h: Int): FloatArray {
        val out = model?.copyOf() ?: FloatArray(w * h)
        val add = paint(strokes, RotoMode.ADD, w, h)
        val remove = paint(strokes, RotoMode.REMOVE, w, h)
        for (i in out.indices) {
            if (add[i] > 0f) out[i] = 1f
            if (remove[i] > 0f) out[i] = 0f
        }
        return out
    }

    /** Where the network is asked about the object for a painted frame: one point for each add stroke (at most [max]), inside the stroke. */
    fun seedsFromStrokes(strokes: List<RotoStroke>, w: Int, h: Int, remove: FloatArray? = null, max: Int = 3): List<IntArray> {
        val seeds = ArrayList<IntArray>()
        for (s in strokes) {
            if (s.mode != RotoMode.ADD || s.points.isEmpty()) continue
            val mx = s.points.map { it.x }.average(); val my = s.points.map { it.y }.average()
            // The stroke point nearest to the middle of the stroke that is not painted over by a remove stroke.
            val best = s.points.filter { p -> remove == null || remove[(p.y * (h - 1)).toInt() * w + (p.x * (w - 1)).toInt()] == 0f }
                .minByOrNull { (it.x - mx) * (it.x - mx) + (it.y - my) * (it.y - my) } ?: continue
            seeds += intArrayOf((best.x * (w - 1)).toInt(), (best.y * (h - 1)).toInt())
            if (seeds.size >= max) break
        }
        return seeds
    }

    class Component(val area: Int, val seed: IntArray, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    /** Connected parts (8 neighbours) of the mask above [threshold], largest first, each with the point deepest inside it. */
    fun components(mask: FloatArray, w: Int, h: Int, threshold: Float = 0.5f): List<Component> {
        val label = IntArray(w * h)
        val result = ArrayList<Component>()
        val stack = IntArray(w * h)
        var next = 0
        val dist = distanceInside(mask, w, h, threshold)
        for (start in mask.indices) {
            if (mask[start] <= threshold || label[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start; label[start] = next
            var area = 0
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var best = start
            while (sp > 0) {
                val i = stack[--sp]
                area++
                val x = i % w; val y = i / w
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (dist[i] > dist[best]) best = i
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (mask[j] > threshold && label[j] == 0) { label[j] = next; stack[sp++] = j }
                }
            }
            result += Component(area, intArrayOf(best % w, best / w), minX, minY, maxX, maxY)
        }
        return result.sortedByDescending { it.area }
    }

    /** Distance (in pixels, chamfer 3-4) from each pixel inside the mask to the nearest pixel outside it; 0 outside. */
    fun distanceInside(mask: FloatArray, w: Int, h: Int, threshold: Float = 0.5f): IntArray {
        val inf = Int.MAX_VALUE / 2
        val d = IntArray(w * h) { if (mask[it] > threshold) inf else 0 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0) continue
            var v = d[i]
            v = min(v, if (x == 0 || y == 0) 3 else d[i - w - 1] + 4)
            v = min(v, if (y == 0) 3 else d[i - w] + 3)
            v = min(v, if (y == 0 || x == w - 1) 3 else d[i - w + 1] + 4)
            v = min(v, if (x == 0) 3 else d[i - 1] + 3)
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0) continue
            var v = d[i]
            v = min(v, if (x == w - 1 || y == h - 1) 3 else d[i + w + 1] + 4)
            v = min(v, if (y == h - 1) 3 else d[i + w] + 3)
            v = min(v, if (y == h - 1 || x == 0) 3 else d[i + w - 1] + 4)
            v = min(v, if (x == w - 1) 3 else d[i + 1] + 3)
            d[i] = v
        }
        return d
    }

    /** Where to ask about the object in the next frame: the deepest point of each big part of [previous]. */
    fun seedsFromMask(previous: FloatArray, w: Int, h: Int, max: Int = 2, minShare: Float = 0.12f): List<IntArray> {
        val parts = components(previous, w, h)
        if (parts.isEmpty()) return emptyList()
        val total = parts.sumOf { it.area }.toFloat()
        return parts.filterIndexed { i, c -> i == 0 || c.area / total >= minShare }.take(max).map { it.seed }
    }

    /** Intersection over union of two masks (above [threshold]); 1 when both are empty. */
    fun iou(a: FloatArray, b: FloatArray, threshold: Float = 0.5f): Float {
        var inter = 0; var union = 0
        for (i in a.indices) {
            val x = a[i] > threshold; val y = b[i] > threshold
            if (x && y) inter++
            if (x || y) union++
        }
        return if (union == 0) 1f else inter.toFloat() / union
    }

    fun area(mask: FloatArray, threshold: Float = 0.5f): Int = mask.count { it > threshold }

    /** Grows the mask by [radius] pixels (separable max filter). */
    fun dilate(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask.copyOf()
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, x - radius)..min(w - 1, x + radius)) if (mask[y * w + k] > m) m = mask[y * w + k]
            tmp[y * w + x] = m
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, y - radius)..min(h - 1, y + radius)) if (tmp[k * w + x] > m) m = tmp[k * w + x]
            out[y * w + x] = m
        }
        return out
    }

    /** How far an object may move between two frames: a share of its size, at least a few pixels. */
    fun motionAllowance(area: Int, w: Int, h: Int): Int = max(3, (sqrt(area.toFloat()) * 0.35f).toInt()).coerceAtMost(min(w, h) / 3)

    /** One run of the propagation: starting at [start], going [step] (+1 or −1) frames at a time up to and excluding [stop]. */
    class Segment(val start: Int, val step: Int, val stop: Int)

    /**
     * How the frames [first]..[last] are filled from the painted [keys] (frame numbers): each key is followed forward
     * until the next key; the first key is also followed backward to [first]. Painting a key again only needs the frames
     * from there on, see [after].
     */
    fun plan(keys: List<Int>, first: Int, last: Int): List<Segment> {
        val sorted = keys.filter { it in first..last }.distinct().sorted()
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<Segment>()
        if (sorted[0] > first) out += Segment(sorted[0], -1, first - 1)
        for (i in sorted.indices) out += Segment(sorted[i], +1, if (i + 1 < sorted.size) sorted[i + 1] else last + 1)
        return out
    }

    /** The segments to redo after the key at [changed] was painted: that key and the frames after it, up to the next key. */
    fun after(keys: List<Int>, changed: Int, first: Int, last: Int): List<Segment> {
        val all = plan(keys + changed, first, last)
        val sorted = (keys + changed).filter { it in first..last }.distinct().sorted()
        // Painting the earliest key also changes the frames before it.
        return all.filter { s -> s.start == changed && (s.step > 0 || changed == sorted.first()) }
    }

    /** Frames covered by a segment, in the order they are visited, without its first (the key itself). */
    fun framesOf(s: Segment): IntArray {
        val n = if (s.step > 0) max(0, s.stop - s.start - 1) else max(0, s.start - s.stop - 1)
        return IntArray(n) { s.start + s.step * (it + 1) }
    }

    /**
     * Renders a frame for the screen. [frame] is opaque ARGB; [mask] 0..1 (null = not cut out yet, shown plain).
     * FINAL: the cut-out over a checkerboard. OVERLAY: the frame tinted with a colour where the mask is. MASK: white = kept.
     */
    fun compose(frame: IntArray, mask: FloatArray?, view: View, w: Int, h: Int, out: IntArray = IntArray(frame.size), tint: Int = 0xFF2BD9FE.toInt()): IntArray {
        for (i in frame.indices) {
            val a = mask?.get(i)?.coerceIn(0f, 1f)
            val px = frame[i]
            out[i] = when {
                a == null -> px
                view == View.MASK -> { val v = (a * 255f + 0.5f).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                view == View.OVERLAY -> mix(px, tint, a * 0.55f)
                else -> {
                    val x = i % w; val y = i / w
                    val check = if (((x / 12) + (y / 12)) % 2 == 0) 0xFF3A3F4B.toInt() else 0xFF2A2E38.toInt()
                    mix(check, px, a)
                }
            }
        }
        return out
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF; val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
