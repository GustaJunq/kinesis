package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.LoopMode
import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.BlendMode
import dev.kinesis.core.model.CameraAim
import dev.kinesis.core.model.CameraContent
import dev.kinesis.core.model.NullContent
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.FillSpec
import dev.kinesis.core.model.FontSpec
import dev.kinesis.core.model.Gradient
import dev.kinesis.core.model.GradientStop
import dev.kinesis.core.model.ImageContent
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightContent
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.Mask
import dev.kinesis.core.model.MaskShape
import dev.kinesis.core.model.Material
import dev.kinesis.core.model.Matte
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.model.Model3DContent
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.PathPoint
import dev.kinesis.core.model.PathSpec
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ProjectMeta
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeSpec
import dev.kinesis.core.model.TextAnimator
import dev.kinesis.core.model.TextAnimatorType
import dev.kinesis.core.model.TextContent
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.model.Transform3D
import dev.kinesis.core.model.Transition
import dev.kinesis.core.model.TransitionType
import dev.kinesis.core.model.TrimSpec
import java.io.File

/** Fixtures shared by tests. */
object TestProjects {

    val repoRoot: File
        get() = File(System.getProperty("kinesis.repoRoot") ?: "..").absoluteFile

    fun <T> keys(vararg k: Pair<Double, T>, easing: Easing = Easing.LINEAR, loop: LoopMode = LoopMode.NONE) =
        Animatable(k.first().second, k.map { Keyframe(it.first, it.second, easing) }, loop)

