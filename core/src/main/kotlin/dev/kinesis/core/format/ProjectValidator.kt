package dev.kinesis.core.format

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.expr.ExpressionEngine
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectTarget
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightContent
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.ShapeKind

enum class Severity { ERROR, WARNING }

/** A problem found by [ProjectValidator]. [code] values are stable and shared with the `knsproj` CLI. */
data class ValidationIssue(val severity: Severity, val code: String, val path: String, val message: String) {
    override fun toString() = "${severity.name.lowercase()} [$code] $path: $message"
}

data class ValidationReport(val issues: List<ValidationIssue>) {
    val errors get() = issues.filter { it.severity == Severity.ERROR }
    val warnings get() = issues.filter { it.severity == Severity.WARNING }
    val isValid get() = errors.isEmpty()
}

/**
 * Semantic validation that JSON Schema cannot express: unique ids, references, hierarchy cycles,
 * timing and content consistency. Mirrored by `tools/knsproj/semantic.py`.
 */
object ProjectValidator {

    /**
     * @param availableFiles entry names present in the container (e.g. `assets/logo.png`), or null to skip file checks.
     */
    fun validate(project: KnsProject, availableFiles: Set<String>? = null): ValidationReport {
        val issues = ArrayList<ValidationIssue>()
        fun err(code: String, path: String, msg: String) { issues += ValidationIssue(Severity.ERROR, code, path, msg) }
        fun warn(code: String, path: String, msg: String) { issues += ValidationIssue(Severity.WARNING, code, path, msg) }

        // Composition
        val comp = project.composition
        if (comp.width !in 16..8192 || comp.height !in 16..8192) err("composition.size", "composition", "width and height must be between 16 and 8192 pixels")
        if (comp.width % 2 != 0 || comp.height % 2 != 0) warn("composition.oddSize", "composition", "odd width/height will be rounded down to even numbers when exporting MP4")
        if (!(comp.fps >= 1.0 && comp.fps <= 120.0)) err("composition.fps", "composition.fps", "fps must be between 1 and 120")
        if (!(comp.duration > 0.0 && comp.duration <= 3600.0)) err("composition.duration", "composition.duration", "duration must be between 0 and 3600 seconds")
        if (comp.motionBlur.samples !in 1..64) err("composition.motionBlur", "composition.motionBlur.samples", "samples must be between 1 and 64")
        if (comp.motionBlur.shutterAngle !in 0f..720f) err("composition.motionBlur", "composition.motionBlur.shutterAngle", "shutterAngle must be between 0 and 720")

        // Assets
        val assetsById = HashMap<String, AssetType>()
        project.assets.forEachIndexed { i, asset ->
            val path = "assets[$i]"
            if (asset.id.isBlank()) err("asset.id", "$path.id", "asset id must not be empty")
            if (assetsById.put(asset.id, asset.type) != null) err("asset.duplicateId", "$path.id", "duplicate asset id '${asset.id}'")
            val normalized = KnsprojArchive.normalizeEntryName(asset.path)
            if (normalized == null || !normalized.startsWith(KnsprojArchive.ASSETS_DIR) || normalized != asset.path) {
                err("asset.path", "$path.path", "asset path must be a relative path inside 'assets/' (got '${asset.path}')")
            } else if (availableFiles != null && asset.path !in availableFiles) {
                err("asset.missingFile", "$path.path", "file '${asset.path}' is not present in the project")
            }
        }

        // Layers (ids are unique across the whole tree)
        val seenIds = HashSet<String>()
        var cameraCount = 0
        var lightCount = 0

        fun checkAnimatable(anim: Animatable<*>, path: String, layerDuration: Double) {
            anim.expression?.let { src ->
                if (src.length > ExpressionEngine.MAX_SOURCE) {
                    err("expression.length", "$path.expression", "expression is longer than ${ExpressionEngine.MAX_SOURCE} characters")
                } else if (src.isNotBlank()) {
                    ExpressionEngine.check(src)?.let { e -> err("expression.syntax", "$path.expression", e.message ?: "invalid expression") }
                }
            }
            if (!anim.isAnimated) return
            val times = anim.keyframes.map { it.time }
            for (i in 1 until times.size) {
                if (times[i] - times[i - 1] < Animatable.KEYFRAME_EPSILON) {
                    err("keyframe.order", "$path.keyframes[$i]", "keyframe times must be strictly increasing")
                }
            }
            // Keyframes outside the layer are legal (trimmed layers keep them), but an animation that lies
            // entirely outside it is never visible and usually a mistake.
            if (times.first() > layerDuration + Animatable.KEYFRAME_EPSILON || times.last() < -Animatable.KEYFRAME_EPSILON) {
                warn("keyframe.outOfRange", "$path.keyframes", "all keyframes are outside the layer's time range (0..$layerDuration s); times are relative to the layer start")
            }
        }

        fun checkAssetRef(id: String?, expected: AssetType, path: String) {
            if (id == null) return
            val type = assetsById[id]
            when {
                type == null -> err("layer.missingAsset", path, "asset '$id' does not exist")
                type != expected -> err("layer.assetType", path, "asset '$id' is a ${type.name.lowercase()}, expected ${expected.name.lowercase()}")
            }
        }

        // Environment of 3D layers
        with(comp.environment) {
            checkAssetRef(asset, AssetType.IMAGE, "composition.environment.asset")
            if (intensity !in 0f..10f) err("composition.environment", "composition.environment.intensity", "intensity must be between 0 and 10")
        }

        fun visit(layers: List<Layer>, base: String, depth: Int) {
            if (depth > 16) {
                err("group.depth", base, "groups are nested too deeply (max 16 levels)")
                return
            }
            val siblings = layers.associateBy { it.id }
            layers.forEachIndexed { i, layer ->
                val p = "$base[$i]"
                if (layer.id.isBlank()) err("layer.id", "$p.id", "layer id must not be empty")
                if (!seenIds.add(layer.id)) err("layer.duplicateId", "$p.id", "duplicate layer id '${layer.id}'")
                if (layer.end <= 0.0) warn("layer.start", "$p.start", "the layer ends before time 0 and is never visible")
                if (!(layer.duration > 0.0)) err("layer.duration", "$p.duration", "duration must be > 0")

                // Content object must match the type
                val contentOk = when (layer.type) {
                    LayerType.TEXT -> layer.text != null
                    LayerType.SHAPE -> layer.shape != null
                    LayerType.IMAGE -> layer.image != null
                    LayerType.VIDEO -> layer.video != null
                    LayerType.AUDIO -> layer.audio != null
                    LayerType.OBJECT3D -> layer.object3d != null
                    LayerType.MODEL3D -> layer.model3d != null
                    LayerType.CAMERA -> layer.camera != null
                    LayerType.LIGHT -> layer.light != null
                    LayerType.GROUP, LayerType.NULL -> true
                }
                val key = layer.type.name.lowercase()
                if (!contentOk) err("layer.missingContent", p, "a '$key' layer needs a \"$key\" object")
                if (layer.type != LayerType.GROUP && layer.layers.isNotEmpty()) err("layer.children", "$p.layers", "only group layers can have child layers")

                // Parent
                layer.parent?.let { parentId ->
                    val parent = siblings[parentId]
                    when {
                        parentId == layer.id -> err("layer.parentSelf", "$p.parent", "a layer cannot be its own parent")
                        parent == null -> err("layer.parentMissing", "$p.parent", "parent '$parentId' must be a layer in the same group")
                        layer.is3DLayer != parent.is3DLayer ->
                            err("layer.parentKind", "$p.parent", "2D layers can only be parented to 2D layers and 3D layers to 3D layers")
                        parent.type == LayerType.CAMERA || parent.type == LayerType.LIGHT ->
                            err("layer.parentKind", "$p.parent", "cameras and lights cannot be parents")
                        parent.type == LayerType.AUDIO -> err("layer.parentKind", "$p.parent", "audio layers cannot be parents")
                    }
                }

                // Matte
                layer.matte?.let { matte ->
                    val source = siblings[matte.layer]
                    when {
                        matte.layer == layer.id -> err("layer.matteSelf", "$p.matte.layer", "a layer cannot be its own matte")
                        source == null -> err("layer.matteMissing", "$p.matte.layer", "matte layer '${matte.layer}' must be in the same group")
                        !source.type.isVisual -> err("layer.matteKind", "$p.matte.layer", "matte source must be a visual layer")
                        source.matte?.layer == layer.id -> err("layer.matteCycle", "$p.matte.layer", "two layers cannot be each other's matte")
                    }
                    if (!layer.type.is2DVisual) err("layer.matteKind", "$p.matte", "track mattes apply to 2D visual layers only")
                }

                // Transforms / generic animatables
                val d = layer.duration
                with(layer.transform) {
                    checkAnimatable(anchor, "$p.transform.anchor", d); checkAnimatable(position, "$p.transform.position", d)
                    checkAnimatable(scale, "$p.transform.scale", d); checkAnimatable(rotation, "$p.transform.rotation", d)
                    checkAnimatable(opacity, "$p.transform.opacity", d)
                }
                with(layer.transform3d) {
                    checkAnimatable(anchor, "$p.transform3d.anchor", d); checkAnimatable(position, "$p.transform3d.position", d)
                    checkAnimatable(rotation, "$p.transform3d.rotation", d); checkAnimatable(scale, "$p.transform3d.scale", d)
                    checkAnimatable(opacity, "$p.transform3d.opacity", d)
                }

                // Type specific
                layer.text?.let { t ->
                    checkAnimatable(t.fontSize, "$p.text.fontSize", d); checkAnimatable(t.color, "$p.text.color", d)
                    checkAnimatable(t.letterSpacing, "$p.text.letterSpacing", d)
                    checkAssetRef(t.font.asset, AssetType.FONT, "$p.text.font.asset")
                    if (t.font.weight !in 100..900) err("text.weight", "$p.text.font.weight", "font weight must be between 100 and 900")
                    t.propertyAnimators.forEachIndexed { ai, a ->
                        val ap = "$p.text.propertyAnimators[$ai]"
                        checkAnimatable(a.amount, "$ap.amount", d)
                        a.position?.let { checkAnimatable(it, "$ap.position", d) }
                        a.scale?.let { checkAnimatable(it, "$ap.scale", d) }
                        a.rotation?.let { checkAnimatable(it, "$ap.rotation", d) }
                        a.opacity?.let { checkAnimatable(it, "$ap.opacity", d) }
                        if (a.position == null && a.scale == null && a.rotation == null && a.opacity == null) {
                            warn("text.propertyAnimator", ap, "the animator has no property (position, scale, rotation or opacity), so it changes nothing")
                        }
                    }
                    t.animators.forEachIndexed { ai, a ->
                        if (a.unitDuration <= 0.0) err("text.animator", "$p.text.animators[$ai].unitDuration", "unitDuration must be > 0")
                        if (a.stagger < 0.0) err("text.animator", "$p.text.animators[$ai].stagger", "stagger must be >= 0")
                    }
                }
                layer.shape?.let { s ->
                    checkAnimatable(s.size, "$p.shape.size", d); checkAnimatable(s.roundness, "$p.shape.roundness", d)
                    checkAnimatable(s.fill.color, "$p.shape.fill.color", d); checkAnimatable(s.stroke.color, "$p.shape.stroke.color", d)
                    checkAnimatable(s.stroke.width, "$p.shape.stroke.width", d)
                    checkAnimatable(s.trim.start, "$p.shape.trim.start", d); checkAnimatable(s.trim.end, "$p.shape.trim.end", d)
                    if (s.kind == ShapeKind.PATH && (s.path == null || s.path.points.size < 2)) {
                        err("shape.path", "$p.shape.path", "path shapes need at least 2 points")
                    }
                    s.fill.gradient?.let { g -> if (g.stops.size < 2) err("shape.gradient", "$p.shape.fill.gradient.stops", "a gradient needs at least 2 stops") }
                }
                checkAssetRef(layer.image?.asset, AssetType.IMAGE, "$p.image.asset")
                layer.video?.let { v ->
                    checkAssetRef(v.asset, AssetType.VIDEO, "$p.video.asset")
                    if (!(v.speed > 0.0)) err("media.speed", "$p.video.speed", "speed must be > 0")
                    if (v.sourceStart < 0.0) err("media.sourceStart", "$p.video.sourceStart", "sourceStart must be >= 0")
                    checkAnimatable(v.volume, "$p.video.volume", d)
                }
                layer.audio?.let { a ->
                    checkAssetRef(a.asset, AssetType.AUDIO, "$p.audio.asset")
                    if (!(a.speed > 0.0)) err("media.speed", "$p.audio.speed", "speed must be > 0")
                    if (a.sourceStart < 0.0) err("media.sourceStart", "$p.audio.sourceStart", "sourceStart must be >= 0")
                    checkAnimatable(a.volume, "$p.audio.volume", d)
                }
                layer.object3d?.let { o ->
                    checkAnimatable(o.size, "$p.object3d.size", d)
                    checkAssetRef(o.material.texture, AssetType.IMAGE, "$p.object3d.material.texture")
                    o.text?.let { t ->
                        val tp = "$p.object3d.text"
                        if (o.primitive != Primitive.TEXT) warn("object3d.text", tp, "\"text\" is only used when primitive is \"text\"")
                        checkAssetRef(t.font.asset, AssetType.FONT, "$tp.font.asset")
                        if (t.font.weight !in 100..900) err("text.weight", "$tp.font.weight", "font weight must be between 100 and 900")
                        if (t.fontSize !in 4f..2000f) err("text3d.range", "$tp.fontSize", "fontSize must be between 4 and 2000")
                        if (t.depth !in 1f..2000f) err("text3d.range", "$tp.depth", "depth must be between 1 and 2000")
                        if (t.bevel < 0f || t.bevel > t.depth / 2f) err("text3d.range", "$tp.bevel", "bevel must be between 0 and half the depth")
                        if (!(t.lineHeight > 0f)) err("text3d.range", "$tp.lineHeight", "lineHeight must be > 0")
                    }
                }
                layer.model3d?.let { m ->
                    checkAssetRef(m.asset, AssetType.MODEL, "$p.model3d.asset")
                    checkAssetRef(m.material?.texture, AssetType.IMAGE, "$p.model3d.material.texture")
                }
                layer.camera?.let { c ->
                    c.dof?.let { f ->
                        checkAnimatable(f.focusDistance, "$p.camera.dof.focusDistance", d)
                        checkAnimatable(f.range, "$p.camera.dof.range", d)
                        checkAnimatable(f.blur, "$p.camera.dof.blur", d)
                    }
                    c.position?.let { checkAnimatable(it, "$p.camera.position", d) }
                    checkAnimatable(c.target, "$p.camera.target", d); checkAnimatable(c.rotation, "$p.camera.rotation", d)
                    checkAnimatable(c.fov, "$p.camera.fov", d)
                }
                if (layer.nullObject != null && layer.type != LayerType.NULL) warn("layer.nullContent", "$p.null", "the \"null\" object is only used by null layers")
                layer.light?.let { l ->
                    checkAnimatable(l.shadowDarkness, "$p.light.shadowDarkness", d)
                    if (l.shadowSoftness !in 0f..8f) err("light.shadow", "$p.light.shadowSoftness", "shadowSoftness must be between 0 and 8")
                    if (l.castShadows && l.kind == LightKind.AMBIENT) warn("light.shadow", "$p.light.castShadows", "ambient lights do not cast shadows")
                }
                if (layer.video?.alphaPacked == true && layer.type != LayerType.VIDEO) warn("media.alphaPacked", "$p", "alphaPacked only applies to video layers")
                if (layer.type == LayerType.CAMERA) cameraCount++
                if (layer.type == LayerType.LIGHT) lightCount++

                if (layer.masks.size > Mask.MAX_MASKS) warn("layer.masks", "$p.masks", "only the first ${Mask.MAX_MASKS} masks are rendered")
                if (layer.masks.isNotEmpty() && !layer.type.is2DVisual) warn("layer.masks", "$p.masks", "masks apply to 2D visual layers only")
                if (!layer.type.isVisual && (layer.effects.isNotEmpty())) warn("layer.effects", "$p.effects", "effects have no visible result on ${layer.type.name.lowercase()} layers")
                layer.effects.forEachIndexed { ei, e ->
                    val spec = EffectRegistry.get(e.type) ?: return@forEachIndexed
                    val ep = "$p.effects[$ei]"
                    if (spec.target == EffectTarget.LAYER_3D && !layer.type.isRendered3D && layer.type.isVisual) {
                        warn("effect.layerKind", "$ep.type", "${spec.id} only works on object3d and model3d layers; it is ignored here")
                    }
                    for (param in spec.params) {
                        val v = e.params[param.name] ?: continue
                        when (v) {
                            is EffectValue.Num -> {
                                checkAnimatable(v.anim, "$ep.${param.name}", d)
                                fun outside(x: Float) = param.clamp(x) != x
                                val range = "${param.min ?: "-∞"}..${param.max ?: "∞"}"
                                if (!v.anim.isAnimated && outside(v.anim.value)) err("effect.range", "$ep.${param.name}", "${param.name} must be in $range")
                                v.anim.keyframes.forEachIndexed { k, kf ->
                                    if (outside(kf.value)) err("effect.range", "$ep.${param.name}.keyframes[$k].value", "${param.name} must be in $range")
                                }
                            }
                            is EffectValue.Col -> checkAnimatable(v.anim, "$ep.${param.name}", d)
                            is EffectValue.Point -> checkAnimatable(v.anim, "$ep.${param.name}", d)
                            else -> {}
                        }
                    }
                }
                listOf(layer.transitionIn to "transitionIn", layer.transitionOut to "transitionOut").forEach { (t, name) ->
                    if (t != null && !(t.duration > 0.0)) err("layer.transition", "$p.$name.duration", "transition duration must be > 0")
                }

                if (layer.type == LayerType.GROUP) visit(layer.layers, "$p.layers", depth + 1)
            }

            // Parent cycles within this container
            layers.forEach { layer ->
                val seen = HashSet<String>()
                var cur: Layer? = layer
                while (cur?.parent != null) {
                    if (!seen.add(cur.id)) {
                        err("layer.parentCycle", "$base[${layers.indexOf(layer)}].parent", "parent chain of '${layer.id}' contains a cycle")
                        break
                    }
                    cur = siblings[cur.parent!!]
                }
            }
        }
        visit(project.layers, "layers", 0)

        if (cameraCount > 1) warn("camera.multiple", "layers", "several camera layers: the top-most active camera is used")
        if (lightCount > LightContent.MAX_LIGHTS) warn("light.count", "layers", "only ${LightContent.MAX_LIGHTS} lights are used at the same time")

        return ValidationReport(issues)
    }
}
