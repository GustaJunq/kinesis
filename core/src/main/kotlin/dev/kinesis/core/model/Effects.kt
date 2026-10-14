package dev.kinesis.core.model

import dev.kinesis.core.anim.AnimColor
import dev.kinesis.core.anim.AnimFloat
import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Visual effects, applied in order to the rendered layer (in composition space).
 * Serialized with a `"type"` discriminator, e.g. `{"type": "blur", "radius": 12}`.
 */
@Serializable
sealed class Effect {
    abstract val enabled: Boolean
    abstract fun withEnabled(enabled: Boolean): Effect

    /** Gaussian blur; [radius] in pixels. */
    @Serializable
    @SerialName("blur")
    data class Blur(
        override val enabled: Boolean = true,
        val radius: AnimFloat = anim(12f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    /** Brightness/contrast/saturation in -1..1, hue rotation in degrees. */
    @Serializable
    @SerialName("colorAdjust")
    data class ColorAdjust(
        override val enabled: Boolean = true,
        val brightness: AnimFloat = anim(0f),
        val contrast: AnimFloat = anim(0f),
        val saturation: AnimFloat = anim(0f),
        val hue: AnimFloat = anim(0f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    /** Replaces the colour (keeping alpha) by [color], mixed by [amount] (0..1). */
    @Serializable
    @SerialName("tint")
    data class Tint(
        override val enabled: Boolean = true,
        val color: AnimColor = anim(Color(0.49f, 0.36f, 1f)),
        val amount: AnimFloat = anim(1f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    /** [angle] in degrees (direction the shadow is cast, 90 = down), [distance] and [softness] in pixels. */
    @Serializable
    @SerialName("dropShadow")
    data class DropShadow(
        override val enabled: Boolean = true,
        val color: AnimColor = anim(Color.BLACK),
        val opacity: AnimFloat = anim(0.6f),
        val angle: AnimFloat = anim(90f),
        val distance: AnimFloat = anim(16f),
        val softness: AnimFloat = anim(16f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    @Serializable
    @SerialName("glow")
    data class Glow(
        override val enabled: Boolean = true,
        val color: AnimColor = anim(Color.WHITE),
        val radius: AnimFloat = anim(24f),
        val intensity: AnimFloat = anim(1f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    @Serializable
    @SerialName("invert")
    data class Invert(
        override val enabled: Boolean = true,
        val amount: AnimFloat = anim(1f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    /** Mosaic with square cells of [size] pixels. */
    @Serializable
    @SerialName("pixelate")
    data class Pixelate(
        override val enabled: Boolean = true,
        val size: AnimFloat = anim(16f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    /** RGB split of [amount] pixels along [angle] degrees. */
    @Serializable
    @SerialName("chromaticAberration")
    data class ChromaticAberration(
        override val enabled: Boolean = true,
        val amount: AnimFloat = anim(6f),
        val angle: AnimFloat = anim(0f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }

    /** Darkens the edges of the layer's frame. [amount] and [softness] in 0..1. */
    @Serializable
    @SerialName("vignette")
    data class Vignette(
        override val enabled: Boolean = true,
        val amount: AnimFloat = anim(0.5f),
        val softness: AnimFloat = anim(0.5f),
    ) : Effect() {
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
    }
}
