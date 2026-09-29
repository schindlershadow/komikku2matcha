package com.schindler.k2m

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncService.createChannels(this)
        AutoSync.schedule(this)  // keeps the periodic work registered (no-op when auto-sync is off)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(vm)
            }
        }
    }
}

private val TABS = listOf("Library", "Jobs", "Books", "Settings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(if (vm.prefs.token.isBlank()) 3 else 0) }
    val snack = remember { SnackbarHostState() }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (vm.prefs.token.isNotBlank()) vm.refreshAll()
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = {
                    Column {
                        Text("Komikku → Matcha")
                        Text(vm.serverUrl ?: "not connected", style = MaterialTheme.typography.labelSmall)
                    }
                })
                StatusLine(vm)
            }
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, name ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Text(listOf("📚", "⚙", "📖", "🔧")[i]) }, label = { Text(name) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> LibraryScreen(vm) { tab = 3 }
                1 -> JobsScreen(vm)
                2 -> BooksScreen(vm)
                3 -> SettingsScreen(vm)
            }
        }
    }
}

/** Reads the fast-changing progress text in its own scope, so updates redraw only this line. */
@Composable
fun StatusLine(vm: AppViewModel) {
    val taskStatus by TaskBus.status.collectAsState()
    val status = vm.busy ?: taskStatus ?: return
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(status, Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Only background tasks (uploads, sends, watching a job) can be cancelled; quick in-app checks can't.
        if (taskStatus != null) TextButton(onClick = { vm.cancelTask() }) { Text("Cancel") }
    }
    val transfer by TaskBus.transfer.collectAsState()
    val left by TaskBus.bytesLeft.collectAsState()
    transfer?.let { TransferTile(it, left) }
}

