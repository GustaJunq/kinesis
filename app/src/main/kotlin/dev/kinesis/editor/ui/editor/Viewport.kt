package dev.kinesis.editor.ui.editor

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.kinesis.core.anim.at
import dev.kinesis.core.edit.LayerProps
import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.updateShape
import dev.kinesis.core.scene.FrameScene
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.render.PreviewRenderer
import dev.kinesis.editor.render.PreviewState
import dev.kinesis.editor.render.TypefaceProvider
import dev.kinesis.editor.render.ViewportRect
import dev.kinesis.editor.ui.theme.KColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** How one-finger drags affect a selected 3D object. */
enum class Tool3D { MOVE, ROTATE }

private const val AXIS_LENGTH = 0.75f

@Composable
fun Viewport(
    vm: EditorViewModel,
    project: KnsProject,
    time: Double,
    playing: Boolean,
    selection: String?,
    mediaRevision: Int,
    tool3D: Tool3D,
    onRenderError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val paddingPx = with(density) { 12f * this.density }
    val errorCallback by rememberUpdatedState(onRenderError)
    val renderer = remember {
        PreviewRenderer(context.assets, vm.files, paddingPx) { msg -> errorCallback(msg) }
    }
    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            glView.queueEvent { renderer.release() }
        }
    }

    // Push state to the GL thread.
    renderer.state = PreviewState(project, time, playing, mediaRevision)
    glView.requestRender()

    // Refresh the home-screen thumbnail a moment after edits settle.
    LaunchedEffect(project) {
        delay(1500)
        renderer.requestThumbnail(480) { bmp -> vm.saveThumbnail(bmp) }
        glView.requestRender()
    }
    LaunchedEffect(project, mediaRevision) { renderer.retry() }

    val typefaces = remember { TypefaceProvider { vm.files.file(it) } }
    val geometry = remember { SceneGeometry(typefaces) { vm.current } }
    val scene = remember(project, time) { SceneEvaluator.evaluate(project, time) }

    var sizeW by remember { mutableIntStateOf(1) }
    var sizeH by remember { mutableIntStateOf(1) }
    val vp = ViewportRect.fit(sizeW, sizeH, project.composition.width, project.composition.height, paddingPx)
    val scale = vp.width / project.composition.width

    fun toView(p: Vec2) = Offset(vp.left + (p.x + project.composition.width / 2f) * scale, vp.top + (p.y + project.composition.height / 2f) * scale)

    val currentScene by rememberUpdatedState(scene)
    val currentVp by rememberUpdatedState(vp)
    val currentTool by rememberUpdatedState(tool3D)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val handleRadius = with(density) { 28.dp.toPx() }

    Box(modifier.onSizeChanged { sizeW = it.width; sizeH = it.height }) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val v = currentVp
                        val p0 = vm.current
                        val s = v.width / p0.composition.width
                        fun toComp(o: Offset) = Vec2((o.x - v.left) / s - p0.composition.width / 2f, (o.y - v.top) / s - p0.composition.height / 2f)
                        var gesture: Gesture? = null
                        var moved = false
                        var pan = Offset.Zero
                        var zoom = 1f
                        var rotation = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            pan += event.calculatePan()
                            zoom *= event.calculateZoom()
                            rotation += event.calculateRotation()
                            if (!moved && (pan.getDistance() > touchSlop || abs(zoom - 1f) > 0.02f || abs(rotation) > 2f || event.changes.size > 1)) {
                                moved = true
                                if (vm.selection.value == null) {
                                    geometry.hitTest(currentScene, toComp(down.position), null)?.let { vm.select(it) }
                                }
                                gesture = startGesture(vm, currentScene, toComp(down.position), s, handleRadius, currentTool, geometry)
                            }
                            if (moved) {
                                gesture?.update(Vec2(pan.x / s, pan.y / s), zoom, rotation)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                        if (moved) {
                            vm.endGesture()
                        } else {
                            vm.select(geometry.hitTest(currentScene, toComp(down.position), vm.selection.value))
                        }
                    }
                },
        ) {
            // Composition frame
            drawRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(vp.left, vp.top),
                size = androidx.compose.ui.geometry.Size(vp.width, vp.height),
                style = Stroke(1f),
            )
            val sel = selection ?: return@Canvas
            val node = geometry.findNode(scene, sel)
            val outline = node?.let { geometry.outline(it, scene.camera) }
            val accent = KColors.Accent
            when (outline) {
                is Outline.Quad -> {
                    pathHandles(vm.current, sel, scene, geometry)?.forEach { pt ->
                        val v = toView(pt)
                        drawRect(Color.White, Offset(v.x - 6f * density.density, v.y - 6f * density.density), androidx.compose.ui.geometry.Size(12f * density.density, 12f * density.density))
                        drawRect(accent, Offset(v.x - 6f * density.density, v.y - 6f * density.density), androidx.compose.ui.geometry.Size(12f * density.density, 12f * density.density), style = Stroke(2f))
                    }
                    val path = Path().apply {
                        val c = outline.corners.map { toView(it) }
                        moveTo(c[0].x, c[0].y)
                        for (i in 1 until 4) lineTo(c[i].x, c[i].y)
                        close()
                    }
                    drawPath(path, accent, style = Stroke(2f * density.density))
                    outline.corners.forEach { drawCircle(Color.White, 4f * density.density, toView(it)) }
                    drawCircle(accent, 5f * density.density, toView(outline.pivot))
                }
                is Outline.Box3D -> {
                    val a = toView(outline.min)
                    val b = toView(outline.max)
                    drawRect(accent.copy(alpha = 0.7f), a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y), style = Stroke(1.5f * density.density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                    axisHandles(vm.current, sel, scene)?.forEach { (axis, from, to) ->
                        val color = when (axis) { 0 -> KColors.AxisX; 1 -> KColors.AxisY; else -> KColors.AxisZ }
                        drawLine(color, toView(from), toView(to), strokeWidth = 3f * density.density)
                        drawCircle(color, 9f * density.density, toView(to))
                    }
                }
                null -> Unit
            }
        }
    }
}

