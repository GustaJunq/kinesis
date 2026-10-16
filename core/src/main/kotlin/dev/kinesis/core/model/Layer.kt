@file:OptIn(ExperimentalSerializationApi::class)

package dev.kinesis.core.model

import dev.kinesis.core.anim.AnimFloat
import dev.kinesis.core.anim.AnimVec2
import dev.kinesis.core.anim.AnimVec3
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LayerType(val is3D: Boolean = false, val isVisual: Boolean = true) {
    @SerialName("text") TEXT,
    @SerialName("shape") SHAPE,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO(isVisual = false),
    @SerialName("group") GROUP,
    @SerialName("object3d") OBJECT3D(is3D = true),
    @SerialName("model3d") MODEL3D(is3D = true),
    @SerialName("camera") CAMERA(is3D = true, isVisual = false),
    @SerialName("light") LIGHT(is3D = true, isVisual = false),
    /** Invisible layer that only carries a transform for other layers to inherit (2D, or 3D with `null.threeD`). */
    @SerialName("null") NULL(isVisual = false),
    ;

    /** Layers whose geometry lives in 3D space and that are drawn through the camera. */
    val isRendered3D: Boolean get() = this == OBJECT3D || this == MODEL3D

    /** Layers drawn with a 2D transform. */
    val is2DVisual: Boolean get() = isVisual && !is3D
}

@Serializable
enum class BlendMode {
    @SerialName("normal") NORMAL,
    @SerialName("add") ADD,
    @SerialName("multiply") MULTIPLY,
    @SerialName("screen") SCREEN,
}

/**
 * A layer of the composition. Every layer has a [type] and the content object with the same name
 * (`text`, `shape`, `image`, `video`, `audio`, `object3d`, `model3d`, `camera`, `light`, `null`), except
 * groups, whose content is [layers].
 *
 * Timing: the layer is visible from [start] to [start] + [duration] (seconds, in the time of its container).
 * Keyframe times inside the layer are relative to [start].
 */
@Serializable
data class Layer(
    @EncodeDefault val id: String,
    @EncodeDefault val type: LayerType,
    @EncodeDefault val name: String = "",
    @EncodeDefault val start: Double = 0.0,
    @EncodeDefault val duration: Double = 5.0,
    val visible: Boolean = true,
    val locked: Boolean = false,
    /** Id of a layer in the same container whose transform this layer inherits. */
    val parent: String? = null,
    /** Timeline colour label (`#RRGGBB`). */
    val label: String? = null,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val motionBlur: Boolean = false,
    /** Transform of 2D layers (text, shape, image, video, group). */
    val transform: Transform2D = Transform2D(),
    /** Transform of 3D layers (object3d, model3d). */
    val transform3d: Transform3D = Transform3D(),
    val effects: List<Effect> = emptyList(),
    val masks: List<Mask> = emptyList(),
    val matte: Matte? = null,
    val transitionIn: Transition? = null,
    val transitionOut: Transition? = null,
    val text: TextContent? = null,
    val shape: ShapeContent? = null,
    val image: ImageContent? = null,
    val video: MediaContent? = null,
    val audio: MediaContent? = null,
    val object3d: Object3DContent? = null,
    val model3d: Model3DContent? = null,
    val camera: CameraContent? = null,
    val light: LightContent? = null,
    @SerialName("null") val nullObject: NullContent? = null,
    /** Children of a group, in painter's order. Their times are relative to the group's start. */
    val layers: List<Layer> = emptyList(),
) {
    val end: Double get() = start + duration

    /** True for layers that live in 3D space: 3D objects, cameras, lights and 3D null objects. */
    val is3DLayer: Boolean get() = type.is3D || (type == LayerType.NULL && nullObject?.threeD == true)

    /** Layers whose [transform] (2D) is inherited by children: drawn 2D layers, groups and 2D null objects. */
    val hasTransform2D: Boolean get() = type.is2DVisual || (type == LayerType.NULL && !is3DLayer)

    /** Layers whose [transform3d] is inherited by children: 3D objects, models and 3D null objects. */
    val hasTransform3D: Boolean get() = type.isRendered3D || (type == LayerType.NULL && is3DLayer)

    fun isActiveAt(time: Double): Boolean = visible && time >= start && time < end

    /** Local (layer) time for a container time. */
    fun localTime(containerTime: Double): Double = containerTime - start

    /** The media content of video/audio layers. */
    val media: MediaContent? get() = video ?: audio

    /** The asset referenced by this layer's content, if any. */
    val assetId: String?
        get() = when (type) {
            LayerType.IMAGE -> image?.asset
            LayerType.VIDEO -> video?.asset
            LayerType.AUDIO -> audio?.asset
            LayerType.MODEL3D -> model3d?.asset
            LayerType.TEXT -> text?.font?.asset
            else -> null
        }

    /** All asset ids referenced by this layer and its own content (not children). */
    fun referencedAssets(): List<String> = buildList {
        assetId?.let { add(it) }
        object3d?.material?.texture?.let { add(it) }
        object3d?.text?.font?.asset?.let { add(it) }
        model3d?.material?.texture?.let { add(it) }
    }
}

