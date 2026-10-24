package dev.kinesis.core.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Expressions taken from the After Effects reference (https://ae-expressions.docsforadobe.dev/) and its examples page.
 * Cases with `pending = null` must work today. A case with `pending = "<phase>"` is a known gap listed in
 * docs/expressions-compat.md: it is only reported (not asserted) until that phase of docs/expressions-plan.md lands,
 * and then the `pending` marker is removed so it becomes a regression test.
 */
class AeGoldenTest {
    private val comp = ExprComp(1080.0, 1920.0, 10.0, 1.0 / 30.0, 3)
    private val own = ExprLayerInfo("L", 3, 0.0, 5.0, doubleArrayOf(540.0, 960.0), doubleArrayOf(100.0, 100.0), 0.0, 100.0)
    private val others = mapOf(
        1 to ExprLayerInfo("A", 1, 0.0, 5.0, doubleArrayOf(100.0, 200.0), doubleArrayOf(100.0, 100.0), 0.0, 100.0),
        2 to ExprLayerInfo("B", 2, 0.0, 5.0, doubleArrayOf(300.0, 400.0), doubleArrayOf(100.0, 100.0), 0.0, 100.0),
    )

    private class Case(
        val name: String,
        val source: String,
        val expected: DoubleArray,
        val time: Double = 0.0,
        val vector: Boolean = false,
        val value: DoubleArray = doubleArrayOf(0.0),
        val keys: List<Pair<Double, Double>> = emptyList(),
        val pending: String? = null,
    )

    private fun d(vararg v: Double) = v

    private val cases = listOf(
        // General / Global
        Case("comp size centred (Comp.width/height)", "[thisComp.width / 2, thisComp.height / 2];", d(540.0, 960.0), vector = true),
        Case("posterizeTime + random is in [0,1)", "posterizeTime(1); random() < 1 ? 1 : 0", d(1.0)),
        // Vector math
        Case("add with different sizes", "add([10, 20], [1, 2, 3])", d(11.0, 22.0, 3.0), vector = true),
        Case("length of vector", "length([3, 4])", d(5.0)),
        Case("length as distance", "length([0, 0], [3, 4])", d(5.0)),
        Case("normalize", "normalize([3, 4])", d(0.6, 0.8), vector = true),
        Case("cross product", "cross([1, 0, 0], [0, 1, 0])", d(0.0, 0.0, 1.0), vector = true),
        Case("clamp array", "clamp([-5, 50, 700], 0, 500)", d(0.0, 50.0, 500.0), vector = true),
        Case("lookAt", "lookAt([0, 0, 0], [0, 0, 100])", d(0.0, 0.0, 0.0), vector = true, pending = "fase 1 (P6)"),
        // Other math
        Case("degreesToRadians", "degreesToRadians(180)", d(PI)),
        Case("radiansToDegrees", "radiansToDegrees(Math.PI)", d(180.0)),
        // Interpolation
        Case("linear(time,0,6,20,80)", "linear(time, 0, 6, 20, 80)", d(50.0), time = 3.0),
        Case("linear(value,0,100,20,80)", "linear(value, 0, 100, 20, 80)", d(50.0), value = d(50.0)),
        Case("linear short form", "linear(0.5, 10, 20)", d(15.0)),
        Case("ease endpoints", "ease(time, 0, 2, 0, 100)", d(100.0), time = 5.0),
        Case("easeOut flash by key distance (explicit thisProperty)", "d = Math.abs(time - thisProperty.nearestKey(time).time); easeOut(d, 0, .1, 100, 0)", d(100.0), time = 1.0, keys = listOf(1.0 to 0.0, 2.0 to 1.0)),
        // Random
        Case("seedRandom timeless is stable", "seedRandom(123456, true); random() * 100 == (function(){ seedRandom(123456, true); return random() * 100; })() ? 1 : 0", d(1.0), time = 1.0),
        Case("gaussRandom range sanity", "x = gaussRandom(0, 100); isNaN(x) ? 0 : 1", d(1.0), pending = "fase 1 (P6)"),
        Case("noise in [-1,1]", "n = noise(time); (n >= -1 && n <= 1) ? 1 : 0", d(1.0), pending = "fase 1 (P6)"),
        // Property
        Case("wiggle window (before)", "timeToStart = 2; timeToStop = 4; if ((time > timeToStart) && (time < timeToStop)) { wiggle(3,25); } else { value; }", d(7.0), time = 1.0, value = d(7.0)),
        Case("wiggle one axis keeps the other", "freq = 3; amp = 50; w = wiggle(freq,amp); [value[0], Math.abs(w[1] - value[1]) <= amp ? 20 : -1]", d(10.0, 20.0), vector = true, value = d(10.0, 20.0)),
        Case("loopIn/loopOut continue sum", "loopIn(\"continue\") + loopOut(\"continue\") - value", d(1.5), time = 1.5, value = d(1.5), keys = listOf(1.0 to 1.0, 2.0 to 2.0)),
        Case("key().time via thisProperty", "thisProperty.key(thisProperty.numKeys).time", d(2.0), keys = listOf(1.0 to 1.0, 2.0 to 2.0)),
        Case("bare numKeys", "numKeys", d(2.0), keys = listOf(1.0 to 1.0, 2.0 to 2.0), pending = "fase 1 (P2)"),
        Case("bare nearestKey", "nearestKey(time).index", d(2.0), time = 1.9, keys = listOf(1.0 to 1.0, 2.0 to 2.0), pending = "fase 1 (P2)"),
        Case("smooth", "smooth(0.2, 5)", d(1.0), pending = "fase 1 (P6)"),
        Case("loopOutDuration", "loopOutDuration(\"cycle\", 1)", d(1.0), keys = listOf(0.0 to 0.0, 1.0 to 1.0), pending = "fase 1 (P6)"),
        // Time conversion
        Case("timeToFrames", "timeToFrames(1)", d(30.0), pending = "fase 1 (P6)"),
        Case("framesToTime", "framesToTime(15)", d(0.5), pending = "fase 1 (P6)"),
        // Color conversion
        Case("hexToRgb", "hexToRgb(\"#FF00FF\")", d(1.0, 0.0, 1.0, 1.0), vector = true, pending = "fase 1 (P6)"),
        // Examples page: array operators and Property objects
        Case("circle: array + array", "[(thisComp.width/2), (thisComp.height/2)] + [Math.sin(time)*50, -Math.cos(time)*50]", d(540.0, 910.0), vector = true, pending = "fase 1 (P1)"),
        Case("between two layers", "(thisComp.layer(1).position + thisComp.layer(2).position)/2", d(200.0, 300.0), vector = true, pending = "fase 1 (P1, P2)"),
        Case("layer position as property", "thisComp.layer(1).position.valueAtTime(time - .5)", d(100.0, 200.0), vector = true, pending = "fase 1 (P3)"),
        Case("relative layer lookup", "thisComp.layer(thisLayer, -1).index", d(2.0), pending = "fase 1 (P3)"),
        Case("rotation + noise", "rotation + 360*noise(time)", d(0.0), pending = "fase 1 (P2, P6)"),
    )

    @Test
    fun referenceExpressionsBehaveAsDocumented() {
        var pass = 0
        val pendingStillFailing = ArrayList<String>()
        val pendingNowWorking = ArrayList<String>()
        for (c in cases) {
            val outcome = runCatching { run(c) }
            if (c.pending == null) {
                val r = outcome.getOrElse { throw AssertionError("${c.name}: ${it.message}", it) }
                assertEquals("${c.name}: size", c.expected.size, r.size)
                for (i in c.expected.indices) assertEquals("${c.name}[$i]", c.expected[i], r[i], 1e-9)
                pass++
            } else {
                val r = outcome.getOrNull()
                val ok = r != null && r.size == c.expected.size && c.expected.indices.all { Math.abs(c.expected[it] - r[it]) < 1e-9 }
                if (ok) pendingNowWorking += c.name else pendingStillFailing += "${c.name} [${c.pending}]"
            }
        }
        println("AE golden: $pass passing, ${pendingStillFailing.size} pending, ${pendingNowWorking.size} pending cases already working")
        pendingStillFailing.forEach { println("  pending: $it") }
        // A pending case that already works should lose its marker so it guards against regressions.
        assertTrue("remove the pending marker of: $pendingNowWorking", pendingNowWorking.isEmpty())
    }

    @Test
    fun randomnessInTheReferenceIsDeterministic() {
        val a = run(Case("r", "random(100)", d(0.0), time = 1.0))
        val b = run(Case("r", "random(100)", d(0.0), time = 1.0))
        assertEquals(a[0], b[0], 0.0)
    }

    private fun run(c: Case): DoubleArray {
        val keys = c.keys
        val valueAt: (Double) -> DoubleArray = { t -> if (keys.isEmpty()) c.value else doubleArrayOf(lerp(keys, t)) }
        val input = ExprInput(
            time = c.time, value = c.value, valueIsArray = c.vector || c.value.size > 1, inPoint = 0.0, outPoint = 5.0, layerIndex = 3,
            comp = comp, seed = 7, keys = keys.map { ExprKey(it.first, doubleArrayOf(it.second)) },
            valueAtTime = valueAt, ownLayer = own, layerLookup = { ref -> if (ref is Int) others[ref] else others.values.firstOrNull { it.name == ref } },
        )
        return ExpressionEngine.evaluate(c.source, input).values
    }

    private fun lerp(keys: List<Pair<Double, Double>>, t: Double): Double {
        if (t <= keys.first().first) return keys.first().second
        if (t >= keys.last().first) return keys.last().second
        val i = keys.indexOfLast { it.first <= t }
        val (t0, v0) = keys[i]; val (t1, v1) = keys[i + 1]
        return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    }
}
