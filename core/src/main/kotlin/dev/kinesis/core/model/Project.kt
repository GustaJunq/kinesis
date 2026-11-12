@file:OptIn(ExperimentalSerializationApi::class)

package dev.kinesis.core.model

import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec3
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

const val FORMAT_ID = "knsproj"

/** Format version written by this build. Older versions are migrated on load; newer ones are rejected. */
const val FORMAT_VERSION = 12

/** Root of a `.knsproj` document (`project.json`). */
@Serializable
data class KnsProject(
    @EncodeDefault val format: String = FORMAT_ID,
    @EncodeDefault val formatVersion: Int = FORMAT_VERSION,
    val generator: String? = null,
    @EncodeDefault val meta: ProjectMeta = ProjectMeta(),
    @EncodeDefault val composition: Composition = Composition(),
    val assets: List<Asset> = emptyList(),
    /** Time references on the timeline, independent of the layers. Order is not significant. */
    val markers: List<Marker> = emptyList(),
    /** The viewport's own camera and view mode (format v9). Editor state only: never rendered into the video. */
    val editorView: EditorView? = null,
    /** Top-level layers in painter's order: index 0 is drawn first (bottom), the last layer is on top. */
    @EncodeDefault val layers: List<Layer> = emptyList(),
) {
    fun asset(id: String?): Asset? = if (id == null) null else assets.firstOrNull { it.id == id }
}

/** A named point in time on the timeline ruler (format v8). */
@Serializable
data class Marker(
    @EncodeDefault val id: String,
    /** Composition seconds. */
    @EncodeDefault val time: Double,
    val name: String = "",
    /** `#RRGGBB`; null uses the default colour. */
    val color: String? = null,
)

/** What the editor viewport shows. */
@Serializable
enum class EditorViewMode {
    /** The picture the active camera renders (what is exported); gestures move the selected layer. */
    @SerialName("camera") CAMERA,

    /** A free work camera looking at the scene, drawn with a gizmo of the scene's camera; gestures navigate. */
    @SerialName("editor") EDITOR,

    /** Seen through the active camera, but gestures move that camera (keyframes when auto-keyframe is on). */
    @SerialName("inside") INSIDE,
}

/**
 * The free "editor view" of the viewport (format v9): a camera that orbits [target] at [distance], looking from the
 * direction given by [yaw] and [pitch]. It is saved with the project so the view is the same when it is reopened, but
 * it is not part of what is rendered, and changing it is not an undo step.
 */
@Serializable
data class EditorView(
    val mode: EditorViewMode = EditorViewMode.CAMERA,
    /** Point the editor camera looks at, in composition pixels (origin at the centre, +y down). */
    val target: Vec3 = Vec3.ZERO,
    /** Degrees: 0 looks along +z; positive turns the camera towards +x. */
    val yaw: Float = DEFAULT_YAW,
    /** Degrees: positive looks down from above. */
    val pitch: Float = DEFAULT_PITCH,
    /** Pixels from [target]. */
    val distance: Float = 2600f,
    /** Vertical field of view in degrees. */
    val fov: Float = 40f,
) {
    companion object {
        const val DEFAULT_YAW = 28f
        const val DEFAULT_PITCH = 18f
    }
}

@Serializable
data class ProjectMeta(
    @EncodeDefault val title: String = "Untitled",
    val description: String? = null,
    val author: String? = null,
    /** ISO-8601 timestamps. */
    val createdAt: String? = null,
    val modifiedAt: String? = null,
)

@Serializable
data class Composition(
    @EncodeDefault val width: Int = 1080,
    @EncodeDefault val height: Int = 1920,
    @EncodeDefault val fps: Double = 30.0,
    /** Seconds. */
    @EncodeDefault val duration: Double = 10.0,
    @EncodeDefault val background: Color = Color(0.05f, 0.05f, 0.07f),
    val motionBlur: MotionBlurSettings = MotionBlurSettings(),
    /** Lighting environment of 3D layers: reflections and ambient light. */
    val environment: Environment = Environment(),
) {
    val frameCount: Int get() = (duration * fps).roundToInt().coerceAtLeast(1)
    val frameDuration: Double get() = 1.0 / fps
    val aspect: Float get() = width.toFloat() / height.toFloat()

    /** Time of frame [frame] in seconds. */
    fun frameTime(frame: Int): Double = frame / fps

    /** Snaps [time] to the closest frame boundary. */
    fun snap(time: Double): Double = (time * fps).roundToInt() / fps
}

/**
 * What 3D materials reflect and the soft ambient light they receive. Without [asset] a procedural studio is used.
 */
@Serializable
data class Environment(
    /** Id of an `image` asset with an equirectangular (360°, 2:1) panorama. */
    val asset: String? = null,
    /** Multiplier of reflections and ambient light from the environment (0..10). */
    val intensity: Float = 1f,
    /** Turns the panorama around the vertical axis, in degrees. */
    val rotation: Float = 0f,
)

/** Motion blur applies to layers with `motionBlur: true` when [enabled]. */
@Serializable
data class MotionBlurSettings(
    val enabled: Boolean = true,
    /** Shutter angle in degrees (180 = half a frame of exposure). */
    val shutterAngle: Float = 180f,
    /** Sub-frame samples rendered per frame. */
    val samples: Int = 8,
)

@Serializable
enum class AssetType {
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO,
    @SerialName("model") MODEL,
    @SerialName("font") FONT,
}

/** A media file embedded in the project under `assets/`. */
@Serializable
data class Asset(
    val id: String,
    val type: AssetType,
    /** Path inside the project container, always under `assets/` (e.g. `assets/logo.png`). */
    val path: String,
    /** Original file name, for display. */
    val name: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Seconds (video and audio). */
    val duration: Double? = null,
    val hasAudio: Boolean? = null,
    val sha256: String? = null,
)
