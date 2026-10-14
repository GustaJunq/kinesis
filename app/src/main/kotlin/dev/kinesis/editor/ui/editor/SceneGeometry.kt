package dev.kinesis.editor.ui.editor

import android.graphics.RectF
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.scene.CameraState
import dev.kinesis.core.scene.Content2D
import dev.kinesis.core.scene.FrameScene
import dev.kinesis.core.scene.GroupNode
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.Node3D
import dev.kinesis.core.scene.RenderNode
import dev.kinesis.editor.render.ShapeRasterizer
import dev.kinesis.editor.render.TextRasterizer
import dev.kinesis.editor.render.TypefaceProvider

/** Screen-space outline of a selectable layer, in composition pixels (centre origin). */
sealed class Outline {
    abstract val layerId: String
    abstract fun contains(p: Vec2): Boolean

    /** Four corners of the transformed content rectangle (2D layers). */
    class Quad(override val layerId: String, val corners: List<Vec2>, val pivot: Vec2) : Outline() {
        override fun contains(p: Vec2): Boolean {
            // Point in convex polygon (corners are in order).
            var sign = 0
            for (i in 0 until 4) {
                val a = corners[i]; val b = corners[(i + 1) % 4]
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (s != 0) {
                    if (sign == 0) sign = s else if (s != sign) return false
                }
            }
            return true
        }
    }

    /** Projected bounding box of a 3D object. */
    class Box3D(override val layerId: String, val min: Vec2, val max: Vec2, val center: Vec2) : Outline() {
        override fun contains(p: Vec2) = p.x in min.x..max.x && p.y in min.y..max.y
    }
}

/** Computes outlines for hit-testing and gizmos (UI thread; uses the same layout code as the renderer). */
class SceneGeometry(private val typefaces: TypefaceProvider, private val project: () -> KnsProject) {

    fun localBounds(n: Node2D): RectF? {
        val p = project()
        return when (val c = n.content) {
            is Content2D.Text -> TextRasterizer.measure(c, typefaces)
            is Content2D.Shape -> {
                val r = RectF()
                ShapeRasterizer.path(c).computeBounds(r, true)
                val pad = if (c.strokeEnabled) c.strokeWidth / 2 else 0f
                r.inset(-pad, -pad)
                if (r.width() < 2f && r.height() < 2f) null else r
            }
            is Content2D.Image -> p.asset(c.assetId)?.let { a -> sized(a.width, a.height) }
            is Content2D.Video -> p.asset(c.assetId)?.let { a -> sized(a.width, a.height) }
        }
    }

    private fun sized(w: Int?, h: Int?): RectF {
        val ww = (w ?: 400).toFloat()
        val hh = (h ?: 400).toFloat()
        return RectF(-ww / 2, -hh / 2, ww / 2, hh / 2)
    }

    fun outline(n: RenderNode, camera: CameraState): Outline? = when (n) {
        is Node2D -> localBounds(n)?.let { r ->
            val m = n.matrix
            Outline.Quad(
                n.layerId,
                listOf(m.apply(Vec2(r.left, r.top)), m.apply(Vec2(r.right, r.top)), m.apply(Vec2(r.right, r.bottom)), m.apply(Vec2(r.left, r.bottom))),
                m.apply(Vec2.ZERO),
            )
        }
        is Node3D -> {
            val pts = ArrayList<Vec2>()
            for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) for (z in listOf(-0.5f, 0.5f)) {
                camera.project(n.model.transformPoint(Vec3(x, y, z)))?.let { pts += n.post.apply(it) }
            }
            val center = camera.project(n.model.transformPoint(Vec3.ZERO))?.let { n.post.apply(it) }
            if (pts.isEmpty() || center == null) null
            else Outline.Box3D(n.layerId, Vec2(pts.minOf { it.x }, pts.minOf { it.y }), Vec2(pts.maxOf { it.x }, pts.maxOf { it.y }), center)
        }
        is GroupNode -> null
    }

    /** Flattened visual nodes, top-most first (for hit testing). */
    fun topDown(scene: FrameScene): List<RenderNode> {
        val out = ArrayList<RenderNode>()
        fun visit(nodes: List<RenderNode>) {
            for (n in nodes.asReversed()) {
                if (n is GroupNode) visit(n.children) else out += n
            }
        }
        visit(scene.nodes)
        return out
    }

    /**
     * Layer to select for a tap at [p]: the top-most hit; taps inside a group select the group unless the current
     * selection is already inside it.
     */
    fun hitTest(scene: FrameScene, p: Vec2, currentSelection: String?): String? {
        val proj = project()
        for (n in topDown(scene)) {
            val o = outline(n, scene.camera) ?: continue
            if (!o.contains(p)) continue
            val ancestors = LayerTree.ancestors(proj, n.layerId)
            if (ancestors.isEmpty()) return n.layerId
            val selAncestors = currentSelection?.let { LayerTree.ancestors(proj, it) + it }.orEmpty()
            // Descend one level per tap: pick the deepest ancestor the selection is already in, plus one.
            val inside = ancestors.indexOfLast { it in selAncestors }
            return if (inside < 0) ancestors.first() else ancestors.getOrNull(inside + 1) ?: n.layerId
        }
        return null
    }

    fun findNode(scene: FrameScene, id: String): RenderNode? {
        fun visit(nodes: List<RenderNode>): RenderNode? {
            for (n in nodes) {
                if (n.layerId == id) return n
                if (n is GroupNode) visit(n.children)?.let { return it }
            }
            return null
        }
        return visit(scene.nodes)
    }
}
