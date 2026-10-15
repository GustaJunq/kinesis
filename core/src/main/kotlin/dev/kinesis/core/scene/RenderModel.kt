package dev.kinesis.core.scene

import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.BlendMode
import dev.kinesis.core.model.FontSpec
import dev.kinesis.core.model.Gradient
import dev.kinesis.core.model.GradientStop
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.MaskMode
import dev.kinesis.core.model.MaskShape
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.model.PathSpec
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeCap
import dev.kinesis.core.model.StrokeJoin
import dev.kinesis.core.model.TextAlign
import dev.kinesis.core.model.TextAnimator

/**
 * Everything needed to draw one frame, with every animated value resolved. Produced by [SceneEvaluator]
 * and consumed by renderers. Coordinates are composition pixels with the origin at the centre, +y down.
 */
class FrameScene(
    val time: Double,
    val width: Int,
    val height: Int,
    val background: Color,
    val camera: CameraState,
    /** Sum of ambient lights in linear RGB (colour converted from sRGB, times intensity). */
    val ambient: Color,
    val lights: List<LightState>,
    val nodes: List<RenderNode>,
)

class CameraState(
    val position: Vec3,
    val target: Vec3,
    val fov: Float,
    val view: Mat4,
    val projection: Mat4,
    val width: Int,
    val height: Int,
) {
    val viewProjection: Mat4 = projection * view

    /** Projects a world point to composition pixels (centre origin), or null when behind the camera. */
    fun project(world: Vec3): Vec2? {
        val h = viewProjection.transformHomogeneous(world)
        if (h[3] <= 1e-4f) return null
        return Vec2(h[0] / h[3] * width / 2f, -h[1] / h[3] * height / 2f)
    }

    /** Distance from the camera to [world] along the view direction. */
    fun depthOf(world: Vec3): Float = -view.transformPoint(world).z
}

class LightState(
    val kind: LightKind,
    /** sRGB colour of the light. */
    val color: Color,
    val intensity: Float,
    val position: Vec3,
    /** Unit vector the light travels along (directional lights). */
    val direction: Vec3,
    val radius: Float,
)

/**
 * An effect with its parameters evaluated at the frame time and clamped to the registry ranges.
 * Points are in composition pixels (origin at the centre, +y down); lengths are composition pixels.
 */
class ResolvedEffect(
    val type: String,
    private val values: Map<String, Any>,
    /** Layer-local time in seconds (drives time-based procedural effects). */
    val time: Double,
    /** Layer origin (pivot) in composition pixels. */
    val origin: Vec2,
    /** Motion effects: transform applied to the rendered layer image, in composition pixels. */
    val transform: Affine2D? = null,
    /** Echo: the layer at earlier times, oldest last, with their opacity. */
    val echoes: List<EchoSample> = emptyList(),
) {
    fun f(name: String): Float = values[name] as Float
    fun i(name: String): Int = Math.round(values[name] as Float)
    fun color(name: String): Color = values[name] as Color
    fun point(name: String): Vec2 = values[name] as Vec2
    fun flag(name: String): Boolean = values[name] as Boolean
    fun choice(name: String): String = values[name] as String

    @Suppress("UNCHECKED_CAST")
    fun stops(name: String): List<GradientStop> = values[name] as List<GradientStop>

    /** Every value, keyed by parameter name (used by generic shader setup). */
    val all: Map<String, Any> get() = values
}

class EchoSample(val node: RenderNode, val opacity: Float)

data class ResolvedMask(
    val shape: MaskShape,
    val mode: MaskMode,
    val center: Vec2,
    val size: Vec2,
    val roundness: Float,
    val feather: Float,
    val opacity: Float,
    val inverted: Boolean,
)

/**
 * Linear wipe in layer-local content space: the visible part is a band of width [progress] (0..1 of the
 * content width) touching the right edge when [anchoredRight], else the left edge.
 */
data class WipeState(val progress: Float, val anchoredRight: Boolean)

