package dev.kinesis.editor.ui.home

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import dev.kinesis.editor.ui.common.OverlineLabel
import dev.kinesis.editor.ui.common.PillChip
import dev.kinesis.editor.ui.onboarding.LanguageChoices
import dev.kinesis.editor.ui.onboarding.ThemeChoices
import dev.kinesis.editor.ui.settings.AppSettings
import dev.kinesis.editor.ui.common.PrimaryButton
import dev.kinesis.editor.ui.common.SecondaryButton
import dev.kinesis.editor.ui.theme.DEFAULT_COMPOSITION_BACKGROUND
import dev.kinesis.editor.ui.theme.KBrushes
import dev.kinesis.editor.ui.theme.KColors
import dev.kinesis.editor.ui.theme.KDims
import dev.kinesis.editor.ui.theme.KShapes
import dev.kinesis.editor.ui.theme.KType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app)
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects = _projects.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    /** False until the project list has been read once (shows a loading state instead of the empty state). */
    val loaded = _loaded.asStateFlow()
    private val _importError = MutableStateFlow<String?>(null)
    /** Why the last .knsproj import failed (shown in a dialog), or null. */
    val importError = _importError.asStateFlow()

    fun dismissImportError() {
        _importError.value = null
    }
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val _interrupted = MutableStateFlow<ProjectSummary?>(null)
    /** The project that was open when the app was closed unexpectedly (offered for reopening once per process). */
    val interrupted = _interrupted.asStateFlow()

    init {
        store.cleanup()
        refresh()
        if (!recoveryChecked) {
            recoveryChecked = true
            viewModelScope.launch {
                _interrupted.value = withContext(Dispatchers.IO) {
                    store.interruptedSession()?.let { id -> store.list().firstOrNull { it.id == id } }
                }
            }
        }
    }

    fun dismissInterrupted() {
        _interrupted.value = null
        viewModelScope.launch(Dispatchers.IO) { store.clearSession() }
    }

    private companion object {
        var recoveryChecked = false
    }

    fun refresh() {
        viewModelScope.launch {
            _projects.value = withContext(Dispatchers.IO) { store.list() }
            _loaded.value = true
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
                _importError.value = getApplication<Application>().getString(R.string.import_invalid, e.message ?: "")
            } catch (e: Exception) {
                _importError.value = getApplication<Application>().getString(R.string.import_failed, e.message ?: e.javaClass.simpleName)
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

private enum class HomeTab { PROJECTS, PRESETS, SETTINGS }

@Composable
fun HomeScreen(onOpen: (String) -> Unit, incoming: Uri? = null, onIncomingHandled: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HomeViewModel = viewModel(factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))
    val projects by vm.projects.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val importError by vm.importError.collectAsState()
    val interrupted by vm.interrupted.collectAsState()
    var tab by rememberSaveable { mutableStateOf(HomeTab.PROJECTS) }
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
        bottomBar = { HomeNav(tab) { tab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                HomeTab.PROJECTS -> ProjectsTab(
                    projects = projects,
                    loaded = loaded,
                    onNew = { showNew = true },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onOpen = onOpen,
                    onDuplicate = vm::duplicate,
                    onExport = { p ->
                        exportId = p.id
                        exportLauncher.launch(p.title.replace(Regex("[^A-Za-z0-9_ -]+"), "_") + ".knsproj")
                    },
                    onDelete = { confirmDelete = it },
                )
                HomeTab.PRESETS -> PresetsTab { r ->
                    val (w, h) = dimensions(r, 1080)
                    vm.create(r.label, w, h, 30.0, 10.0, DEFAULT_BACKGROUND, onOpen)
                }
                HomeTab.SETTINGS -> SettingsTab()
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = KColors.Accent)
        }
    }

    if (showNew) {
        NewProjectDialog(onDismiss = { showNew = false }) { title, w, h, fps, duration, bg ->
            showNew = false
            vm.create(title, w, h, fps, duration, bg, onOpen)
        }
    }
    interrupted?.let { p ->
        AlertDialog(
            onDismissRequest = vm::dismissInterrupted,
            containerColor = KColors.Sheet,
            shape = KShapes.Card,
            title = { Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.recovery_message, p.title), color = KColors.TextSoft) },
            confirmButton = {
                TextButton(onClick = { vm.dismissInterrupted(); onOpen(p.id) }) { Text(stringResource(R.string.recovery_open), color = KColors.Accent) }
            },
            dismissButton = { TextButton(onClick = vm::dismissInterrupted) { Text(stringResource(R.string.recovery_later), color = KColors.TextSoft) } },
        )
    }
    importError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissImportError,
            containerColor = KColors.Sheet,
            shape = KShapes.Card,
            icon = { Icon(Icons.Outlined.FolderOpen, null, tint = KColors.Danger) },
            title = { Text(stringResource(R.string.import_error_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(message, color = KColors.TextSoft, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = vm::dismissImportError) { Text(stringResource(R.string.ok), color = KColors.Accent) } },
        )
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = KColors.Sheet,
            shape = KShapes.Card,
            title = { Text(stringResource(R.string.delete_project_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.delete_project_message, p.title), color = KColors.TextSoft) },
            confirmButton = {
                TextButton(onClick = { vm.delete(p.id); confirmDelete = null }) { Text(stringResource(R.string.delete), color = KColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel), color = KColors.TextSoft) } },
        )
    }
}

