package dev.kinesis.editor.ui.home

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.editor.BuildConfig
import dev.kinesis.editor.R
import dev.kinesis.editor.data.ProjectImportException
import dev.kinesis.editor.data.ProjectStore
import dev.kinesis.editor.data.ProjectSummary
import dev.kinesis.editor.ui.common.ChoiceChips
import dev.kinesis.editor.ui.common.ColorPickerDialog
import dev.kinesis.editor.ui.common.ColorSwatch
import dev.kinesis.editor.ui.common.formatTime
import dev.kinesis.editor.ui.theme.KColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects = _projects.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    init {
        store.cleanup()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _projects.value = withContext(Dispatchers.IO) { store.list() }
        }
    }

    fun create(title: String, width: Int, height: Int, fps: Double, duration: Double, background: Color, open: (String) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                store.create(Factory.newProject(title, width, height, fps, duration, background, Instant.now().toString(), "Kinesis Editor ${BuildConfig.VERSION_NAME}"))
            }
            refresh()
            open(id)
        }
    }

    fun import(uri: Uri, open: (String) -> Unit) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            _busy.value = true
            try {
                val id = withContext(Dispatchers.IO) {
                    (resolver.openInputStream(uri) ?: error("Cannot open file")).use { store.import(it) }
                }
                refresh()
                open(id)
            } catch (e: ProjectImportException) {
                messages.tryEmit(getApplication<Application>().getString(R.string.import_invalid, e.message ?: ""))
            } catch (e: Exception) {
                messages.tryEmit(getApplication<Application>().getString(R.string.import_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _busy.value = false
            }
        }
    }

    fun export(id: String, uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            _busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    (resolver.openOutputStream(uri, "wt") ?: error("Cannot write file")).use { store.export(id, it) }
                }
                messages.tryEmit(getApplication<Application>().getString(R.string.export_project_done))
            } catch (e: Exception) {
                messages.tryEmit(getApplication<Application>().getString(R.string.export_project_failed, e.message ?: ""))
            } finally {
                _busy.value = false
            }
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.duplicate(id) }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, incoming: Uri? = null, onIncomingHandled: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HomeViewModel = viewModel(factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))
    val projects by vm.projects.collectAsState()
    val busy by vm.busy.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            vm.import(incoming, onOpen)
            onIncomingHandled()
        }
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, onOpen)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportId
        if (uri != null && id != null) vm.export(id, uri)
        exportId = null
    }

    Scaffold(
        containerColor = KColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = stringResource(R.string.import_project))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KColors.Background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.new_project)) },
                containerColor = KColors.Accent,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (projects.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Movie, null, tint = KColors.TextSecondary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.no_projects), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.no_projects_hint), color = KColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(projects, key = { it.id }) { p ->
                        ProjectCard(
                            p,
                            onOpen = { onOpen(p.id) },
                            onDuplicate = { vm.duplicate(p.id) },
                            onExport = {
                                exportId = p.id
                                exportLauncher.launch(p.title.replace(Regex("[^A-Za-z0-9_ -]+"), "_") + ".knsproj")
                            },
                            onDelete = { confirmDelete = p },
                        )
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (showNew) {
        NewProjectDialog(onDismiss = { showNew = false }) { title, w, h, fps, duration, bg ->
            showNew = false
            vm.create(title, w, h, fps, duration, bg, onOpen)
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_project_title)) },
            text = { Text(stringResource(R.string.delete_project_message, p.title)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(p.id); confirmDelete = null }) { Text(stringResource(R.string.delete), color = KColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ProjectCard(p: ProjectSummary, onOpen: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumb = remember(p.thumbnail, p.modified) {
        p.thumbnail?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(KColors.Surface)
            .clickable(enabled = p.error == null, onClick = onOpen),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(KColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            } else {
                Icon(Icons.Outlined.Movie, null, tint = KColors.TextSecondary)
            }
        }
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (p.error != null) stringResource(R.string.project_unreadable) else "${p.width}×${p.height} · ${formatTime(p.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.error != null) KColors.Danger else KColors.TextSecondary,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (p.error == null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_knsproj)) }, onClick = { menu = false; onExport() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = KColors.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private data class Ratio(val label: String, val w: Int, val h: Int)

private val RATIOS = listOf(Ratio("9:16", 9, 16), Ratio("16:9", 16, 9), Ratio("1:1", 1, 1), Ratio("4:5", 4, 5), Ratio("3:4", 3, 4), Ratio("4:3", 4, 3))

/** Pixel size for an aspect ratio whose shorter side has [shortSide] pixels (even numbers, as MP4 needs). */
private fun dimensions(r: Ratio, shortSide: Int): Pair<Int, Int> {
    fun even(v: Double) = ((v / 2).roundToInt() * 2).coerceAtLeast(16)
    return if (r.w <= r.h) shortSide to even(shortSide.toDouble() * r.h / r.w) else even(shortSide.toDouble() * r.w / r.h) to shortSide
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun OptionTile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) KColors.AccentSoft.copy(alpha = 0.55f) else KColors.SurfaceHigh)
            .border(1.5.dp, if (selected) KColors.Accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** New project: only a name, an aspect ratio, a quality and a frame rate. */
@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int, Double, Double, Color) -> Unit) {
    val untitled = stringResource(R.string.untitled)
    var title by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf(RATIOS[0]) }
    var shortSide by remember { mutableStateOf(1080) }
    var fps by remember { mutableStateOf(30.0) }
    val qualities = listOf(720 to R.string.quality_hd, 1080 to R.string.quality_fhd, 2160 to R.string.quality_4k)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(20.dp))
                .background(KColors.Surface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(stringResource(R.string.new_project), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.project_name)) },
                shape = RoundedCornerShape(12.dp),
            )

            SectionLabel(stringResource(R.string.aspect_ratio))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems(RATIOS) { r ->
                    OptionTile(r == ratio, { ratio = r }, Modifier.width(78.dp)) {
                        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                            val scale = 40f / maxOf(r.w, r.h)
                            Box(
                                Modifier
                                    .width((r.w * scale).dp)
                                    .height((r.h * scale).dp)
                                    .border(2.dp, if (r == ratio) KColors.Accent else KColors.TextSecondary, RoundedCornerShape(4.dp)),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(r.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            SectionLabel(stringResource(R.string.quality))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                qualities.forEach { (side, label) ->
                    val (w, h) = dimensions(ratio, side)
                    OptionTile(side == shortSide, { shortSide = side }, Modifier.weight(1f)) {
                        Text(stringResource(label), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("$w×$h", fontSize = 11.sp, color = KColors.TextSecondary)
                    }
                }
            }

            SectionLabel(stringResource(R.string.fps_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(24.0, 30.0, 60.0).forEach { f ->
                    OptionTile(f == fps, { fps = f }, Modifier.weight(1f)) {
                        Text("${f.roundToInt()}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("fps", fontSize = 11.sp, color = KColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = {
                        val (w, h) = dimensions(ratio, shortSide)
                        onCreate(title.trim().ifBlank { untitled }, w, h, fps, 10.0, DEFAULT_BACKGROUND)
                    },
                    modifier = Modifier.weight(2f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.create), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private val DEFAULT_BACKGROUND = Color.parse("#0E0F13")
