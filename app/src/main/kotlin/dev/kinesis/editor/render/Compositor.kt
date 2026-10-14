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
import dev.kinesis.core.model.BlendMode
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.MaskMode
import dev.kinesis.core.model.MaskShape
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.scene.Content2D
import dev.kinesis.core.scene.FrameScene
import dev.kinesis.core.scene.GroupNode
import dev.kinesis.core.scene.MatteRef
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.Node3D
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
class RenderResources(assets: AssetManager, val files: AssetFiles) {
    val shaders = ShaderLibrary(assets)
    val quad = UnitQuad()
    val pool = TargetPool()
    val typefaces = TypefaceProvider { files.file(it) }
    val content = ContentTextures(typefaces)
    val images = ImageTextures(files, min(4096, Gl.maxTextureSize()))
    val videos = VideoFrames(files)
    val meshes = MeshCache(files)

    fun release() {
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
)

/**
 * Draws a [FrameScene] into an offscreen target of the output size, in premultiplied alpha.
 * Preview and export use the same code path, so what you see is what you export.
 */
class Compositor(private val res: RenderResources) {
    private var output: RenderTarget? = null

    private class Ctx(
        val project: KnsProject,
        val scene: FrameScene,
        val settings: RenderSettings,
        /** Output pixels per composition pixel. */
        val scale: Float,
        val proj: Mat4,
        val width: Int,
        val height: Int,
    )

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
        val bg = scene.background
        out.clear(bg.r * bg.a, bg.g * bg.a, bg.b * bg.a, 1f)
        drawNodes(scene.nodes, out, ctx)
        GLES30.glDisable(GLES30.GL_BLEND)
        res.videos.endFrame()
        res.content.trim()
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