@Composable
private fun HomeNav(tab: HomeTab, onSelect: (HomeTab) -> Unit) {
    Column(Modifier.background(KColors.Bar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Line))
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            NavItem(Icons.Outlined.GridView, R.string.nav_projects, tab == HomeTab.PROJECTS, Modifier.weight(1f)) { onSelect(HomeTab.PROJECTS) }
            NavItem(Icons.Outlined.Style, R.string.nav_presets, tab == HomeTab.PRESETS, Modifier.weight(1f)) { onSelect(HomeTab.PRESETS) }
            NavItem(Icons.Outlined.Settings, R.string.nav_settings, tab == HomeTab.SETTINGS, Modifier.weight(1f)) { onSelect(HomeTab.SETTINGS) }
        }
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) KColors.OnChipActive else KColors.TextSecondary
    Column(
        modifier
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.width(56.dp).height(28.dp).clip(KShapes.Pill).background(if (selected) KColors.ChipActive else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)) }
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.kinesis_logo), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ProjectsTab(
    projects: List<ProjectSummary>,
    loaded: Boolean,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (ProjectSummary) -> Unit,
    onDelete: (ProjectSummary) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(KDims.Gutter, 12.dp, KDims.Gutter, 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Brand()
                Spacer(Modifier.height(18.dp))
                PrimaryButton(stringResource(R.string.new_project), onNew, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)
                Spacer(Modifier.height(10.dp))
                SecondaryButton(stringResource(R.string.open_knsproj), onImport, Modifier.fillMaxWidth(), icon = Icons.Outlined.FolderOpen, pill = true)
                Spacer(Modifier.height(18.dp))
                OverlineLabel(stringResource(R.string.your_projects))
            }
        }
        when {
            !loaded -> items(4) { SkeletonCard() }
            projects.isEmpty() -> item(span = { GridItemSpan(2) }) { EmptyProjects() }
            else -> items(projects, key = { it.id }) { p ->
                ProjectCard(p, onOpen = { onOpen(p.id) }, onDuplicate = { onDuplicate(p.id) }, onExport = { onExport(p) }, onDelete = { onDelete(p) })
            }
        }
    }
}

@Composable
private fun EmptyProjects() {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Movie, null, tint = KColors.TextSecondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_projects), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.no_projects_hint), color = KColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SkeletonCard() {
    Column(Modifier.clip(KShapes.Card).background(KColors.Card).semantics { contentDescription = "…" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(THUMB_RATIO).background(KColors.Field))
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(KShapes.Pill).background(KColors.Field))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(KShapes.Pill).background(KColors.Line))
        }
    }
}

private const val THUMB_RATIO = 1.3f

