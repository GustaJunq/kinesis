package dev.kinesis.core.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

class ExpressionEngineTest {
    private val comp = ExprComp(1080.0, 1920.0, 10.0, 1.0 / 30.0, 3)
    private val layer = ExprLayerInfo("L", 1, 0.0, 5.0, doubleArrayOf(540.0, 960.0), doubleArrayOf(100.0, 100.0), 0.0, 100.0)

    /** Input for a scalar property keyed at [keys] (time → value), sampled linearly, evaluated at [time]. */
    private fun scalar(
        time: Double,
        value: Double = 0.0,
        keys: List<Pair<Double, Double>> = emptyList(),
        textIndex: Int = 0,
        textTotal: Int = 0,
        selector: Double? = null,
        seed: Long = 7,
    ) = ExprInput(
        time = time, value = doubleArrayOf(value), valueIsArray = false, inPoint = 0.0, outPoint = 5.0, layerIndex = 1,
        comp = comp, seed = seed, keys = keys.map { ExprKey(it.first, doubleArrayOf(it.second)) },
        valueAtTime = { t -> doubleArrayOf(linearAt(keys, t, value)) }, ownLayer = layer,
        textIndex = textIndex, textTotal = textTotal, selectorValue = selector?.let { doubleArrayOf(it) },
    )

    private fun linearAt(keys: List<Pair<Double, Double>>, t: Double, default: Double): Double {
        if (keys.isEmpty()) return default
        if (t <= keys.first().first) return keys.first().second
        if (t >= keys.last().first) return keys.last().second
        val i = keys.indexOfLast { it.first <= t }
        val (t0, v0) = keys[i]; val (t1, v1) = keys[i + 1]
        return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    }

    private fun num(src: String, input: ExprInput) = ExpressionEngine.evaluate(src, input).values[0]

    @Test
    fun resultIsTheLastEvaluatedStatement() {
        assertEquals(6.0, num("a = 2; b = 3; a * b", scalar(0.0)), 0.0)
        assertEquals(1.5, num("t = time; if (t > 1) { 10 } else { t + 0.5 }", scalar(1.0)), 1e-12)
        assertEquals(10.0, num("t = time; if (t > 1) { 10 } else { t + 0.5 }", scalar(2.0)), 1e-12)
    }

    @Test
    fun contextVariables() {
        val i = scalar(2.5, value = 40.0)
        assertEquals(2.5, num("time", i), 0.0)
        assertEquals(40.0, num("value", i), 0.0)
        assertEquals(0.0, num("inPoint", i), 0.0)
        assertEquals(5.0, num("outPoint", i), 0.0)
        assertEquals(1.0, num("index", i), 0.0)
        assertEquals(1080.0, num("thisComp.width", i), 0.0)
        assertEquals(3.0, num("thisComp.numLayers", i), 0.0)
        assertEquals(1.0, num("thisLayer.index", i), 0.0)
        assertEquals(5.0, num("thisLayer.outPoint", i), 0.0)
    }

    @Test
    fun vectorsAndMath() {
        val r = ExpressionEngine.evaluate("add([1, 2], [10, 20])", scalar(0.0))
        assertTrue(r.isArray); assertEquals(11.0, r.values[0], 0.0); assertEquals(22.0, r.values[1], 0.0)
        assertEquals(5.0, num("length([3, 4])", scalar(0.0)), 1e-12)
        assertEquals(5.0, num("length([0, 0], [3, 4])", scalar(0.0)), 1e-12)
        val n = ExpressionEngine.evaluate("normalize([0, 3])", scalar(0.0)).values
        assertEquals(0.0, n[0], 1e-12); assertEquals(1.0, n[1], 1e-12)
        val c = ExpressionEngine.evaluate("clamp([-5, 50, 500], 0, 100)", scalar(0.0)).values
        assertEquals(listOf(0.0, 50.0, 100.0), c.toList())
        assertEquals(PI, num("Math.PI", scalar(0.0)), 0.0)
    }

