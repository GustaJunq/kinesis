package dev.kinesis.core

import dev.kinesis.core.edit.Factory
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.model.RotoBrush
import dev.kinesis.core.model.RotoFrame
import dev.kinesis.core.model.RotoMode
import dev.kinesis.core.model.RotoStroke
import dev.kinesis.core.video.ObjectSegmenter
import dev.kinesis.core.video.RotoOps
import dev.kinesis.core.video.RotoPropagator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class RotoBrushTest {
    private val w = 120
    private val h = 80

    /** A dark frame with a bright disc of radius [r] at ([cx], [cy]). */
    private fun frame(cx: Int, cy: Int, r: Int = 12): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        if (hypot((x - cx).toFloat(), (y - cy).toFloat()) <= r) 0xFFDCDCDC.toInt() else 0xFF323232.toInt()
    }

    private fun truth(cx: Int, cy: Int, r: Int = 12) = FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        if (hypot((x - cx).toFloat(), (y - cy).toFloat()) <= r) 1f else 0f
    }

    /** Stand-in for the network: grows from each point over pixels of a similar brightness. */
    private val grower = ObjectSegmenter { frame, fw, fh, seeds ->
        val out = FloatArray(fw * fh)
        for (s in seeds) {
            val ref = frame[s[1] * fw + s[0]] and 0xFF
            val stack = ArrayDeque<Int>()
            stack.addLast(s[1] * fw + s[0])
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (out[i] > 0f) continue
                if (abs((frame[i] and 0xFF) - ref) > 40) continue
                out[i] = 1f
                val x = i % fw; val y = i / fw
                if (x > 0) stack.addLast(i - 1); if (x < fw - 1) stack.addLast(i + 1)
                if (y > 0) stack.addLast(i - fw); if (y < fh - 1) stack.addLast(i + fw)
            }
        }
        out
    }

    private fun stroke(mode: RotoMode, vararg pts: Pair<Int, Int>, size: Float = 0.08f) =
        RotoStroke(mode, size, pts.map { Vec2(it.first / (w - 1f), it.second / (h - 1f)) })

    @Test
    fun brushPaintsAContinuousStrokeAtTheRightSize() {
        val painted = RotoOps.paint(listOf(stroke(RotoMode.ADD, 10 to 40, 110 to 40, size = 0.25f)), RotoMode.ADD, w, h)
        // 0.25 of the shorter side (80) = 20 px wide: radius 10 around y = 40.
        assertEquals(1f, painted[40 * w + 60], 0f)
        assertEquals(1f, painted[31 * w + 60], 0f)
        assertEquals(0f, painted[25 * w + 60], 0f)
        assertEquals(0f, painted[40 * w + 90 + 30], 0f)
        for (x in 10..110) assertEquals("gap at $x", 1f, painted[40 * w + x], 0f)
        assertEquals(0f, RotoOps.paint(listOf(stroke(RotoMode.ADD, 10 to 40)), RotoMode.REMOVE, w, h).sum(), 0f)
    }

    @Test
    fun aPaintedFrameCutsOutTheObjectAndStrokesCorrectIt() {
        val p = RotoPropagator(grower, w, h)
        val f = frame(60, 40)
        val first = p.painted(f, listOf(stroke(RotoMode.ADD, 55 to 40, 65 to 40)))
        assertTrue(RotoOps.iou(first.mask, truth(60, 40)) > 0.9f)
        // A remove stroke across the right half takes that part out.
        val cut = p.painted(f, listOf(stroke(RotoMode.ADD, 55 to 40), stroke(RotoMode.REMOVE, 68 to 30, 68 to 50, size = 0.3f)))
        assertTrue(cut.mask[40 * w + 52] > 0.5f)
        assertTrue(cut.mask[40 * w + 66] < 0.5f)
        // An add stroke where the network found nothing is kept as painted.
        val extra = p.painted(f, listOf(stroke(RotoMode.ADD, 20 to 20, 25 to 20)))
        assertTrue(extra.mask[20 * w + 22] > 0.5f)
    }

    @Test
    fun theObjectIsFollowedAcrossFrames() {
        val p = RotoPropagator(grower, w, h)
        var current = p.painted(frame(30, 40), listOf(stroke(RotoMode.ADD, 28 to 40, 32 to 40))).mask
        for (step in 1..25) {
            val cx = 30 + step * 3
            val next = p.next(frame(cx, 40), current)
            assertTrue("step $step confident", next.confident)
            assertTrue("step $step follows: ${RotoOps.iou(next.mask, truth(cx, 40))}", RotoOps.iou(next.mask, truth(cx, 40)) > 0.8f)
            current = next.mask
        }
    }

    @Test
    fun aWrongAnswerKeepsThePreviousMaskAndIsFlagged() {
        val everything = ObjectSegmenter { _, fw, fh, _ -> FloatArray(fw * fh) { 1f } }
        val p = RotoPropagator(everything, w, h)
        val prev = truth(60, 40)
        val r = p.next(frame(60, 40), prev)
        assertFalse(r.confident)
        assertTrue(r.mask === prev)
        // And when the object is gone, nothing is invented.
        val nothing = RotoPropagator({ _, fw, fh, _ -> FloatArray(fw * fh) }, w, h)
        assertFalse(nothing.next(frame(60, 40), prev).confident)
        assertFalse(p.next(frame(60, 40), FloatArray(w * h)).confident)
    }

    @Test
    fun seedsAreInsideTheObject() {
        val seeds = RotoOps.seedsFromMask(truth(70, 30), w, h)
        assertEquals(1, seeds.size)
        assertTrue(hypot((seeds[0][0] - 70).toFloat(), (seeds[0][1] - 30).toFloat()) <= 2f)
        val two = FloatArray(w * h) { if (truth(30, 40)[it] > 0 || truth(90, 40, 10)[it] > 0) 1f else 0f }
        assertEquals(2, RotoOps.seedsFromMask(two, w, h).size)
        val tiny = FloatArray(w * h) { if (truth(30, 40)[it] > 0 || truth(100, 10, 2)[it] > 0) 1f else 0f }
        assertEquals(1, RotoOps.seedsFromMask(tiny, w, h).size)       // a speck is not worth another question
    }

    @Test
    fun planFollowsEachKeyForwardAndTheFirstOneBackward() {
        val plan = RotoOps.plan(listOf(10, 4, 20), first = 0, last = 29)
        assertEquals(listOf(Triple(4, -1, -1), Triple(4, 1, 10), Triple(10, 1, 20), Triple(20, 1, 30)), plan.map { Triple(it.start, it.step, it.stop) })
        assertEquals(listOf(3, 2, 1, 0), RotoOps.framesOf(plan[0]).toList())
        assertEquals(listOf(5, 6, 7, 8, 9), RotoOps.framesOf(plan[1]).toList())
        assertEquals((21..29).toList(), RotoOps.framesOf(plan[3]).toList())
        // Painting key 10 again redoes only 11..19.
        val redo = RotoOps.after(listOf(4, 10, 20), 10, 0, 29)
        assertEquals(1, redo.size)
        assertEquals((11..19).toList(), RotoOps.framesOf(redo[0]).toList())
        // Painting the earliest key also redoes the frames before it.
        assertEquals(2, RotoOps.after(listOf(4, 10), 4, 0, 29).size)
        assertTrue(RotoOps.plan(emptyList(), 0, 10).isEmpty())
    }

    @Test
    fun theThreeViews() {
        val f = frame(60, 40)
        val m = truth(60, 40)
        val mask = RotoOps.compose(f, m, RotoOps.View.MASK, w, h)
        assertEquals(0xFFFFFFFF.toInt(), mask[40 * w + 60])
        assertEquals(0xFF000000.toInt(), mask[5 * w + 5])
        val final = RotoOps.compose(f, m, RotoOps.View.FINAL, w, h)
        assertEquals(f[40 * w + 60], final[40 * w + 60])
        assertTrue(final[5 * w + 5] != f[5 * w + 5])           // the background is the checkerboard
        val overlay = RotoOps.compose(f, m, RotoOps.View.OVERLAY, w, h)
        assertEquals(f[5 * w + 5], overlay[5 * w + 5])
        assertTrue(overlay[40 * w + 60] != f[40 * w + 60])
        assertEquals(f[40 * w + 60], RotoOps.compose(f, null, RotoOps.View.MASK, w, h)[40 * w + 60])
    }

    @Test
    fun strokesAreSavedWithTheProjectAndValidated() {
        val p0 = Factory.newProject("t", 1080, 1920, 30.0, 4.0, Color.parse("#000000"), null, "test")
        val src = Asset("src", AssetType.VIDEO, "assets/a.mp4", width = 1080, height = 1920, duration = 4.0)
        val cut = Asset("cut", AssetType.VIDEO, "assets/b.mp4", width = 2160, height = 1920, duration = 4.0)
        val roto = RotoBrush("src", 0.5, 3.0, listOf(RotoFrame(0.0, listOf(RotoStroke(RotoMode.ADD, 0.05f, listOf(Vec2(0.4f, 0.5f), Vec2(0.5f, 0.55f)))))))
        val layer = Factory.video(p0.copy(assets = listOf(src, cut)), 0.0, cut, "cut").let { it.copy(video = MediaContent("cut", alphaPacked = true, roto = roto)) }
        val project = p0.copy(assets = listOf(src, cut), layers = listOf(layer))
        assertTrue(ProjectValidator.validate(project).errors.toString(), ProjectValidator.validate(project).isValid)
        val back = KnsprojJson.decode(KnsprojJson.encode(project))
        assertEquals(roto, back.layers[0].video!!.roto)
        // Problems are reported.
        fun codes(r: RotoBrush) = ProjectValidator.validate(project.copy(layers = listOf(layer.copy(video = layer.video!!.copy(roto = r))))).errors.map { it.code }
        assertEquals(listOf("roto.source"), codes(roto.copy(source = "nope")))
        assertEquals(listOf("roto.time"), codes(roto.copy(frames = listOf(RotoFrame(9.0, roto.frames[0].strokes)))))
        assertEquals(listOf("roto.stroke"), codes(roto.copy(frames = listOf(RotoFrame(0.0, listOf(RotoStroke(points = listOf(Vec2(2f, 0.5f)))))))))
        assertEquals(listOf("roto.stroke"), codes(roto.copy(frames = listOf(RotoFrame(0.0, listOf(RotoStroke(points = emptyList())))))))
        assertEquals(listOf("roto.range"), codes(roto.copy(duration = 0.0)))
        assertNotNull(back.layers[0].video)
    }
}
