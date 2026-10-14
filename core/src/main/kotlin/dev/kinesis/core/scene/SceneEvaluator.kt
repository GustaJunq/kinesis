package dev.kinesis.core.scene

import dev.kinesis.core.anim.at
import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.math.clamp
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightContent
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.TextContent
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Resolves a project at a point in time into a [FrameScene]. Pure and deterministic: the same project and
 * time always produce the same scene, which is what makes preview and export identical.
 */
object SceneEvaluator {

    const val NEAR_PLANE = 10f
    const val FAR_PLANE = 200_000f

    fun evaluate(project: KnsProject, time: Double): FrameScene {
        val comp = project.composition
        val camera = evaluateCamera(project, time)
        val (ambient, lights) = evaluateLights(project, time)
        val nodes = evaluateContainer(project.layers, time, Affine2D.IDENTITY, camera, comp)
        return FrameScene(time, comp.width, comp.height, comp.background, camera, ambient, lights, nodes)
    }

    // ------------------------------------------------------------------ containers

    private fun evaluateContainer(
        layers: List<Layer>,
        containerTime: Double,
        containerMatrix: Affine2D,
        camera: CameraState,
        comp: Composition,
    ): List<RenderNode> {
        val byId = layers.associateBy { it.id }
        // Layers used as track mattes are hidden; they are only drawn into the matte.
        val matteSources = layers.mapNotNullTo(HashSet()) { l -> l.matte?.layer?.takeIf { l.visible } }
        val result = ArrayList<RenderNode>()
        for (layer in layers) {
            if (!layer.type.isVisual || !layer.isActiveAt(containerTime) || layer.id in matteSources) continue
            var matte: MatteRef? = null
            val m = layer.matte
            if (m != null) {
                val source = byId[m.layer]
                val inverted = m.mode == MatteMode.ALPHA_INVERTED || m.mode == MatteMode.LUMA_INVERTED
                val node = if (source != null && containerTime >= source.start && containerTime < source.end) {
                    evaluateLayer(source, byId, containerTime, containerMatrix, camera, comp, null)
                } else {
                    null
                }
                // An absent matte hides the layer; an absent inverted matte reveals all of it.
                if (node == null && !inverted) continue
                if (node != null) matte = MatteRef(node, m.mode)
            }
            evaluateLayer(layer, byId, containerTime, containerMatrix, camera, comp, matte)?.let { result += it }
        }
        return result
    }

    private fun evaluateLayer(
        layer: Layer,
        siblings: Map<String, Layer>,
        containerTime0: Double,
        containerMatrix: Affine2D,
        camera: CameraState,
        comp: Composition,
        matte: MatteRef?,
    ): RenderNode? {
        val containerTime = posterized(layer, containerTime0)
        val t = layer.localTime(containerTime)
        val transition = Transitions.evaluate(layer, t, comp)
        fun effects(toComp: Affine2D) = resolveEffects(layer.effects, t, toComp) { index, delay, count, decay ->
            // Echo copies: the layer at earlier times, with only the effects placed before the echo.
            val base = layer.copy(effects = layer.effects.take(index))
            (1..count).mapNotNull { k ->
                val ct = containerTime - k * delay
                if (!layer.isActiveAt(ct)) return@mapNotNull null
                evaluateLayer(base, siblings, ct, containerMatrix, camera, comp, null)?.let { EchoSample(it, decay.toDouble().pow(k).toFloat()) }
            }
        }
        return when {
            layer.type.isRendered3D -> evaluate3D(layer, siblings, containerTime, containerMatrix, camera, transition, ::effects)
            layer.type == LayerType.GROUP -> {
                val parent = parentMatrix2D(layer, siblings, containerTime)
                val world = containerMatrix * parent * localMatrix2D(layer, t)
                val pivot = world.apply(layer.transform.anchor.at(t))
                val matrix = transition.matrix(pivot) * world
                val children = evaluateContainer(layer.layers, t, matrix, camera, comp)
                GroupNode(
                    layerId = layer.id,
                    matrix = matrix,
                    opacity = (layer.transform.opacity.at(t) * transition.opacity * wipeFallback(transition)).clamp(0f, 1f),
                    blendMode = layer.blendMode,
                    effects = effects(matrix),
                    motionBlur = layer.motionBlur,
                    transitionBlur = transition.blur,
                    masks = resolveMasks(layer.masks, t),
                    matte = matte,
                    children = children,
                )
            }
            layer.type.is2DVisual -> {
                val content = content2D(layer, t) ?: return null
                val parent = parentMatrix2D(layer, siblings, containerTime)
                val world = containerMatrix * parent * localMatrix2D(layer, t)
                val pivot = world.apply(layer.transform.anchor.at(t))
                val matrix = transition.matrix(pivot) * world
                Node2D(
                    layerId = layer.id,
                    layerType = layer.type,
                    matrix = matrix,
                    opacity = (layer.transform.opacity.at(t) * transition.opacity).clamp(0f, 1f),
                    blendMode = layer.blendMode,
                    effects = effects(matrix),
                    motionBlur = layer.motionBlur,
                    transitionBlur = transition.blur,
                    masks = resolveMasks(layer.masks, t),
                    matte = matte,
                    wipe = transition.wipe,
                    content = content,
                )
            }
            else -> null
        }
    }