    @Test
    fun linearAndEase() {
        assertEquals(50.0, num("linear(1, 0, 2, 0, 100)", scalar(0.0)), 1e-9)
        assertEquals(0.0, num("linear(-1, 0, 2, 0, 100)", scalar(0.0)), 1e-9)
        assertEquals(100.0, num("linear(5, 0, 2, 0, 100)", scalar(0.0)), 1e-9)
        assertEquals(25.0, num("linear(0.25, 0, 100)", scalar(0.0)), 1e-9)
        val v = ExpressionEngine.evaluate("linear(1, 0, 2, [0, 10], [100, 30])", scalar(0.0)).values
        assertEquals(50.0, v[0], 1e-9); assertEquals(20.0, v[1], 1e-9)
        // Ease curves: 0 and 1 at the ends, symmetric at the middle for ease, slow start for easeIn, slow end for easeOut.
        assertEquals(0.5, num("ease(0.5, 0, 1, 0, 1)", scalar(0.0)), 1e-9)
        assertTrue(num("easeIn(0.25, 0, 1, 0, 1)", scalar(0.0)) < 0.25)
        assertTrue(num("easeOut(0.25, 0, 1, 0, 1)", scalar(0.0)) > 0.25)
        assertEquals(1.0, num("easeIn(1, 0, 1, 0, 1)", scalar(0.0)), 1e-9)
        assertEquals(1.0, num("easeOut(1, 0, 1, 0, 1)", scalar(0.0)), 1e-9)
    }

    @Test
    fun randomIsDeterministic() {
        val a = num("seedRandom(5, true); random()", scalar(1.0))
        val b = num("seedRandom(5, true); random()", scalar(2.0))
        assertEquals("timeless seed gives the same value at every time", a, b, 0.0)
        val c = num("seedRandom(5, false); random()", scalar(1.0))
        val d = num("seedRandom(5, false); random()", scalar(2.0))
        assertTrue("seed varies per frame unless timeless", c != d)
        assertEquals(c, num("seedRandom(5, false); random()", scalar(1.0)), 0.0)
        val r = num("random(10, 20)", scalar(0.0))
        assertTrue(r in 10.0..20.0)
        assertEquals(num("random()", scalar(0.5, seed = 1)), num("random()", scalar(0.5, seed = 1)), 0.0)
        assertTrue(num("random()", scalar(0.5, seed = 1)) != num("random()", scalar(0.5, seed = 2)))
        assertEquals(num("Math.random()", scalar(0.5)), num("random()", scalar(0.5)), 0.0)
    }

    @Test
    fun wiggleIsDeterministicAndBounded() {
        val src = "wiggle(2, 50)"
        for (t in listOf(0.0, 0.31, 1.7)) {
            assertEquals(num(src, scalar(t, 100.0)), num(src, scalar(t, 100.0)), 0.0)
            assertTrue(Math.abs(num(src, scalar(t, 100.0)) - 100.0) <= 50.0 + 1e-9)
        }
        // It moves, is continuous in time and differs between seeds.
        val a = (0..40).map { num(src, scalar(it * 0.05, 0.0)) }
        assertTrue(a.max() - a.min() > 10.0)
        for (i in 1 until a.size) assertTrue("continuous", Math.abs(a[i] - a[i - 1]) < 30.0)
        assertTrue(num(src, scalar(1.0, 0.0, seed = 1)) != num(src, scalar(1.0, 0.0, seed = 2)))
        // Vectors wiggle each axis independently.
        val vin = ExprInput(
            time = 1.0, value = doubleArrayOf(10.0, 20.0), valueIsArray = true, inPoint = 0.0, outPoint = 5.0, layerIndex = 1,
            comp = comp, seed = 3, keys = emptyList(), valueAtTime = { doubleArrayOf(10.0, 20.0) }, ownLayer = layer,
        )
        val w = ExpressionEngine.evaluate("wiggle(3, 10, 2, 0.5)", vin).values
        assertEquals(2, w.size)
        assertTrue(w[0] - 10.0 != w[1] - 20.0)
    }

    private val keys = listOf(1.0 to 0.0, 2.0 to 10.0, 3.0 to 40.0)

