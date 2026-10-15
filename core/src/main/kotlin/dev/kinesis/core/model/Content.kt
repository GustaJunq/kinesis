package dev.kinesis.core.model

import dev.kinesis.core.anim.AnimColor
import dev.kinesis.core.anim.AnimFloat
import dev.kinesis.core.anim.AnimVec2
import dev.kinesis.core.anim.AnimVec3
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- Text

@Serializable
enum class TextAlign {
    @SerialName("left") LEFT,
    @SerialName("center") CENTER,
    @SerialName("right") RIGHT,
}

/** Font: a system family (`sans-serif`, `serif`, `monospace`, `sans-serif-condensed`, `cursive`, ...) or an embedded font asset. */
@Serializable
data class FontSpec(
    val family: String = "sans-serif",
    /** 100..900 */
    val weight: Int = 700,
    val italic: Boolean = false,
    /** Id of a `font` asset; overrides [family] when present. */
    val asset: String? = null,
)

@Serializable
data class TextContent(
    val content: String = "Text",
    val font: FontSpec = FontSpec(),
    /** Pixels. */
    val fontSize: AnimFloat = anim(96f),
    val color: AnimColor = anim(Color.WHITE),
    val align: TextAlign = TextAlign.CENTER,
    /** Multiple of the font size. */
    val lineHeight: Float = 1.2f,
    /** Extra spacing between characters, in em. */
    val letterSpacing: AnimFloat = anim(0f),
    /** Wrap width in pixels; 0 disables wrapping. */
    val maxWidth: Float = 0f,
    val stroke: StrokeSpec? = null,
    val animators: List<TextAnimator> = emptyList(),
    /** After Effects style animators: per-character properties weighted by an expression selector. */
    val propertyAnimators: List<TextPropertyAnimator> = emptyList(),
)

@Serializable
enum class TextAnimatorType {
    @SerialName("typewriter") TYPEWRITER,
    @SerialName("fade") FADE,
    @SerialName("slideUp") SLIDE_UP,
    @SerialName("slideDown") SLIDE_DOWN,
    @SerialName("slideLeft") SLIDE_LEFT,
    @SerialName("slideRight") SLIDE_RIGHT,
    @SerialName("scale") SCALE,
    @SerialName("rotate") ROTATE,
    /** Continuous vertical wave (ignores [TextAnimator.mode]). */
    @SerialName("wave") WAVE,
}

@Serializable
enum class TextAnimatorMode {
    @SerialName("in") IN,
    @SerialName("out") OUT,
}

@Serializable
enum class TextUnit {
    @SerialName("character") CHARACTER,
    @SerialName("word") WORD,
    @SerialName("line") LINE,
}

@Serializable
enum class TextOrder {
    @SerialName("forward") FORWARD,
    @SerialName("backward") BACKWARD,
    @SerialName("center") CENTER,
    @SerialName("random") RANDOM,
}

/**
 * Per-character / word / line animation. Unit `i` (in [order]) animates from `start + i·stagger`
 * for [unitDuration] seconds.
 */
@Serializable
data class TextAnimator(
    val type: TextAnimatorType = TextAnimatorType.FADE,
    val mode: TextAnimatorMode = TextAnimatorMode.IN,
    val unit: TextUnit = TextUnit.CHARACTER,
    /** Layer-local seconds. */
    val start: Double = 0.0,
    val stagger: Double = 0.05,
    val unitDuration: Double = 0.4,
    val easing: Easing = Easing.Preset(EasingPreset.EASE_OUT_CUBIC),
    /** Pixels for slides and wave amplitude, degrees for rotate, starting scale in percent for scale (0 = from nothing). */
    val amount: Float = 60f,
    val order: TextOrder = TextOrder.FORWARD,
)