    private fun evaluate3D(
        layer: Layer,
        siblings: Map<String, Layer>,
        containerTime: Double,
        containerMatrix: Affine2D,
        camera: CameraState,
        transition: TransitionState,
        effects: (Affine2D) -> List<ResolvedEffect>,
    ): RenderNode? {
        val t = layer.localTime(containerTime)
        val world = parentMatrix3D(layer, siblings, containerTime) * localMatrix3D(layer, t)
        val (primitive, assetId, size, material) = when (layer.type) {
            LayerType.OBJECT3D -> {
                val o = layer.object3d ?: return null
                if (o.primitive == Primitive.EMPTY) return null
                Quad(o.primitive, null, o.size.at(t), resolveMaterial(o.material, t))
            }
            LayerType.MODEL3D -> {
                val m = layer.model3d ?: return null
                Quad(null, m.asset, m.size.at(t), m.material?.let { resolveMaterial(it, t) })
            }
            else -> return null
        }
        val model = world * Mat4.scale(size, size, size)
        val pivot = camera.project(world.transformPoint(Vec3.ZERO)) ?: Vec2.ZERO
        return Node3D(
            layerId = layer.id,
            layerType = layer.type,
            model = model,
            primitive = primitive,
            modelAssetId = assetId,
            material = material,
            opacity = (layer.transform3d.opacity.at(t) * transition.opacity * wipeFallback(transition)).clamp(0f, 1f),
            blendMode = layer.blendMode,
            effects = effects(containerMatrix * Affine2D.translate(pivot)),
            motionBlur = layer.motionBlur,
            transitionBlur = transition.blur,
            post = containerMatrix * transition.matrix(pivot),
        )
    }

    /** Wipes need content bounds; groups and 3D layers fade instead. */
    private fun wipeFallback(t: TransitionState): Float = t.wipe?.progress ?: 1f

    private data class Quad(val primitive: Primitive?, val asset: String?, val size: Float, val material: ResolvedMaterial?)

    // ------------------------------------------------------------------ transforms

    fun localMatrix2D(layer: Layer, t: Double): Affine2D {
        val tr = layer.transform
        return Affine2D.layer(tr.position.at(t), tr.anchor.at(t), tr.scale.at(t), tr.rotation.at(t))
    }

    fun localMatrix3D(layer: Layer, t: Double): Mat4 {
        val tr = layer.transform3d
        return Mat4.layer(tr.position.at(t), tr.anchor.at(t), tr.scale.at(t), tr.rotation.at(t))
    }

    /** Product of the parents' local matrices (outermost first). Parents are evaluated at their own local time. */
    fun parentMatrix2D(layer: Layer, siblings: Map<String, Layer>, containerTime: Double): Affine2D {
        var m = Affine2D.IDENTITY
        for (p in parentChain(layer, siblings)) {
            if (!p.type.is2DVisual) break
            m = localMatrix2D(p, p.localTime(containerTime)) * m
        }
        return m
    }