/** One drag/pinch interaction on the viewport. */
private fun interface Gesture {
    /** [pan] in composition pixels since the gesture started; [zoom]/[rotation] accumulated. */
    fun update(pan: Vec2, zoom: Float, rotation: Float)
}

/** Vertices of a selected `path` shape in composition pixels. */
private fun pathHandles(project: KnsProject, id: String, scene: FrameScene, geometry: SceneGeometry): List<Vec2>? {
    val layer = LayerTree.find(project, id) ?: return null
    val pts = layer.shape?.takeIf { it.kind == dev.kinesis.core.model.ShapeKind.PATH }?.path?.points ?: return null
    val node = geometry.findNode(scene, id) as? dev.kinesis.core.scene.Node2D ?: return null
    return pts.map { node.matrix.apply(it.point) }
}

/** Screen-space handles of the selected 3D layer's local axes: (axis index, origin, tip) in composition pixels. */
private fun axisHandles(project: KnsProject, id: String, scene: FrameScene): List<Triple<Int, Vec2, Vec2>>? {
    val layer = LayerTree.find(project, id) ?: return null
    if (!layer.type.isRendered3D) return null
    val parent = SceneEvaluator.parentSpace3D(project, id, scene.time) ?: return null
    val local = scene.time - LayerTree.containerOffset(project, id) - layer.start
    val pos = layer.transform3d.position.at(local)
    val size = (layer.object3d?.size ?: layer.model3d?.size)?.at(local) ?: 200f
    val len = size * AXIS_LENGTH
    val origin = scene.camera.project(parent.transformPoint(pos)) ?: return null
    val axes = listOf(Vec3(len, 0f, 0f), Vec3(0f, -len, 0f), Vec3(0f, 0f, -len))
    return axes.mapIndexedNotNull { i, a -> scene.camera.project(parent.transformPoint(pos + a))?.let { Triple(i, origin, it) } }
}

