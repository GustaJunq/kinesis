package dev.kinesis.editor.ui.onboarding

import android.app.Activity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.common.PrimaryButton
import dev.kinesis.editor.ui.editor.highlightJs
import dev.kinesis.editor.ui.settings.AppLanguage
import dev.kinesis.editor.ui.settings.AppSettings
import dev.kinesis.editor.ui.settings.ThemeMode
import dev.kinesis.editor.ui.theme.DarkPalette
import dev.kinesis.editor.ui.theme.KBrushes
import dev.kinesis.editor.ui.theme.LightPalette
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.theme.KType
import kotlinx.coroutines.launch

/** First-run tour: what Kinesis is, how to animate, expressions, and the language and theme choice. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = 4
    val state = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()
    val last = state.currentPage == pages - 1
    Column(Modifier.fillMaxSize().background(KColors.Background)) {
        Row(Modifier.fillMaxWidth().height(KDims.TopBar).padding(horizontal = KDims.Gutter), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (!last) {
                Text(
                    stringResource(R.string.onb_skip), color = KColors.TextSoft, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(KShapes.Pill).clickable(role = Role.Button, onClick = onFinish).padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        HorizontalPager(state, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.height(220.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (page) {
                        0 -> BrandArt()
                        1 -> TimelineArt()
                        2 -> ExpressionArt()
                        else -> ChoicesArt()
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(TITLES[page]), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(BODIES[page]), style = MaterialTheme.typography.bodyLarge, color = KColors.TextSoft, textAlign = TextAlign.Center)
                if (page == pages - 1) {
                    Spacer(Modifier.height(20.dp))
                    Choices()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(KDims.Gutter), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages) { i ->
                    Box(
                        Modifier.size(if (i == state.currentPage) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                            .background(if (i == state.currentPage) KColors.Accent else KColors.LineStrong),
                    )
                }
            }
            PrimaryButton(
                stringResource(if (last) R.string.onb_start else R.string.onb_next),
                onClick = { if (last) onFinish() else scope.launch { state.animateScrollToPage(state.currentPage + 1) } },
            )
        }
    }
}

private val TITLES = listOf(R.string.onb_title_1, R.string.onb_title_2, R.string.onb_title_3, R.string.onb_title_4)
private val BODIES = listOf(R.string.onb_body_1, R.string.onb_body_2, R.string.onb_body_3, R.string.onb_body_4)

/** Language and theme pickers together (the last page of the tour). */
@Composable
fun Choices() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LanguageChoices()
        ThemeChoices()
    }
}

@Composable
fun LanguageChoices() {
    val activity = LocalContext.current as? Activity
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf(AppLanguage.ENGLISH to R.string.lang_en, AppLanguage.PORTUGUESE to R.string.lang_pt, AppLanguage.SYSTEM to R.string.lang_system)
        for ((lang, label) in options) {
            PillChip(stringResource(label), AppSettings.language == lang, { if (activity != null && AppSettings.language != lang) AppSettings.setLanguage(activity, lang) })
        }
    }
}

@Composable
fun ThemeChoices() {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf(ThemeMode.DARK to R.string.theme_dark, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.SYSTEM to R.string.theme_system)
        for ((mode, label) in options) {
            PillChip(stringResource(label), AppSettings.themeMode == mode, { AppSettings.setThemeMode(context, mode) })
        }
    }
}

@Composable
private fun BrandArt() {
    val t = rememberInfiniteTransition(label = "brand")
    val angle by t.animateFloat(-5f, 5f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "tilt")
    Image(
        painterResource(R.drawable.kinesis_logo), contentDescription = stringResource(R.string.app_name),
        modifier = Modifier.size(150.dp).rotate(angle),
    )
}

@Composable
private fun TimelineArt() {
    val t = rememberInfiniteTransition(label = "timeline")
    val progress by t.animateFloat(0.1f, 0.9f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "playhead")
    val accent = KColors.Accent
    val keyframe = KColors.Keyframe
    val playhead = KColors.Selection
    val card = KColors.Card
    val line = KColors.Line
    Canvas(Modifier.fillMaxWidth().height(190.dp).clip(KShapes.Card).background(card).border(1.dp, line, KShapes.Card)) {
        val rows = listOf(
            Triple(0.05f, 0.55f, KColors.ClipTextStart),
            Triple(0.25f, 0.85f, KColors.ClipPink),
            Triple(0.15f, 0.7f, KColors.ClipTeal),
        )
        val rowH = size.height / 4.2f
        rows.forEachIndexed { i, (from, to, color) ->
            val top = rowH * 0.5f + i * rowH * 1.15f
            drawRoundRect(color, Offset(size.width * from, top), Size(size.width * (to - from), rowH * 0.8f), CornerRadius(8.dp.toPx()))
            for (k in listOf(0.25f, 0.55f, 0.8f)) {
                val cx = size.width * (from + (to - from) * k)
                val cy = top + rowH * 0.4f
                val r = 5.dp.toPx()
                val path = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
                drawPath(path, keyframe)
            }
        }
        val x = size.width * progress
        drawLine(playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        drawCircle(playhead, 5.dp.toPx(), Offset(x, 6.dp.toPx()))
        drawCircle(accent, 3.dp.toPx(), Offset(x, 6.dp.toPx()))
    }
}

@Composable
private fun ExpressionArt() {
    val t = rememberInfiniteTransition(label = "bounce")
    val bounce by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "t")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            "BOUNCE".forEachIndexed { i, c ->
                val local = (bounce - i * 0.06f).coerceIn(0f, 1f)
                val lift = (Math.sin(local * Math.PI * 2.0) * Math.exp(-3.0 * local) * 22.0).toFloat()
                Text(c.toString(), color = KColors.Keyframe, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = lift.coerceAtLeast(0f).dp))
            }
        }
        Text(
            highlightJs("wiggle(3, 40)\nloopOut(\"pingpong\")"),
            style = KType.Mono.copy(fontSize = 14.sp, color = KColors.TextPrimary),
            modifier = Modifier.clip(KShapes.Tile).background(KColors.Field).border(1.dp, KColors.Line, KShapes.Tile).padding(16.dp).fillMaxWidth(0.8f),
        )
    }
}

@Composable
private fun ChoicesArt() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(true, false).forEach { dark ->
            val palette = if (dark) DarkPalette else LightPalette
            val bg = palette.card
            val fg = palette.textPrimary
            Column(
                Modifier.size(104.dp, 132.dp).clip(KShapes.Card).background(bg).border(1.dp, KColors.LineStrong, KShapes.Card).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(KBrushes.Accent))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(KShapes.Pill).background(fg.copy(alpha = 0.7f)))
                Box(Modifier.fillMaxWidth(0.6f).height(8.dp).clip(KShapes.Pill).background(fg.copy(alpha = 0.35f)))
                Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = 0.12f)))
            }
        }
    }
}
