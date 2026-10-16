package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.edit.AnimProp
import dev.kinesis.core.math.Color as KColor
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Layer
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.theme.KType
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Numeric components of a property value (x/y/z, r/g/b), or null for unsupported types. */
internal fun componentsOf(v: Any?): FloatArray? = when (v) {
    is Float -> floatArrayOf(v)
    is Vec2 -> floatArrayOf(v.x, v.y)
    is Vec3 -> floatArrayOf(v.x, v.y, v.z)
    is KColor -> floatArrayOf(v.r, v.g, v.b)
    else -> null
}

/** [v] with component [i] replaced by [x]. */
@Suppress("UNCHECKED_CAST")
internal fun <T> withComponent(v: T, i: Int, x: Float): T = when (v) {
    is Float -> x as T
    is Vec2 -> (if (i == 0) v.copy(x = x) else v.copy(y = x)) as T
    is Vec3 -> (when (i) { 0 -> v.copy(x = x); 1 -> v.copy(y = x); else -> v.copy(z = x) }) as T
    is KColor -> (when (i) { 0 -> v.copy(r = x.coerceIn(0f, 1f)); 1 -> v.copy(g = x.coerceIn(0f, 1f)); else -> v.copy(b = x.coerceIn(0f, 1f)) }) as T
    else -> v
}

private val COMPONENT_COLORS = listOf(KColors.AxisX, KColors.AxisY, KColors.AxisZ)

/**
 * Graph (value over time) of one animated property: every component as a curve, keyframes as points to drag in
 * time and value, and the Bézier handles of the selected keyframe's segment drawn in real units.
 */
