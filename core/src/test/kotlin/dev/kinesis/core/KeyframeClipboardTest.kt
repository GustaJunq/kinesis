package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.KeyframeClipboard
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.Transform2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyframeClipboardTest {
    private val source = Layer(
        "a", LayerType.SHAPE, duration = 10.0, shape = ShapeContent(),
        transform = Transform2D(
            position = Animatable(Vec2.ZERO, listOf(Keyframe(1.0, Vec2.ZERO, Easing.EASE_IN_OUT), Keyframe(3.0, Vec2(100f, 0f)))),
            rotation = Animatable(0f, listOf(Keyframe(2.0, 0f), Keyframe(4.0, 90f))),
        ),
    )

    @Test
    fun copiesRelativeToTheFirstKeyframe() {
        val clip = KeyframeClipboard.copy(source)!!
        assertEquals(setOf("transform.position", "transform.rotation"), clip.entries.keys)
        assertEquals(3.0, clip.span, 1e-9)
        assertEquals(listOf(0.0, 2.0), clip.entries.getValue("transform.position").keyframes.map { it.time })
        assertEquals(listOf(1.0, 3.0), clip.entries.getValue("transform.rotation").keyframes.map { it.time })
        assertNull(KeyframeClipboard.copy(source.copy(transform = Transform2D())))
        assertEquals(setOf("transform.rotation"), KeyframeClipboard.copy(source, setOf("transform.rotation"))!!.entries.keys)
    }

    @Test
    fun pastesAtThePlayheadReplacingOnlyTheCoveredRange() {
        val target = Layer(
            "b", LayerType.SHAPE, duration = 10.0, shape = ShapeContent(),
            transform = Transform2D(rotation = Animatable(5f, listOf(Keyframe(0.5, 5f), Keyframe(6.0, 7f), Keyframe(9.0, 8f)))),
        )
        val (pasted, count) = KeyframeClipboard.paste(target, KeyframeClipboard.copy(source)!!, at = 5.0)
        assertEquals(2, count)
        assertEquals(listOf(5.0, 7.0), pasted.transform.position.keyframes.map { it.time })
        assertEquals(Easing.EASE_IN_OUT, pasted.transform.position.keyframes[0].easing)
        // 0.5 and 9.0 are outside 5..8 and stay; 6.0 is replaced by the pasted rotation (6 and 8).
        assertEquals(listOf(0.5, 6.0, 8.0, 9.0), pasted.transform.rotation.keyframes.map { it.time })
        assertEquals(listOf(5f, 0f, 90f, 8f), pasted.transform.rotation.keyframes.map { it.value })
    }

    @Test
    fun skipsPropertiesTheTargetDoesNotHave() {
        val cube = Layer("c", LayerType.OBJECT3D, duration = 10.0, object3d = Object3DContent(size = anim(10f)))
        val (same, count) = KeyframeClipboard.paste(cube, KeyframeClipboard.copy(source)!!, at = 0.0)
        assertEquals(0, count)
        assertEquals(cube, same)
    }
}