sealed class RenderNode {
    abstract val layerId: String
    abstract val layerType: LayerType
    abstract val opacity: Float
    abstract val blendMode: BlendMode
    abstract val effects: List<ResolvedEffect>
    abstract val motionBlur: Boolean
    /** Extra blur from transitions, in pixels. */
    abstract val transitionBlur: Float
}

/** Content of 2D layers, in layer-local pixels (centred on the local origin). */
sealed class Content2D {
    class Text(
        val content: String,
        val font: FontSpec,
        val fontSize: Float,
        val color: Color,
        val align: TextAlign,
        val lineHeight: Float,
        val letterSpacing: Float,
        val maxWidth: Float,
        val strokeColor: Color?,
        val strokeWidth: Float,
        val animators: List<TextAnimator>,
        /** Layer-local time used to evaluate [animators]. */
        val localTime: Double,
    ) : Content2D()

    class Shape(
        val kind: ShapeKind,
        val size: Vec2,
        val roundness: Float,
        val sides: Int,
        val innerRatio: Float,
        val path: PathSpec?,
        val fillEnabled: Boolean,
        val fillColor: Color,
        val gradient: Gradient?,
        val strokeEnabled: Boolean,
        val strokeColor: Color,
        val strokeWidth: Float,
        val cap: StrokeCap,
        val join: StrokeJoin,
        val trimStart: Float,
        val trimEnd: Float,
        val trimOffset: Float,
    ) : Content2D() {
        val isTrimmed: Boolean get() = trimStart > 0.0001f || trimEnd < 0.9999f
    }

    class Image(val assetId: String) : Content2D()

    class Video(val assetId: String, val sourceTime: Double) : Content2D()
}

class Node2D(
    override val layerId: String,
    override val layerType: LayerType,
    /** Layer-local → composition pixels. */
    val matrix: Affine2D,
    override val opacity: Float,
    override val blendMode: BlendMode,
    override val effects: List<ResolvedEffect>,
    override val motionBlur: Boolean,
    override val transitionBlur: Float,
    val masks: List<ResolvedMask>,
    val matte: MatteRef?,
    val wipe: WipeState?,
    val content: Content2D,
) : RenderNode()

class ResolvedMaterial(
    val color: Color,
    val metallic: Float,
    val roughness: Float,
    val emissive: Color,
    val textureAssetId: String?,
)

class Node3D(
    override val layerId: String,
    override val layerType: LayerType,
    /** Primitive meshes and models are normalized to a unit cube; [model] scales them to the world. */
    val model: Mat4,
    val primitive: Primitive?,
    val modelAssetId: String?,
    /** Null for models without an override: use the file's materials. */
    val material: ResolvedMaterial?,
    override val opacity: Float,
    override val blendMode: BlendMode,
    override val effects: List<ResolvedEffect>,
    override val motionBlur: Boolean,
    override val transitionBlur: Float,
    /** 2D transform applied to the rendered image (group transforms and transitions). */
    val post: Affine2D,
) : RenderNode()

class GroupNode(
    override val layerId: String,
    /** Group-local → composition pixels (already applied to 2D children; used for masks). */
    val matrix: Affine2D,
    override val opacity: Float,
    override val blendMode: BlendMode,
    override val effects: List<ResolvedEffect>,
    override val motionBlur: Boolean,
    override val transitionBlur: Float,
    val masks: List<ResolvedMask>,
    val matte: MatteRef?,
    val children: List<RenderNode>,
) : RenderNode() {
    override val layerType: LayerType get() = LayerType.GROUP

    /** True when the group can be drawn straight into its target without an offscreen pass. */
    val isPassThrough: Boolean
        get() = opacity >= 0.999f && blendMode == BlendMode.NORMAL && effects.isEmpty() && masks.isEmpty() && matte == null && transitionBlur <= 0f
}

/** Track matte: [source] is drawn offscreen and used as alpha/luma mask. */
class MatteRef(val source: RenderNode, val mode: MatteMode)
