package dev.kinesis.editor.ui.theme

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.tan

/**
 * A rounded parallelogram: a rounded rectangle sheared by [degrees] around its vertical centre, leaning to the right.
 * It stays inside the bounds it is given (the top edge touches the right side, the bottom edge the left side).
 * The tool bar, its active item and the play tab are the only skewed elements of the editor; their content is not.
 */
class SkewShape(private val radius: Dp = 16.dp, private val degrees: Float = 15f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val k = tan(Math.toRadians(degrees.toDouble())).toFloat()
        val shear = k * size.height
        val r = min(with(density) { radius.toPx() }, min(size.width - shear, size.height) / 2f).coerceAtLeast(0f)
        val path = Path().apply {
            addRoundRect(RoundRect(shear / 2f, 0f, size.width - shear / 2f, size.height, CornerRadius(r)))
        }
        val m = Matrix()
        m.values[Matrix.SkewX] = -k
        m.values[Matrix.TranslateX] = shear / 2f
        path.transform(m)
        return Outline.Generic(path)
    }

    /** Horizontal room the shear takes at each end of a box of this [height]. */
    fun inset(height: Dp): Dp = (height.value * tan(Math.toRadians(degrees.toDouble())).toFloat() / 2f).dp
}

object KSkew {
    val Bar = SkewShape(radius = 20.dp)
    val Pill = SkewShape(radius = 16.dp)
}