@Composable
fun GraphEditorPanel(
    vm: EditorViewModel,
    layer: Layer,
    prop: AnimProp<*>,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNCHECKED_CAST")
    val p = prop as AnimProp<Any?>
    val anim = p.get(layer)
    val keys = anim.keyframes
    val comps = componentsOf(anim.value)?.size ?: 0
    var selKey by remember(layer.id, prop.id) { mutableIntStateOf(-1) }
    var selComp by remember(layer.id, prop.id) { mutableIntStateOf(0) }
    var hidden by remember(layer.id, prop.id) { mutableStateOf(setOf<Int>()) }
    val local = vm.localTime(layer)
    val latestLayer by rememberUpdatedState(layer)

    // View box: time and value ranges that hold every keyframe and the sampled curve.
    val first = keys.firstOrNull()?.time ?: 0.0
    val last = keys.lastOrNull()?.time ?: layer.duration
    val pad = max(0.25, (last - first) * 0.08)
    val tMin = first - pad
    val tMax = last + pad
    val samples = 160
    val curves = remember(anim, comps) {
        Array(comps) { c -> FloatArray(samples + 1) { i -> componentsOf(anim.sample(tMin + (tMax - tMin) * i / samples, p.interpolate))!![c] } }
    }
    var vMin = Float.POSITIVE_INFINITY
    var vMax = Float.NEGATIVE_INFINITY
    for (c in 0 until comps) if (c !in hidden) for (v in curves[c]) { vMin = min(vMin, v); vMax = max(vMax, v) }
    if (!vMin.isFinite()) { vMin = 0f; vMax = 1f }
    if (vMax - vMin < 1e-3f) { vMin -= 1f; vMax += 1f }
    val vPad = (vMax - vMin) * 0.12f
    vMin -= vPad; vMax += vPad

    Column(
        modifier.fillMaxWidth().background(KColors.Card).padding(horizontal = KDims.Gutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlineLabel(stringResource(R.string.graph_editor) + " · " + title, Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(KDims.MinTouch)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.close), tint = KColors.TextSoft)
            }
        }
        if (comps == 0 || keys.size < 2) {
            Text(stringResource(R.string.graph_needs_keyframes), style = KType.Mono, color = KColors.TextSoft)
            return@Column
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(KShapes.Tile)
                .background(KColors.Background)
                .pointerInput(keys.size, comps, hidden) {
                    detectTapGestures { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        fun pos(t: Double, v: Float) = Offset(((t - tMin) / (tMax - tMin)).toFloat() * w, h - (v - vMin) / (vMax - vMin) * h)
                        val a = latestLayer.let { p.get(it) }
                        var best = -1 to -1
                        var bestD = 28.dp.toPx()
                        a.keyframes.forEachIndexed { k, kf ->
                            val values = componentsOf(kf.value) ?: return@forEachIndexed
                            for (c in values.indices) {
                                if (c in hidden) continue
                                val q = pos(kf.time, values[c])
                                val d = hypot(q.x - o.x, q.y - o.y)
                                if (d < bestD) { bestD = d; best = k to c }
                            }
                        }
                        selKey = best.first
                        if (best.second >= 0) selComp = best.second
                    }
                }
                .pointerInput(keys.size, comps, hidden, selKey, selComp) {
                    // 0 = nothing, 1 = keyframe, 2 = first handle, 3 = second handle
                    var mode = 0
                    var key = -1
                    var comp = 0
                    detectDragGestures(
                        onDragStart = { o ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            fun pos(t: Double, v: Float) = Offset(((t - tMin) / (tMax - tMin)).toFloat() * w, h - (v - vMin) / (vMax - vMin) * h)
                            val a = p.get(latestLayer)
                            mode = 0
                            var bestD = 28.dp.toPx()
                            handlePositions(a, selKey, selComp, ::pos)?.let { (h1, h2) ->
                                hypot(h1.x - o.x, h1.y - o.y).takeIf { it < bestD }?.let { bestD = it; mode = 2; key = selKey; comp = selComp }
                                hypot(h2.x - o.x, h2.y - o.y).takeIf { it < bestD }?.let { bestD = it; mode = 3; key = selKey; comp = selComp }
                            }
                            a.keyframes.forEachIndexed { k, kf ->
                                val values = componentsOf(kf.value) ?: return@forEachIndexed
                                for (c in values.indices) {
                                    if (c in hidden) continue
                                    val q = pos(kf.time, values[c])
                                    val d = hypot(q.x - o.x, q.y - o.y)
                                    if (d < bestD) { bestD = d; mode = 1; key = k; comp = c }
                                }
                            }
                            if (mode == 1) { selKey = key; selComp = comp }
                        },
                        onDragEnd = { vm.endGesture() },
                        onDragCancel = { vm.endGesture() },
                    ) { change, _ ->
                        if (mode == 0) return@detectDragGestures
                        change.consume()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val t = tMin + (change.position.x / w).coerceIn(0f, 1f) * (tMax - tMin)
                        val v = vMin + (h - change.position.y) / h * (vMax - vMin)
                        val gestureKey = "graph:${layer.id}:${prop.id}"
                        when (mode) {
                            1 -> vm.updateLayer(layer.id, gestureKey) { l -> moveKey(p, l, key, comp, t, v, vm) }
                            else -> vm.updateLayer(layer.id, gestureKey) { l -> dragHandle(p, l, key, comp, mode == 2, t, v) }
                        }
                    }
                },
        ) {
            val w = size.width; val h = size.height
            fun pos(t: Double, v: Float) = Offset(((t - tMin) / (tMax - tMin)).toFloat() * w, h - (v - vMin) / (vMax - vMin) * h)
            // Zero line and playhead.
            if (0f in vMin..vMax) drawLine(KColors.LineStrong, pos(tMin, 0f), pos(tMax, 0f))
            drawLine(KColors.Accent.copy(alpha = 0.6f), pos(local, vMin), pos(local, vMax), strokeWidth = 2f)
            for (c in 0 until comps) {
                if (c in hidden) continue
                val color = if (comps == 1) KColors.Accent else COMPONENT_COLORS[c % 3]
                val path = Path()
                for (i in 0..samples) {
                    val q = pos(tMin + (tMax - tMin) * i / samples, curves[c][i])
                    if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
                }
                drawPath(path, color.copy(alpha = if (c == selComp) 1f else 0.55f), style = Stroke((if (c == selComp) 3 else 2).dp.toPx()))
                keys.forEachIndexed { k, kf ->
                    val q = pos(kf.time, componentsOf(kf.value)!![c])
                    val picked = k == selKey && c == selComp
                    drawCircle(if (picked) KColors.Keyframe else Color.White, (if (picked) 7 else 5).dp.toPx(), q)
                }
            }
            handlePositions(anim, selKey, selComp, ::pos)?.let { (h1, h2) ->
                val a = pos(keys[selKey].time, componentsOf(keys[selKey].value)!![selComp])
                val b = pos(keys[selKey + 1].time, componentsOf(keys[selKey + 1].value)!![selComp])
                drawLine(KColors.Keyframe, a, h1, strokeWidth = 2f)
                drawLine(KColors.Keyframe, b, h2, strokeWidth = 2f)
                drawCircle(KColors.Keyframe, 6.dp.toPx(), h1)
                drawCircle(KColors.Keyframe, 6.dp.toPx(), h2)
            }
        }
        if (comps > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val names = if (anim.value is KColor) listOf("R", "G", "B") else listOf("X", "Y", "Z")
                for (c in 0 until comps) {
                    PillChip(names[c], selected = c !in hidden, onClick = {
                        hidden = if (c in hidden) hidden - c else (hidden + c).takeIf { it.size < comps } ?: hidden
                    })
                }
            }
        }
        val sel = keys.getOrNull(selKey)
        Text(
            if (sel == null) stringResource(R.string.graph_hint)
            else "t = ${"%.2f".format(sel.time)} s · ${componentsOf(sel.value)!!.joinToString(", ") { "%.1f".format(it) }}",
            style = KType.Mono, color = KColors.TextSoft,
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.copyKeyframes(layer.id, setOf(prop.id)) }) { Text(stringResource(R.string.copy_property_keyframes)) }
            TextButton(onClick = { vm.pasteKeyframes(layer.id) }, enabled = vm.keyframeClip != null) { Text(stringResource(R.string.paste_keyframes)) }
            TextButton(onClick = { vm.setEasingAll(layer.id, prop, Easing.EASE_IN_OUT) }) { Text(stringResource(R.string.smooth_all)) }
        }
    }
}