    fun parentMatrix3D(layer: Layer, siblings: Map<String, Layer>, containerTime: Double): Mat4 {
        var m = Mat4.identity()
        for (p in parentChain(layer, siblings)) {
            if (!p.type.isRendered3D) break
            m = localMatrix3D(p, p.localTime(containerTime)) * m
        }
        return m
    }

    private fun parentChain(layer: Layer, siblings: Map<String, Layer>): List<Layer> {
        val chain = ArrayList<Layer>()
        val seen = hashSetOf(layer.id)
        var cur = layer.parent?.let { siblings[it] }
        while (cur != null && seen.add(cur.id)) {
            chain += cur
            cur = cur.parent?.let { siblings[it] }
        }
        return chain
    }

    // ------------------------------------------------------------------ content

    private fun content2D(layer: Layer, t: Double): Content2D? = when (layer.type) {
        LayerType.TEXT -> textContent(layer.text ?: TextContent(), t)
        LayerType.SHAPE -> shapeContent(layer.shape ?: ShapeContent(), t)
        LayerType.IMAGE -> layer.image?.let { Content2D.Image(it.asset) }
        LayerType.VIDEO -> layer.video?.let { Content2D.Video(it.asset, it.sourceTime(t).coerceAtLeast(0.0)) }
        else -> null
    }

    private fun textContent(c: TextContent, t: Double) = Content2D.Text(
        content = c.content,
        font = c.font,
        fontSize = c.fontSize.at(t).coerceAtLeast(1f),
        color = c.color.at(t),
        align = c.align,
        lineHeight = c.lineHeight,
        letterSpacing = c.letterSpacing.at(t),
        maxWidth = c.maxWidth,
        strokeColor = c.stroke?.takeIf { it.enabled }?.color?.at(t),
        strokeWidth = c.stroke?.takeIf { it.enabled }?.width?.at(t)?.coerceAtLeast(0f) ?: 0f,
        animators = c.animators,
        localTime = t,
    )

    private fun shapeContent(s: ShapeContent, t: Double) = Content2D.Shape(
        kind = s.kind,
        size = s.size.at(t).let { Vec2(it.x.coerceAtLeast(0f), it.y.coerceAtLeast(0f)) },
        roundness = s.roundness.at(t).coerceAtLeast(0f),
        sides = s.sides.at(t).roundToInt().coerceIn(3, 64),
        innerRatio = s.innerRatio.at(t).clamp(0f, 1f),
        path = s.path,
        fillEnabled = s.fill.enabled,
        fillColor = s.fill.color.at(t),
        gradient = s.fill.gradient,
        strokeEnabled = s.stroke.enabled,
        strokeColor = s.stroke.color.at(t),
        strokeWidth = s.stroke.width.at(t).coerceAtLeast(0f),
        cap = s.stroke.cap,
        join = s.stroke.join,
        trimStart = s.trim.start.at(t).clamp(0f, 1f),
        trimEnd = s.trim.end.at(t).clamp(0f, 1f),
        trimOffset = s.trim.offset.at(t),
    )

    private fun resolveMaterial(m: Material, t: Double) = ResolvedMaterial(
        color = m.color.at(t),
        metallic = m.metallic.at(t).clamp(0f, 1f),
        roughness = m.roughness.at(t).clamp(0.02f, 1f),
        emissive = m.emissive.at(t),
        textureAssetId = m.texture,
    )