    @Test
    fun loopOutCycleAndPingpong() {
        // Before and inside the keyframed range nothing changes.
        assertEquals(5.0, num("loopOut(\"cycle\")", scalar(1.5, keys = keys)), 1e-9)
        assertEquals(40.0, num("loopOut(\"cycle\")", scalar(3.0, keys = keys)), 1e-9)
        // cycle: span 1..3 (2 s). t = 3.5 → 1.5 → 5; t = 4 → 2 → 10; t = 5.5 → 1.5 → 5.
        assertEquals(5.0, num("loopOut(\"cycle\")", scalar(3.5, keys = keys)), 1e-9)
        assertEquals(10.0, num("loopOut(\"cycle\")", scalar(4.0, keys = keys)), 1e-9)
        assertEquals(5.0, num("loopOut(\"cycle\")", scalar(5.5, keys = keys)), 1e-9)
        // pingpong: t = 3.5 → mirrored 2.5 → 25; t = 5.5 → forward again 1.5 → 5.
        assertEquals(25.0, num("loopOut(\"pingpong\")", scalar(3.5, keys = keys)), 1e-9)
        assertEquals(5.0, num("loopOut(\"pingpong\")", scalar(5.5, keys = keys)), 1e-9)
        // default type is cycle
        assertEquals(5.0, num("loopOut()", scalar(3.5, keys = keys)), 1e-9)
    }

    @Test
    fun loopOutOffsetAndContinue() {
        // offset adds the net change of every full cycle (40 per cycle).
        assertEquals(45.0, num("loopOut(\"offset\")", scalar(3.5, keys = keys)), 1e-9)
        assertEquals(40.0 + 10.0, num("loopOut(\"offset\")", scalar(4.0, keys = keys)), 1e-9)
        assertEquals(40.0 + 45.0, num("loopOut(\"offset\")", scalar(5.5, keys = keys)), 1e-9)
        // continue extends the speed of the last segment (+30 per second).
        assertEquals(40.0 + 30.0 * 0.5, num("loopOut(\"continue\")", scalar(3.5, keys = keys)), 1e-6)
    }

    @Test
    fun loopInAndKeyLimit() {
        // loopIn mirrors loopOut before the first keyframe: t = 0.5 → 2.5 (cycle) → 25.
        assertEquals(25.0, num("loopIn(\"cycle\")", scalar(0.5, keys = keys)), 1e-9)
        assertEquals(0.0, num("loopIn(\"cycle\")", scalar(1.0, keys = keys)), 1e-9)
        assertEquals(5.0, num("loopIn(\"pingpong\")", scalar(0.5, keys = keys)), 1e-9)
        // numKeyframes = 1 loops only the last segment (2 → 3): t = 4 → 2 → ... t=4: e=2, k=2, r=0 → 2.0 → 10
        assertEquals(10.0, num("loopOut(\"cycle\", 1)", scalar(4.0, keys = keys)), 1e-9)
        assertEquals(25.0, num("loopOut(\"cycle\", 1)", scalar(3.5, keys = keys)), 1e-9)
        // Without animation the value is returned.
        assertEquals(7.0, num("loopOut(\"cycle\")", scalar(4.0, value = 7.0)), 0.0)
    }

    @Test
    fun valueAtTimeAndKeyAccess() {
        assertEquals(25.0, num("valueAtTime(2.5)", scalar(0.0, keys = keys)), 1e-9)
        assertEquals(3.0, num("thisProperty.numKeys", scalar(0.0, keys = keys)), 0.0)
        assertEquals(10.0, num("key(2).value", scalar(0.0, keys = keys)), 0.0)
        assertEquals(3.0, num("thisProperty.key(3).time", scalar(0.0, keys = keys)), 0.0)
        assertEquals(2.0, num("thisProperty.nearestKey(2.2).time", scalar(0.0, keys = keys)), 0.0)
    }

    @Test
    fun posterizeTimeQuantisesTime() {
        assertEquals(1.0, num("posterizeTime(2); time", scalar(1.3)), 1e-9)
        assertEquals(1.5, num("posterizeTime(2); time", scalar(1.6)), 1e-9)
    }

    @Test
    fun conditionalsVariablesAndFunctions() {
        val src = """
            function pulse(t, period) { return Math.floor(t / period) % 2 === 0 ? 100 : 0; }
            var x = 0;
            for (var i = 0; i < 10; i++) { x += i; }
            if (x === 45) { pulse(time, 1) + 1 } else { -1 }
        """.trimIndent()
        assertEquals(101.0, num(src, scalar(0.5)), 0.0)
        assertEquals(1.0, num(src, scalar(1.5)), 0.0)
        // Scopes: variables of one evaluation do not leak into the next.
        assertEquals(3.0, num("leak = 3; leak", scalar(0.0)), 0.0)
        try { num("leak", scalar(0.0)); fail() } catch (e: ExpressionError) { assertTrue(e.reason.contains("leak")) }
    }