/**
 * A text animator in the After Effects sense: it holds properties (any subset of position, scale, rotation, opacity)
 * and an Expression Selector whose [amount] (percent) says how strongly each character receives them.
 *
 * Per character, with `a = amount / 100` (each axis for position and scale when the expression returns an array):
 * - position: `offset = position · a` (pixels)
 * - scale: `factor = 1 + (scale / 100 − 1) · a` (the property is in percent; 100 means unchanged)
 * - rotation: `rotation · a` (degrees)
 * - opacity: `1 + (opacity / 100 − 1) · a`
 *
 * The [amount] expression runs once per character (or word / line, see [unit]) and sees `textIndex` (1-based),
 * `textTotal`, `selectorValue` and the usual context (`time`, `inPoint`, `outPoint`, `index`, `thisComp`, ...).
 * Its `value` is the base amount (100 by default). Several animators combine multiplicatively / additively.
 */
@Serializable
data class TextPropertyAnimator(
    val name: String = "Animator 1",
    val enabled: Boolean = true,
    /** What `textIndex` counts: characters (spaces included, as in After Effects), words or lines. */
    val unit: TextUnit = TextUnit.CHARACTER,
    /** Pixels, added to the character position. */
    val position: AnimVec2? = null,
    /** Percent; 100 = unchanged. */
    val scale: AnimVec2? = null,
    /** Degrees, clockwise. */
    val rotation: AnimFloat? = null,
    /** Percent; 100 = unchanged. */
    val opacity: AnimFloat? = null,
    /** Selector amount in percent. Give it an `expression` to drive the animation per character. */
    val amount: AnimFloat = anim(100f),
)

// ---------------------------------------------------------------- Shapes

@Serializable
enum class ShapeKind {
    @SerialName("rectangle") RECTANGLE,
    @SerialName("ellipse") ELLIPSE,
    @SerialName("polygon") POLYGON,
    @SerialName("star") STAR,
    @SerialName("path") PATH,
}

@Serializable
enum class GradientType {
    @SerialName("linear") LINEAR,
    @SerialName("radial") RADIAL,
}

@Serializable
data class GradientStop(val offset: Float, val color: Color)

/** Gradient fill. [start]/[end] are layer-local pixels (for radial: centre and a point on the edge). */
@Serializable
data class Gradient(
    val type: GradientType = GradientType.LINEAR,
    val start: Vec2 = Vec2(-200f, 0f),
    val end: Vec2 = Vec2(200f, 0f),
    val stops: List<GradientStop> = listOf(GradientStop(0f, Color.WHITE), GradientStop(1f, Color.BLACK)),
)

@Serializable
data class FillSpec(
    val enabled: Boolean = true,
    val color: AnimColor = anim(Color.WHITE),
    val gradient: Gradient? = null,
)

@Serializable
enum class StrokeCap {
    @SerialName("butt") BUTT,
    @SerialName("round") ROUND,
    @SerialName("square") SQUARE,
}

@Serializable
enum class StrokeJoin {
    @SerialName("miter") MITER,
    @SerialName("round") ROUND,
    @SerialName("bevel") BEVEL,
}

@Serializable
data class StrokeSpec(
    val enabled: Boolean = true,
    val color: AnimColor = anim(Color.WHITE),
    val width: AnimFloat = anim(8f),
    val cap: StrokeCap = StrokeCap.ROUND,
    val join: StrokeJoin = StrokeJoin.ROUND,
)

/** Trim paths: only the part of the outline between [start] and [end] (0..1, shifted by [offset]) is drawn. */
@Serializable
data class TrimSpec(
    val start: AnimFloat = anim(0f),
    val end: AnimFloat = anim(1f),
    val offset: AnimFloat = anim(0f),
)

/** Vertex of a Bézier path; tangents are relative to [point]. */
@Serializable
data class PathPoint(
    val point: Vec2,
    @SerialName("in") val inTangent: Vec2 = Vec2.ZERO,
    @SerialName("out") val outTangent: Vec2 = Vec2.ZERO,
)

@Serializable
data class PathSpec(
    val closed: Boolean = true,
    val points: List<PathPoint> = emptyList(),
)