private fun durationLabel(seconds: Double) = String.format(Locale.getDefault(), "%.1f s", seconds)

@Composable
private fun ProjectCard(p: ProjectSummary, onOpen: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val thumb = remember(p.thumbnail, p.modified) {
        p.thumbnail?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
    }
    val broken = p.error != null
    Column(
        Modifier
            .clip(KShapes.Card)
            .background(KColors.Card)
            .border(1.dp, if (pressed) KColors.Accent else KColors.Line, KShapes.Card)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = !broken, role = Role.Button, onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(THUMB_RATIO).background(KColors.Field), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.Movie, null, tint = KColors.TextSecondary)
            }
        }
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (broken) {
                    Text(stringResource(R.string.project_unreadable), style = MaterialTheme.typography.bodySmall, color = KColors.Danger)
                } else {
                    Text("${durationLabel(p.duration)} · ${p.width}×${p.height}", style = KType.MonoSmall, color = KColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(KDims.MinTouch)) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_for, p.title), tint = KColors.TextSoft)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = KColors.Field) {
                    if (!broken) {
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

@Composable
private fun PresetsTab(onPick: (Ratio) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(KDims.Gutter, 12.dp, KDims.Gutter, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(stringResource(R.string.nav_presets), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.presets_hint), color = KColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        rowItems(RATIOS) { r ->
            val (w, h) = dimensions(r, 1080)
            Row(
                Modifier.fillMaxWidth().heightIn(min = KDims.MinTouch).clip(KShapes.Card).background(KColors.Card).border(1.dp, KColors.Line, KShapes.Card)
                    .clickable(role = Role.Button) { onPick(r) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    val scale = 36f / maxOf(r.w, r.h)
                    Box(Modifier.width((r.w * scale).dp).height((r.h * scale).dp).border(2.dp, KColors.Accent, RoundedCornerShape(4.dp)))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.titleSmall)
                    Text("$w×$h · 30 fps", style = KType.MonoSmall, color = KColors.TextSecondary)
                }
                Icon(Icons.Filled.Add, null, tint = KColors.TextSoft)
            }
        }
    }
}

@Composable
private fun SettingsTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KDims.Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().clip(KShapes.Card).background(KColors.Card).border(1.dp, KColors.Line, KShapes.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlineLabel(stringResource(R.string.settings_language))
            LanguageChoices()
            Spacer(Modifier.height(4.dp))
            OverlineLabel(stringResource(R.string.settings_appearance))
            ThemeChoices()
        }
        val context = LocalContext.current
        Column(Modifier.fillMaxWidth().clip(KShapes.Card).background(KColors.Card).border(1.dp, KColors.Line, KShapes.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlineLabel(stringResource(R.string.settings_preview))
            Text(stringResource(R.string.settings_preview_resolution), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f to "Full", 0.5f to "½", 0.25f to "¼").forEach { (scale, label) ->
                    PillChip(label, AppSettings.previewScale == scale, { AppSettings.setPreviewScale(context, scale) })
                }
            }
            Text(stringResource(R.string.settings_preview_3d), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.quality_low, R.string.quality_medium, R.string.quality_high).forEachIndexed { level, label ->
                    PillChip(stringResource(label), AppSettings.previewQuality3D == level, { AppSettings.setPreviewQuality3D(context, level) })
                }
            }
            SettingSwitch(stringResource(R.string.settings_proxies), stringResource(R.string.settings_proxies_hint), AppSettings.useProxies) {
                AppSettings.setUseProxies(context, it)
            }
        }
        Column(Modifier.fillMaxWidth().clip(KShapes.Card).background(KColors.Card).border(1.dp, KColors.Line, KShapes.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlineLabel(stringResource(R.string.settings_safety))
            SettingSwitch(stringResource(R.string.settings_mirror), stringResource(R.string.settings_mirror_hint), AppSettings.mirrorToDocuments) {
                AppSettings.setMirrorToDocuments(context, it)
            }
            Text(stringResource(R.string.settings_snapshots_hint), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
        }
        SecondaryButton(stringResource(R.string.settings_welcome), { AppSettings.setOnboardingDone(context, false) }, Modifier.fillMaxWidth(), pill = true)
        var showLicenses by remember { mutableStateOf(false) }
        SecondaryButton(stringResource(R.string.third_party_licenses), { showLicenses = true }, Modifier.fillMaxWidth(), pill = true)
        if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
        SettingRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME, mono = true)
        SettingRow(stringResource(R.string.settings_format), stringResource(R.string.settings_format_value))
        SettingRow(stringResource(R.string.settings_fonts), stringResource(R.string.settings_fonts_value))
    }
}