/** Where the X4's card is: in the X4 (reached over WiFi) or plugged into this phone. */
@Composable
fun TargetChooser(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.setCard(uri) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("The X4's card:", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.selectable(vm.target != CARD) { vm.setTargetTo("wifi") }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(vm.target != CARD, null); Text("in the X4 (WiFi)", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.selectable(vm.target == CARD) { if (vm.cardName == null) picker.launch(null) else vm.setTargetTo(CARD) },
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(vm.target == CARD, null); Text("in this phone", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (vm.target == CARD) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("SD card: ${vm.cardName ?: "not picked"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { picker.launch(null) }) { Text(if (vm.cardName == null) "Pick SD card" else "Change") }
    }
}

/** A series' cover art from the server (a placeholder box until it loads, or if there is none). */
@Composable
fun SeriesCover(vm: AppViewModel, folder: String, width: androidx.compose.ui.unit.Dp) {
    LaunchedEffect(folder) { vm.loadCover(folder) }
    val img = vm.covers[folder]
    val mod = Modifier.width(width).height(width * 1.45f).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
    if (img != null) androidx.compose.foundation.Image(img, contentDescription = "$folder cover", modifier = mod,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(mod.background(MaterialTheme.colorScheme.surfaceVariant))
}

// ── Library ─────────────────────────────────────────────────────

private fun stateColor(s: String) = when (s) {
    "converted" -> Color(0xFF2E7D32); "pending" -> Color(0xFFEF6C00); "new", "changed" -> Color(0xFF1565C0); else -> Color.Gray
}

private fun stateLabel(s: String) = when (s) {
    "new" -> "on phone only"; "changed" -> "re-downloaded"; "pending" -> "not converted"; "ignored" -> "not synced (deleted)"; else -> s
}

@Composable
fun LibraryScreen(vm: AppViewModel, openSettings: () -> Unit) {
    val library = vm.library
    val all = remember(library) {
        library.flatMap { it.counts.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val allChapters = remember(library) { library.flatMap { it.chapters } }
    val selectedRows = remember(allChapters, selected) { allChapters.filter { "${it.title}/${it.file}" in selected } }
    var confirmSync by remember { mutableStateOf<Pair<List<String>, () -> Unit>?>(null) }
    var chooseEngine by remember { mutableStateOf<Pair<List<ChapterRow>, () -> Unit>?>(null) }
    fun trySync(rows: List<ChapterRow>, onServer: () -> Unit) {
        val proceed: () -> Unit = { if (vm.prefs.phoneConvertFallback) chooseEngine = rows to onServer else onServer() }
        val novels = rows.filter { it.looksLikeNovel }.map { it.title }.distinct()
        if (novels.isNotEmpty()) confirmSync = novels to proceed else proceed()
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        vm.update?.let { u ->
            item(key = "update") {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Update ${u.versionName} available", fontWeight = FontWeight.SemiBold)
                            Text("Installed: ${Updater.installedName(androidx.compose.ui.platform.LocalContext.current)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { vm.installUpdate() }) { Text("Install") }
                    }
                }
            }
        }
        if (vm.prefs.komikkuTree == null) item(key = "pick") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pick Komikku's download folder in Settings so new chapters can be found and uploaded.")
                    TextButton(onClick = openSettings) { Text("Open Settings") }
                }
            }
        }
        item(key = "summary") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOf("new", "changed", "pending", "converted").mapNotNull { s -> all[s]?.let { "$it ${stateLabel(s)}" } }
                    .joinToString(" · ").ifEmpty { "No chapters yet" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.refreshAll() }) { Text("Scan") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { trySync(allChapters) { vm.sync() } },
                    enabled = (all["new"] ?: 0) + (all["changed"] ?: 0) + (all["pending"] ?: 0) > 0) { Text("Sync all") }
            }
            if (selected.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                Button(onClick = { trySync(selectedRows) { vm.syncChapters(selectedRows); selected = emptySet() } },
                    Modifier.weight(1f)) { Text("Sync ${selected.size} selected") }
            }
        }
        for (t in library) {
            val open = t.title in expanded
            item(key = "t:${t.title}") {
                var menu by remember { mutableStateOf(false) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) vm.changeCover(t, uri)
                }
                Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(Modifier.clickable { expanded = if (open) expanded - t.title else expanded + t.title }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        t.folder?.let { f -> Box(Modifier.clickable { vm.openCoverPicker(t) }) { SeriesCover(vm, f, 48.dp) } }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(t.title, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.counts.entries.joinToString(" · ") { "${it.value} ${stateLabel(it.key)}" } +
                                    (if (t.chapters.any { it.looksLikeNovel }) " · ⚠ looks like a novel" else ""),
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if (t.counts.keys.any { it != "converted" }) TextButton(onClick = { trySync(t.chapters) { vm.sync(listOf(t)) } }) { Text("Sync") }
                            }
                        }
                        if (t.folder != null) Box {
                            TextButton(onClick = { menu = true }) { Text("⋯") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Change cover…") }, onClick = { menu = false; picker.launch("image/*") })
                                DropdownMenuItem(text = { Text("Use AniList cover") }, onClick = { menu = false; vm.resetCover(t, refetch = false) })
                                DropdownMenuItem(text = { Text("Choose cover…") }, onClick = { menu = false; vm.openCoverPicker(t) })
                                DropdownMenuItem(text = { Text("Search AniList again") }, onClick = { menu = false; vm.resetCover(t, refetch = true) })
                                if (t.chapters.any { it.ignored }) DropdownMenuItem(text = { Text("Sync again (stop ignoring)") },
                                    onClick = { menu = false; vm.unignore(t) })
                            }
                        }
                    }
                }
            }
            // One lazy item per chapter: only the rows on screen are laid out.
            if (open) items(t.chapters, key = { "c:${t.title}/${it.file}" }) { c ->
                val key = "${c.title}/${c.file}"
                val on = key in selected
                Row(Modifier.fillMaxWidth().clickable { selected = if (on) selected - key else selected + key }
                    .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(c.file.removeSuffix(".cbz") + (if (c.looksLikeNovel) " ⚠" else ""),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(stateLabel(c.state) + (c.server?.textBlocks?.let { " · $it texts" } ?: ""),
                        color = stateColor(c.state), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    confirmSync?.let { (novels, action) ->
        AlertDialog(
            onDismissRequest = { confirmSync = null },
            title = { Text("Looks like a novel") },
            text = { Text("${novels.joinToString(", ")} looks like a text novel, not manga (by its title). The converter " +
                "treats every page as a comic scan, so there's no real panel structure to zoom into and text will be " +
                "small and cramped on the device's screen. Convert anyway?") },
            confirmButton = { Button(onClick = { confirmSync = null; action() }) { Text("Convert anyway") } },
            dismissButton = { TextButton(onClick = { confirmSync = null }) { Text("Cancel") } },
        )
    }
    chooseEngine?.let { (rows, onServer) ->
        val onPhone = rows.count { it.phone != null }
        AlertDialog(
            onDismissRequest = { chooseEngine = null },
            title = { Text("Convert with") },
            text = { Text("The server converts with full quality (OCR, search, covers). This phone can convert " +
                "without it, but with no OCR, search, covers, or delta sync." +
                if (onPhone < rows.size) "\n\n${rows.size - onPhone} of ${rows.size} chapter(s) aren't downloaded " +
                    "to the phone yet, so converting on the phone would skip those." else "") },
            confirmButton = { Button(onClick = { chooseEngine = null; onServer() }) { Text("Server") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { chooseEngine = null }) { Text("Cancel") }
                    TextButton(onClick = { chooseEngine = null; vm.convertOnPhone(rows.mapNotNull { it.phone }); selected = emptySet() },
                        enabled = onPhone > 0) { Text("This phone") }
                }
            },
        )
    }
}

// ── Jobs ────────────────────────────────────────────────────────

@Composable
fun JobsScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        while (true) { vm.refreshJobs(); delay(if (vm.jobs.any { it.active }) 3000 else 15000) }
    }
    Column(Modifier.fillMaxSize()) {
        if (vm.jobs.any { !it.active }) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.clearFinishedJobs() }) { Text("Clear finished") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.jobs.isEmpty()) item { Text("No jobs yet. Use Sync on the Library tab.") }
            items(vm.jobs, key = { it.id }) { j ->
                val detail = vm.jobDetails[j.id]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.clickable {
                        if (j.active) { if (detail != null) vm.closeJob(j.id) else vm.loadJob(j.id) } else vm.viewLog(j.id)
                    }.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(when (j.kind) { "push" -> "Send to X4"; "delete" -> "Delete"; else -> "Conversion" }, fontWeight = FontWeight.SemiBold)
                                Text("${j.status.replace('_', ' ')} · ${j.done}/${j.total ?: "?"}" +
                                    (j.pagesTotal?.takeIf { it > 0 && j.active }?.let { " · ${j.pagesDone}/$it pages" } ?: "") +
                                    (j.etaSeconds?.takeIf { j.active }?.let { " · ~${formatDuration(it)} left" } ?: "") +
                                    " · " + DateUtils.getRelativeTimeSpanString((j.created * 1000).toLong()), style = MaterialTheme.typography.bodySmall)
                                j.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                            if (j.active) {
                                TextButton(onClick = { vm.watch(j) }) { Text("Notify") }
                                TextButton(onClick = { vm.cancel(j) }) { Text("Cancel") }
                            }
                        }
                        if (j.active) {
                            val total = j.total ?: 0
                            if (total > 0) LinearProgressIndicator(progress = { j.done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            j.current?.let { Text(it + (j.currentPages?.let { p -> " ($p pages)" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                        }
                        j.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (j.active) {
                            if (detail == null) Text("Tap for details", style = MaterialTheme.typography.labelSmall)
                            else if (detail.outcomes.isEmpty()) Text("No chapters processed yet", style = MaterialTheme.typography.bodySmall)
                            else detail.outcomes.forEach { o ->
                                Text("${if (o.status == "failed") "✗" else "✓"} ${o.label.substringAfterLast('/')} — ${o.status}" +
                                    (if (o.detail.isNotEmpty()) ": ${o.detail}" else "") + (if (o.warning.isNotEmpty()) " ⚠ ${o.warning}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (o.status == "failed") MaterialTheme.colorScheme.error else Color.Unspecified)
                            }
                        } else Text("Tap to view log", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    vm.viewingLog?.let { id -> JobLogDialog(vm.jobLogs[id], onDismiss = { vm.closeLog() }) }
}

@Composable
fun JobLogDialog(text: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Job log") },
        text = {
            if (text == null) CircularProgressIndicator()
            else Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier
                .verticalScroll(rememberScrollState()).heightIn(max = 480.dp))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ── Books ───────────────────────────────────────────────────────

@Composable
fun BooksScreen(vm: AppViewModel) {
    // A Set, not a list: every row asks "am I selected?" on every frame.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }   // expanded titles; all collapsed at first
    var x4Open by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sendingEpub by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    var sendingEpubFolder by remember { mutableStateOf<android.net.Uri?>(null) }
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) sendingEpub = uris
    }
    val epubFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) sendingEpubFolder = uri
    }
    val saved = vm.saved
    val lib = vm.deviceLib
    val states = remember(vm.books, vm.x4Match, lib) { vm.books.associate { it.path to vm.x4State(it) } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            item(key = "x4") {
                val cur = states.values.count { it == "current" }
                val old = states.values.count { it == "older" }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TargetChooser(vm)
                        Text(if (lib == null || lib.scannedAt == 0L) "X4 not checked yet" else
                            "X4 (checked ${DateUtils.getRelativeTimeSpanString(lib.scannedAt)}): $cur up to date" +
                                (if (old > 0) ", $old older version" else "") + ", ${vm.books.size - cur - old} not on it" +
                                (if (lib.incomplete.isNotEmpty()) ". ${lib.incomplete.size} incomplete chapter(s) on the X4 that it " +
                                    "doesn't list: tap Fix problems" else ""),
                            style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.checkX4() }) { Text("Check X4") }
                            OutlinedButton(onClick = { vm.findRead() }) { Text("Clean up read…") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.findProblems() }) { Text("Fix problems…") }
                            OutlinedButton(onClick = { vm.planReadSync() }) { Text("Sync read status…") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { epubPicker.launch("application/epub+zip") }, Modifier.weight(1f)) { Text("Send EPUB…") }
                            OutlinedButton(onClick = { epubFolderPicker.launch(null) }, Modifier.weight(1f)) { Text("Send EPUB folder…") }
                        }
                        if (lib != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selected = states.filterValues { it == null }.keys }) { Text("Select not on X4") }
                            if (old > 0) OutlinedButton(onClick = { selected = states.filterValues { it == "older" }.keys }) {
                                Text("Select outdated ($old)")
                            }
                        }
                        Text(if (vm.target == CARD) "Uses the X4's SD card plugged into this phone (card reader or card slot)."
                            else "Start File Transfer on the X4; the phone must be on its WiFi or the same WiFi.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (vm.books.isEmpty()) item { Text("No converted books yet.") }
            for ((title, bs) in vm.booksByTitle) {
                val expanded = title in open
                item(key = "t:$title") {
                    val all = bs.all { it.path in selected }
                    val chosen = bs.count { it.path in selected }
                    val onX4 = bs.count { states[it.path] == "current" }
                    val older = bs.count { states[it.path] == "older" }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Checkbox(checked = all, onCheckedChange = {
                            selected = if (all) selected - bs.map { b -> b.path }.toSet() else selected + bs.map { b -> b.path }
                        })
                        Box(Modifier.clickable { vm.titleForFolder(title)?.let { vm.openCoverPicker(it) } }) { SeriesCover(vm, title, 32.dp) }
                        Column(Modifier.weight(1f).padding(start = 8.dp).clickable { open = if (expanded) open - title else open + title }) {
                            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull("${bs.size} chapters", "$onX4 on X4".takeIf { onX4 > 0 }, "$older older on X4".takeIf { older > 0 },
                                "$chosen selected".takeIf { chosen > 0 }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { open = if (expanded) open - title else open + title }) { Text(if (expanded) "▾" else "▸") }
                    }
                }
                if (expanded) items(bs, key = { it.path }) { b ->
                    val on = b.path in selected
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        selected = if (on) selected - b.path else selected + b.path
                    }) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 24.dp, end = 8.dp))
                        Text(b.name, Modifier.weight(1f))
                        if (b.path in saved) Text("on phone · ", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                        if (b.path in vm.x4Incomplete) Text("incomplete on X4 · ", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        when (states[b.path]) {
                            "current" -> Text("on X4 · ", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                            "older" -> Text("older on X4 · ", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
                        }
                        Text("%.1f MB".format(b.bytes / 1e6), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (lib != null) {
                val onlyX4 = vm.onlyOnX4()
                if (onlyX4.isNotEmpty() || lib.otherBooks.isNotEmpty()) item(key = "x4only") {
                    Row(Modifier.fillMaxWidth().clickable { x4Open = !x4Open }.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Only on the X4", fontWeight = FontWeight.SemiBold)
                            Text("${onlyX4.size} manga chapters · ${lib.otherBooks.size} other books", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (x4Open) "▾" else "▸", Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (x4Open) {
                    items(onlyX4, key = { "x4m:$it" }) { Text("📖 $it", style = MaterialTheme.typography.bodySmall) }
                    items(lib.otherBooks, key = { "x4o:$it" }) { Text("📄 $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (selected.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val paths = vm.books.map { it.path }.filter { it in selected }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.download(selected) }, Modifier.weight(1f)) { Text("Download zip") }
                OutlinedButton(onClick = { vm.saveOffline(paths); selected = emptySet() }, Modifier.weight(1f)) { Text("Save to phone") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                TextButton(onClick = { deleting = true }) { Text("Delete…") }
                Button(onClick = { sending = true }, Modifier.weight(1f)) { Text("Send ${selected.size} to X4") }
            }
        }
    }
    vm.cleanup?.let { progress -> CleanupDialog(vm, progress) }
    if (deleting) DeleteDialog(vm, vm.books.map { it.path }.filter { it in selected }, onDismiss = { deleting = false }) {
        deleting = false; selected = emptySet()
    }
    vm.coverPicker?.let { CoverPickerDialog(vm, it) }
    vm.readSync?.let { ReadSyncDialog(vm, it) }
    vm.problems?.let { found -> ProblemsDialog(vm, found) }
    if (sending) SendDialog(vm, onDismiss = { sending = false }, readCount = selected.count { it in vm.komikkuRead }) { device, viaServer, replace, skipRead ->
        vm.send(vm.books.map { it.path }.filter { it in selected }, device, viaServer, replace, skipRead)
        sending = false
        selected = emptySet()
    }
    sendingEpub?.let { uris ->
        SendEpubDialog(vm, uris.size, showShelfPicker = true, onDismiss = { sendingEpub = null }) { device, shelf ->
            vm.sendEpub(uris, device, shelf)
            sendingEpub = null
        }
    }
    sendingEpubFolder?.let { tree ->
        SendEpubDialog(vm, count = -1, showShelfPicker = false, onDismiss = { sendingEpubFolder = null }) { device, _ ->
            vm.sendEpubFolder(tree, device)
            sendingEpubFolder = null
        }
    }
}

/** Delete chosen chapters from the server and/or the X4, optionally keeping them from syncing again. */
@Composable
fun DeleteDialog(vm: AppViewModel, paths: List<String>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val onX4 = paths.count { vm.x4Match[it] != null || vm.x4Incomplete[it] != null }
    // Whole series selected: offer to stop syncing it, future chapters included.
    val wholeSeries = vm.booksByTitle.filter { (_, bs) -> bs.isNotEmpty() && bs.all { it.path in paths } }.map { it.first }
    var server by remember { mutableStateOf(true) }
    var x4 by remember { mutableStateOf(false) }
    var ignore by remember { mutableStateOf(true) }
    var series by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${paths.size} chapter(s)") },
        text = {
            Column {
                Row(Modifier.clickable { server = !server }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(server, null); Text("Delete the converted copies on the server")
                }
                if (server) Row(Modifier.clickable { ignore = !ignore }.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ignore, null); Text("Don't sync them again (also deletes the uploaded CBZ; the phone's Komikku copy stays)")
                }
                if (server && wholeSeries.isNotEmpty()) Row(Modifier.clickable { series = !series }.padding(start = 24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(series, null); Text("Stop syncing ${wholeSeries.joinToString()} (future chapters too)")
                }
                Row(Modifier.clickable { x4 = !x4 }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(x4, null); Text("Also delete from the X4 ($onX4 there; File Transfer must be running)")
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.deleteBooks(paths, server, x4, ignore, series); onDone() }, enabled = server || x4) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Read status that differs between the X4 and Komikku, both directions ticked. */
@Composable
fun ReadSyncDialog(vm: AppViewModel, plan: AppViewModel.ReadSync) {
    var toKomikku by remember(plan) { mutableStateOf(plan.toKomikku.toSet()) }
    var toX4 by remember(plan) { mutableStateOf(plan.toX4.toSet()) }
    AlertDialog(
        onDismissRequest = { vm.readSync = null },
        title = { Text("Sync read status") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Using Komikku's backup from ${DateUtils.getRelativeTimeSpanString(plan.backupDate)}: chapters read in " +
                    "Komikku after that aren't known (create a backup in Komikku first for the latest)." +
                    (if (plan.unmatched > 0) " ${plan.unmatched} chapter(s) on the X4 aren't in the backup." else ""),
                    style = MaterialTheme.typography.bodySmall)
                if (plan.toKomikku.isEmpty() && plan.toX4.isEmpty()) Text("Everything matches.", Modifier.padding(top = 8.dp))
                if (plan.toKomikku.isNotEmpty()) {
                    Text("Read on the X4 → mark read in Komikku (${plan.toKomikku.size})", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    Text("Saves a small backup to Downloads and opens Komikku: restore it there (Settings → Data and storage → " +
                        "Restore backup). Restoring only adds \"read\"; nothing else changes.", style = MaterialTheme.typography.bodySmall)
                    plan.toKomikku.forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable { toKomikku = if (r in toKomikku) toKomikku - r else toKomikku + r },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(r in toKomikku, null); Text(r.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (plan.toX4.isNotEmpty()) {
                    Text("Read in Komikku → mark read on the X4 (${plan.toX4.size})", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    Text("Marked finished on the X4 like its own \"mark as read\"; Clean up read can then remove them.",
                        style = MaterialTheme.typography.bodySmall)
                    plan.toX4.forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable { toX4 = if (r in toX4) toX4 - r else toX4 + r },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(r in toX4, null); Text(r.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.applyReadSync(plan.toKomikku.filter { it in toKomikku }, plan.toX4.filter { it in toX4 }) },
                enabled = toKomikku.isNotEmpty() || toX4.isNotEmpty()) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = { vm.readSync = null }) { Text("Cancel") } },
    )
}

/** Tap a series cover: pick from Komikku's, AniList's matches, the chapters' first pages, or your own image. */
@Composable
fun CoverPickerDialog(vm: AppViewModel, title: TitleRow) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { vm.coverPicker = null; vm.changeCover(title, uri) }
    }
    AlertDialog(
        onDismissRequest = { vm.coverPicker = null },
        title = { Text("Cover for ${title.title}") },
        text = {
            val choices = vm.coverChoices
            if (choices == null) Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Looking for covers…") }
            else if (choices.isEmpty()) Text("No cover choices found. Upload your own below.")
            else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), modifier = Modifier.heightIn(max = 460.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(choices.size) { i ->
                    val c = choices[i]
                    Column(Modifier.clickable { vm.chooseCover(title, c) }) {
                        val img = vm.coverChoiceImages[c.id]
                        val mod = Modifier.fillMaxWidth().height(120.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        if (img != null) androidx.compose.foundation.Image(img, c.label, mod, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(mod.background(MaterialTheme.colorScheme.surfaceVariant))
                        Text(c.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picker.launch("image/*") }) { Text("Upload your own…") } },
        dismissButton = { TextButton(onClick = { vm.coverPicker = null }) { Text("Close") } },
    )
}

/** What "Fix problems…" found, all ticked: fixing re-converts on the server and replaces the X4 copies. */
@Composable
fun ProblemsDialog(vm: AppViewModel, found: Map<String, List<String>>) {
    var chosen by remember(found) { mutableStateOf(found.keys) }
    AlertDialog(
        onDismissRequest = { vm.problems = null },
        title = { Text(if (found.isEmpty()) "No problems found" else "Fix ${found.size} chapter(s)") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(if (found.isEmpty()) "Every page on the server decodes and matches its index" +
                        (if (vm.problemsOnX4) ", and the X4's copies match the server's." else ". The X4 wasn't reachable, so its copies weren't compared.")
                    else "Fixing re-converts chapters with bad pages from their CBZ, then " +
                        (if (vm.problemsOnX4) "updates them on the X4 where they are, sending only the files that are missing, damaged or changed, " +
                            "and clearing their cached page images (your reading position stays)."
                        else "you can send them to the X4 (the X4 wasn't reachable now)."),
                    style = MaterialTheme.typography.bodySmall)
                found.forEach { (path, issues) ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (path in chosen) chosen - path else chosen + path },
                        verticalAlignment = Alignment.Top) {
                        Checkbox(path in chosen, null)
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(path.removePrefix("manga/"), style = MaterialTheme.typography.bodyMedium)
                            issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (found.isNotEmpty()) Button(onClick = { vm.fixProblems(chosen.toList().sortedWith(NaturalOrder)) }, enabled = chosen.isNotEmpty()) {
                Text("Fix ${chosen.size}")
            } else TextButton(onClick = { vm.problems = null }) { Text("OK") }
        },
        dismissButton = { if (found.isNotEmpty()) TextButton(onClick = { vm.problems = null }) { Text("Cancel") } },
    )
}

/**
 * Review before deleting: finished books (the X4's own rule: on the last page) start ticked, books in
 * progress are listed unticked with how far they got, unopened ones are only counted.
 */
@Composable
fun CleanupDialog(vm: AppViewModel, progress: Map<String, ReadingProgress?>) {
    val read = progress.filterValues { it?.read == true }.keys.toList()
    val reading = progress.filterValues { it != null && !it.read }
    val unopened = progress.count { it.value == null }
    var chosen by remember(progress) { mutableStateOf(read.toSet()) }
    AlertDialog(
        onDismissRequest = { vm.cleanup = null },
        title = { Text("Clean up the X4") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("${read.size} finished · ${reading.size} in progress · $unopened not opened. " +
                    "Ticked books are deleted from the X4, along with their reading position. They stay on the server, " +
                    "so you can send them again.", style = MaterialTheme.typography.bodySmall)
                if (progress.isNotEmpty() && progress.values.all { it == null })
                    Text("No reading progress found for any book. If you have read some, tell the developer: the X4 may " +
                        "store progress differently than expected.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                (read.map { it to progress[it] } + reading.toList()).forEach { (path, p) ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (path in chosen) chosen - path else chosen + path },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(path in chosen, null)
                        Column(Modifier.weight(1f)) {
                            Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                            Text(path.removePrefix("manga/").substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (p?.read == true) "read" else "${p?.percent ?: 0}%", style = MaterialTheme.typography.bodySmall,
                            color = if (p?.read == true) Color(0xFF2E7D32) else Color.Unspecified)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.deleteFromX4(chosen.toList().sortedWith(NaturalOrder)) }, enabled = chosen.isNotEmpty()) {
                Text("Delete ${chosen.size} from X4")
            }
        },
        dismissButton = { TextButton(onClick = { vm.cleanup = null }) { Text("Cancel") } },
    )
}

@Composable
fun SendDialog(vm: AppViewModel, onDismiss: () -> Unit, readCount: Int = 0, onSend: (String, Boolean, Boolean, Boolean) -> Unit) {
    var skipRead by remember { mutableStateOf(true) }
    var device by remember { mutableStateOf(vm.prefs.deviceIp) }
    var viaServer by remember { mutableStateOf(false) }
    var toCard by remember { mutableStateOf(vm.target == CARD) }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to X4") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("On the X4, start File Transfer. From this phone: connect the phone to the X4's WiFi " +
                    "(\"CrossPoint-Reader\", the X4 is then 192.168.4.1) or put both on the same WiFi. Books are downloaded to " +
                    "the phone first (mobile data works while on the X4's WiFi), then copied over WiFi. " +
                    "From the server: the X4 must be on your home WiFi.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(device, { device = it }, label = { Text("X4 IP address") }, singleLine = true,
                    placeholder = { Text("Automatic") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.discover() }) { Text("Find X4 on the network") }
                vm.devices.forEach { d ->
                    Text("${d.hostname}  ${d.ip}  (seen by ${d.via})", Modifier.fillMaxWidth().clickable { device = d.ip }.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.selectable(toCard) { toCard = true; viaServer = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(toCard, null); Text("To the SD card plugged into this phone (fastest)" +
                        if (vm.cardName == null) " — pick it in Settings first" else "")
                }
                Row(Modifier.selectable(!viaServer && !toCard) { viaServer = false; toCard = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!viaServer && !toCard, null); Text("From this phone to the X4 over WiFi")
                }
                Row(Modifier.selectable(viaServer) { viaServer = true; toCard = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(viaServer, null); Text("From the server (X4 on home WiFi)")
                }
                Row(Modifier.clickable { replace = !replace }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(replace, null); Text("Replace books already on the X4")
                }
                if (readCount > 0) Row(Modifier.clickable { skipRead = !skipRead }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(skipRead, null); Text("Skip $readCount chapter(s) already read in Komikku")
                }
            }
        },
        confirmButton = { Button(onClick = { onSend(if (toCard) CARD else device.trim(), viaServer && !toCard, replace, skipRead && readCount > 0) },
            enabled = !toCard || vm.cardName != null) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Copy already-downloaded EPUB files straight to the X4 (or its card): no conversion, no server round-trip,
 *  since the firmware reads EPUB natively and discovers any .epub anywhere on the card. */
@Composable
fun SendEpubDialog(vm: AppViewModel, count: Int, showShelfPicker: Boolean, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var device by remember { mutableStateOf(vm.prefs.deviceIp) }
    var toCard by remember { mutableStateOf(vm.target == CARD) }
    var shelf by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send EPUB") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text((if (count >= 0) "$count file(s) selected. "
                      else "Every EPUB directly inside that folder will be sent, grouped into one shelf named after it. ") +
                    "On the X4, start File Transfer and connect the phone to its WiFi " +
                    "(\"CrossPoint-Reader\") or the same WiFi. The X4 reads EPUB natively, so these go straight on " +
                    "as-is — no conversion.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(device, { device = it }, label = { Text("X4 IP address") }, singleLine = true,
                    placeholder = { Text("Automatic") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.discover() }) { Text("Find X4 on the network") }
                vm.devices.forEach { d ->
                    Text("${d.hostname}  ${d.ip}  (seen by ${d.via})", Modifier.fillMaxWidth().clickable { device = d.ip }.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.selectable(toCard) { toCard = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(toCard, null); Text("To the SD card plugged into this phone (fastest)" +
                        if (vm.cardName == null) " — pick it in Settings first" else "")
                }
                Row(Modifier.selectable(!toCard) { toCard = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!toCard, null); Text("From this phone to the X4 over WiFi")
                }
                if (showShelfPicker) {
                    OutlinedTextField(shelf, { shelf = it }, label = { Text("Shelf") }, singleLine = true,
                        placeholder = { Text("None — loose in Books") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.loadShelves(if (toCard) CARD else device.trim()) }) { Text("Show existing shelves") }
                    vm.epubShelves.forEach { s ->
                        Text(s, Modifier.fillMaxWidth().clickable { shelf = s }.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSend(if (toCard) CARD else device.trim(), shelf) },
            enabled = !toCard || vm.cardName != null) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Settings ────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var home by remember { mutableStateOf(vm.prefs.homeUrl) }
    var remote by remember { mutableStateOf(vm.prefs.remoteUrl) }
    var token by remember { mutableStateOf(vm.prefs.token) }
    var tree by remember { mutableStateOf(vm.prefs.komikkuTree) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.prefs.komikkuTree = uri.toString()
            tree = uri.toString()
        }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(home, { home = it }, label = { Text("Home URL (LAN)") }, placeholder = { Text("http://<server-ip>:8765") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(remote, { remote = it }, label = { Text("Remote URL (HTTPS, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("Get the token on the server with: python3 k2m_server.py --print-token", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                vm.prefs.homeUrl = home; vm.prefs.remoteUrl = remote; vm.prefs.token = token
                vm.testConnection()
            }) { Text("Save & test") }
        }
        Text("X4", style = MaterialTheme.typography.titleMedium)
        var ip by remember { mutableStateOf(vm.prefs.deviceIp) }
        OutlinedTextField(ip, { ip = it; vm.prefs.deviceIp = it }, label = { Text("X4 address") }, singleLine = true,
            placeholder = { Text("Automatic") }, modifier = Modifier.fillMaxWidth())
        Text("Leave blank to find the X4 automatically (its hotspot, the last address it had, or a network search)" +
            (vm.prefs.lastX4.takeIf { it.isNotBlank() }?.let { "; last found at $it" } ?: "") + ".",
            style = MaterialTheme.typography.bodySmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Manga: left button turns the page forward")
                Text("Sets the X4's \"Reversed page turn\" (Settings → Controls), which also applies to vertical Japanese " +
                    "books. Needs File Transfer running on the X4." +
                    if (vm.x4ReverseTurn == null) " Tap Read to see the current setting." else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (vm.x4ReverseTurn == null) TextButton(onClick = { vm.readX4Settings() }) { Text("Read") }
            else Switch(vm.x4ReverseTurn!!, { vm.setReverseTurn(it) })
        }

        TargetChooser(vm)
        Text("Plug the X4's microSD into the phone (a USB card reader, or the phone's card slot) for much faster copying " +
            "than the X4's WiFi, then pick the card's root folder. Sends, Check X4, Clean up, Fix problems and read " +
            "status then work on the card directly.", style = MaterialTheme.typography.bodySmall)

        Text("Conversion", style = MaterialTheme.typography.titleMedium)
        var device by remember { mutableStateOf(vm.prefs.device) }
        listOf("x4" to "X4 / X4 Pro (480×800)", "x3" to "X3 (528×792)").forEach { (id, label) ->
            Row(Modifier.selectable(device == id) { device = id; vm.setDevice(id) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(device == id, null); Text(label)
            }
        }
        var coverPage by remember { mutableStateOf(vm.prefs.coverPage) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cover page with chapter number")
                Text("Series art with the chapter number in large type, as each chapter's first page: the X4 uses it " +
                    "as the chapter's cover and shelf cover.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(coverPage, { coverPage = it; vm.setCoverPage(it) })
        }
        Text("Changing either converts the chapters again on the next Sync.", style = MaterialTheme.typography.bodySmall)

        Text("Automatic sync", style = MaterialTheme.typography.titleMedium)
        var auto by remember { mutableStateOf(vm.prefs.autoSync) }
        var hours by remember { mutableStateOf(vm.prefs.autoSyncHours) }
        var homeOnly by remember { mutableStateOf(vm.prefs.autoSyncHomeOnly) }
        var charging by remember { mutableStateOf(vm.prefs.autoSyncCharging) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Upload and convert new chapters automatically", Modifier.weight(1f))
            Switch(auto, { auto = it; vm.setAutoSync(enabled = it) })
        }
        if (auto) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Every", Modifier.padding(end = 4.dp))
                listOf(1, 6, 12, 24).forEach { h ->
                    Row(Modifier.selectable(hours == h) { hours = h; vm.setAutoSync(hours = h) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(hours == h, null); Text("${h}h", Modifier.padding(end = 8.dp))
                    }
                }
            }
            Row(Modifier.clickable { homeOnly = !homeOnly; vm.setAutoSync(homeOnly = homeOnly) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(homeOnly, null)
                Text("Only on the home network (the server must answer at the Home URL; never uses mobile data)")
            }
            Row(Modifier.clickable { charging = !charging; vm.setAutoSync(charging = charging) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(charging, null); Text("Only while charging")
            }
            Text("Last run: ${vm.prefs.autoSyncLast.ifEmpty { "not yet" }}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.autoSyncNow() }) { Text("Run now") }
        }

        Text("Komikku downloads", style = MaterialTheme.typography.titleMedium)
        Text(tree?.let { android.net.Uri.decode(it).substringAfterLast(':') } ?: "not set", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker.launch(null) }) { Text("Pick Komikku download folder") }
        Text("Pick Komikku's main folder (the one holding \"downloads\" and \"autobackup\"): chapters come from downloads, " +
            "and the covers Komikku shows come from its newest backup. Picking just \"downloads\" works too, without " +
            "Komikku's covers. The app only reads it.", style = MaterialTheme.typography.bodySmall)
        when (vm.komikkuBackup) {
            true -> Text("Komikku backup found: its covers are used for series art.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            false -> Text("No Komikku backup in this folder (Komikku → Settings → Data and storage → automatic backups).",
                style = MaterialTheme.typography.bodySmall)
            null -> {}
        }

        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text("Installed: ${Updater.installedName(ctx)}" + (vm.update?.let { " · available: ${it.versionName}" } ?: ""),
            style = MaterialTheme.typography.bodySmall)
        var autoUpdate by remember { mutableStateOf(vm.prefs.autoUpdate) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Install updates automatically")
                Text("From the server, when the app opens and during automatic sync. The first update asks to allow " +
                    "\"Install unknown apps\"; after that Android 12+ usually installs without asking.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(autoUpdate, { autoUpdate = it; vm.prefs.autoUpdate = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Check now") }
            if (vm.update != null) Button(onClick = { vm.installUpdate() }) { Text("Install ${vm.update!!.versionName}") }
        }

        Text("No-server fallback", style = MaterialTheme.typography.titleMedium)
        var phoneConvert by remember { mutableStateOf(vm.prefs.phoneConvertFallback) }
        var confirmPhoneConvert by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow converting on this phone")
                Text("When the server can't be reached: no OCR, no text search, no dictionary lookup, no " +
                    "translation, no covers, no delta sync -- just pages resized to fit the screen with a " +
                    "basic whitespace-gutter panel split. Slower and heavier on the phone's battery than the " +
                    "server doing it. A fallback, not a replacement.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(phoneConvert, { if (it) confirmPhoneConvert = true else { phoneConvert = false; vm.prefs.phoneConvertFallback = false } })
        }
        if (confirmPhoneConvert) AlertDialog(
            onDismissRequest = { confirmPhoneConvert = false },
            title = { Text("Convert on this phone?") },
            text = { Text("This is a fallback for when the server isn't reachable, not a replacement for it: no OCR, " +
                "no text search or dictionary lookup, no translation, no generated covers, and no delta sync (a " +
                "re-sent chapter is replaced whole). Panel splitting is a basic whitespace-gutter heuristic, not " +
                "the server's model, so results vary more by page. It also runs entirely on the phone's CPU, which " +
                "is slower and uses more battery than the server converting it.") },
            confirmButton = { Button(onClick = { confirmPhoneConvert = false; phoneConvert = true; vm.prefs.phoneConvertFallback = true }) { Text("Enable anyway") } },
            dismissButton = { TextButton(onClick = { confirmPhoneConvert = false }) { Text("Cancel") } },
        )
    }
}
