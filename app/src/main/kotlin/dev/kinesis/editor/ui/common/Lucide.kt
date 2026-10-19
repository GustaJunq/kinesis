package dev.kinesis.editor.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons from Lucide (https://lucide.dev, ISC license, compatible with GPL v3), kept as local vectors: 24×24, 2 px
 * round strokes, no fill. The stroke is black; `Icon(tint = …)` recolours it.
 */
object Lucide {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}H${x + w - r}a$r $r 0 0 1 $r ${r}V${y + h - r}a$r $r 0 0 1 ${-r} ${r}H${x + r}a$r $r 0 0 1 ${-r} ${-r}V${y + r}a$r $r 0 0 1 $r ${-r}z"

    val ArrowLeft by lazy { icon("arrow-left", "m12 19-7-7 7-7", "M19 12H5") }
    val ChevronRight by lazy { icon("chevron-right", "m9 18 6-6-6-6") }
    val Settings by lazy {
        icon(
            "settings",
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
            circle(12f, 12f, 3f),
        )
    }
    val Video by lazy {
        icon("video", "m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5", rect(2f, 6f, 14f, 12f, 2f))
    }
    val Eye by lazy {
        icon("eye", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", circle(12f, 12f, 3f))
    }
    val Diamond by lazy {
        icon("diamond", "M2.7 10.3a2.41 2.41 0 0 0 0 3.41l7.59 7.59a2.41 2.41 0 0 0 3.41 0l7.59-7.59a2.41 2.41 0 0 0 0-3.41L13.7 2.71a2.41 2.41 0 0 0-3.41 0z")
    }
    val Move3d by lazy { icon("move-3d", "M5 3v16h16", "m5 19 6-6", "m2 6 3-3 3 3", "m18 16 3 3-3 3") }
    val Bookmark by lazy { icon("bookmark", "m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z") }
    val Undo2 by lazy { icon("undo-2", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11") }
    val Redo2 by lazy { icon("redo-2", "m15 14 5-5-5-5", "M20 9H9.5A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20H13") }
    val Copy by lazy { icon("copy", rect(8f, 8f, 14f, 14f, 2f), "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2") }
    val PanelRightClose by lazy { icon("panel-right-close", rect(3f, 3f, 18f, 18f, 2f), "M15 3v18", "m8 9 3 3-3 3") }
    val PanelRightOpen by lazy { icon("panel-right-open", rect(3f, 3f, 18f, 18f, 2f), "M15 3v18", "m10 15-3-3 3-3") }
    val SkipBack by lazy {
        icon("skip-back", "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z", "M3 20V4")
    }
    val SkipForward by lazy {
        icon("skip-forward", "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z", "M21 4v16")
    }
    val Play by lazy {
        icon("play", "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")
    }
    val Pause by lazy { icon("pause", rect(14f, 3f, 5f, 18f, 1f), rect(5f, 3f, 5f, 18f, 1f)) }

    // Tools
    val Layers by lazy {
        icon(
            "layers",
            "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z",
            "M22 17.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65",
            "M22 12.65l-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65",
        )
    }
    val Shapes by lazy {
        icon(
            "shapes",
            "M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z",
            rect(3f, 14f, 7f, 7f, 1f),
            circle(17.5f, 17.5f, 3.5f),
        )
    }
    val Type by lazy { icon("type", "M12 4v16", "M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2", "M9 20h6") }
    val Image by lazy { icon("image", rect(3f, 3f, 18f, 18f, 2f), circle(9f, 9f, 2f), "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val Box by lazy {
        icon(
            "box",
            "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z",
            "m3.3 7 8.7 5 8.7-5",
            "M12 22V12",
        )
    }
    val Sparkles by lazy {
        icon(
            "sparkles",
            "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z",
            "M20 3v4", "M22 5h-4", "M4 17v2", "M5 18H3",
        )
    }
    val Activity by lazy {
        icon("activity", "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2")
    }
    val Music by lazy { icon("music", "M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)) }
    val Camera by lazy {
        icon(
            "camera",
            "M13.997 4a2 2 0 0 1 1.76 1.05l.486.9A2 2 0 0 0 18.003 7H20a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1.997a2 2 0 0 0 1.759-1.048l.489-.904A2 2 0 0 1 10.004 4z",
            circle(12f, 13f, 3f),
        )
    }
    val Captions by lazy { icon("captions", rect(3f, 5f, 18f, 14f, 2f), "M7 15h4m4 0h2", "M7 11h2m4 0h4") }
}
