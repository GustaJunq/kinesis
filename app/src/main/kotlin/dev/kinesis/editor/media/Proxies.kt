package dev.kinesis.editor.media

import android.content.res.AssetManager
import android.util.Log
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.VideoProxy
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Light copies of heavy videos (short side [PROXY_SHORT_SIDE]) that the preview decodes instead of the
 * originals. Made one at a time in the background with the export pipeline; export always reads the originals.
 * Kept in the app cache: a proxy is rebuilt if its original file changes.
 */
class VideoProxies(private val dir: File, private val assets: AssetManager, private val files: AssetFiles, private val pcm: PcmCache) {
    private val ready = ConcurrentHashMap<String, VideoProxy>()

    @Volatile
    var cancelled = false

    /** The proxy of [assetId], when one is complete. */
    fun get(assetId: String): VideoProxy? = ready[assetId]

    /** Whether [asset] is heavy enough to need a proxy. */
    fun needsProxy(asset: Asset): Boolean {
        if (asset.type != AssetType.VIDEO) return false
        val w = asset.width ?: return false
        val h = asset.height ?: return false
        val file = files.file(asset.id)?.takeIf { it.isFile } ?: return false
        val duration = asset.duration ?: return false
        if (duration <= 0.0) return false
        val mbps = file.length() * 8 / duration / 1_000_000.0
        return min(w, h) > 1080 || mbps > 30.0
    }

    /**
     * Makes the missing proxies of [project]'s heavy videos (blocking; run on a background thread).
     * [onReady] is called after each new proxy.
     */
    fun build(project: KnsProject, onReady: (String) -> Unit) {
        for (asset in project.assets) {
            if (cancelled) return
            if (!needsProxy(asset)) continue
            val original = files.file(asset.id) ?: continue
            val w = asset.width ?: continue
            val h = asset.height ?: continue
            val file = File(dir, "${asset.id}_${original.length()}_${original.lastModified()}_${w}x$h.mp4")
            if (file.isFile) {
                ready[asset.id] = VideoProxy(file, w, h)
                continue
            }
            dir.mkdirs()
            dir.listFiles { f -> f.name.startsWith("${asset.id}_") }?.forEach { it.delete() }
            val tmp = File(dir, "${asset.id}.tmp")
            try {
                VideoExporter(assets, files, pcm).export(
                    proxyProject(project, asset, w, h),
                    PROXY_SETTINGS,
                    tmp,
                    progress = {},
                    cancelled = { cancelled }
                )
                if (!tmp.renameTo(file)) continue
                ready[asset.id] = VideoProxy(file, w, h)
                onReady(asset.id)
            } catch (e: ExportCancelled) {
                tmp.delete()
                return
            } catch (e: Exception) {
                Log.w("Kinesis", "proxy of ${asset.id} failed", e)
                tmp.delete()
            }
        }
    }

    /** A project made of the video alone, at the proxy size. */
    private fun proxyProject(project: KnsProject, asset: Asset, w: Int, h: Int): KnsProject {
        val s = min(1f, PROXY_SHORT_SIDE.toFloat() / min(w, h))
        val pw = max(16, (w * s / 2).roundToInt() * 2)
        val ph = max(16, (h * s / 2).roundToInt() * 2)
        val duration = asset.duration ?: 1.0
        var p = Factory.newProject("proxy", pw, ph, project.composition.fps, duration, Color.parse("#000000"), null, "Kinesis proxy")
        p = p.copy(assets = listOf(asset))
        return p.copy(layers = listOf(Factory.video(p, 0.0, asset, "video")))
    }

    companion object {
        const val PROXY_SHORT_SIDE = 720
        private val PROXY_SETTINGS = ExportSettings(ExportResolution.ORIGINAL, ExportQuality.STANDARD, includeAudio = false)
    }
}