    /** A project that uses every layer type and most features. */
    fun full(): KnsProject = KnsProject(
        generator = "tests",
        meta = ProjectMeta(title = "Everything", createdAt = "2026-01-01T00:00:00Z"),
        composition = Composition(width = 1080, height = 1920, fps = 30.0, duration = 10.0, background = Color.parse("#0B1E4D")),
        assets = listOf(
            Asset("img", AssetType.IMAGE, "assets/img.png", name = "photo.png", width = 4, height = 2),
            Asset("vid", AssetType.VIDEO, "assets/vid.mp4", duration = 3.0, width = 1920, height = 1080, hasAudio = true),
            Asset("snd", AssetType.AUDIO, "assets/snd.m4a", duration = 30.0),
            Asset("mdl", AssetType.MODEL, "assets/mdl.glb"),
            Asset("fnt", AssetType.FONT, "assets/fnt.ttf"),
        ),
        layers = listOf(
            Layer(
                id = "bg", type = LayerType.SHAPE, name = "Background", duration = 10.0,
                shape = ShapeContent(
                    kind = ShapeKind.RECTANGLE, size = anim(Vec2(1080f, 1920f)),
                    fill = FillSpec(gradient = Gradient(stops = listOf(GradientStop(0f, Color.parse("#112244")), GradientStop(1f, Color.parse("#000000"))))),
                ),
            ),
            Layer(
                id = "star", type = LayerType.SHAPE, name = "Star", start = 1.0, duration = 4.0, motionBlur = true,
                transform = Transform2D(
                    position = keys(0.0 to Vec2(-300f, 0f), 2.0 to Vec2(300f, 0f), easing = Easing.Preset(EasingPreset.EASE_IN_OUT_CUBIC)),
                    rotation = keys(0.0 to 0f, 4.0 to 360f),
                ),
                shape = ShapeContent(
                    kind = ShapeKind.STAR, sides = anim(6f), innerRatio = anim(0.45f),
                    stroke = StrokeSpec(color = anim(Color.WHITE), width = anim(6f)),
                    trim = TrimSpec(end = keys(0.0 to 0f, 1.0 to 1f)),
                ),
                effects = listOf(
                    Effect.of("glow", "radius" to 20f),
                    Effect.create("dropShadow"),
                    Effect.of("pinchBulge", "amount" to -0.3f, "center" to dev.kinesis.core.math.Vec2(10f, 20f)),
                    Effect.of(
                        "gradientMap",
                        "stops" to dev.kinesis.core.model.EffectValue.Stops(
                            listOf(dev.kinesis.core.model.GradientStop(0f, Color.BLACK), dev.kinesis.core.model.GradientStop(0.5f, Color.parse("#FF4D33")), dev.kinesis.core.model.GradientStop(1f, Color.WHITE)),
                        ),
                    ),
                    Effect.of("warp", "mode" to "fractal", "complexity" to 5, "seed" to 7).withNumber("amount", keys(0.0 to 0f, 1.0 to 40f)),
                    Effect.of("channelShuffle", "red" to "blue", "blue" to "red"),
                    Effect.of("glass", "useLuminanceAsHeight" to true).copy(enabled = false),
                ),
                transitionIn = Transition(TransitionType.ZOOM_IN, 0.5),
                transitionOut = Transition(TransitionType.FADE, 0.5),
            ),
            Layer(
                id = "path", type = LayerType.SHAPE, name = "Line", duration = 3.0,
                shape = ShapeContent(
                    kind = ShapeKind.PATH,
                    path = PathSpec(closed = false, points = listOf(PathPoint(Vec2(-100f, 0f), outTangent = Vec2(50f, -80f)), PathPoint(Vec2(100f, 0f)))),
                    fill = FillSpec(enabled = false), stroke = StrokeSpec(width = anim(10f)),
                ),
            ),
            Layer(
                id = "title", type = LayerType.TEXT, name = "Title", start = 0.5, duration = 5.0,
                transform = Transform2D(position = anim(Vec2(0f, -600f)), opacity = keys(0.0 to 0f, 0.5 to 1f, easing = Easing.CubicBezier(0.2f, 0f, 0f, 1f))),
                text = TextContent(
                    content = "Hello\nKinesis", font = FontSpec("serif", 800, italic = true), fontSize = anim(120f),
                    color = keys(0.0 to Color.WHITE, 2.0 to Color.parse("#FFC857")),
                    animators = listOf(TextAnimator(type = TextAnimatorType.SLIDE_UP, stagger = 0.04)),
                    stroke = StrokeSpec(color = anim(Color.BLACK), width = anim(3f)),
                ),
                masks = listOf(Mask(shape = MaskShape.ELLIPSE, size = anim(Vec2(800f, 300f)), feather = anim(20f))),
                blendMode = BlendMode.SCREEN,
            ),
            Layer(id = "photo", type = LayerType.IMAGE, name = "Photo", duration = 10.0, visible = false, image = ImageContent("img")),
            Layer(
                id = "masked", type = LayerType.VIDEO, name = "Clip", duration = 3.0, matte = Matte("photo", MatteMode.LUMA),
                video = MediaContent("vid", sourceStart = 0.5, speed = 1.5, volume = keys(0.0 to 1f, 2.0 to 0f)),
            ),
            Layer(id = "music", type = LayerType.AUDIO, name = "Music", duration = 10.0, audio = MediaContent("snd", fadeIn = 1.0, fadeOut = 2.0)),
            Layer(
                id = "grp", type = LayerType.GROUP, name = "Group", start = 2.0, duration = 6.0,
                transform = Transform2D(scale = anim(Vec2(1.2f, 1.2f))),
                layers = listOf(
                    Layer(id = "child", type = LayerType.SHAPE, name = "Child", start = 1.0, duration = 2.0, shape = ShapeContent(kind = ShapeKind.ELLIPSE)),
                    Layer(id = "child2", type = LayerType.TEXT, name = "Child 2", duration = 6.0, parent = "child", text = TextContent("in group")),
                ),
            ),
            Layer(
                id = "cube", type = LayerType.OBJECT3D, name = "Cube", duration = 10.0,
                transform3d = Transform3D(rotation = keys(0.0 to Vec3.ZERO, 10.0 to Vec3(0f, 720f, 0f)), position = anim(Vec3(0f, 200f, 0f))),
                object3d = Object3DContent(Primitive.CUBE, size = anim(400f), material = Material(color = anim(Color.parse("#C0C4CC")), metallic = anim(1f), roughness = anim(0.25f), texture = "img")),
            ),
            Layer(
                id = "orbit", type = LayerType.OBJECT3D, name = "Pivot", duration = 10.0,
                object3d = Object3DContent(Primitive.EMPTY),
                transform3d = Transform3D(rotation = keys(0.0 to Vec3.ZERO, 10.0 to Vec3(0f, 360f, 0f), loop = LoopMode.CYCLE)),
            ),
            Layer(id = "moon", type = LayerType.MODEL3D, name = "Model", duration = 10.0, parent = "orbit", transform3d = Transform3D(position = anim(Vec3(500f, 0f, 0f))), model3d = Model3DContent("mdl", size = anim(150f))),
            Layer(id = "rig", type = LayerType.NULL, name = "Rig", duration = 10.0, nullObject = NullContent(threeD = true), transform3d = Transform3D(position = anim(Vec3(0f, 0f, 100f)))),
            Layer(id = "cam", type = LayerType.CAMERA, name = "Camera", duration = 10.0, parent = "rig", camera = CameraContent(position = anim(Vec3(0f, -300f, -2500f)), rotation = anim(Vec3(5f, 0f, 0f)), aim = CameraAim.ROTATION, fov = anim(45f))),
            Layer(id = "pin", type = LayerType.NULL, name = "Pin", duration = 10.0, transform = Transform2D(position = anim(Vec2(40f, -20f)))),
            Layer(id = "key", type = LayerType.LIGHT, name = "Key", duration = 10.0, light = LightContent(LightKind.DIRECTIONAL, intensity = anim(1.2f))),
            Layer(id = "amb", type = LayerType.LIGHT, name = "Ambient", duration = 10.0, light = LightContent(LightKind.AMBIENT, intensity = anim(0.3f))),
        ),
    )

    /** Placeholder bytes for every asset of [project]. */
    fun fakeAssetFiles(project: KnsProject): Map<String, ByteArray> =
        project.assets.associate { it.path to "data of ${it.id}".toByteArray() }
}
