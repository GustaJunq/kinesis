package dev.kinesis.editor.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES11Ext
import android.opengl.GLES30
import dev.kinesis.core.geom.ModelMaterial
import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.BlendMode
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectSpec
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.MaskMode
import dev.kinesis.core.model.MaskShape
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.ParamUnit
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.Shading
import dev.kinesis.core.model.Text3D
import dev.kinesis.core.scene.Content2D
import dev.kinesis.core.scene.FrameScene
import dev.kinesis.core.scene.GroupNode
import dev.kinesis.core.scene.MatteRef
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.Node3D
import dev.kinesis.core.scene.NullNode
import dev.kinesis.core.scene.RenderNode
import dev.kinesis.core.scene.ResolvedEffect
import dev.kinesis.core.scene.ResolvedMask
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.core.scene.WipeState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** GPU resources shared by every frame rendered in one EGL context. */
class RenderResources(assets: AssetManager, val files: AssetFiles, proxies: (String) -> VideoProxy? = { null }) {
    val shaders = ShaderLibrary(assets)
    val quad = UnitQuad()
    val pool = TargetPool()
    val typefaces = TypefaceProvider { files.file(it) }
    val content = ContentTextures(typefaces)
    val images = ImageTextures(files, min(4096, Gl.maxTextureSize()))
    val videos = VideoFrames(files, proxies)
    val meshes = MeshCache(files, typefaces)
    val environments = EnvironmentMaps(files, min(4096, Gl.maxTextureSize()))
    private var shadowMap: ShadowMap? = null
    private var noShadow: ShadowMap? = null

    /** The shadow map of [size] texels (recreated when the size changes). */
    fun shadowMap(size: Int): ShadowMap = shadowMap?.takeIf { it.size == size } ?: run {
        shadowMap?.release()
        ShadowMap(size).also { shadowMap = it }
    }

    /** A 1×1 depth texture bound to the shadow sampler when nothing casts shadows (sampler types must match). */
    fun noShadow(): ShadowMap = noShadow ?: ShadowMap(1).also { noShadow = it }

    fun release() {
        shadowMap?.release(); shadowMap = null
        noShadow?.release(); noShadow = null
        environments.release()
        pool.releaseAll()
        content.release()
        images.release()
        videos.release()
        meshes.release()
        quad.release()
        shaders.release()
    }
}

/** Per-frame options. */
data class RenderSettings(
    /** Sub-frames for motion blur (1 disables it). */
    val motionBlurSamples: Int,
    val allowMotionBlur: Boolean = true,
    /** Lighter effects (fewer samples), used by the preview while playing. Export always uses full quality. */
    val preview: Boolean = false,
    /** Side of the shadow map in texels (3D quality). */
    val shadowMapSize: Int = 2048,
    /** Taps per pixel of the depth-of-field blur (3D quality). */
    val dofSamples: Int = 32,
    /** Leave the background transparent (pre-rendering a layer) instead of filling it. */
    val transparentBackground: Boolean = false,
) {
    companion object {
        /** 3D quality presets: low, medium, high. */
        fun quality3D(level: Int): Pair<Int, Int> = when (level) {
            0 -> 512 to 12
            1 -> 1024 to 20
            else -> 2048 to 32
        }
    }
}

/**
 * Draws a [FrameScene] into an offscreen target of the output size, in premultiplied alpha.
 * Preview and export use the same code path, so what you see is what you export.
 */
class Compositor(private val res: RenderResources) {
    private var output: RenderTarget? = null

    /** Layer being drawn (for error reports: which layer failed, e.g. out of memory). */
    @Volatile
    var currentLayerId: String? = null
        private set

    private class Ctx(
        val project: KnsProject,
        val scene: FrameScene,
        val settings: RenderSettings,
        /** Output pixels per composition pixel. */
        val scale: Float,
        val proj: Mat4,
        val width: Int,
        val height: Int,
    ) {
        /** Sample budget of many-tap effects (lens/directional/radial blur, outline…). */
        val samples: Int get() = if (settings.preview) 16 else 48

        /** While drawing motion-blur sub-frames, the real composite below (for effects that read the backdrop). */
        var backdrop: RenderTarget? = null

        /** Scenes evaluated at motion-blur sub-frame times (shared by every blurred layer of the frame), by layer id. */
        val subScenes = HashMap<Double, Map<String, RenderNode>>()

        /** Whether a 3D layer with motion blur stays put during the shutter (then it is drawn without blur). */
        val staticDuringShutter = HashMap<String, Boolean>()

        /** Meshes of each 3D node (several passes need them: shadow, depth of field, colour). */
        val meshes = java.util.IdentityHashMap<Node3D, List<Pair<GpuMesh, Mat4>>>()

        /** Shadow of the first shadow-casting light, rendered once per frame. */
        var shadow: Shadow? = null
    }

    private class Shadow(val lightIndex: Int, val matrix: Mat4, val map: ShadowMap, val darkness: Float, val softness: Float)

    fun render(project: KnsProject, scene: FrameScene, outWidth: Int, outHeight: Int, settings: RenderSettings): RenderTarget {
        val out = output?.takeIf { it.width == outWidth && it.height == outHeight } ?: run {
            output?.release()
            RenderTarget(outWidth, outHeight).also { output = it }
        }
        res.pool.resize(outWidth, outHeight)
        res.content.beginFrame()
        res.videos.beginFrame()
        val ctx = Ctx(
            project, scene, settings,
            scale = outWidth.toFloat() / scene.width,
            proj = Mat4.scale(2f / scene.width, -2f / scene.height, 1f),
            width = outWidth, height = outHeight,
        )
        ctx.shadow = buildShadow(ctx)
        if (ctx.shadow == null) res.noShadow()
        val bg = scene.background
        currentLayerId = null
        if (settings.transparentBackground) out.clear() else out.clear(bg.r * bg.a, bg.g * bg.a, bg.b * bg.a, 1f)
        drawNodes(scene.nodes, out, ctx)
        GLES30.glDisable(GLES30.GL_BLEND)
        res.videos.endFrame()
        res.content.trim()
        res.images.trim()
        return out
    }