    @Test
    fun errorsReportTheLine() {
        val e = assertThrowsExpr("a = 1;\nb = ;\nc = 3;")
        assertEquals(2, e.line)
        val r = assertThrowsExpr("x = 1;\n\ny = nope + 1;\ny")
        assertEquals(3, r.line)
        assertTrue(r.reason.contains("nope"))
        assertEquals(0, assertThrowsExpr("\"text\"").line)
        assertNull(ExpressionEngine.check("time * 2"))
        assertNotNull(ExpressionEngine.check("time *"))
    }

    private fun assertThrowsExpr(src: String): ExpressionError {
        try {
            ExpressionEngine.evaluate(src, scalar(0.0))
        } catch (e: ExpressionError) {
            return e
        }
        fail("expected an error")
        throw IllegalStateException()
    }

    @Test
    fun endlessLoopsAreStopped() {
        val e = assertThrowsExpr("while (true) { }")
        assertTrue(e.reason.contains("too long") || e.reason.contains("ran too long"))
        assertThrowsExpr("for (;;) { x = [1,2,3].concat([4]); }")
        assertThrowsExpr("function f(n) { return f(n + 1); } f(0)")
        // The engine is still usable afterwards.
        assertEquals(2.0, num("1 + 1", scalar(0.0)), 0.0)
    }

    @Test
    fun sandboxHasNoJavaOrNondeterminism() {
        assertThrowsExpr("java.lang.System.exit(0)")
        assertThrowsExpr("Packages.java.io.File")
        assertThrowsExpr("new Date().getTime()")
        assertThrowsExpr("importClass(java.io.File)")
        assertThrowsExpr("a = {}; a.getClass()")
    }

    @Test
    fun bounceTextExpressionIsAcceptedUnchanged() {
        val src = """
            delay = 0.030;
            myDelay = delay * textIndex;
            t = (time - inPoint) - myDelay;

            if (t >= 0) {
                freq = 2;
                amplitude = 50;
                decay = 8.0;

                s = amplitude * Math.cos(freq * t * 2 * Math.PI)
                    / Math.exp(decay * t);

                [s, s]
            } else {
                value
            }
        """.trimIndent()
        // Before the character's turn the selector amount keeps its base value (100).
        val before = ExpressionEngine.evaluate(src, scalar(0.01, value = 100.0, textIndex = 5, textTotal = 10, selector = 100.0))
        assertEquals(100.0, before.values[0], 0.0)
        // After: damped oscillation, per character delayed by 0.03 s × textIndex.
        for ((time, idx) in listOf(0.5 to 3, 1.0 to 10, 0.2 to 1)) {
            val t = time - 0.0 - 0.03 * idx
            val expected = 50.0 * cos(2 * t * 2 * PI) / exp(8.0 * t)
            val r = ExpressionEngine.evaluate(src, scalar(time, value = 100.0, textIndex = idx, textTotal = 10, selector = 100.0))
            assertTrue(r.isArray)
            assertEquals(expected, r.values[0], 1e-9)
            assertEquals(expected, r.values[1], 1e-9)
        }
        // The first character starts earlier than the last.
        val first = ExpressionEngine.evaluate(src, scalar(0.05, value = 100.0, textIndex = 1, textTotal = 10)).values[0]
        val last = ExpressionEngine.evaluate(src, scalar(0.05, value = 100.0, textIndex = 10, textTotal = 10)).values[0]
        assertTrue(first < 100.0 && last == 100.0)
    }

    @Test
    fun supportedLanguageFeatures() {
        fun works(src: String) = ExpressionEngine.check(src, scalar(0.0)) == null
        assertTrue(works("let a = 2; const b = 3; a * b"))
        assertTrue(works("f = (x) => x * 2; f(4)"))
        assertTrue(works("a = 3; s = `v${"$"}{a}`; a"))
        assertTrue(works("Math.sign(-2) + Math.trunc(2.5) + Math.hypot(3, 4)"))
        assertTrue(works("[1, 2, 3].map(function (x) { return x * 2; })[2]"))
        // Known gaps of the embedded engine (Rhino): documented in docs/expressoes.md.
        assertFalse(works("function f(a, b = 2) { return a + b; } f(1)"))
        assertFalse(works("a = [1, 2]; b = [...a, 3]; b[2]"))
    }
}