@Composable
private fun SettingSwitch(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = KColors.TextPrimary)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = KColors.Accent))
    }
}

@Composable
private fun SettingRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().clip(KShapes.Card).background(KColors.Card).border(1.dp, KColors.Line, KShapes.Card).padding(14.dp)) {
        OverlineLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(value, style = if (mono) KType.MonoLarge else MaterialTheme.typography.bodyMedium, color = KColors.TextPrimary)
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
    OverlineLabel(text, Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun OptionTile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(KShapes.Tile)
            .background(if (selected) KColors.ChipActive else KColors.Field)
            .border(1.5.dp, if (selected) KColors.Accent else androidx.compose.ui.graphics.Color.Transparent, KShapes.Tile)
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
                .clip(RoundedCornerShape(KDims.SheetRadius))
                .background(KColors.Sheet)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(stringResource(R.string.new_project), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.project_name)) },
                shape = KShapes.Tile,
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
                        Text("$w×$h", style = KType.MonoSmall, color = KColors.TextSecondary)
                    }
                }
            }

            SectionLabel(stringResource(R.string.fps_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(24.0, 30.0, 60.0).forEach { f ->
                    OptionTile(f == fps, { fps = f }, Modifier.weight(1f)) {
                        Text("${f.roundToInt()}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("fps", style = KType.MonoSmall, color = KColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
                PrimaryButton(
                    stringResource(R.string.create),
                    onClick = {
                        val (w, h) = dimensions(ratio, shortSide)
                        onCreate(title.trim().ifBlank { untitled }, w, h, fps, 10.0, DEFAULT_BACKGROUND)
                    },
                    modifier = Modifier.weight(2f),
                )
            }
        }
    }
}

private val DEFAULT_BACKGROUND = Color.parse(DEFAULT_COMPOSITION_BACKGROUND)

/** Third-party credits: which components Kinesis ships, under which license, and the full license texts. */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries = remember {
        listOf(
            R.string.license_vidstab to "vidstab-NOTICE.txt",
            R.string.license_gpl3 to "GPL-3.0.txt",
            R.string.license_mediapipe to "mediapipe-NOTICE.txt",
            R.string.license_apache to "apache-2.0.txt",
            R.string.license_others to "others-NOTICE.txt",
            R.string.license_manrope to "manrope-OFL.txt",
            R.string.license_barlow to "barlow-condensed-OFL.txt",
            R.string.license_jetbrains to "jetbrains-mono-OFL.txt",
        )
    }
    var open by remember { mutableStateOf<Int?>(null) }
    val text = remember(open) {
        open?.let { index ->
            runCatching { context.assets.open("licenses/" + entries[index].second).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(open?.let { entries[it].first } ?: R.string.third_party_licenses)) },
        text = {
            if (open == null) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.third_party_intro), style = MaterialTheme.typography.bodySmall, color = KColors.TextSecondary)
                    entries.forEachIndexed { i, (label, _) -> TextButton(onClick = { open = i }, Modifier.fillMaxWidth()) { Text(stringResource(label), Modifier.fillMaxWidth()) } }
                }
            } else {
                Text(text.orEmpty(), Modifier.verticalScroll(rememberScrollState()), fontSize = 11.sp, color = KColors.TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = { if (open != null) open = null else onDismiss() }) { Text(stringResource(if (open != null) R.string.back else R.string.close)) } },
    )
}