private fun startGesture(
    vm: EditorViewModel,
    scene: FrameScene,
    downComp: Vec2,
    viewScale: Float,
    handleRadiusPx: Float,
    tool: Tool3D,
    geometry: SceneGeometry,
): Gesture? {
    val id = vm.selection.value ?: return null
    val project = vm.current
    var layer = LayerTree.find(project, id) ?: return null
    if (layer.locked) return null
    val key = "viewport:$id:${System.nanoTime()}"
    val local = vm.localTime(layer)

    when {
        layer.type == LayerType.CAMERA -> {
            if (layer.camera?.position == null) {
                vm.updateLayer(id, key) { LayerProps.withExplicitCameraPosition(it, project.composition.height) }
                layer = LayerTree.find(vm.current, id) ?: return null
            }
            val prop = LayerProps.typed<Vec3>(LayerProps.find(layer, "camera.position") ?: return null)
            val target = layer.camera!!.target.at(local)
            val start = prop.valueAt(layer, local) - target
            val r0 = start.length().coerceAtLeast(1f)
            val yaw0 = atan2(start.x, -start.z)
            val pitch0 = asin((-start.y / r0).coerceIn(-1f, 1f))
            return Gesture { pan, zoom, _ ->
                val yaw = yaw0 + Math.toRadians(pan.x * 0.3).toFloat()
                val pitch = (pitch0 + Math.toRadians(pan.y * 0.3).toFloat()).coerceIn(-1.48f, 1.48f)
                val r = r0 / zoom.coerceAtLeast(0.05f)
                val offset = Vec3(r * cos(pitch) * sin(yaw), -r * sin(pitch), -r * cos(pitch) * cos(yaw))
                vm.setProperty(id, prop, target + offset, key)
            }
        }
        layer.type == LayerType.LIGHT -> {
            val prop = LayerProps.typed<Vec3>(LayerProps.find(layer, "light.position") ?: return null)
            val start = prop.valueAt(layer, local)
            return Gesture { pan, _, _ -> vm.setProperty(id, prop, Vec3(start.x + pan.x, start.y + pan.y, start.z), key) }
        }
        layer.type.isRendered3D -> return start3D(vm, layer, scene, downComp, viewScale, handleRadiusPx, tool, key, local)
        layer.type.is2DVisual && pathVertexAt(project, id, scene, geometry, downComp, handleRadiusPx / viewScale) != null -> {
            val index = pathVertexAt(project, id, scene, geometry, downComp, handleRadiusPx / viewScale)!!
            val node = geometry.findNode(scene, id) as? dev.kinesis.core.scene.Node2D ?: return null
            val inv = node.matrix.inverse() ?: return null
            val start = layer.shape?.path?.points?.getOrNull(index)?.point ?: return null
            val startComp = node.matrix.apply(start)
            return Gesture { pan, _, _ ->
                val local = inv.apply(startComp + pan)
                vm.updateLayer(id, key) { l ->
                    l.updateShape { sh -> sh.path?.let { pth -> sh.copy(path = dev.kinesis.core.edit.PathOps.movePoint(pth, index, local)) } ?: sh }
                }
            }
        }
        layer.type.is2DVisual -> {
            val parent = SceneEvaluator.parentSpace2D(project, id, vm.time.value) ?: Affine2D.IDENTITY
            val inv = parent.inverse() ?: Affine2D.IDENTITY
            val posProp = LayerProps.typed<Vec2>(LayerProps.find(layer, "transform.position")!!)
            val scaleProp = LayerProps.typed<Vec2>(LayerProps.find(layer, "transform.scale")!!)
            val rotProp = LayerProps.typed<Float>(LayerProps.find(layer, "transform.rotation")!!)
            val pos0 = posProp.valueAt(layer, local)
            val scale0 = scaleProp.valueAt(layer, local)
            val rot0 = rotProp.valueAt(layer, local)
            return Gesture { pan, zoom, rotation ->
                vm.updateLayer(id, key) { l ->
                    var x = posProp.setValue(l, local, pos0 + inv.applyVector(pan))
                    if (abs(zoom - 1f) > 0.001f) x = scaleProp.setValue(x, local, Vec2(scale0.x * zoom, scale0.y * zoom))
                    if (abs(rotation) > 0.01f) x = rotProp.setValue(x, local, rot0 + rotation)
                    x
                }
            }
        }
    }
    return null
}