    private fun drawNodes(nodes: List<RenderNode>, target: RenderTarget, ctx: Ctx) {
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
                draw3DBatch(batch, target, ctx, opacityOverride = null)
                i = j
                continue
            }
            drawNode(n, target, ctx)
            i++
        }
    }

    private fun motionBlurOn(n: RenderNode, ctx: Ctx): Boolean {
        val mb = ctx.project.composition.motionBlur
        return n.motionBlur && mb.enabled && ctx.settings.allowMotionBlur && ctx.settings.motionBlurSamples > 1 && mb.shutterAngle > 0f &&
            !(n is Node2D && n.content is Content2D.Video)
    }

    private fun isBatchable(n: Node3D, ctx: Ctx): Boolean =
        n.effects.isEmpty() && n.blendMode == BlendMode.NORMAL && n.transitionBlur <= 0f && isIdentity(n.post) && !motionBlurOn(n, ctx)

    private fun drawNode(n: RenderNode, target: RenderTarget, ctx: Ctx) {
        if (motionBlurOn(n, ctx)) drawMotionBlurred(n, target, ctx) else drawNodeAt(n, target, ctx, n.opacity, n.blendMode)
    }

    /** Renders [n] into [target] with the given opacity and blend mode. */
    private fun drawNodeAt(n: RenderNode, target: RenderTarget, ctx: Ctx, opacity: Float, blend: BlendMode) {
        when (n) {
            is Node2D -> {
                val offscreen = n.effects.isNotEmpty() || n.matte != null || n.transitionBlur > 0f
                if (!offscreen) {
                    drawContent2D(n, target, ctx, opacity, blend)
                } else {
                    var layer = res.pool.acquire()
                    drawContent2D(n, layer, ctx, 1f, BlendMode.NORMAL)
                    layer = applyEffects(n.effects, n.transitionBlur, layer, ctx)
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
                    layer = applyEffects(n.effects, n.transitionBlur, layer, ctx)
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
                    draw3DBatch(listOf(n), layer, ctx, opacityOverride = 1f)
                    layer = applyEffects(n.effects, n.transitionBlur, layer, ctx)
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
            val sub = if (k == samples / 2 && samples % 2 == 1) n else findNode(SceneEvaluator.evaluate(ctx.project, t).nodes, n.layerId)
            if (sub == null) continue
            val tmp = res.pool.acquire()
            drawNodeAt(sub, tmp, ctx, sub.opacity, BlendMode.NORMAL)
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

    private fun findNode(nodes: List<RenderNode>, id: String): RenderNode? {
        for (n in nodes) {
            if (n.layerId == id) return n
            if (n is GroupNode) findNode(n.children, id)?.let { return it }
        }
        return null
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
                val rect = RectF(-v.width / 2f, -v.height / 2f, v.width / 2f, v.height / 2f)
                target.bind()
                applyBlend(blend)
                val p = res.shaders.get("quad.vert", "quad_oes.frag")
                setupQuad(p, mvp, rect, flipY = true, texMatrix = v.texMatrix, opacity = opacity, premultiplied = true, masks = n.masks, maskMatrix = null, wipe = n.wipe)
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
        masks: List<ResolvedMask>, maskMatrix: Affine2D?, wipe: WipeState?,
    ) {
        p.use()
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
        target.bind()
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        applyBlend(BlendMode.NORMAL)
        val p = res.shaders.get("mesh.vert", "mesh.frag")
        p.use()
        setupLights(p, ctx.scene)
        fun opacityOf(n: Node3D) = opacityOverride ?: n.opacity
        GLES30.glDepthMask(true)
        for (n in nodes) if (opacityOf(n) >= 0.999f) drawMesh(p, n, opacityOf(n))
        val translucent = nodes.filter { opacityOf(it) < 0.999f && opacityOf(it) > 0.001f }
            .sortedByDescending { ctx.scene.camera.depthOf(it.model.transformPoint(Vec3.ZERO)) }
        if (translucent.isNotEmpty()) {
            GLES30.glDepthMask(false)
            for (n in translucent) drawMesh(p, n, opacityOf(n))
            GLES30.glDepthMask(true)
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glFrontFace(GLES30.GL_CCW)
    }

    private fun setupLights(p: Program, scene: FrameScene) {
        val cam = scene.camera
        p.setMat4("u_viewProj", cam.viewProjection.m)
        p.set3f("u_cameraPos", cam.position.x, cam.position.y, cam.position.z)
        val amb = scene.ambient
        p.set3f("u_ambient", amb.r, amb.g, amb.b)
        val luma = 0.2126f * amb.r + 0.7152f * amb.g + 0.0722f * amb.b
        p.set1f("u_envIntensity", (0.25f + luma * 3f).coerceIn(0.25f, 1.6f))
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

    private fun drawMesh(p: Program, n: Node3D, opacity: Float) {
        p.set1f("u_opacity", opacity)
        val primitive = n.primitive
        if (primitive != null) {
            val mat = n.material ?: return
            setModel(p, n.model)
            setMaterial(p, mat.color, mat.metallic, mat.roughness, mat.emissive, mat.textureAssetId?.let { res.images.get(it)?.texture } ?: 0)
            res.meshes.primitive(primitive).draw()
            return
        }
        val model = res.meshes.model(n.modelAssetId ?: return) ?: return
        val matrix = n.model * model.normalization
        setModel(p, matrix)
        val override = n.material
        for (part in model.parts) {
            if (override != null) {
                setMaterial(p, override.color, override.metallic, override.roughness, override.emissive, override.textureAssetId?.let { res.images.get(it)?.texture } ?: part.texture)
            } else {
                val m: ModelMaterial = part.material
                // glTF factors are linear; the shader expects sRGB inputs.
                setMaterial(p, toSrgb(m.baseColor), m.metallic, m.roughness, toSrgb(m.emissive), part.texture)
            }
            part.mesh.draw()
        }
    }

    private fun setModel(p: Program, model: Mat4) {
        p.setMat4("u_model", model.m)
        p.setMat3("u_normalMatrix", model.normalMatrix())
        GLES30.glFrontFace(if (det3(model) < 0f) GLES30.GL_CW else GLES30.GL_CCW)
    }

    private fun setMaterial(p: Program, color: Color, metallic: Float, roughness: Float, emissive: Color, texture: Int) {
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

    private fun applyEffects(effects: List<ResolvedEffect>, transitionBlur: Float, input: RenderTarget, ctx: Ctx): RenderTarget {
        var cur = input
        for (e in effects) {
            cur = when (e) {
                is ResolvedEffect.Blur -> blur(cur, e.radius, ctx)
                is ResolvedEffect.ColorAdjust -> pass("color_adjust.frag", cur) { p ->
                    p.set1f("u_brightness", e.brightness); p.set1f("u_contrast", e.contrast)
                    p.set1f("u_saturation", e.saturation); p.set1f("u_hue", e.hue)
                }
                is ResolvedEffect.Tint -> pass("tint.frag", cur) { p ->
                    p.set4f("u_color", e.color.r, e.color.g, e.color.b, e.color.a); p.set1f("u_amount", e.amount)
                }
                is ResolvedEffect.Invert -> pass("invert.frag", cur) { p -> p.set1f("u_amount", e.amount) }
                is ResolvedEffect.Pixelate -> pass("pixelate.frag", cur) { p ->
                    val px = e.size * ctx.scale
                    p.set2f("u_cell", px / ctx.width, px / ctx.height)
                }
                is ResolvedEffect.ChromaticAberration -> pass("chroma.frag", cur) { p ->
                    p.set2f("u_offset", e.offset.x * ctx.scale / ctx.width, -e.offset.y * ctx.scale / ctx.height)
                }
                is ResolvedEffect.Vignette -> pass("vignette.frag", cur) { p ->
                    p.set1f("u_amount", e.amount); p.set1f("u_softness", e.softness); p.set1f("u_aspect", ctx.width.toFloat() / ctx.height)
                }
                is ResolvedEffect.DropShadow -> {
                    val blurred = blur(copy(cur), e.softness, ctx)
                    val r = pass("shadow.frag", cur) { p ->
                        bindTexture2D(1, blurred.texture)
                        p.set1i("u_blurred", 1)
                        p.set2f("u_offset", e.offset.x * ctx.scale / ctx.width, -e.offset.y * ctx.scale / ctx.height)
                        p.set4f("u_color", e.color.r, e.color.g, e.color.b, e.opacity * e.color.a)
                        p.set1f("u_glow", 0f)
                    }
                    res.pool.release(blurred)
                    r
                }
                is ResolvedEffect.Glow -> {
                    val blurred = blur(copy(cur), e.radius, ctx)
                    val r = pass("shadow.frag", cur) { p ->
                        bindTexture2D(1, blurred.texture)
                        p.set1i("u_blurred", 1)
                        p.set2f("u_offset", 0f, 0f)
                        p.set4f("u_color", e.color.r, e.color.g, e.color.b, e.intensity)
                        p.set1f("u_glow", 1f)
                    }
                    res.pool.release(blurred)
                    r
                }
            }
        }
        if (transitionBlur > 0f) cur = blur(cur, transitionBlur, ctx)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        return cur
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
    }
}
