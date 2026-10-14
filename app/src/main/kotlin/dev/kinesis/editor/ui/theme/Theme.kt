package dev.kinesis.editor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object KColors {
    val Background = Color(0xFF121520)
    val Surface = Color(0xFF1B1E2B)
    val SurfaceHigh = Color(0xFF232736)
    val SurfaceHighest = Color(0xFF2D3244)
    val Outline = Color(0xFF363B52)
    val Accent = Color(0xFF4C8DFF)
    val AccentSoft = Color(0xFF1C3D75)
    val Teal = Color(0xFF4FD1C5)
    val Keyframe = Color(0xFFFFC857)
    val Danger = Color(0xFFFF5C7A)
    val TextPrimary = Color(0xFFECEDF3)
    val TextSecondary = Color(0xFF9EA1B3)
    val Playhead = Color(0xFFFF6B8B)
    val AxisX = Color(0xFFFF5C5C)
    val AxisY = Color(0xFF6BE675)
    val AxisZ = Color(0xFF5EA8FF)

    /** Clip colours per layer type in the timeline. */
    val ClipText = Color(0xFF6D5BD0)
    val ClipShape = Color(0xFF2F8F86)
    val ClipImage = Color(0xFF3A6EA8)
    val ClipVideo = Color(0xFF8A4F9E)
    val ClipAudio = Color(0xFF3F8F4F)
    val Clip3D = Color(0xFFB0703A)
    val ClipGroup = Color(0xFF555A70)
    val ClipCamera = Color(0xFF8F7B2F)
}

private val scheme = darkColorScheme(
    primary = KColors.Accent,
    onPrimary = Color.Black,
    primaryContainer = KColors.AccentSoft,
    onPrimaryContainer = Color.White,
    secondary = KColors.Teal,
    onSecondary = Color.Black,
    background = KColors.Background,
    onBackground = KColors.TextPrimary,
    surface = KColors.Surface,
    onSurface = KColors.TextPrimary,
    surfaceVariant = KColors.SurfaceHigh,
    onSurfaceVariant = KColors.TextSecondary,
    surfaceContainer = KColors.Surface,
    surfaceContainerHigh = KColors.SurfaceHigh,
    surfaceContainerHighest = KColors.SurfaceHighest,
    surfaceContainerLow = KColors.Surface,
    outline = KColors.Outline,
    outlineVariant = KColors.Outline,
    error = KColors.Danger,
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun KinesisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Icons and text outside a Surface would otherwise default to black on the dark background.
        CompositionLocalProvider(LocalContentColor provides KColors.TextPrimary, content = content)
    }
}