private fun pathVertexAt(project: KnsProject, id: String, scene: FrameScene, geometry: SceneGeometry, p: Vec2, radius: Float): Int? {
    val handles = pathHandles(project, id, scene, geometry) ?: return null
    val best = handles.withIndex().minByOrNull { (_, h) -> hypot(h.x - p.x, h.y - p.y) } ?: return null
    return best.index.takeIf { hypot(best.value.x - p.x, best.value.y - p.y) <= radius }
}

private fun start3D(
    vm: EditorViewModel,
    layer: Layer,
    scene: FrameScene,
    downComp: Vec2,
    viewScale: Float,
    handleRadiusPx: Float,
    tool: Tool3D,
    key: String,
    local: Double,
): Gesture? {
    val id = layer.id
    val project = vm.current
    val parent = SceneEvaluator.parentSpace3D(project, id, vm.time.value) ?: Mat4.identity()
    val posProp = LayerProps.typed<Vec3>(LayerProps.find(layer, "transform3d.position")!!)
    val rotProp = LayerProps.typed<Vec3>(LayerProps.find(layer, "transform3d.rotation")!!)
    val scaleProp = LayerProps.typed<Vec3>(LayerProps.find(layer, "transform3d.scale")!!)
    val pos0 = posProp.valueAt(layer, local)
    val rot0 = rotProp.valueAt(layer, local)
    val scale0 = scaleProp.valueAt(layer, local)
    val cam = scene.camera

    // Constrained move along an axis handle?
    val handles = axisHandles(project, id, scene)
    val hit = handles?.minByOrNull { (_, _, tip) -> hypot(tip.x - downComp.x, tip.y - downComp.y) }
    if (tool == Tool3D.MOVE && hit != null && hypot(hit.third.x - downComp.x, hit.third.y - downComp.y) * viewScale < handleRadiusPx) {
        val (axisIndex, from, to) = hit
        val screenAxis = to - from
        val len2 = screenAxis.x * screenAxis.x + screenAxis.y * screenAxis.y
        val size = (layer.object3d?.size ?: layer.model3d?.size)?.at(local) ?: 200f
        val worldLen = size * AXIS_LENGTH
        val axis = when (axisIndex) { 0 -> Vec3(1f, 0f, 0f); 1 -> Vec3(0f, -1f, 0f); else -> Vec3(0f, 0f, -1f) }
        return Gesture { pan, _, _ ->
            if (len2 < 1f) return@Gesture
            val k = (pan.x * screenAxis.x + pan.y * screenAxis.y) / len2 * worldLen
            vm.setProperty(id, posProp, pos0 + axis * k, key)
        }
    }

    if (tool == Tool3D.ROTATE) {
        return Gesture { pan, _, rotation ->
            vm.setProperty(id, rotProp, Vec3(rot0.x - pan.y * 0.4f, rot0.y + pan.x * 0.4f, rot0.z + rotation), key)
        }
    }

    // Free move in the parent's XY plane, solved through the projected X and Y axes.
    val unit = 100f
    val o = cam.project(parent.transformPoint(pos0))
    val ax = cam.project(parent.transformPoint(pos0 + Vec3(unit, 0f, 0f)))
    val ay = cam.project(parent.transformPoint(pos0 + Vec3(0f, unit, 0f)))
    return Gesture { pan, zoom, rotation ->
        var dx = pan.x
        var dy = pan.y
        if (o != null && ax != null && ay != null) {
            val a = ax - o
            val b = ay - o
            val det = a.x * b.y - a.y * b.x
            if (abs(det) > 1e-3f) {
                dx = (pan.x * b.y - pan.y * b.x) / det * unit
                dy = (a.x * pan.y - a.y * pan.x) / det * unit
            }
        }
        vm.updateLayer(id, key) { l ->
            var x = posProp.setValue(l, local, Vec3(pos0.x + dx, pos0.y + dy, pos0.z))
            if (abs(zoom - 1f) > 0.001f) x = scaleProp.setValue(x, local, scale0 * zoom)
            if (abs(rotation) > 0.01f) x = rotProp.setValue(x, local, Vec3(rot0.x, rot0.y, rot0.z + rotation))
            x
        }
    }
}
