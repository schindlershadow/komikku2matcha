package com.schindler.k2m

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

private class SleepRow(val name: String, val series: String, val item: SleepItem?)

/** Manage the BMPs Matcha shows as its sleep screen: draw them from series art, use your own, send or delete them. */
@Composable
fun SleepScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshSleep() }
    var pickFor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<List<String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val folder = pickFor
        pickFor = null
        if (uri != null && folder != null) vm.setSleepImage(folder, uri)
    }
    val items = vm.sleepItems
    val rows = remember(vm.library, items) {
        val byName = items.associateBy { it.name }
        val fromLibrary = vm.library.mapNotNull { t -> t.folder?.let { SleepRow(it, t.title, byName[it]) } }
        val known = fromLibrary.map { it.name }.toSet()
        (fromLibrary + items.filter { it.name !in known }.map { SleepRow(it.name, it.series, it) })
            .sortedWith(compareBy({ it.item == null }, { it.series.lowercase() }))
    }
    val onX4 = vm.sleepOnX4
    val onlyOnX4 = remember(onX4, rows) {
        val names = rows.filter { it.item != null }.map { "${it.name}.bmp" }.toSet()
        onX4?.keys?.filter { it !in names }?.sorted().orEmpty()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "top") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Matcha's Custom sleep screen shows a random picture from the X4's /sleep folder. Pick " +
                        "\"Custom\" as the sleep screen in the X4's settings. A /sleep.bmp at the card's root, or a " +
                        "/.sleep folder, overrides it.", style = MaterialTheme.typography.bodySmall)
                    TargetChooser(vm)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.generateSleep(null) }) { Text("Draw for all series") }
                        OutlinedButton(onClick = { vm.checkSleepX4() }) { Text("Check X4") }
                    }
                    OutlinedButton(onClick = { vm.sendSleep(items.map { it.name }) }, enabled = items.isNotEmpty()) {
                        Text("Send all (${items.size}) to ${if (vm.target == CARD) "the card" else "the X4"}")
                    }
                    if (onX4 != null) Text("${onX4.size} on ${if (vm.target == CARD) "the card" else "the X4"}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (rows.isEmpty()) item { Text("No series yet.") }
        items(rows, key = { it.name }) { r ->
            SleepRowCard(vm, r, onX4, onPick = { pickFor = r.name; picker.launch("image/*") }, onDelete = { deleting = listOf(r.name) })
        }
        if (onlyOnX4.isNotEmpty()) {
            item(key = "only") { Text("Only on the X4 (not from the server)", style = MaterialTheme.typography.titleSmall) }
            items(onlyOnX4, key = { "x4:$it" }) { file ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(file, Modifier.weight(1f))
                    TextButton(onClick = { deleting = listOf(file.removeSuffix(".bmp").removeSuffix(".BMP")) }) { Text("Delete") }
                }
            }
        }
    }

    deleting?.let { names ->
        val serverHas = names.all { n -> items.any { it.name == n } }
        var fromServer by remember(names) { mutableStateOf(serverHas) }
        var fromX4 by remember(names) { mutableStateOf(onX4 != null && names.all { "$it.bmp" in onX4 }) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete sleep screen") },
            text = {
                Column {
                    Text(names.joinToString())
                    if (serverHas) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(fromServer, { fromServer = it }); Text("From the server")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(fromX4, { fromX4 = it }); Text("From ${if (vm.target == CARD) "the SD card" else "the X4"}")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = fromServer || fromX4, onClick = { deleting = null; vm.deleteSleep(names, fromServer, fromX4) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SleepRowCard(vm: AppViewModel, r: SleepRow, onX4: Map<String, Long>?, onPick: () -> Unit, onDelete: () -> Unit) {
    val item = r.item
    val img = item?.let { vm.sleepImages["${it.name}@${it.mtime}"] }
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val box = Modifier.width(60.dp).height(100.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            if (img != null) Image(img, r.series, box, contentScale = ContentScale.Crop)
            else Box(box.background(MaterialTheme.colorScheme.surfaceVariant))
            Column(Modifier.weight(1f)) {
                Text(r.series, maxLines = 2)
                Text(when {
                    item == null -> "No sleep screen yet"
                    item.custom -> "Your own image"
                    else -> "Drawn from the cover"
                } + if (onX4 != null && item != null) (if ("${r.name}.bmp" in onX4) " · on X4" else " · not on X4") else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (item == null) Column {
                TextButton(onClick = { vm.generateSleep(listOf(r.name)) }) { Text("Draw") }
                TextButton(onClick = onPick) { Text("Own image…") }
            } else Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { vm.sendSleep(listOf(r.name)) }) { Text("Send") }
                Box {
                    TextButton(onClick = { menu = true }) { Text("More ▾") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Redraw from the cover") }, onClick = { menu = false; vm.generateSleep(listOf(r.name), force = true) })
                        DropdownMenuItem(text = { Text("Use your own image…") }, onClick = { menu = false; onPick() })
                        DropdownMenuItem(text = { Text("Delete…") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
