package dev.kinesis.editor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.editor.R

/**
 * Every colour, type style and dimension of the UI. Screens use these tokens only (no hex values elsewhere).
 * Dark mode only. Contrast: TextPrimary/TextSoft/TextSecondary and Accent pass 4.5:1 on Background, Bar, Sheet and Card.
 */
object KColors {
    // Surfaces
    val Background = Color(0xFF06050E)
    val Bar = Color(0xFF0E0C1A)
    val Sheet = Color(0xFF12102A)
    val Card = Color(0xFF14112B)
    val Field = Color(0xFF1C1840)
    val Line = Color(0xFF1B1730)
    val LineStrong = Color(0xFF2C2650)

    // Text
    val TextPrimary = Color(0xFFF1EEFF)
    val TextSoft = Color(0xFFB9B4D6)
    val TextSecondary = Color(0xFF8F88B8)
    val Disabled = Color(0xFF5E577F)

    // Accent (gradient 135° from AccentStart to AccentEnd; OnAccent is text on it)
    val AccentStart = Color(0xFF8B5CF6)
    val AccentEnd = Color(0xFFC084FC)
    val OnAccent = Color(0xFF12082B)
    /** Solid accent for icons, text, sliders and outlines (the light end of the gradient, for contrast). */
    val Accent = AccentEnd
    val ChipActive = Color(0xFF2A2160)
    val OnChipActive = Color(0xFFE5D6FF)

    // Highlights
    val Keyframe = Color(0xFFFFC857)
    val Teal = Color(0xFF4FD1C5)
    val Pink = Color(0xFFFF6B8B)
    val Danger = Pink
    val Playhead = Color(0xFFFFFFFF)
    val Scrim = Color(0xB3000000)
    val ScrimLight = Color(0x66000000)
    val White = Color(0xFFFFFFFF)

    // Viewport gizmo axes
    val AxisX = Color(0xFFFF5C5C)
    val AxisY = Color(0xFF6BE675)
    val AxisZ = Color(0xFF5EA8FF)

    // Timeline clips
    val ClipTextStart = Color(0xFF6D45E8)
    val ClipTextEnd = Color(0xFF8B5CF6)
    val ClipTextSecondary = Color(0xFF5B3FD6)
    val ClipPink = Color(0xFFC23A62)
    val ClipAmber = Color(0xFFFFC857)
    val OnClipAmber = Color(0xFF1A1200)
    val ClipTeal = Color(0xFF167A72)
    val ClipViolet = Color(0xFF4A38B8)

    // Legacy names still used by some panels
    val Surface = Card
    val SurfaceHigh = Field
    val SurfaceHighest = ChipActive
    val Outline = LineStrong
    val AccentSoft = ChipActive

    /** Colour picker swatch background (transparency checker). */
    val CheckerA = Color(0xFF555555)
    val CheckerB = Color(0xFF999999)
}

object KBrushes {
    /** The accent gradient at 135° (top-left to bottom-right). */
    val Accent: Brush = Brush.linearGradient(listOf(KColors.AccentStart, KColors.AccentEnd), start = Offset.Zero, end = Offset.Infinite)
    val ClipText: Brush = Brush.horizontalGradient(listOf(KColors.ClipTextStart, KColors.ClipTextEnd))
    /** Dark scrim under labels drawn on images. */
    val ImageScrim: Brush = Brush.verticalGradient(listOf(KColors.ScrimLight, KColors.Scrim))
}

object KFonts {
    val Manrope = FontFamily(
        Font(R.font.manrope_500, FontWeight.Medium),
        Font(R.font.manrope_600, FontWeight.SemiBold),
        Font(R.font.manrope_700, FontWeight.Bold),
        Font(R.font.manrope_800, FontWeight.ExtraBold),
    )
    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_400, FontWeight.Normal),
        Font(R.font.jetbrains_mono_500, FontWeight.Medium),
        Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
    )
    /** Only for composition content shown in the UI (e.g. project placeholders). */
    val Display = FontFamily(Font(R.font.barlow_condensed_800, FontWeight.ExtraBold))
}