/** 2D transform. Positions are composition pixels with the origin at the composition centre (+y down). */
@Serializable
data class Transform2D(
    /** Pivot point in layer-local pixels (the content is centred on the local origin). */
    val anchor: AnimVec2 = anim(Vec2.ZERO),
    val position: AnimVec2 = anim(Vec2.ZERO),
    /** Scale factors (1 = 100%). */
    val scale: AnimVec2 = anim(Vec2.ONE),
    /** Degrees, clockwise. */
    val rotation: AnimFloat = anim(0f),
    /** 0..1 */
    val opacity: AnimFloat = anim(1f),
)

/** 3D transform. Pixels, +x right, +y down, +z away from the viewer; rotations in degrees applied X, then Y, then Z. */
@Serializable
data class Transform3D(
    val anchor: AnimVec3 = anim(Vec3.ZERO),
    val position: AnimVec3 = anim(Vec3.ZERO),
    val rotation: AnimVec3 = anim(Vec3.ZERO),
    val scale: AnimVec3 = anim(Vec3.ONE),
    val opacity: AnimFloat = anim(1f),
)

@Serializable
enum class MaskShape {
    @SerialName("rectangle") RECTANGLE,
    @SerialName("ellipse") ELLIPSE,
}

@Serializable
enum class MaskMode {
    @SerialName("add") ADD,
    @SerialName("subtract") SUBTRACT,
    @SerialName("intersect") INTERSECT,
}

/** Shape mask in layer-local pixels. Up to [MAX_MASKS] masks per layer are rendered. */
@Serializable
data class Mask(
    val shape: MaskShape = MaskShape.RECTANGLE,
    val mode: MaskMode = MaskMode.ADD,
    val position: AnimVec2 = anim(Vec2.ZERO),
    val size: AnimVec2 = anim(Vec2(400f, 400f)),
    /** Corner radius in pixels (rectangles). */
    val roundness: AnimFloat = anim(0f),
    /** Edge softness in pixels. */
    val feather: AnimFloat = anim(0f),
    val opacity: AnimFloat = anim(1f),
    val inverted: Boolean = false,
    val enabled: Boolean = true,
) {
    companion object {
        const val MAX_MASKS = 4
    }
}

@Serializable
enum class MatteMode {
    @SerialName("alpha") ALPHA,
    @SerialName("alphaInverted") ALPHA_INVERTED,
    @SerialName("luma") LUMA,
    @SerialName("lumaInverted") LUMA_INVERTED,
}

/** Track matte: this layer is only visible where the [layer] (a sibling) is opaque / bright. */
@Serializable
data class Matte(
    val layer: String,
    val mode: MatteMode = MatteMode.ALPHA,
)

@Serializable
enum class TransitionType {
    @SerialName("fade") FADE,
    @SerialName("slideLeft") SLIDE_LEFT,
    @SerialName("slideRight") SLIDE_RIGHT,
    @SerialName("slideUp") SLIDE_UP,
    @SerialName("slideDown") SLIDE_DOWN,
    @SerialName("zoomIn") ZOOM_IN,
    @SerialName("zoomOut") ZOOM_OUT,
    @SerialName("spin") SPIN,
    @SerialName("blur") BLUR,
    @SerialName("wipeLeft") WIPE_LEFT,
    @SerialName("wipeRight") WIPE_RIGHT,
}

/**
 * In/out transition. `transitionIn` plays during the first [duration] seconds of the layer,
 * `transitionOut` during the last [duration] seconds.
 */
@Serializable
data class Transition(
    @EncodeDefault val type: TransitionType = TransitionType.FADE,
    @EncodeDefault val duration: Double = 0.5,
    val easing: Easing = Easing.Preset(EasingPreset.EASE_OUT_CUBIC),
    /** Slide distance in pixels; defaults to a quarter of the composition size. */
    val distance: Float? = null,
)
