package dev.kinesis.editor.ui.editor

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.kinesis.core.anim.anim
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectTarget
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.R
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.OffscreenGl
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.CRC32
import kotlin.math.max

/**
 * Tile images of the effect picker: each effect rendered by the real [Compositor] over the sample image
 * `res/drawable-nodpi/effect_preview_sample.jpg` (cropped to cover the square, never stretched). Renders run on one
 * background thread with its own GL context and are cached in memory and in `cacheDir` (keyed by the registry
 * version and the sample image, so replacing the image or changing an effect invalidates them).
 */
class EffectPreviews private constructor(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "kinesis-effect-previews").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val memory = ConcurrentHashMap<String, Bitmap>()

    // Confined to the executor thread.
    private var gl: OffscreenGl? = null
    private var resources: RenderResources? = null
    private var compositor: Compositor? = null
    private var sample: File? = null
    private var sampleSize = 640 to 640
    private var dir: File? = null

    /** The preview for an effect id, or for [TEXT_KEY]; null if it cannot be rendered on this device. */
    suspend fun get(key: String): Bitmap? = memory[key] ?: withContext(dispatcher) { load(key) }

    private fun load(key: String): Bitmap? {
        memory[key]?.let { return it }
        return try {
            prepareSample()
            val file = File(dir!!, "$key.png")
            val cached = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
            val bmp = cached ?: render(key).also { b ->
                file.parentFile?.mkdirs()
                file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            memory[key] = bmp
            bmp
        } catch (e: Throwable) {
            Log.w("Kinesis", "effect preview '$key' failed", e)
            null
        }
    }

    private fun prepareSample() {
        if (sample != null) return
        val bytes = context.resources.openRawResource(R.drawable.effect_preview_sample).use { it.readBytes() }
        val crc = CRC32().apply { update(bytes) }.value
        val root = File(context.cacheDir, "effect-previews")
        val d = File(root, "v${EffectRegistry.VERSION}-${java.lang.Long.toHexString(crc)}")
        // Drop previews made for an older sample image or registry version.
        root.listFiles()?.filter { it.isDirectory && it.name != d.name }?.forEach { it.deleteRecursively() }
        d.mkdirs()
        val f = File(d, "sample.jpg")
        if (!f.isFile || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        sampleSize = opts.outWidth.coerceAtLeast(1) to opts.outHeight.coerceAtLeast(1)
        sample = f
        dir = d
    }

    private fun render(key: String): Bitmap {
        if (gl == null) {
            gl = OffscreenGl()
            val res = RenderResources(context.assets, AssetFiles { id -> if (id == SAMPLE_ID) sample else null })
            resources = res
            compositor = Compositor(res)
        }
        val project = previewProject(key)
        val comp = compositor!!
        val target = comp.render(project, SceneEvaluator.evaluate(project, PREVIEW_TIME), SIZE, SIZE, RenderSettings(motionBlurSamples = 1))
        return comp.readPixels(target)
    }

    private fun previewProject(key: String): KnsProject {
        var p = Factory.newProject("preview", SIZE, SIZE, 30.0, 4.0, Color.parse("#1B1E2B"), null, "preview")
        val (w, h) = sampleSize
        val asset = Asset(SAMPLE_ID, AssetType.IMAGE, "assets/sample.jpg", width = w, height = h)
        p = p.copy(assets = listOf(asset))
        // Cover: the image fills the square and is cropped, never stretched.
        val cover = max(SIZE.toFloat() / w, SIZE.toFloat() / h)
        val image = Factory.image(p, 0.0, asset, "sample").let { it.copy(transform = it.transform.copy(scale = anim(Vec2(cover, cover)))) }
        if (key == TEXT_KEY) {
            val text = Factory.text(p, 0.0, "Aa", "text").let { t -> t.copy(text = t.text!!.copy(fontSize = anim(120f))) }
            return p.copy(layers = listOf(image, text))
        }
        val effect = Effect.create(key)
        val layers = when {
            effect.spec.target == EffectTarget.LAYER_3D -> {
                val cube = Factory.object3d(p, 0.0, Primitive.CUBE, "cube")
                listOf(image, cube.copy(
                    effects = listOf(effect),
                    transform3d = cube.transform3d.copy(rotation = anim(Vec3(-25f, 35f, 0f)), scale = anim(Vec3(0.45f, 0.45f, 0.45f))),
                ))
            }
            key == "timeSlice" -> {
                // Something that moves, so the strips show different moments.
                val star = Factory.shape(p, 0.0, ShapeKind.STAR, "star")
                val spin = Animatable(0f, listOf(Keyframe(0.0, 0f), Keyframe(4.0, 1440f)))
                listOf(image, star.copy(effects = listOf(effect), transform = star.transform.copy(scale = anim(Vec2(0.9f, 0.9f)), rotation = spin)))
            }
            key == "copyBackground" -> {
                // A star that copies the picture below and inverts it, so the copied region shows.
                val star = Factory.shape(p, 0.0, ShapeKind.STAR, "star")
                listOf(image, star.copy(effects = listOf(effect, Effect.create("invert")), transform = star.transform.copy(scale = anim(Vec2(0.5f, 0.5f)))))
            }
            key in SHAPE_SUBJECT -> {
                val star = Factory.shape(p, 0.0, ShapeKind.STAR, "star")
                listOf(image, star.copy(effects = listOf(effect), transform = star.transform.copy(scale = anim(Vec2(0.5f, 0.5f)))))
            }
            else -> listOf(image.copy(effects = listOf(effect)))
        }
        return p.copy(layers = layers)
    }

    companion object {
        const val TEXT_KEY = "text"
        private const val SAMPLE_ID = "sample"
        private const val SIZE = 256
        private const val PREVIEW_TIME = 1.1

        /** Effects that only show on a shape with transparent surroundings (edges, shadows, motion, glass). */
        private val SHAPE_SUBJECT = setOf(
            "glass", "outline", "matteChoker", "dropShadow", "glow", "deepGlow", "echo",
            "autoShake", "spin", "oscillate", "float", "pulse",
        )

        @Volatile private var instance: EffectPreviews? = null

        fun get(context: Context): EffectPreviews =
            instance ?: synchronized(this) { instance ?: EffectPreviews(context.applicationContext).also { instance = it } }
    }
}

/** Preview image for an effect id (or [EffectPreviews.TEXT_KEY]); null while rendering. */
@Composable
fun rememberEffectPreview(key: String): ImageBitmap? {
    val context = LocalContext.current
    val previews = remember { EffectPreviews.get(context) }
    val bitmap by produceState<ImageBitmap?>(null, key) { value = previews.get(key)?.asImageBitmap() }
    return bitmap
}
