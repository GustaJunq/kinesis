package dev.kinesis.core.edit

import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.FillSpec
import dev.kinesis.core.model.ImageContent
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightContent
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.model.Model3DContent
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ProjectMeta
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.TextContent
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.model.Transform3D
import kotlin.math.min

/** Creates new projects and sensible default layers for the editor's "add" actions. */
object Factory {

    val PALETTE = listOf(
        Color.parse("#7C5CFF"), Color.parse("#4FD1C5"), Color.parse("#FF6B8B"),
        Color.parse("#FFC857"), Color.parse("#5EA8FF"), Color.parse("#9BE564"),
    )

    fun newProject(title: String, width: Int, height: Int, fps: Double, duration: Double, background: Color, nowIso: String?, generator: String) =
        KnsProject(
            generator = generator,
            meta = ProjectMeta(title = title, createdAt = nowIso, modifiedAt = nowIso),
            composition = Composition(width = width, height = height, fps = fps, duration = duration, background = background),
        )

    /** A new layer spans the rest of the composition from [start] (at least 1 s). */
    private fun defaultDuration(project: KnsProject, start: Double): Double =
        (project.composition.duration - start).coerceAtLeast(1.0)

    private fun base(project: KnsProject, type: LayerType, name: String, start: Double) = Layer(
        id = Ids.newId(TimelineOps.prefixFor(type), LayerTree.allIds(project)),
        type = type,
        name = name,
        start = start,
        duration = defaultDuration(project, start),
    )

    fun text(project: KnsProject, start: Double, content: String = "Kinesis", name: String = "Text") =
        base(project, LayerType.TEXT, name, start).copy(
            text = TextContent(content = content, fontSize = anim((project.composition.width * 0.1f).coerceIn(24f, 200f))),
        )

    fun shape(project: KnsProject, start: Double, kind: ShapeKind, name: String): Layer {
        val s = min(project.composition.width, project.composition.height) * 0.35f
        val color = PALETTE[LayerTree.all(project).size % PALETTE.size]
        return base(project, LayerType.SHAPE, name, start).copy(
            shape = ShapeContent(
                kind = kind,
                size = anim(Vec2(s, s)),
                roundness = anim(if (kind == ShapeKind.RECTANGLE) s * 0.08f else 0f),
                sides = anim(if (kind == ShapeKind.STAR) 5f else 6f),
                fill = FillSpec(color = anim(color)),
            ),
        )
    }

    /** Image layer scaled to fit inside the composition. */
    fun image(project: KnsProject, start: Double, asset: Asset, name: String) =
        base(project, LayerType.IMAGE, name, start).copy(
            image = ImageContent(asset.id),
            transform = Transform2D(scale = anim(fitScale(project, asset))),
        )

    fun video(project: KnsProject, start: Double, asset: Asset, name: String): Layer {
        val length = asset.duration ?: defaultDuration(project, start)
        return base(project, LayerType.VIDEO, name, start).copy(
            duration = length.coerceAtLeast(TimelineOps.MIN_DURATION),
            video = MediaContent(asset.id),
            transform = Transform2D(scale = anim(fitScale(project, asset))),
        )
    }

    fun audio(project: KnsProject, start: Double, asset: Asset, name: String): Layer {
        val length = asset.duration ?: defaultDuration(project, start)
        return base(project, LayerType.AUDIO, name, start).copy(
            duration = length.coerceAtLeast(TimelineOps.MIN_DURATION),
            audio = MediaContent(asset.id),
        )
    }

    fun object3d(project: KnsProject, start: Double, primitive: Primitive, name: String): Layer {
        val size = min(project.composition.width, project.composition.height) * 0.35f
        val color = PALETTE[LayerTree.all(project).size % PALETTE.size]
        return base(project, LayerType.OBJECT3D, name, start).copy(
            transform3d = Transform3D(rotation = anim(if (primitive == Primitive.PLANE) Vec3.ZERO else Vec3(-20f, 30f, 0f))),
            object3d = Object3DContent(primitive = primitive, size = anim(size), material = Material(color = anim(color), metallic = anim(0.1f), roughness = anim(0.4f))),
        )
    }

    fun model3d(project: KnsProject, start: Double, asset: Asset, name: String): Layer {
        val size = min(project.composition.width, project.composition.height) * 0.45f
        return base(project, LayerType.MODEL3D, name, start).copy(model3d = Model3DContent(asset.id, size = anim(size)))
    }

    fun camera(project: KnsProject, start: Double, name: String) =
        LayerProps.withExplicitCameraPosition(base(project, LayerType.CAMERA, name, start).copy(camera = CameraContent()), project.composition.height)

    fun light(project: KnsProject, start: Double, kind: LightKind, name: String) =
        base(project, LayerType.LIGHT, name, start).copy(light = LightContent(kind = kind, intensity = anim(if (kind == LightKind.AMBIENT) 0.3f else 1f)))

    fun fitScale(project: KnsProject, asset: Asset): Vec2 {
        val w = asset.width ?: return Vec2.ONE
        val h = asset.height ?: return Vec2.ONE
        if (w <= 0 || h <= 0) return Vec2.ONE
        val s = min(project.composition.width / w.toFloat(), project.composition.height / h.toFloat())
        return Vec2(s, s)
    }

    fun newAsset(project: KnsProject, type: AssetType, extension: String, name: String?, mimeType: String?): Asset {
        val id = Ids.newId(type.name.lowercase(), project.assets.mapTo(HashSet()) { it.id })
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        return Asset(id = id, type = type, path = "assets/$id.$ext", name = name, mimeType = mimeType)
    }
}
