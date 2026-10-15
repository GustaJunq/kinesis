@file:OptIn(ExperimentalSerializationApi::class)

package dev.kinesis.core.model

import dev.kinesis.core.math.Color
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

const val FORMAT_ID = "knsproj"

/** Format version written by this build. Older versions are migrated on load; newer ones are rejected. */
const val FORMAT_VERSION = 5

/** Root of a `.knsproj` document (`project.json`). */
@Serializable
data class KnsProject(
    @EncodeDefault val format: String = FORMAT_ID,
    @EncodeDefault val formatVersion: Int = FORMAT_VERSION,
    val generator: String? = null,
    @EncodeDefault val meta: ProjectMeta = ProjectMeta(),
    @EncodeDefault val composition: Composition = Composition(),
    val assets: List<Asset> = emptyList(),
    /** Top-level layers in painter's order: index 0 is drawn first (bottom), the last layer is on top. */
    @EncodeDefault val layers: List<Layer> = emptyList(),
) {
    fun asset(id: String?): Asset? = if (id == null) null else assets.firstOrNull { it.id == id }
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
) {
    val frameCount: Int get() = (duration * fps).roundToInt().coerceAtLeast(1)
    val frameDuration: Double get() = 1.0 / fps
    val aspect: Float get() = width.toFloat() / height.toFloat()

    /** Time of frame [frame] in seconds. */
    fun frameTime(frame: Int): Double = frame / fps

    /** Snaps [time] to the closest frame boundary. */
    fun snap(time: Double): Double = (time * fps).roundToInt() / fps
}

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