    /**
     * Evaluates the enabled effects at layer-local time [t]. [toComp] maps layer-local pixels to composition pixels
     * (used for `point` parameters). [echoes] produces the copies of the `echo` effect at [index] in the list.
     */
    fun resolveEffects(
        effects: List<Effect>,
        t: Double,
        toComp: Affine2D = Affine2D.IDENTITY,
        echoes: (index: Int, delay: Double, count: Int, decay: Float) -> List<EchoSample> = { _, _, _, _ -> emptyList() },
    ): List<ResolvedEffect> {
        val origin = toComp.apply(Vec2.ZERO)
        val out = ArrayList<ResolvedEffect>()
        effects.forEachIndexed { index, e ->
            if (!e.enabled) return@forEachIndexed
            val spec = EffectRegistry.get(e.type) ?: return@forEachIndexed
            val values = HashMap<String, Any>()
            for (p in spec.params) {
                values[p.name] = when (p.type) {
                    ParamType.NUMBER -> p.clamp(e.number(p.name).at(t))
                    ParamType.INTEGER -> p.clamp(e.number(p.name).at(t).roundToInt().toFloat())
                    ParamType.COLOR -> e.color(p.name).at(t)
                    ParamType.POINT -> toComp.apply(e.point(p.name).at(t))
                    ParamType.BOOLEAN -> e.flag(p.name)
                    ParamType.CHOICE -> e.choice(p.name)
                    ParamType.STOPS -> e.stops(p.name).sortedBy { it.offset }
                }
            }
            var r = ResolvedEffect(e.type, values, t, origin)
            when (e.type) {
                "echo" -> r = ResolvedEffect(e.type, values, t, origin, echoes = echoes(index, r.f("delay").toDouble(), r.i("count"), r.f("decay")))
                else -> EffectMotion.transform(r)?.let { m -> r = ResolvedEffect(e.type, values, t, origin, transform = m) }
            }
            out += r
        }
        return out
    }

    /** `posterizeTime`: the whole layer is evaluated at the start of its current low-frame-rate frame. */
    private fun posterized(layer: Layer, containerTime: Double): Double {
        val e = layer.effects.firstOrNull { it.enabled && it.type == "posterizeTime" } ?: return containerTime
        val t = layer.localTime(containerTime)
        val fps = EffectRegistry.require("posterizeTime").param("fps").clamp(e.number("fps").at(t)).toDouble()
        val quantized = floor(t * fps + 1e-6) / fps
        return containerTime - (t - quantized)
    }

    private fun resolveMasks(masks: List<Mask>, t: Double): List<ResolvedMask> =
        masks.filter { it.enabled }.take(Mask.MAX_MASKS).map { m ->
            ResolvedMask(
                shape = m.shape,
                mode = m.mode,
                center = m.position.at(t),
                size = m.size.at(t).let { Vec2(it.x.coerceAtLeast(0f), it.y.coerceAtLeast(0f)) },
                roundness = m.roundness.at(t).coerceAtLeast(0f),
                feather = m.feather.at(t).coerceAtLeast(0f),
                opacity = m.opacity.at(t).clamp(0f, 1f),
                inverted = m.inverted,
            )
        }

    // ------------------------------------------------------------------ camera & lights

    /** Every camera/light layer active at [time], with its container time and siblings (for parenting). */
    private fun activeLayersOfType(project: KnsProject, time: Double, type: LayerType): List<Triple<Layer, Double, Map<String, Layer>>> {
        val out = ArrayList<Triple<Layer, Double, Map<String, Layer>>>()
        fun visit(layers: List<Layer>, containerTime: Double) {
            val byId by lazy { layers.associateBy { it.id } }
            for (l in layers) {
                if (!l.isActiveAt(containerTime)) continue
                if (l.type == type) out += Triple(l, containerTime, byId)
                if (l.type == LayerType.GROUP) visit(l.layers, l.localTime(containerTime))
            }
        }
        visit(project.layers, time)
        return out
    }

    fun defaultCameraDistance(height: Int, fov: Float): Float =
        (height / 2f) / tan(Math.toRadians(fov / 2.0)).toFloat()

    fun evaluateCamera(project: KnsProject, time: Double): CameraState {
        val comp = project.composition
        val active = activeLayersOfType(project, time, LayerType.CAMERA).lastOrNull()
        var fov = CameraContent.DEFAULT_FOV
        var position = Vec3(0f, 0f, -defaultCameraDistance(comp.height, fov))
        var target = Vec3.ZERO
        if (active != null) {
            val (layer, containerTime, siblings) = active
            val cam = layer.camera ?: CameraContent()
            val t = layer.localTime(containerTime)
            fov = cam.fov.at(t).clamp(1f, 170f)
            val parent = parentMatrix3D(layer, siblings, containerTime)
            position = parent.transformPoint(cam.position?.at(t) ?: Vec3(0f, 0f, -defaultCameraDistance(comp.height, fov)))
            target = parent.transformPoint(cam.target.at(t))
        }
        val view = Mat4.lookAt(position, target, Vec3(0f, -1f, 0f))
        val projection = Mat4.perspective(fov, comp.aspect, NEAR_PLANE, FAR_PLANE)
        return CameraState(position, target, fov, view, projection, comp.width, comp.height)
    }