/** Screen positions of the two Bézier handles of segment [key] on component [comp], or null when not editable. */
private fun handlePositions(anim: Animatable<Any?>, key: Int, comp: Int, pos: (Double, Float) -> Offset): Pair<Offset, Offset>? {
    if (key < 0 || key >= anim.keyframes.size - 1) return null
    val k0 = anim.keyframes[key]; val k1 = anim.keyframes[key + 1]
    val b = k0.easing.bezierOrNull() ?: return null
    if (k0.easing is Easing.Preset && (k0.easing as Easing.Preset).preset.bezier == null) return null
    val v0 = componentsOf(k0.value)?.getOrNull(comp) ?: return null
    val v1 = componentsOf(k1.value)?.getOrNull(comp) ?: return null
    val dt = k1.time - k0.time
    return pos(k0.time + b.x1 * dt, v0 + b.y1 * (v1 - v0)) to pos(k0.time + b.x2 * dt, v0 + b.y2 * (v1 - v0))
}

/** Moves keyframe [key] to time [t] (snapped to frames, kept between its neighbours) and sets component [comp] to [v]. */
private fun moveKey(p: AnimProp<Any?>, l: Layer, key: Int, comp: Int, t: Double, v: Float, vm: EditorViewModel): Layer {
    val a = p.get(l)
    if (key !in a.keyframes.indices) return l
    val fps = vm.current.composition.fps
    val frame = 1.0 / fps
    val lo = a.keyframes.getOrNull(key - 1)?.time?.plus(frame) ?: Double.NEGATIVE_INFINITY
    val hi = a.keyframes.getOrNull(key + 1)?.time?.minus(frame) ?: Double.POSITIVE_INFINITY
    val snapped = ((t * fps).roundToInt() / fps).coerceIn(lo, hi)
    val list = a.keyframes.toMutableList()
    list[key] = Keyframe(snapped, withComponent(list[key].value, comp, v), list[key].easing)
    return p.set(l, a.copy(value = list.first().value, keyframes = list))
}

/** Drags a Bézier handle of segment [key]: x is clamped to the segment, y follows the value (overshoot allowed). */
private fun dragHandle(p: AnimProp<Any?>, l: Layer, key: Int, comp: Int, firstHandle: Boolean, t: Double, v: Float): Layer {
    val a = p.get(l)
    if (key < 0 || key >= a.keyframes.size - 1) return l
    val k0 = a.keyframes[key]; val k1 = a.keyframes[key + 1]
    val b = k0.easing.bezierOrNull() ?: return l
    val v0 = componentsOf(k0.value)?.getOrNull(comp) ?: return l
    val v1 = componentsOf(k1.value)?.getOrNull(comp) ?: return l
    val x = ((t - k0.time) / (k1.time - k0.time)).toFloat().coerceIn(0f, 1f)
    val dv = v1 - v0
    // A flat segment has no value scale: keep the handle's progress.
    val y = if (abs(dv) < 1e-4f) (if (firstHandle) b.y1 else b.y2) else ((v - v0) / dv).coerceIn(-2f, 3f)
    val e = if (firstHandle) Easing.CubicBezier(x, y, b.x2, b.y2) else Easing.CubicBezier(b.x1, b.y1, x, y)
    return p.setEasing(l, key, e)
}
