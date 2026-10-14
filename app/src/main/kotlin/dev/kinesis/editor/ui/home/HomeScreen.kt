package dev.kinesis.editor.ui.home

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private data class AspectPreset(val label: String, val width: Int, val height: Int)

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int, Double, Double, Color) -> Unit) {
    val presets = listOf(
        AspectPreset("9:16", 1080, 1920), AspectPreset("16:9", 1920, 1080), AspectPreset("1:1", 1080, 1080),
        AspectPreset("4:5", 1080, 1350), AspectPreset("4K", 3840, 2160),
    )
    val untitled = stringResource(R.string.untitled)
    var title by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(presets[0]) }
    var fps by remember { mutableStateOf(30.0) }
    var duration by remember { mutableStateOf("10") }
    var background by remember { mutableStateOf(Color.parse("#0E0F13")) }
    var picking by remember { mutableStateOf(false) }
    val durationValue = duration.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 3600 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_project)) },
        confirmButton = {
            TextButton(enabled = durationValue != null, onClick = {
                onCreate(title.ifBlank { untitled }, preset.width, preset.height, fps, durationValue ?: 10.0, background)
            }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true)
                Text(stringResource(R.string.format), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                ChoiceChips(presets.map { it to it.label }, preset, { preset = it })
                Text("${preset.width} × ${preset.height}", style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                Text(stringResource(R.string.frame_rate), style = MaterialTheme.typography.labelMedium, color = KColors.TextSecondary)
                ChoiceChips(listOf(24.0 to "24", 25.0 to "25", 30.0 to "30", 60.0 to "60"), fps, { fps = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = duration, onValueChange = { duration = it }, singleLine = true,
                        label = { Text(stringResource(R.string.duration_seconds)) }, modifier = Modifier.weight(1f),
                        isError = durationValue == null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.background), style = MaterialTheme.typography.labelSmall, color = KColors.TextSecondary)
                        ColorSwatch(background, { picking = true }, size = 36)
                    }
                }
            }
        },
    )
    if (picking) {
        ColorPickerDialog(background, onDismiss = { picking = false }, onChange = {}, onConfirm = { background = it; picking = false })
    }
}
