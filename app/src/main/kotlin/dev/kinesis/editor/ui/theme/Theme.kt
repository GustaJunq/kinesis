package dev.kinesis.editor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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

/** The colours that change between the dark and the light theme. */
class KPalette(
    val isDark: Boolean,
    val background: Color,
    val bar: Color,
    val sheet: Color,
    val card: Color,
    val field: Color,
    val line: Color,
    val lineStrong: Color,
    val textPrimary: Color,
    val textSoft: Color,
    val textSecondary: Color,
    val disabled: Color,
    /** Solid accent for icons, text, sliders and outlines. */
    val accent: Color,
    val chipActive: Color,
    val onChipActive: Color,
    val keyframe: Color,
    val teal: Color,
    val pink: Color,
    /** Playhead and selection outline of the timeline. */
    val selection: Color,
)

val DarkPalette = KPalette(
    isDark = true,
    background = Color(0xFF06050E), bar = Color(0xFF0E0C1A), sheet = Color(0xFF12102A), card = Color(0xFF14112B),
    field = Color(0xFF1C1840), line = Color(0xFF1B1730), lineStrong = Color(0xFF2C2650),
    textPrimary = Color(0xFFF1EEFF), textSoft = Color(0xFFB9B4D6), textSecondary = Color(0xFF8F88B8), disabled = Color(0xFF5E577F),
    accent = Color(0xFFC084FC), chipActive = Color(0xFF2A2160), onChipActive = Color(0xFFE5D6FF),
    keyframe = Color(0xFFFFC857), teal = Color(0xFF4FD1C5), pink = Color(0xFFFF6B8B), selection = Color(0xFFFFFFFF),
)

val LightPalette = KPalette(
    isDark = false,
    background = Color(0xFFF6F4FF), bar = Color(0xFFFFFFFF), sheet = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF),
    field = Color(0xFFEEEAFB), line = Color(0xFFE6E1F5), lineStrong = Color(0xFFD3CCEB),
    textPrimary = Color(0xFF1A1530), textSoft = Color(0xFF4A4468), textSecondary = Color(0xFF666085), disabled = Color(0xFFA7A1C2),
    accent = Color(0xFF7C3AED), chipActive = Color(0xFFE6DAFF), onChipActive = Color(0xFF3B1E8A),
    keyframe = Color(0xFFC98A00), teal = Color(0xFF0B8F84), pink = Color(0xFFD42B52), selection = Color(0xFF1A1530),
)

/**
 * Every colour, type style and dimension of the UI. Screens use these tokens only (no hex values elsewhere).
 * The colours that depend on the theme read [palette], which is Compose state: screens recompose when it changes.
 * Contrast (dark): TextPrimary/TextSoft/TextSecondary and Accent pass 4.5:1 on Background, Bar, Sheet and Card.
 */
object KColors {
    var palette: KPalette by mutableStateOf(DarkPalette)

    // Surfaces
    val Background: Color get() = palette.background
    val Bar: Color get() = palette.bar
    val Sheet: Color get() = palette.sheet
    val Card: Color get() = palette.card
    val Field: Color get() = palette.field
    val Line: Color get() = palette.line
    val LineStrong: Color get() = palette.lineStrong

    // Text
    val TextPrimary: Color get() = palette.textPrimary
    val TextSoft: Color get() = palette.textSoft
    val TextSecondary: Color get() = palette.textSecondary
    val Disabled: Color get() = palette.disabled

    // Accent (gradient 135° from AccentStart to AccentEnd; OnAccent is text on it)
    val AccentStart = Color(0xFF8B5CF6)
    val AccentEnd = Color(0xFFC084FC)
    val OnAccent = Color(0xFF12082B)
    /** Solid accent for icons, text, sliders and outlines (darker in the light theme, for contrast). */
    val Accent: Color get() = palette.accent
    val ChipActive: Color get() = palette.chipActive
    val OnChipActive: Color get() = palette.onChipActive

    // Highlights
    val Keyframe: Color get() = palette.keyframe
    val Teal: Color get() = palette.teal
    val Pink: Color get() = palette.pink
    val Danger: Color get() = palette.pink
    /** Playhead and selection outline. */
    val Playhead: Color get() = palette.selection
    val Selection: Color get() = palette.selection
    val Scrim = Color(0xB3000000)
    val ScrimLight = Color(0x66000000)
    /** Pure white, for content drawn on images and clips (not a theme colour). */
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
    val Surface: Color get() = Card
    val SurfaceHigh: Color get() = Field
    val SurfaceHighest: Color get() = ChipActive
    val Outline: Color get() = LineStrong
    val AccentSoft: Color get() = ChipActive

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

private fun scheme(p: KPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = KColors.OnAccent,
        primaryContainer = p.chipActive,
        onPrimaryContainer = p.onChipActive,
        secondary = p.teal,
        onSecondary = KColors.OnAccent,
        secondaryContainer = p.chipActive,
        onSecondaryContainer = p.onChipActive,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.sheet,
        onSurface = p.textPrimary,
        surfaceVariant = p.field,
        onSurfaceVariant = p.textSoft,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.bar,
        surfaceContainer = p.sheet,
        surfaceContainerHigh = p.card,
        surfaceContainerHighest = p.field,
        inverseSurface = p.textPrimary,
        inverseOnSurface = p.background,
        outline = p.lineStrong,
        outlineVariant = p.line,
        error = p.pink,
        onError = KColors.OnAccent,
        scrim = KColors.Scrim,
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = KColors.OnAccent,
        primaryContainer = p.chipActive,
        onPrimaryContainer = p.onChipActive,
        secondary = p.teal,
        onSecondary = KColors.OnAccent,
        secondaryContainer = p.chipActive,
        onSecondaryContainer = p.onChipActive,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.sheet,
        onSurface = p.textPrimary,
        surfaceVariant = p.field,
        onSurfaceVariant = p.textSoft,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.bar,
        surfaceContainer = p.sheet,
        surfaceContainerHigh = p.card,
        surfaceContainerHighest = p.field,
        inverseSurface = p.textPrimary,
        inverseOnSurface = p.background,
        outline = p.lineStrong,
        outlineVariant = p.line,
        error = p.pink,
        onError = KColors.OnAccent,
        scrim = KColors.Scrim,
    )
}

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
fun KinesisTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    // Set before the content composes so that every token read below sees the right palette.
    if (KColors.palette !== palette) KColors.palette = palette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = remember(dark) { scheme(palette) }, typography = typography, shapes = shapes) {
        // Icons and text outside a Surface would otherwise default to the wrong colour.
        CompositionLocalProvider(LocalContentColor provides KColors.TextPrimary, content = content)
    }
}