object KType {
    /** Timecodes and numeric values. */
    val Mono = TextStyle(fontFamily = KFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    val MonoSmall = Mono.copy(fontSize = 11.sp)
    val MonoLarge = Mono.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val Display = TextStyle(fontFamily = KFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
    /** Small uppercase section labels. */
    val Overline = TextStyle(fontFamily = KFonts.Manrope, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
}

object KDims {
    val MinTouch = 48.dp
    val TileRadius = 12.dp
    val CardRadius = 14.dp
    val SheetRadius = 22.dp
    val FieldRadius = 10.dp
    val ClipRadius = 7.dp
    val Gutter = 16.dp
    val TopBar = 56.dp
    val ToolBar = 64.dp
}

object KShapes {
    val Tile = RoundedCornerShape(KDims.TileRadius)
    val Card = RoundedCornerShape(KDims.CardRadius)
    val Field = RoundedCornerShape(KDims.FieldRadius)
    val Pill = RoundedCornerShape(percent = 50)
    val Sheet = RoundedCornerShape(topStart = KDims.SheetRadius, topEnd = KDims.SheetRadius)
    val Clip = RoundedCornerShape(KDims.ClipRadius)
}

/** Quick colours offered by the colour picker for layer content (composition data, not UI colours). */
val CONTENT_PALETTE = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF7C5CFF, 0xFF4FD1C5, 0xFFFF6B8B, 0xFFFFC857, 0xFF5EA8FF, 0xFF9BE564,
    0xFFFF8A3D, 0xFF0A2463, 0xFF1E4FBF, 0xFFC0C4CC, 0xFF2B2D42, 0xFFE63946,
)

/** Default composition background of new projects (composition data, not a UI colour). */
const val DEFAULT_COMPOSITION_BACKGROUND = "#0E0F13"

private val scheme = darkColorScheme(
    primary = KColors.Accent,
    onPrimary = KColors.OnAccent,
    primaryContainer = KColors.ChipActive,
    onPrimaryContainer = KColors.OnChipActive,
    secondary = KColors.Teal,
    onSecondary = KColors.OnAccent,
    secondaryContainer = KColors.ChipActive,
    onSecondaryContainer = KColors.OnChipActive,
    background = KColors.Background,
    onBackground = KColors.TextPrimary,
    surface = KColors.Sheet,
    onSurface = KColors.TextPrimary,
    surfaceVariant = KColors.Field,
    onSurfaceVariant = KColors.TextSoft,
    surfaceContainerLowest = KColors.Background,
    surfaceContainerLow = KColors.Bar,
    surfaceContainer = KColors.Sheet,
    surfaceContainerHigh = KColors.Card,
    surfaceContainerHighest = KColors.Field,
    inverseSurface = KColors.TextPrimary,
    inverseOnSurface = KColors.Background,
    outline = KColors.LineStrong,
    outlineVariant = KColors.Line,
    error = KColors.Pink,
    onError = KColors.OnAccent,
    scrim = KColors.Scrim,
)

private fun manrope(size: Int, weight: FontWeight, line: Int = size + 6) =
    TextStyle(fontFamily = KFonts.Manrope, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val typography = Typography(
    displaySmall = manrope(28, FontWeight.ExtraBold),
    headlineSmall = manrope(22, FontWeight.ExtraBold),
    titleLarge = manrope(20, FontWeight.ExtraBold),
    titleMedium = manrope(16, FontWeight.Bold),
    titleSmall = manrope(14, FontWeight.Bold),
    bodyLarge = manrope(15, FontWeight.Medium),
    bodyMedium = manrope(14, FontWeight.Medium),
    bodySmall = manrope(12, FontWeight.Medium),
    labelLarge = manrope(14, FontWeight.Bold),
    labelMedium = manrope(12, FontWeight.SemiBold),
    labelSmall = manrope(11, FontWeight.SemiBold),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = KShapes.Field,
    medium = KShapes.Tile,
    large = KShapes.Card,
    extraLarge = RoundedCornerShape(KDims.SheetRadius),
)

@Composable
fun KinesisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        // Icons and text outside a Surface would otherwise default to black on the dark background.
        CompositionLocalProvider(LocalContentColor provides KColors.TextPrimary, content = content)
    }
}