@Serializable
data class ShapeContent(
    val kind: ShapeKind = ShapeKind.RECTANGLE,
    /** Bounding size in pixels (rectangle, ellipse, polygon, star). */
    val size: AnimVec2 = anim(Vec2(400f, 400f)),
    /** Corner radius in pixels. */
    val roundness: AnimFloat = anim(0f),
    /** Polygon sides / star points (rounded to an integer, minimum 3). */
    val sides: AnimFloat = anim(5f),
    /** Star inner radius as a fraction of the outer radius. */
    val innerRatio: AnimFloat = anim(0.5f),
    /** Geometry of `path` shapes. */
    val path: PathSpec? = null,
    val fill: FillSpec = FillSpec(),
    val stroke: StrokeSpec = StrokeSpec(enabled = false),
    val trim: TrimSpec = TrimSpec(),
)

// ---------------------------------------------------------------- Media

@Serializable
data class ImageContent(val asset: String)

/** Video or audio clip. */
@Serializable
data class MediaContent(
    val asset: String,
    /** Seconds into the media file where the clip starts (trim in-point). */
    val sourceStart: Double = 0.0,
    /** Playback speed multiplier. */
    val speed: Double = 1.0,
    /** Linear gain (1 = unchanged). */
    val volume: AnimFloat = anim(1f),
    val muted: Boolean = false,
    /** Audio fades in seconds. */
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
) {
    /** Media time (seconds) shown at layer-local time [local]. */
    fun sourceTime(local: Double): Double = sourceStart + local * speed
}

// ---------------------------------------------------------------- 3D

@Serializable
enum class Primitive {
    @SerialName("cube") CUBE,
    @SerialName("sphere") SPHERE,
    @SerialName("cylinder") CYLINDER,
    @SerialName("cone") CONE,
    @SerialName("torus") TORUS,
    @SerialName("plane") PLANE,
    /** Invisible transform node, useful as a parent. */
    @SerialName("empty") EMPTY,
}

/** Physically based material (metallic-roughness). */
@Serializable
data class Material(
    val color: AnimColor = anim(Color(0.82f, 0.82f, 0.85f)),
    /** 0 = dielectric, 1 = metal. */
    val metallic: AnimFloat = anim(0f),
    /** 0 = mirror, 1 = fully rough. */
    val roughness: AnimFloat = anim(0.5f),
    val emissive: AnimColor = anim(Color.BLACK),
    /** Id of an `image` asset used as base colour texture. */
    val texture: String? = null,
)

@Serializable
data class Object3DContent(
    val primitive: Primitive = Primitive.CUBE,
    /** Edge length / diameter in pixels: the primitive fits in a cube of this size. */
    val size: AnimFloat = anim(300f),
    val material: Material = Material(),
)

@Serializable
data class Model3DContent(
    /** Id of a `model` asset (GLB or glTF with embedded buffers). */
    val asset: String,
    /** The model is scaled so its largest dimension equals this many pixels. */
    val size: AnimFloat = anim(400f),
    /** Replaces every material of the model when present. */
    val material: Material? = null,
)

@Serializable
data class CameraContent(
    /** Camera position; when absent the camera sits in front of the composition so z = 0 maps 1:1 to pixels. */
    val position: AnimVec3? = null,
    /** Point the camera looks at. */
    val target: AnimVec3 = anim(Vec3.ZERO),
    /** Vertical field of view in degrees. */
    val fov: AnimFloat = anim(DEFAULT_FOV),
) {
    companion object {
        const val DEFAULT_FOV = 40f
    }
}

@Serializable
enum class LightKind {
    @SerialName("ambient") AMBIENT,
    @SerialName("directional") DIRECTIONAL,
    @SerialName("point") POINT,
}

@Serializable
data class LightContent(
    val kind: LightKind = LightKind.DIRECTIONAL,
    val color: AnimColor = anim(Color.WHITE),
    val intensity: AnimFloat = anim(1f),
    /** Point light position, or where a directional light shines from. */
    val position: AnimVec3 = anim(Vec3(-800f, -1000f, -1200f)),
    /** Directional lights point from [position] towards [target]. */
    val target: AnimVec3 = anim(Vec3.ZERO),
    /** Point light reach in pixels. */
    val radius: AnimFloat = anim(4000f),
) {
    companion object {
        const val MAX_LIGHTS = 4
    }
}