    /** Draws [target] into the currently bound framebuffer, inside the given viewport. */
    fun present(target: RenderTarget, x: Int, y: Int, w: Int, h: Int) {
        GLES30.glViewport(x, y, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val p = res.shaders.get("fullscreen.vert", "copy.frag")
        p.use()
        bindTexture2D(0, target.texture)
        p.set1i("u_texture", 0)
        res.quad.draw()
    }

    /**
     * Draws [target] as a packed-alpha frame filling a [w]×[h] viewport: straight colour on the left half,
     * alpha as grey on the right half (what video layers with `alphaPacked` read back).
     */
    fun presentPacked(target: RenderTarget, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val p = res.shaders.get("fullscreen.vert", "pack_alpha.frag")
        p.use()
        bindTexture2D(0, target.texture)
        p.set1i("u_texture", 0)
        res.quad.draw()
    }

    /** Reads [target] back into a bitmap (top row first). */
    fun readPixels(target: RenderTarget): Bitmap {
        target.bind()
        val buf = ByteBuffer.allocateDirect(target.width * target.height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, target.width, target.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val row = target.width * 4
        val flipped = ByteBuffer.allocateDirect(buf.capacity()).order(ByteOrder.nativeOrder())
        val tmp = ByteArray(row)
        for (yy in target.height - 1 downTo 0) {
            buf.position(yy * row)
            buf.get(tmp)
            flipped.put(tmp)
        }
        flipped.position(0)
        val bmp = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(flipped)
        return bmp
    }

    fun release() {
        output?.release()
        output = null
    }

    // ------------------------------------------------------------------ node dispatch

    private fun drawNodes(allNodes: List<RenderNode>, target: RenderTarget, ctx: Ctx) {
        // Null objects only mark a transform for the editor; they must not split batches of 3D layers.
        val nodes = if (allNodes.any { it is NullNode }) allNodes.filter { it !is NullNode } else allNodes
        var i = 0
        while (i < nodes.size) {
            val n = nodes[i]
            if (n is Node3D && isBatchable(n, ctx)) {
                var j = i
                val batch = ArrayList<Node3D>()
                while (j < nodes.size && nodes[j] is Node3D && isBatchable(nodes[j] as Node3D, ctx)) {
                    batch += nodes[j] as Node3D
                    j++
                }
                currentLayerId = batch.first().layerId
                draw3DBatch(batch, target, ctx, opacityOverride = null)
                i = j
                continue
            }
            if (n !is GroupNode) currentLayerId = n.layerId
            drawNode(n, target, ctx)
            i++
        }
    }

    private fun motionBlurOn(n: RenderNode, ctx: Ctx): Boolean {
        val mb = ctx.project.composition.motionBlur
        return n.motionBlur && mb.enabled && ctx.settings.allowMotionBlur && ctx.settings.motionBlurSamples > 1 && mb.shutterAngle > 0f &&
            !(n is Node2D && n.content is Content2D.Video) && !(n is Node3D && isStaticDuringShutter(n, ctx))
    }

    /** The scene at composition time [t], indexed by layer id (evaluated once per frame and time). */
    private fun sceneAt(ctx: Ctx, t: Double): Map<String, RenderNode> = ctx.subScenes.getOrPut(t) {
        val index = HashMap<String, RenderNode>()
        fun walk(nodes: List<RenderNode>) {
            for (x in nodes) {
                index[x.layerId] = x
                if (x is GroupNode) walk(x.children)
            }
        }
        walk(SceneEvaluator.evaluate(ctx.project, t).nodes)
        index
    }

    /** True when [n] looks the same at both ends of the shutter interval: motion blur would only cost time. */
    private fun isStaticDuringShutter(n: Node3D, ctx: Ctx): Boolean = ctx.staticDuringShutter.getOrPut(n.layerId) {
        if (n.effects.isNotEmpty() || n.transitionBlur > 0f) return@getOrPut false
        val comp = ctx.project.composition
        val half = comp.motionBlur.shutterAngle / 360.0 * comp.frameDuration / 2
        for (t in doubleArrayOf(ctx.scene.time - half, ctx.scene.time + half)) {
            val o = sceneAt(ctx, t)[n.layerId] as? Node3D ?: return@getOrPut false
            if (o.primitive != n.primitive || o.modelAssetId != n.modelAssetId || o.text3d != n.text3d) return@getOrPut false
            if (o.post != n.post || abs(o.opacity - n.opacity) > 1e-4f) return@getOrPut false
            val a = o.model.m
            val b = n.model.m
            for (i in 0 until 16) if (abs(a[i] - b[i]) > 1e-3f) return@getOrPut false
        }
        true
    }

    private fun isBatchable(n: Node3D, ctx: Ctx): Boolean =
        n.effects.isEmpty() && n.blendMode == BlendMode.NORMAL && n.transitionBlur <= 0f && isIdentity(n.post) && !motionBlurOn(n, ctx)

    private fun drawNode(n: RenderNode, target: RenderTarget, ctx: Ctx) {
        if (motionBlurOn(n, ctx)) drawMotionBlurred(n, target, ctx) else drawNodeAt(n, target, ctx, n.opacity, n.blendMode)
    }

    /** Renders [n] into [target] with the given opacity and blend mode. */
    private fun drawNodeAt(n: RenderNode, target: RenderTarget, ctx: Ctx, opacity: Float, blend: BlendMode) {
        when (n) {
            is NullNode -> Unit
            is Node2D -> {
                val offscreen = n.effects.isNotEmpty() || n.matte != null || n.transitionBlur > 0f
                if (!offscreen) {
                    drawContent2D(n, target, ctx, opacity, blend)
                } else {
                    var layer = res.pool.acquire()
                    drawContent2D(n, layer, ctx, 1f, BlendMode.NORMAL)
                    layer = applyEffects(n.effects, n.transitionBlur, layer, ctx, target)
                    n.matte?.let { layer = applyMatte(layer, it, ctx) }
                    composite(layer, target, ctx, opacity, blend, Affine2D.IDENTITY, emptyList(), null)
                    res.pool.release(layer)
                }
            }
            is GroupNode -> {
                if (n.isPassThrough && opacity >= 0.999f && blend == BlendMode.NORMAL) {
                    drawNodes(n.children, target, ctx)
                } else {
                    var layer = res.pool.acquire()
                    drawNodes(n.children, layer, ctx)
                    layer = applyEffects(n.effects, n.transitionBlur, layer, ctx, target)
                    n.matte?.let { layer = applyMatte(layer, it, ctx) }
                    composite(layer, target, ctx, opacity, blend, Affine2D.IDENTITY, n.masks, n.matrix.inverse())
                    res.pool.release(layer)
                }
            }
            is Node3D -> {
                if (isBatchable(n, ctx) && blend == BlendMode.NORMAL) {
                    draw3DBatch(listOf(n), target, ctx, opacityOverride = opacity)
                } else {
                    var layer = res.pool.acquire()
                    val depth = n.effects.firstOrNull { it.type == "depthMap" }
                    if (depth != null) drawDepth(n, depth, layer, ctx) else draw3DBatch(listOf(n), layer, ctx, opacityOverride = 1f)
                    layer = applyEffects(n.effects.filter { it.type != "depthMap" }, n.transitionBlur, layer, ctx, target)
                    composite(layer, target, ctx, opacity, blend, n.post, emptyList(), null)
                    res.pool.release(layer)
                }
            }
        }
    }

    /** Averages sub-frame renders over the shutter interval. */
    private fun drawMotionBlurred(n: RenderNode, target: RenderTarget, ctx: Ctx) {
        val comp = ctx.project.composition
        val samples = ctx.settings.motionBlurSamples.coerceIn(2, 64)
        val shutter = comp.motionBlur.shutterAngle / 360.0 * comp.frameDuration
        val accum = res.pool.acquire()
        for (k in 0 until samples) {
            val t = ctx.scene.time - shutter / 2 + shutter * (k + 0.5) / samples
            val sub = if (k == samples / 2 && samples % 2 == 1) n else sceneAt(ctx, t)[n.layerId]
            if (sub == null) continue
            val tmp = res.pool.acquire()
            val outer = ctx.backdrop
            ctx.backdrop = outer ?: target
            drawNodeAt(sub, tmp, ctx, sub.opacity, BlendMode.NORMAL)
            ctx.backdrop = outer
            // Additive accumulation with weight 1/samples.
            accum.bind()
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            val p = res.shaders.get("quad.vert", "quad.frag")
            setupQuad(p, ctx.proj, compRect(ctx), flipY = true, texMatrix = IDENTITY, opacity = 1f / samples, premultiplied = true, masks = emptyList(), maskMatrix = null, wipe = null)
            bindTexture2D(0, tmp.texture)
            p.set1i("u_texture", 0)
            res.quad.draw()
            res.pool.release(tmp)
        }
        composite(accum, target, ctx, 1f, n.blendMode, Affine2D.IDENTITY, emptyList(), null)
        res.pool.release(accum)
    }

    // ------------------------------------------------------------------ 2D content

    private fun drawContent2D(n: Node2D, target: RenderTarget, ctx: Ctx, opacity: Float, blend: BlendMode) {
        val mvp = ctx.proj * n.matrix.toMat4()
        val rasterScale = n.matrix.approximateScale() * ctx.scale
        when (val c = n.content) {
            is Content2D.Text -> res.content.text(n.layerId, c, rasterScale)?.let {
                drawTexture2D(it.texture, it.rect, mvp, target, opacity, blend, n.masks, n.wipe)
            }
            is Content2D.Shape -> res.content.shape(n.layerId, c, rasterScale)?.let {
                drawTexture2D(it.texture, it.rect, mvp, target, opacity, blend, n.masks, n.wipe)
            }
            is Content2D.Image -> res.images.get(c.assetId)?.let { img ->
                val rect = RectF(-img.width / 2f, -img.height / 2f, img.width / 2f, img.height / 2f)
                drawTexture2D(img.texture, rect, mvp, target, opacity, blend, n.masks, n.wipe)
            }
            is Content2D.Video -> res.videos.frame(n.layerId, c.assetId, c.sourceTime)?.let { v ->
                // Packed alpha: the visible picture is the left half of the frame.
                val w = if (c.alphaPacked) v.width / 2f else v.width.toFloat()
                val rect = RectF(-w / 2f, -v.height / 2f, w / 2f, v.height / 2f)
                target.bind()
                applyBlend(blend)
                val p = res.shaders.get("quad.vert", "quad_oes.frag")
                setupQuad(p, mvp, rect, flipY = true, texMatrix = v.texMatrix, opacity = opacity, premultiplied = true, masks = n.masks, maskMatrix = null, wipe = n.wipe, alphaPacked = c.alphaPacked)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, v.texture)
                p.set1i("u_texture", 0)
                res.quad.draw()
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            }
        }
    }

    private fun drawTexture2D(texture: Int, rect: RectF, mvp: Mat4, target: RenderTarget, opacity: Float, blend: BlendMode, masks: List<ResolvedMask>, wipe: WipeState?) {
        target.bind()
        applyBlend(blend)
        val p = res.shaders.get("quad.vert", "quad.frag")
        setupQuad(p, mvp, rect, flipY = false, texMatrix = IDENTITY, opacity = opacity, premultiplied = true, masks = masks, maskMatrix = null, wipe = wipe)
        bindTexture2D(0, texture)
        p.set1i("u_texture", 0)
        res.quad.draw()
    }

    /** Draws an offscreen layer (composition-sized) into [target] through [post]. */
    private fun composite(layer: RenderTarget, target: RenderTarget, ctx: Ctx, opacity: Float, blend: BlendMode, post: Affine2D, masks: List<ResolvedMask>, maskMatrix: Affine2D?) {
        target.bind()
        applyBlend(blend)
        val p = res.shaders.get("quad.vert", "quad.frag")
        setupQuad(p, ctx.proj * post.toMat4(), compRect(ctx), flipY = true, texMatrix = IDENTITY, opacity = opacity, premultiplied = true, masks = masks, maskMatrix = maskMatrix, wipe = null)
        bindTexture2D(0, layer.texture)
        p.set1i("u_texture", 0)
        res.quad.draw()
    }

    private fun compRect(ctx: Ctx) = RectF(-ctx.scene.width / 2f, -ctx.scene.height / 2f, ctx.scene.width / 2f, ctx.scene.height / 2f)

    private fun setupQuad(
        p: Program, mvp: Mat4, rect: RectF, flipY: Boolean, texMatrix: FloatArray, opacity: Float, premultiplied: Boolean,
        masks: List<ResolvedMask>, maskMatrix: Affine2D?, wipe: WipeState?, alphaPacked: Boolean = false,
    ) {
        p.use()
        p.set1f("u_alphaPacked", if (alphaPacked) 1f else 0f)
        p.setMat4("u_mvp", mvp.m)
        p.set4f("u_rect", rect.left, rect.top, rect.width(), rect.height())
        p.setMat4("u_texMatrix", texMatrix)
        p.set1f("u_flipY", if (flipY) 1f else 0f)
        p.set1f("u_opacity", opacity)
        p.set1f("u_premultiplied", if (premultiplied) 1f else 0f)
        p.setMat4("u_maskMatrix", (maskMatrix ?: Affine2D.IDENTITY).toMat4().m)
        val count = min(masks.size, 4)
        p.set1i("u_maskCount", count)
        if (count > 0) {
            val r = FloatArray(16)
            val params = FloatArray(16)
            val extra = FloatArray(8)
            for (i in 0 until count) {
                val m = masks[i]
                r[i * 4] = m.center.x; r[i * 4 + 1] = m.center.y; r[i * 4 + 2] = m.size.x; r[i * 4 + 3] = m.size.y
                params[i * 4] = if (m.shape == MaskShape.RECTANGLE) 0f else 1f
                params[i * 4 + 1] = when (m.mode) { MaskMode.ADD -> 0f; MaskMode.SUBTRACT -> 1f; MaskMode.INTERSECT -> 2f }
                params[i * 4 + 2] = m.roundness
                params[i * 4 + 3] = m.feather
                extra[i * 2] = m.opacity
                extra[i * 2 + 1] = if (m.inverted) 1f else 0f
            }
            p.set4fv("u_maskRect[0]", 4, r)
            p.set4fv("u_maskParams[0]", 4, params)
            p.set2fv("u_maskExtra[0]", 4, extra)
        }
        if (wipe != null) {
            p.set4f("u_wipe", 1f, wipe.progress, if (wipe.anchoredRight) 1f else 0f, 0f)
            p.set4f("u_rectBounds", rect.left, rect.top, rect.width(), rect.height())
        } else {
            p.set4f("u_wipe", 0f, 0f, 0f, 0f)
        }
    }

    // ------------------------------------------------------------------ 3D

    private fun draw3DBatch(nodes: List<Node3D>, target: RenderTarget, ctx: Ctx, opacityOverride: Float?) {
        val dof = ctx.scene.camera.dof
        if (dof == null) {
            drawBatchInto(nodes, target, ctx, opacityOverride)
            return
        }
        // Depth of field: render the batch alone, with its distances, then blur by circle of confusion.
        val layer = res.pool.acquire()
        layer.clear()
        drawBatchInto(nodes, layer, ctx, opacityOverride)
        val depth = res.pool.acquire()
        depth.clear(1f, 0f, 0f, 0f)
        drawLinearDepth(nodes, depth, ctx)
        val blurred = pass("dof.frag", layer) { p ->
            bindTexture2D(1, depth.texture)
            p.set1i("u_depth", 1)
            p.set2f("u_texel", 1f / ctx.width, 1f / ctx.height)
            p.set1f("u_far", DOF_FAR)
            p.set1f("u_focus", dof.focusDistance)
            p.set1f("u_range", dof.range)
            p.set1f("u_maxBlur", dof.blur * ctx.scale)
            p.set1i("u_samples", (if (ctx.settings.preview) ctx.settings.dofSamples / 2 else ctx.settings.dofSamples).coerceIn(4, 64))
        }
        res.pool.release(depth)
        composite(blurred, target, ctx, 1f, BlendMode.NORMAL, Affine2D.IDENTITY, emptyList(), null)
        res.pool.release(blurred)
    }

    private fun drawBatchInto(nodes: List<Node3D>, target: RenderTarget, ctx: Ctx, opacityOverride: Float?) {
        target.bind()
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        applyBlend(BlendMode.NORMAL)
        val p = res.shaders.get("mesh.vert", "mesh.frag")
        p.use()
        setupLights(p, ctx)
        // Uniforms persist in the program: remember what was set last to skip repeating it for every object.
        lastShading = -1
        lastOpacity = Float.NaN
        lastMaterial = null
        fun opacityOf(n: Node3D) = opacityOverride ?: n.opacity
        // Objects entirely outside the view are not drawn (shadows and depth of field have their own passes).
        val visible = if (nodes.size > 1) nodes.filter { inView(it, ctx) } else nodes
        GLES30.glDepthMask(true)
        for (n in visible) if (opacityOf(n) >= 0.999f) drawMesh(p, n, opacityOf(n), ctx)
        val translucent = visible.filter { opacityOf(it) < 0.999f && opacityOf(it) > 0.001f }
            .map { it to ctx.scene.camera.depthOf(it.model.transformPoint(Vec3.ZERO)) }
            .sortedByDescending { it.second }
            .map { it.first }
        if (translucent.isNotEmpty()) {
            GLES30.glDepthMask(false)
            for (n in translucent) drawMesh(p, n, opacityOf(n), ctx)
            GLES30.glDepthMask(true)
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glFrontFace(GLES30.GL_CCW)
    }

    /** Distance to the camera of every opaque pixel of [nodes], for depth of field. */
    private fun drawLinearDepth(nodes: List<Node3D>, target: RenderTarget, ctx: Ctx) {
        target.bind()
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        val p = res.shaders.get("mesh.vert", "lineardepth.frag")
        p.use()
        val cam = ctx.scene.camera
        p.setMat4("u_viewProj", cam.viewProjection.m)
        p.set3f("u_cameraPos", cam.position.x, cam.position.y, cam.position.z)
        p.set1f("u_far", DOF_FAR)
        for (n in nodes) {
            if (nodes.size > 1 && !inView(n, ctx)) continue
            for ((mesh, model) in meshesOf(n, ctx)) {
                setModel(p, model)
                mesh.draw()
            }
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glFrontFace(GLES30.GL_CCW)
    }

    private var lastShading = -1
    private var lastOpacity = Float.NaN
    private var lastMaterial: MaterialKey? = null

    private data class MaterialKey(
        val r: Float, val g: Float, val b: Float, val a: Float, val metallic: Float, val roughness: Float,
        val er: Float, val eg: Float, val eb: Float, val texture: Int,
    )

    /** False when the bounding boxes of every mesh of [n] lie completely outside the camera frustum. */
    private fun inView(n: Node3D, ctx: Ctx): Boolean {
        val vp = ctx.scene.camera.viewProjection.m
        val list = meshesOf(n, ctx)
        if (list.isEmpty()) return true
        for ((mesh, model) in list) {
            val (lo, hi) = mesh.bounds
            val m = model.m
            var outside = 0x3F
            for (corner in 0 until 8) {
                val x = if (corner and 1 == 0) lo.x else hi.x
                val y = if (corner and 2 == 0) lo.y else hi.y
                val z = if (corner and 4 == 0) lo.z else hi.z
                val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
                val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
                val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
                val cx = vp[0] * wx + vp[4] * wy + vp[8] * wz + vp[12]
                val cy = vp[1] * wx + vp[5] * wy + vp[9] * wz + vp[13]
                val cz = vp[2] * wx + vp[6] * wy + vp[10] * wz + vp[14]
                val cw = (vp[3] * wx + vp[7] * wy + vp[11] * wz + vp[15]) * 1.05f + 1f
                var code = 0
                if (cx < -cw) code = code or 1
                if (cx > cw) code = code or 2
                if (cy < -cw) code = code or 4
                if (cy > cw) code = code or 8
                if (cz < -cw) code = code or 16
                if (cz > cw) code = code or 32
                outside = outside and code
                if (outside == 0) break
            }
            if (outside == 0) return true
        }
        return false
    }

    /** The meshes of a 3D node with their model matrices (text and primitives in one piece, models per part). */
    private fun meshesOf(n: Node3D, ctx: Ctx): List<Pair<GpuMesh, Mat4>> = ctx.meshes.getOrPut(n) { buildMeshes(n) }

    private fun buildMeshes(n: Node3D): List<Pair<GpuMesh, Mat4>> {
        val primitive = n.primitive
        if (primitive == Primitive.TEXT) return listOf(res.meshes.text(n.text3d ?: Text3D()) to n.model)
        if (primitive != null) return listOf(res.meshes.primitive(primitive) to n.model)
        val model = res.meshes.model(n.modelAssetId ?: return emptyList()) ?: return emptyList()
        val matrix = n.model * model.normalization
        return model.parts.map { it.mesh to matrix }
    }

    /** All 3D nodes of the scene (inside groups too): shadow casters. */
    private fun all3D(nodes: List<RenderNode>, out: MutableList<Node3D> = ArrayList()): List<Node3D> {
        for (n in nodes) when (n) {
            is Node3D -> out += n
            is GroupNode -> all3D(n.children, out)
            else -> Unit
        }
        return out
    }

    /** Renders the shadow map of the first shadow-casting light, fitted to every 3D object of the frame. */
    private fun buildShadow(ctx: Ctx): Shadow? {
        val lights = ctx.scene.lights.take(4)
        val index = lights.indexOfFirst { it.castShadows && it.kind != LightKind.AMBIENT && it.shadowDarkness > 0.001f }
        if (index < 0) return null
        val casters = all3D(ctx.scene.nodes).filter { it.material?.shading != Shading.WIREFRAME }
        if (casters.isEmpty()) return null
        val drawn = casters.flatMap { meshesOf(it, ctx) }
        if (drawn.isEmpty()) return null
        var lo = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        var hi = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        var loX = lo.x; var loY = lo.y; var loZ = lo.z
        var hiX = hi.x; var hiY = hi.y; var hiZ = hi.z
        for ((mesh, model) in drawn) {
            val (bmin, bmax) = mesh.bounds
            val m = model.m
            for (corner in 0 until 8) {
                val x = if (corner and 1 == 0) bmin.x else bmax.x
                val y = if (corner and 2 == 0) bmin.y else bmax.y
                val z = if (corner and 4 == 0) bmin.z else bmax.z
                val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
                val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
                val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
                if (wx < loX) loX = wx; if (wx > hiX) hiX = wx
                if (wy < loY) loY = wy; if (wy > hiY) hiY = wy
                if (wz < loZ) loZ = wz; if (wz > hiZ) hiZ = wz
            }
        }
        lo = Vec3(loX, loY, loZ)
        hi = Vec3(hiX, hiY, hiZ)
        val center = (lo + hi) * 0.5f
        val radius = ((hi - lo).length() * 0.5f).coerceAtLeast(1f)
        val light = lights[index]
        val matrix = if (light.kind == LightKind.POINT) ShadowCamera.point(center, radius, light.position)
        else ShadowCamera.directional(center, radius, light.direction)
        val map = res.shadowMap(ctx.settings.shadowMapSize.coerceIn(256, min(4096, Gl.maxTextureSize())))
        map.bind()
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.5f, 2f)
        val p = res.shaders.get("shadow_depth.vert", "shadow_depth.frag")
        p.use()
        p.setMat4("u_lightViewProj", matrix.m)
        for ((mesh, model) in drawn) {
            p.setMat4("u_model", model.m)
            mesh.draw()
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return Shadow(index, matrix, map, light.shadowDarkness, light.shadowSoftness)
    }

    private fun setupLights(p: Program, ctx: Ctx) {
        val scene = ctx.scene
        val cam = scene.camera
        p.setMat4("u_viewProj", cam.viewProjection.m)
        p.set3f("u_cameraPos", cam.position.x, cam.position.y, cam.position.z)
        val amb = scene.ambient
        p.set3f("u_ambient", amb.r, amb.g, amb.b)
        val luma = 0.2126f * amb.r + 0.7152f * amb.g + 0.0722f * amb.b
        p.set1f("u_envIntensity", (0.25f + luma * 3f).coerceIn(0.25f, 1.6f))
        // Environment panorama (unit 3) and shadow map (unit 2): always bound so sampler types never clash.
        val env = scene.environment
        val envMap = env.asset?.let { res.environments.get(it) }
        p.set1f("u_envScale", env.intensity.coerceIn(0f, 10f))
        p.set1f("u_envMapOn", if (envMap != null) 1f else 0f)
        p.set1f("u_envMaxLod", envMap?.maxLod ?: 0f)
        p.set1f("u_envRotation", env.rotation / 360f)
        bindTexture2D(3, envMap?.texture ?: 0)
        p.set1i("u_envMap", 3)
        val shadow = ctx.shadow
        bindTexture2D(2, (shadow?.map ?: res.noShadow()).texture)
        p.set1i("u_shadowMap", 2)
        p.set1i("u_shadowLight", shadow?.lightIndex ?: -1)
        if (shadow != null) {
            p.setMat4("u_shadowMatrix", shadow.matrix.m)
            p.set2f("u_shadowTexel", 1f / shadow.map.size, 1f / shadow.map.size)
            p.set1f("u_shadowDarkness", shadow.darkness)
            // Softness is in texels of a 2048 map; keep the same blur in world size for smaller maps.
            p.set1f("u_shadowSoftness", shadow.softness * shadow.map.size / 2048f)
        }
        p.set1f("u_wireWidth", 1.5f * max(1f, ctx.width / 1080f))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val lights = scene.lights.take(4)
        p.set1i("u_lightCount", lights.size)
        if (lights.isEmpty()) return
        val types = IntArray(4)
        val colors = FloatArray(12)
        val positions = FloatArray(12)
        val dirs = FloatArray(12)
        val radii = FloatArray(4)
        lights.forEachIndexed { i, l ->
            types[i] = if (l.kind == LightKind.POINT) 2 else 1
            val c = l.color.toLinear()
            colors[i * 3] = c.r * l.intensity; colors[i * 3 + 1] = c.g * l.intensity; colors[i * 3 + 2] = c.b * l.intensity
            positions[i * 3] = l.position.x; positions[i * 3 + 1] = l.position.y; positions[i * 3 + 2] = l.position.z
            dirs[i * 3] = l.direction.x; dirs[i * 3 + 1] = l.direction.y; dirs[i * 3 + 2] = l.direction.z
            radii[i] = l.radius
        }
        p.set1iv("u_lightType[0]", 4, types)
        p.set3fv("u_lightColor[0]", 4, colors)
        p.set3fv("u_lightPos[0]", 4, positions)
        p.set3fv("u_lightDir[0]", 4, dirs)
        p.set1fv("u_lightRadius[0]", 4, radii)
    }

    private fun drawMesh(p: Program, n: Node3D, opacity: Float, ctx: Ctx) {
        if (opacity != lastOpacity) {
            p.set1f("u_opacity", opacity)
            lastOpacity = opacity
        }
        val override = n.material
        val shading = override?.shading ?: Shading.PBR
        if (shading.ordinal != lastShading) {
            p.set1i("u_shading", shading.ordinal)
            lastShading = shading.ordinal
        }
        val wire = shading == Shading.WIREFRAME
        val primitive = n.primitive
        if (primitive != null) {
            val mat = override ?: return
            setMaterial(p, mat.color, mat.metallic, mat.roughness, mat.emissive, mat.textureAssetId?.let { res.images.get(it)?.texture } ?: 0)
            for ((mesh, model) in meshesOf(n, ctx)) {
                setModel(p, model)
                (if (wire) mesh.wireframe() else mesh).draw()
            }
            return
        }
        val model = res.meshes.model(n.modelAssetId ?: return) ?: return
        val matrix = n.model * model.normalization
        setModel(p, matrix)
        for (part in model.parts) {
            if (override != null) {
                setMaterial(p, override.color, override.metallic, override.roughness, override.emissive, override.textureAssetId?.let { res.images.get(it)?.texture } ?: part.texture)
            } else {
                val m: ModelMaterial = part.material
                // glTF factors are linear; the shader expects sRGB inputs.
                setMaterial(p, toSrgb(m.baseColor), m.metallic, m.roughness, toSrgb(m.emissive), part.texture)
            }
            (if (wire) part.mesh.wireframe() else part.mesh).draw()
        }
    }

    private fun setModel(p: Program, model: Mat4) {
        p.setMat4("u_model", model.m)
        p.setMat3("u_normalMatrix", model.normalMatrix())
        GLES30.glFrontFace(if (det3(model) < 0f) GLES30.GL_CW else GLES30.GL_CCW)
    }

    private fun setMaterial(p: Program, color: Color, metallic: Float, roughness: Float, emissive: Color, texture: Int) {
        val key = MaterialKey(color.r, color.g, color.b, color.a, metallic, roughness, emissive.r, emissive.g, emissive.b, texture)
        if (key == lastMaterial) return
        lastMaterial = key
        p.set4f("u_baseColor", color.r, color.g, color.b, color.a)
        p.set1f("u_metallic", metallic)
        p.set1f("u_roughness", roughness)
        p.set3f("u_emissive", emissive.r, emissive.g, emissive.b)
        p.set1f("u_hasTexture", if (texture != 0) 1f else 0f)
        bindTexture2D(0, texture)
        p.set1i("u_baseTexture", 0)
    }

    private fun toSrgb(c: Color): Color {
        fun g(v: Float) = v.coerceAtLeast(0f).toDouble().pow(1.0 / 2.2).toFloat()
        return Color(g(c.r), g(c.g), g(c.b), c.a)
    }

    private fun det3(m: Mat4): Float =
        m[0, 0] * (m[1, 1] * m[2, 2] - m[2, 1] * m[1, 2]) - m[0, 1] * (m[1, 0] * m[2, 2] - m[1, 2] * m[2, 0]) + m[0, 2] * (m[1, 0] * m[2, 1] - m[1, 1] * m[2, 0])

    // ------------------------------------------------------------------ effects

    /** Runs a full-target pass reading [src] (which is released) and returns the new target. */
    private fun pass(fragment: String, src: RenderTarget, setup: (Program) -> Unit): RenderTarget {
        val dst = res.pool.acquire()
        dst.bind()
        GLES30.glDisable(GLES30.GL_BLEND)
        val p = res.shaders.get("fullscreen.vert", fragment)
        p.use()
        bindTexture2D(0, src.texture)
        p.set1i("u_texture", 0)
        setup(p)
        res.quad.draw()
        res.pool.release(src)
        return dst
    }

    private fun copy(src: RenderTarget): RenderTarget {
        val dst = res.pool.acquire()
        dst.bind()
        GLES30.glDisable(GLES30.GL_BLEND)
        val p = res.shaders.get("fullscreen.vert", "copy.frag")
        p.use()
        bindTexture2D(0, src.texture)
        p.set1i("u_texture", 0)
        res.quad.draw()
        return dst
    }

    private fun blur(src: RenderTarget, radiusComp: Float, ctx: Ctx): RenderTarget {
        val radius = radiusComp * ctx.scale
        if (radius < 0.5f) return src
        val sigmaPx = radius / 2f
        val spacing = max(1f, sigmaPx * 3f / 24f)
        val sigmaTaps = sigmaPx / spacing
        val h = pass("blur.frag", src) { p ->
            p.set2f("u_step", spacing / ctx.width, 0f)
            p.set1f("u_sigma", sigmaTaps)
        }
        return pass("blur.frag", h) { p ->
            p.set2f("u_step", 0f, spacing / ctx.height)
            p.set1f("u_sigma", sigmaTaps)
        }
    }

    /**
     * Runs the layer's effects in order. [below] is the target the layer will be composited into; it holds the
     * composite of the layers underneath (read by effects such as Glass).
     */
    private fun applyEffects(effects: List<ResolvedEffect>, transitionBlur: Float, input: RenderTarget, ctx: Ctx, below: RenderTarget): RenderTarget {
        var cur = input
        for (e in effects) {
            val spec = EffectRegistry.get(e.type) ?: continue
            cur = when {
                e.transform != null -> transformPass(cur, e.transform!!, ctx)
                else -> when (e.type) {
                    "blur" -> blur(cur, e.f("radius"), ctx)
                    "glow" -> glow(cur, e.color("color"), e.f("radius"), e.f("intensity"), ctx)
                    "dropShadow" -> {
                        val a = Math.toRadians(e.f("angle").toDouble())
                        val d = e.f("distance")
                        dropShadow(cur, e.color("color"), e.f("opacity"), (kotlin.math.cos(a) * d).toFloat(), (kotlin.math.sin(a) * d).toFloat(), e.f("softness"), ctx)
                    }
                    "sharpen" -> sharpen(cur, e, ctx)
                    "deepGlow" -> deepGlow(cur, e, ctx)
                    "glass" -> glass(cur, e, ctx, ctx.backdrop ?: below)
                    "echo" -> echo(cur, e, ctx)
                    "posterizeTime", "depthMap" -> cur // handled by scene evaluation / 3D drawing
                    else -> generic(spec, e, cur, ctx)
                }
            }
        }
        if (transitionBlur > 0f) cur = blur(cur, transitionBlur, ctx)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        return cur
    }

    /** Composition point → uv of the offscreen targets (GL orientation, y up). */
    private fun uvOf(p: Vec2, ctx: Ctx) = Vec2(0.5f + p.x / ctx.scene.width, 0.5f - p.y / ctx.scene.height)

    private fun setCommon(p: Program, e: ResolvedEffect, ctx: Ctx) {
        p.set2f("u_resolution", ctx.width.toFloat(), ctx.height.toFloat())
        p.set1f("u_pxScale", ctx.scale)
        p.set1f("u_time", e.time.toFloat())
        uvOf(e.origin, ctx).let { p.set2f("u_origin", it.x, it.y) }
        p.set1i("u_samples", ctx.samples)
    }

    /**
     * Single-pass effects: `shaders/fx/<type>.frag` with one uniform per parameter, named `u_<param>`.
     * Pixel lengths are converted to output pixels, points to uv, choices to their index, switches to 0/1.
     */
    private fun generic(spec: EffectSpec, e: ResolvedEffect, src: RenderTarget, ctx: Ctx): RenderTarget = pass("fx/${spec.id}.frag", src) { p ->
        setCommon(p, e, ctx)
        for (param in spec.params) {
            val u = "u_${param.name}"
            when (param.type) {
                ParamType.NUMBER -> p.set1f(u, if (param.unit == ParamUnit.PX) e.f(param.name) * ctx.scale else e.f(param.name))
                ParamType.INTEGER -> if (param.name == "seed") p.set1f(u, e.i(param.name).toFloat()) else p.set1i(u, e.i(param.name))
                ParamType.COLOR -> e.color(param.name).let { c -> p.set4f(u, c.r, c.g, c.b, c.a) }
                ParamType.POINT -> uvOf(e.point(param.name), ctx).let { v -> p.set2f(u, v.x, v.y) }
                ParamType.BOOLEAN -> p.set1f(u, if (e.flag(param.name)) 1f else 0f)
                ParamType.CHOICE -> p.set1i(u, param.choices.indexOfFirst { it.id == e.choice(param.name) }.coerceAtLeast(0))
                ParamType.STOPS -> {
                    val stops = e.stops(param.name).take(8)
                    val offsets = FloatArray(8)
                    val colors = FloatArray(32)
                    stops.forEachIndexed { k, st ->
                        offsets[k] = st.offset
                        colors[k * 4] = st.color.r; colors[k * 4 + 1] = st.color.g; colors[k * 4 + 2] = st.color.b; colors[k * 4 + 3] = st.color.a
                    }
                    p.set1i("${u}Count", stops.size)
                    p.set1fv("${u}Offset[0]", 8, offsets)
                    p.set4fv("${u}Color[0]", 8, colors)
                }
            }
        }
    }

    private fun glow(cur: RenderTarget, color: Color, radius: Float, intensity: Float, ctx: Ctx): RenderTarget {
        val blurred = blur(copy(cur), radius, ctx)
        val r = pass("shadow.frag", cur) { p ->
            bindTexture2D(1, blurred.texture)
            p.set1i("u_blurred", 1)
            p.set2f("u_offset", 0f, 0f)
            p.set4f("u_color", color.r, color.g, color.b, intensity)
            p.set1f("u_glow", 1f)
        }
        res.pool.release(blurred)
        return r
    }

    private fun dropShadow(cur: RenderTarget, color: Color, opacity: Float, dx: Float, dy: Float, softness: Float, ctx: Ctx): RenderTarget {
        val blurred = blur(copy(cur), softness, ctx)
        val r = pass("shadow.frag", cur) { p ->
            bindTexture2D(1, blurred.texture)
            p.set1i("u_blurred", 1)
            p.set2f("u_offset", dx * ctx.scale / ctx.width, -dy * ctx.scale / ctx.height)
            p.set4f("u_color", color.r, color.g, color.b, opacity * color.a)
            p.set1f("u_glow", 0f)
        }
        res.pool.release(blurred)
        return r
    }

    private fun sharpen(cur: RenderTarget, e: ResolvedEffect, ctx: Ctx): RenderTarget {
        val blurred = blur(copy(cur), e.f("radius"), ctx)
        val r = pass("fx/sharpen.frag", cur) { p ->
            setCommon(p, e, ctx)
            bindTexture2D(1, blurred.texture)
            p.set1i("u_blurred", 1)
            p.set1f("u_amount", e.f("amount"))
        }
        res.pool.release(blurred)
        return r
    }

    /** Bloom: bright areas blurred at three radii, weighted by the falloff and added back. */
    private fun deepGlow(cur: RenderTarget, e: ResolvedEffect, ctx: Ctx): RenderTarget {
        val bright = pass("fx/threshold.frag", copy(cur)) { p -> setCommon(p, e, ctx); p.set1f("u_threshold", e.f("threshold")) }
        val radius = e.f("radius")
        val b1 = blur(copy(bright), radius * 0.15f, ctx)
        val b2 = if (ctx.settings.preview) null else blur(copy(bright), radius * 0.45f, ctx)
        val b3 = blur(bright, radius, ctx)
        val falloff = e.f("falloff")
        var w = floatArrayOf(1f, if (b2 != null) 1f - falloff * 0.5f else 0f, 1f - falloff * 0.8f)
        w = w.map { it / w.sum() }.toFloatArray()
        val color = e.color("color")
        val r = pass("fx/deepGlowCombine.frag", cur) { p ->
            setCommon(p, e, ctx)
            bindTexture2D(1, b1.texture); p.set1i("u_blur1", 1)
            bindTexture2D(2, (b2 ?: b1).texture); p.set1i("u_blur2", 2)
            bindTexture2D(3, b3.texture); p.set1i("u_blur3", 3)
            p.set3f("u_weights", w[0], w[1], w[2])
            p.set1f("u_intensity", e.f("intensity"))
            p.set4f("u_color", color.r, color.g, color.b, color.a)
        }
        res.pool.release(b1)
        b2?.let(res.pool::release)
        res.pool.release(b3)
        return r
    }

    /** Glass: refracts a copy of the composite below through a height field made from the layer. */
    private fun glass(cur: RenderTarget, e: ResolvedEffect, ctx: Ctx, below: RenderTarget): RenderTarget {
        val backdrop = copy(below)
        val softness = maxOf(e.f("bevel"), if (e.flag("useLuminanceAsHeight")) e.f("heightSoftness") else 0f, 1f)
        val height = blur(copy(cur), softness, ctx)
        val r = pass("fx/glass.frag", cur) { p ->
            setCommon(p, e, ctx)
            bindTexture2D(1, backdrop.texture); p.set1i("u_backdrop", 1)
            bindTexture2D(2, height.texture); p.set1i("u_height", 2)
            p.set1f("u_refraction", e.f("refraction"))
            p.set1f("u_dispersion", e.f("dispersion"))
            p.set1f("u_edgeGlow", e.f("edgeGlow"))
            p.set1f("u_edgeGlowWidth", e.f("edgeGlowWidth") * ctx.scale)
            e.color("edgeGlowColor").let { c -> p.set4f("u_edgeGlowColor", c.r, c.g, c.b, c.a) }
            p.set1f("u_bevel", e.f("bevel") * ctx.scale)
            p.set1f("u_useLuminanceAsHeight", if (e.flag("useLuminanceAsHeight")) 1f else 0f)
            p.set1f("u_heightScale", e.f("heightScale") * ctx.scale)
        }
        res.pool.release(backdrop)
        res.pool.release(height)
        return r
    }

    /** Echo: earlier copies of the layer (oldest first) under the current image. */
    private fun echo(cur: RenderTarget, e: ResolvedEffect, ctx: Ctx): RenderTarget {
        if (e.echoes.isEmpty()) return cur
        val acc = res.pool.acquire()
        for (sample in e.echoes.asReversed()) {
            drawNodeAt(sample.node, acc, ctx, sample.node.opacity * sample.opacity, BlendMode.NORMAL)
        }
        composite(cur, acc, ctx, 1f, BlendMode.NORMAL, Affine2D.IDENTITY, emptyList(), null)
        res.pool.release(cur)
        return acc
    }

    /** Motion effects: resamples the image through the inverse of [m] (composition pixels). */
    private fun transformPass(cur: RenderTarget, m: Affine2D, ctx: Ctx): RenderTarget {
        val inv = m.inverse() ?: return cur
        val w = ctx.scene.width.toFloat()
        val h = ctx.scene.height.toFloat()
        // uv -> composition: x = (u - 0.5)·w, y = (0.5 - v)·h ; composition -> uv is the inverse.
        val toComp = Affine2D(a = w, b = 0f, c = 0f, d = -h, tx = -w / 2f, ty = h / 2f)
        val toUv = toComp.inverse()!!
        val k = toUv * inv * toComp
        return pass("fx/transform.frag", cur) { p ->
            p.setMat3("u_inverse", floatArrayOf(k.a, k.b, 0f, k.c, k.d, 0f, k.tx, k.ty, 1f))
        }
    }

    /** Depth Map: the 3D layer drawn as distance to the camera. */
    private fun drawDepth(n: Node3D, e: ResolvedEffect, target: RenderTarget, ctx: Ctx) {
        target.bind()
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        applyBlend(BlendMode.NORMAL)
        val p = res.shaders.get("mesh.vert", "depth.frag")
        p.use()
        val cam = ctx.scene.camera
        p.setMat4("u_viewProj", cam.viewProjection.m)
        p.set3f("u_cameraPos", cam.position.x, cam.position.y, cam.position.z)
        p.set1f("u_near", e.f("near"))
        p.set1f("u_far", e.f("far"))
        p.set1f("u_gamma", e.f("gamma"))
        p.set1f("u_invert", if (e.flag("invert")) 1f else 0f)
        p.set1f("u_opacity", 1f)
        for ((mesh, model) in meshesOf(n, ctx)) {
            setModel(p, model)
            mesh.draw()
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glFrontFace(GLES30.GL_CCW)
    }

    private fun applyMatte(layer: RenderTarget, matte: MatteRef, ctx: Ctx): RenderTarget {
        val m = res.pool.acquire()
        drawNodeAt(matte.source, m, ctx, matte.source.opacity, BlendMode.NORMAL)
        val result = pass("matte.frag", layer) { p ->
            bindTexture2D(1, m.texture)
            p.set1i("u_matte", 1)
            p.set1i("u_mode", when (matte.mode) { MatteMode.ALPHA -> 0; MatteMode.ALPHA_INVERTED -> 1; MatteMode.LUMA -> 2; MatteMode.LUMA_INVERTED -> 3 })
        }
        res.pool.release(m)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        return result
    }

    // ------------------------------------------------------------------ helpers

    private fun applyBlend(mode: BlendMode) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        when (mode) {
            BlendMode.NORMAL -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.ADD -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.MULTIPLY -> GLES30.glBlendFuncSeparate(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.SCREEN -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    private fun bindTexture2D(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun isIdentity(m: Affine2D): Boolean =
        abs(m.a - 1f) < 1e-4f && abs(m.d - 1f) < 1e-4f && abs(m.b) < 1e-4f && abs(m.c) < 1e-4f && abs(m.tx) < 1e-3f && abs(m.ty) < 1e-3f

    companion object {
        private val IDENTITY = Mat4.identityArray()

        /** Distances (pixels) are encoded relative to this for depth of field. */
        private const val DOF_FAR = 60_000f
    }
}
