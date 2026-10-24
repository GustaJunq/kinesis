package dev.kinesis.core.expr

import dev.kinesis.core.audio.AudioAnalyzer
import dev.kinesis.core.edit.AudioKeyframes
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Slider Control, `effect("name")("Slider")` and "convert audio to keyframes" (format v12). */
class SliderControlTest {
    @After
    fun resetSink() {
        ExpressionResolver.onError = null
    }

    private val control = """{"id":"ctl","type":"null","name":"Controls","duration":10,"effects":[
        {"type":"sliderControl","name":"Amp","slider":{"keyframes":[{"time":0,"value":0},{"time":2,"value":100}]}},
        {"type":"sliderControl","slider":40}]}"""

    private fun project(expression: String) = KnsprojJson.decode(
        """{"format":"knsproj","formatVersion":12,"composition":{"width":1080,"height":1920,"fps":30,"duration":10},
            "layers":[$control,{"id":"a","type":"shape","duration":10,"shape":{},"transform":{"opacity":{"value":1,"expression":${KnsprojJson.json.encodeToString(kotlinx.serialization.serializer<String>(), expression)}}}}]}""",
    )

    private fun opacity(p: KnsProject, t: Double) = (SceneEvaluator.evaluate(p, t).nodes.first { it.layerId == "a" } as Node2D).opacity

    @Test
    fun readsAKeyframedSliderByTheNameOfTheEffect() {
        val p = project("""thisComp.layer("Controls").effect("Amp")("Slider")""")
        assertEquals(0.0f, opacity(p, 0.0), 1e-6f)
        assertEquals(0.5f, opacity(p, 1.0), 1e-6f)
        assertEquals(1.0f, opacity(p, 5.0), 1e-6f)
    }

    @Test
    fun anUnnamedEffectAnswersToItsDefaultNameAndToItsIndex() {
        assertEquals(0.4f, opacity(project("""thisComp.layer("Controls").effect("Slider Control")("Slider")"""), 0.0), 1e-6f)
        assertEquals(0.4f, opacity(project("""thisComp.layer(2).effect(2)(1)"""), 0.0), 1e-6f)
        // The layer reference works by index too, and `param()` is the same call.
        assertEquals(0.5f, opacity(project("""thisComp.layer("Controls").effect("Amp").param("Slider")"""), 1.0), 1e-6f)
    }

    @Test
    fun aNamedEffectIsNotFoundByItsDefaultName() {
        val errors = ArrayList<ExpressionError>()
        ExpressionResolver.onError = { _, _, e -> errors += e }
        val p = project("""thisComp.layer("Controls").effect("Nope")("Slider")""")
        assertEquals(1.0f, opacity(p, 0.0), 1e-6f) // the expression failed: the value without it is used
        assertTrue(errors.single().message!!.contains("Nope"))
    }

    @Test
    fun theEffectNameSurvivesSerialization() {
        val p = project("value")
        val json = KnsprojJson.encode(p)
        assertTrue(json.contains("\"name\": \"Amp\""))
        val again = KnsprojJson.decode(json)
        val effects = LayerTree.find(again, "ctl")!!.effects
        assertEquals(listOf("Amp", null), effects.map { it.name })
        assertEquals(12, again.formatVersion)
    }

    @Test
    fun version11DocumentsStillOpen() {
        val v11 = KnsprojJson.encode(project("value")).replace("\"formatVersion\": 12", "\"formatVersion\": 11")
        assertEquals(12, KnsprojJson.decode(v11).formatVersion)
    }

    // ---- convert audio to keyframes

    private val sr = 48_000

    private fun audioProject() = KnsprojJson.decode(
        """{"format":"knsproj","formatVersion":12,"composition":{"width":1080,"height":1920,"fps":30,"duration":10},
            "assets":[{"id":"snd","type":"audio","path":"assets/snd.wav"}],
            "layers":[{"id":"song","type":"audio","start":1,"duration":4,"audio":{"asset":"snd"}}]}""",
    )

    /** One second of silence, then one second of a loud 1 kHz tone, then silence. */
    private fun toneBurst(): dev.kinesis.core.audio.AudioAnalysis {
        val pcm = FloatArray(3 * sr) { i -> if (i in sr until 2 * sr) sin(2 * PI * 1000 * i / sr).toFloat() else 0f }
        return AudioAnalyzer.analyze(pcm, 1, sr)
    }

    @Test
    fun conversionCreatesTheNullLayersWithKeyframedSliders() {
        val p = AudioKeyframes.apply(audioProject(), "song", toneBurst())
        val amp = p.layers.first { it.name == AudioKeyframes.AMPLITUDE_LAYER }
        assertEquals("null", amp.type.name.lowercase())
        assertEquals(1.0, amp.start, 0.0)
        assertEquals(4.0, amp.duration, 0.0)
        assertEquals(listOf("Left Channel", "Right Channel", "Both Channels"), amp.effects.map { it.name })
        val both = amp.effects.last().number("slider")
        assertTrue(both.keyframes.size > 10)
        assertEquals(0.0, both.keyframes.first().time, 0.0)
        assertEquals(4.0, both.keyframes.last().time, 1e-9)
        // Silent first second (layer-local 0..1), the loud tone at 1..2 s (speed 1), normalised to 100 at the peak.
        assertEquals(0f, both.sample(0.5) { a, b, u -> a + (b - a) * u }, 0.5f)
        assertEquals(100f, both.sample(1.5) { a, b, u -> a + (b - a) * u }, 3f)
        assertEquals(0f, both.sample(3.5) { a, b, u -> a + (b - a) * u }, 0.5f)
        // The mid band carries the 1 kHz tone, the low and high bands stay quiet.
        val bands = p.layers.first { it.name == AudioKeyframes.BANDS_LAYER }
        fun at(label: String) = bands.effects.first { it.name == label }.number("slider").sample(1.5) { a, b, u -> a + (b - a) * u }
        assertTrue(at("Mid") > 90f)
        assertTrue(at("Low") < 15f && at("High") < 15f)
    }

    @Test
    fun anExpressionReadsTheConvertedAudioLikeInAfterEffects() {
        var p = AudioKeyframes.apply(audioProject(), "song", toneBurst())
        val shape = KnsprojJson.decode(
            """{"format":"knsproj","formatVersion":12,"composition":{"width":1080,"height":1920,"fps":30,"duration":10},"layers":[
            {"id":"a","type":"shape","duration":10,"shape":{},"transform":{"opacity":{"value":1,"expression":"thisComp.layer(\"Audio Amplitude\").effect(\"Both Channels\")(\"Slider\")"}}}]}""",
        ).layers.single()
        p = p.copy(layers = p.layers + shape)
        assertEquals(0.0f, opacity(p, 1.5), 0.01f) // layer-local time 0.5: silence
        assertEquals(1.0f, opacity(p, 2.5), 0.05f) // layer-local time 1.5: the tone
    }

    @Test
    fun convertingAgainReplacesTheLayersAndSilenceIsCheap() {
        val once = AudioKeyframes.apply(audioProject(), "song", toneBurst())
        val twice = AudioKeyframes.apply(once, "song", toneBurst())
        assertEquals(once.layers.size, twice.layers.size)
        val silent = AudioKeyframes.apply(audioProject(), "song", AudioAnalyzer.analyze(FloatArray(sr), 1, sr))
        val keys = silent.layers.first { it.name == AudioKeyframes.AMPLITUDE_LAYER }.effects.last().number("slider").keyframes
        assertEquals(2, keys.size)
        assertNotNull(silent)
    }
}