    fun evaluateLights(project: KnsProject, time: Double): Pair<Color, List<LightState>> {
        val active = activeLayersOfType(project, time, LayerType.LIGHT)
        if (active.isEmpty()) return DEFAULT_AMBIENT to DEFAULT_LIGHTS
        var ar = 0f; var ag = 0f; var ab = 0f
        val lights = ArrayList<LightState>()
        for ((layer, containerTime, siblings) in active) {
            val light = layer.light ?: LightContent()
            val t = layer.localTime(containerTime)
            val c = light.color.at(t)
            val intensity = light.intensity.at(t).coerceAtLeast(0f)
            val parent = parentMatrix3D(layer, siblings, containerTime)
            if (light.kind == LightKind.AMBIENT) {
                val lin = c.toLinear()
                ar += lin.r * intensity; ag += lin.g * intensity; ab += lin.b * intensity
                continue
            }
            if (lights.size >= LightContent.MAX_LIGHTS) continue
            val pos = parent.transformPoint(light.position.at(t))
            val target = parent.transformPoint(light.target.at(t))
            lights += LightState(
                kind = light.kind,
                color = c,
                intensity = intensity,
                position = pos,
                direction = (target - pos).normalized(),
                radius = light.radius.at(t).coerceAtLeast(1f),
            )
        }
        return Color(ar, ag, ab) to lights
    }

    /** Linear ambient used when the project has no light layers. */
    val DEFAULT_AMBIENT = Color(0.22f, 0.23f, 0.26f)
    val DEFAULT_LIGHTS = listOf(
        LightState(LightKind.DIRECTIONAL, Color(1.0f, 0.97f, 0.92f), 1.1f, Vec3(-800f, -1000f, -1200f), Vec3(800f, 1000f, 1200f).normalized(), 1f),
        LightState(LightKind.DIRECTIONAL, Color(0.6f, 0.7f, 0.85f), 0.45f, Vec3(1000f, 200f, -600f), Vec3(-1000f, -200f, 600f).normalized(), 1f),
    )

    // ------------------------------------------------------------------ queries for editors

    /** Matrix mapping a 2D layer's parent space (where its `position` lives) to composition pixels. */
    fun parentSpace2D(project: KnsProject, layerId: String, time: Double): Affine2D? {
        var containerMatrix = Affine2D.IDENTITY
        var containerTime = time
        var layers = project.layers
        val path = dev.kinesis.core.model.LayerTree.ancestors(project, layerId) + layerId
        for ((i, id) in path.withIndex()) {
            val byId = layers.associateBy { it.id }
            val layer = byId[id] ?: return null
            val parent = parentMatrix2D(layer, byId, containerTime)
            if (i == path.lastIndex) return containerMatrix * parent
            containerMatrix = containerMatrix * parent * localMatrix2D(layer, layer.localTime(containerTime))
            containerTime = layer.localTime(containerTime)
            layers = layer.layers
        }
        return null
    }

    /** World matrix of a 3D layer's parent space (where its `transform3d.position` lives). */
    fun parentSpace3D(project: KnsProject, layerId: String, time: Double): Mat4? {
        val loc = dev.kinesis.core.model.LayerTree.locate(project, layerId) ?: return null
        val siblings = dev.kinesis.core.model.LayerTree.container(project, loc.containerId)
        val layer = siblings.firstOrNull { it.id == layerId } ?: return null
        val containerTime = time - dev.kinesis.core.model.LayerTree.containerOffset(project, layerId)
        return parentMatrix3D(layer, siblings.associateBy { it.id }, containerTime)
    }
}
