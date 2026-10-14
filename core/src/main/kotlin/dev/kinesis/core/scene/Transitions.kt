package dev.kinesis.core.scene

import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.clamp
import dev.kinesis.core.math.lerp
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.Transition
import dev.kinesis.core.model.TransitionType

/** Combined effect of a layer's in/out transitions at one instant. */
data class TransitionState(
    val opacity: Float = 1f,
    val offset: Vec2 = Vec2.ZERO,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val blur: Float = 0f,
    val wipe: WipeState? = null,
) {
    val isIdentity: Boolean
        get() = opacity == 1f && offset == Vec2.ZERO && scale == 1f && rotation == 0f && blur == 0f && wipe == null

    /** 2D transform (in composition pixels) applied around [pivot]. */
    fun matrix(pivot: Vec2): Affine2D {
        if (offset == Vec2.ZERO && scale == 1f && rotation == 0f) return Affine2D.IDENTITY
        return Affine2D.translate(pivot + offset) * Affine2D.rotate(rotation) * Affine2D.scale(scale, scale) * Affine2D.translate(-pivot.x, -pivot.y)
    }

    fun combine(o: TransitionState) = TransitionState(
        opacity = opacity * o.opacity,
        offset = offset + o.offset,
        scale = scale * o.scale,
        rotation = rotation + o.rotation,
        blur = blur + o.blur,
        wipe = wipe ?: o.wipe,
    )
}

object Transitions {
    const val MAX_BLUR = 40f

    /**
     * Evaluates `transitionIn` (first `duration` seconds) and `transitionOut` (last `duration` seconds)
     * at layer-local time [t].
     */
    fun evaluate(layer: Layer, t: Double, comp: Composition): TransitionState {
        var state = TransitionState()
        layer.transitionIn?.let { tr ->
            if (tr.duration > 0.0 && t < tr.duration) {
                val p = tr.easing.transform((t / tr.duration).toFloat().clamp(0f, 1f))
                state = state.combine(stateFor(tr, p, entering = true, comp))
            }
        }
        layer.transitionOut?.let { tr ->
            val startOut = layer.duration - tr.duration
            if (tr.duration > 0.0 && t > startOut) {
                val q = ((t - startOut) / tr.duration).toFloat().clamp(0f, 1f)
                val p = 1f - tr.easing.transform(q)
                state = state.combine(stateFor(tr, p, entering = false, comp))
            }
        }
        return state
    }

    /**
     * [p] is visibility: 0 = fully transitioned away, 1 = at rest. Movement directions describe how the layer
     * moves on screen: `slideLeft` enters from the right moving left and exits towards the left.
     */
    fun stateFor(tr: Transition, p: Float, entering: Boolean, comp: Composition): TransitionState {
        val away = 1f - p
        val dirSign = if (entering) 1f else -1f
        val dx = tr.distance ?: (comp.width * 0.25f)
        val dy = tr.distance ?: (comp.height * 0.25f)
        return when (tr.type) {
            TransitionType.FADE -> TransitionState(opacity = p)
            TransitionType.SLIDE_LEFT -> TransitionState(opacity = p, offset = Vec2(dirSign * dx * away, 0f))
            TransitionType.SLIDE_RIGHT -> TransitionState(opacity = p, offset = Vec2(-dirSign * dx * away, 0f))
            TransitionType.SLIDE_UP -> TransitionState(opacity = p, offset = Vec2(0f, dirSign * dy * away))
            TransitionType.SLIDE_DOWN -> TransitionState(opacity = p, offset = Vec2(0f, -dirSign * dy * away))
            // zoomIn = growing motion: enters from small, exits growing past full size.
            TransitionType.ZOOM_IN -> TransitionState(opacity = p, scale = if (entering) lerp(0.5f, 1f, p) else lerp(1.5f, 1f, p))
            TransitionType.ZOOM_OUT -> TransitionState(opacity = p, scale = if (entering) lerp(1.5f, 1f, p) else lerp(0.5f, 1f, p))
            TransitionType.SPIN -> TransitionState(opacity = p, scale = lerp(0.3f, 1f, p), rotation = dirSign * -180f * away)
            TransitionType.BLUR -> TransitionState(opacity = p, blur = MAX_BLUR * away)
            TransitionType.WIPE_LEFT -> TransitionState(wipe = WipeState(p, anchoredRight = entering))
            TransitionType.WIPE_RIGHT -> TransitionState(wipe = WipeState(p, anchoredRight = !entering))
        }
    }
}
