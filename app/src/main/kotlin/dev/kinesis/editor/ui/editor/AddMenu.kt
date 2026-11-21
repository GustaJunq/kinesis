package dev.kinesis.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kinesis.core.math.Vec2
import dev.kinesis.editor.R
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KShapes

/** Tabs of the add menu. */
enum class AddTab(val label: Int) {
    SHAPE(R.string.add_tab_shape),
    MEDIA(R.string.add_tab_media),
    AUDIO(R.string.add_tab_audio),
    OBJECT(R.string.add_tab_object),
    MODEL(R.string.add_tab_model),
}

/** What the add menu can do besides adding a layer from [AddAction]. */
class AddMenuActions(
    val onAction: (AddAction) -> Unit,
    val onShape: (ShapePreset) -> Unit,
    val onElements: () -> Unit,
    val onCaptions: () -> Unit,
    val onPlugins: () -> Unit,
    val onFreehand: () -> Unit,
    val onVector: () -> Unit,
)

private class MenuItem(val label: Int, val icon: ImageVector, val onClick: () -> Unit)

private const val SHAPE_COLUMNS = 7
private const val SHAPE_ROWS = 3

/**
 * The add menu: tabs for shapes, media, sound, objects and elements, and 3D models; a column at the right draws by hand,
 * draws a vector path or adds text. Shapes come in pages; path shapes show their editable points.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AddMenu(initial: AddTab, actions: AddMenuActions, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KColors.Sheet, shape = KShapes.Sheet, dragHandle = null) {
        Row(Modifier.fillMaxWidth().height(360.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ScrollableTabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = KColors.Sheet,
                    contentColor = KColors.TextPrimary,
                    edgePadding = 8.dp,
                    indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab.ordinal]), color = KColors.Primary) },
                    divider = {},
                ) {
                    for (t in AddTab.entries) {
                        Tab(
                            selected = t == tab,
                            onClick = { tab = t },
                            text = { Text(stringResource(t.label), maxLines = 1, style = MaterialTheme.typography.labelLarge) },
                            selectedContentColor = KColors.Primary,
                            unselectedContentColor = KColors.TextSecondary,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        AddTab.SHAPE -> ShapePages(actions.onShape)
                        AddTab.MEDIA -> ItemGrid(
                            listOf(
                                MenuItem(R.string.layer_image, Icons.Filled.Image) { actions.onAction(AddAction.IMAGE) },
                                MenuItem(R.string.layer_video, Icons.Filled.Movie) { actions.onAction(AddAction.VIDEO) },
                            ),
                        )
                        AddTab.AUDIO -> ItemGrid(
                            listOf(
                                MenuItem(R.string.layer_audio, Icons.Filled.Audiotrack) { actions.onAction(AddAction.AUDIO) },
                                MenuItem(R.string.captions_short, Icons.Filled.Subtitles, actions.onCaptions),
                                MenuItem(R.string.tts_short, Icons.Filled.RecordVoiceOver, actions.onPlugins),
                            ),
                        )
                        AddTab.OBJECT -> ItemGrid(
                            listOf(
                                MenuItem(R.string.elements, Icons.Outlined.Widgets, actions.onElements),
                                MenuItem(R.string.layer_null, Icons.Filled.CenterFocusStrong) { actions.onAction(AddAction.NULL_2D) },
                                MenuItem(R.string.null_3d, Icons.Filled.CenterFocusStrong) { actions.onAction(AddAction.NULL_3D) },
                                MenuItem(R.string.layer_camera, Icons.Filled.CameraAlt) { actions.onAction(AddAction.CAMERA) },
                                MenuItem(R.string.light_directional, Icons.Filled.WbSunny) { actions.onAction(AddAction.LIGHT_DIRECTIONAL) },
                                MenuItem(R.string.light_point, Icons.Filled.Lightbulb) { actions.onAction(AddAction.LIGHT_POINT) },
                                MenuItem(R.string.light_ambient, Icons.Filled.LightMode) { actions.onAction(AddAction.LIGHT_AMBIENT) },
                                MenuItem(R.string.plugins_short, Icons.Outlined.Extension, actions.onPlugins),
                            ),
                        )
                        AddTab.MODEL -> ItemGrid(
                            listOf(
                                MenuItem(R.string.prim_cube, Icons.Filled.ViewInAr) { actions.onAction(AddAction.CUBE) },
                                MenuItem(R.string.prim_sphere, Icons.Filled.Circle) { actions.onAction(AddAction.SPHERE) },
                                MenuItem(R.string.prim_cylinder, Icons.Filled.Token) { actions.onAction(AddAction.CYLINDER) },
                                MenuItem(R.string.prim_cone, Icons.Filled.Category) { actions.onAction(AddAction.CONE) },
                                MenuItem(R.string.prim_torus, Icons.Filled.DonutLarge) { actions.onAction(AddAction.TORUS) },
                                MenuItem(R.string.prim_plane, Icons.Filled.Square) { actions.onAction(AddAction.PLANE) },
                                MenuItem(R.string.prim_text3d, Icons.Filled.TextFields) { actions.onAction(AddAction.TEXT3D) },
                                MenuItem(R.string.layer_model3d, Icons.Filled.Token) { actions.onAction(AddAction.MODEL) },
                            ),
                        )
                    }
                }
            }
            // Side column: draw by hand, draw a vector path, text, close.
            Column(
                Modifier.width(88.dp).fillMaxHeight().background(KColors.SurfaceAlt).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                SideItem(Icons.Filled.Gesture, R.string.draw_freehand, actions.onFreehand)
                SideItem(Icons.Filled.Draw, R.string.draw_vector, actions.onVector)
                SideItem(Icons.Filled.TextFields, R.string.add_text) { actions.onAction(AddAction.TEXT) }
                SideItem(Icons.Filled.Close, R.string.close, onDismiss)
            }
        }
    }
}

@Composable
private fun SideItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = KColors.TextPrimary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = KColors.TextPrimary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ItemGrid(items: List<MenuItem>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items) { item ->
            Column(
                Modifier.clip(KShapes.Tile).background(KColors.Field).clickable(role = Role.Button, onClick = item.onClick).padding(vertical = 12.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, null, tint = KColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShapePages(onShape: (ShapePreset) -> Unit) {
    val perPage = SHAPE_COLUMNS * SHAPE_ROWS
    val pages = ShapePresets.all.chunked(perPage)
    val state = rememberPagerState { pages.size }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state, Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                for (r in pages[page].chunked(SHAPE_COLUMNS)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (preset in r) ShapeTile(preset, Modifier.weight(1f)) { onShape(preset) }
                        repeat(SHAPE_COLUMNS - r.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape)
                        .background(if (i == state.currentPage) KColors.TextPrimary else KColors.LineStrong),
                )
            }
        }
    }
}

/** A shape drawn from its own geometry; path shapes show their points. */
@Composable
private fun ShapeTile(preset: ShapePreset, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(preset.label)
    Box(
        modifier.padding(2.dp).aspectRatio(1f).clip(KShapes.Tile).background(KColors.Field)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val s = size.minDimension * 0.95f
            val c = Offset(size.width / 2f, size.height / 2f)
            fun at(v: Vec2) = Offset(c.x + v.x * s, c.y + v.y * s)
            val color = KColors.TextSoft
            val pts = preset.outline()
            if (pts.size < 2) return@Canvas
            val path = Path().apply {
                if (preset.isPath) {
                    val pp = preset.points
                    moveTo(at(pp[0].point).x, at(pp[0].point).y)
                    val n = if (preset.closed) pp.size else pp.size - 1
                    for (i in 0 until n) {
                        val a = pp[i]
                        val b = pp[(i + 1) % pp.size]
                        val c1 = at(a.point + a.outTangent)
                        val c2 = at(b.point + b.inTangent)
                        val e = at(b.point)
                        if (a.outTangent == Vec2.ZERO && b.inTangent == Vec2.ZERO) lineTo(e.x, e.y) else cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
                    }
                } else {
                    moveTo(at(pts[0]).x, at(pts[0]).y)
                    for (i in 1 until pts.size) lineTo(at(pts[i]).x, at(pts[i]).y)
                }
                if (preset.closed) close()
            }
            if (preset.strokeOnly) drawPath(path, color, style = Stroke(width = s * 0.08f)) else drawPath(path, color)
            if (preset.isPath) {
                for (p in preset.points) {
                    drawCircle(KColors.Field, 2.dp.toPx(), at(p.point))
                    drawCircle(KColors.TextPrimary, 1.3.dp.toPx(), at(p.point))
                }
            }
        }
    }
}
