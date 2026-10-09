package dev.cartridge.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

internal fun dateLabel(time: Long) = if (time <= 0) "Never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
internal fun playLabel(ms: Long): String = if (ms < 60_000) "${ms / 1000} seconds" else "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"

@Composable internal fun LibraryAction(title: String, selected: Boolean, tag: String, enabled: Boolean = true, action: () -> Unit) {
    TextButton(enabled = enabled, onClick = action, modifier = Modifier.fillMaxWidth().testTag(tag)
        .background(if (selected) Color(0xFF34312D) else Color.Transparent)) { Text(title, color = Color(0xFFE9E2D6)) }
}

@Composable fun GameDetails(game: LibraryGame, facts: LibraryFacts?, focus: Int, busy: Boolean, action: (Int) -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("details-panel"), color = Color(0xFF191817)) {
        Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(game.title, fontSize = 27.sp, modifier = Modifier.testTag("details-title"))
                Text(game.system.title, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                listOf("year", "developer", "publisher", "genre", "description").forEach { field ->
                    Text(field.replaceFirstChar(Char::uppercase), color = Color(0xFF9C968B), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    Text(game.metadata[field]?.value ?: "Not set", fontSize = 15.sp, modifier = Modifier.testTag("details-$field"))
                }
                Text("Played ${playLabel(game.playMs)} · Last played ${dateLabel(game.lastPlayed)}", modifier = Modifier.padding(top = 16.dp).testTag("details-history"), fontSize = 13.sp)
                facts?.let {
                    Text(if (it.cachedRom) "Ready to play offline" else "Working copy missing · choose the same ROM to recover", fontSize = 13.sp)
                    Text(it.sourceStatus, fontSize = 13.sp, modifier = Modifier.testTag("details-source"))
                    Text("Battery save: ${dateLabel(it.batteryAt)}\nSaved state: ${dateLabel(it.stateAt)}", fontSize = 13.sp)
                }
                Text("Label: ${game.label?.provenance?.let { "${it.provider} · ${it.region}" } ?: if (game.label == null) "Original study label" else "Custom image"}", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                if (game.media.isNotEmpty()) Text("Additional artwork: ${game.media.values.joinToString { "${it.type} (${it.region})" }}", fontSize = 13.sp)
                val sources = game.metadata.values.map { it.provider }.distinct()
                if (sources.isNotEmpty()) Text("Metadata: ${sources.joinToString()}", fontSize = 12.sp)
            }
            Column(Modifier.width(220.dp)) {
                listOf(if (game.favorite) "Remove favorite" else "Add favorite", "Edit metadata", "Scrape this title", "Locate same ROM", "Undo artwork / metadata", "Back to collection").forEachIndexed { index, title ->
                    LibraryAction(title, focus == index, "details-action-$index", !busy && (index != 4 || game.canUndoEnrichment)) { action(index) }
                }
                Text("↑ ↓ Choose · A Select · B Back", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable fun MetadataEditor(game: LibraryGame, busy: Boolean, cancel: () -> Unit, save: (Map<String, String>) -> Unit) {
    val fields = remember(game.id) { linkedMapOf("title" to (game.metadata["title"]?.value ?: game.title),
        "year" to game.metadata["year"]?.value.orEmpty(), "developer" to game.metadata["developer"]?.value.orEmpty(),
        "publisher" to game.metadata["publisher"]?.value.orEmpty(), "genre" to game.metadata["genre"]?.value.orEmpty(),
        "description" to game.metadata["description"]?.value.orEmpty()) }
    var draft by remember(game.id) { mutableStateOf(fields.toMap()) }
    Surface(Modifier.fillMaxSize().testTag("metadata-editor"), color = Color(0xFF191817)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Edit game details", fontSize = 24.sp)
                Row {
                    TextButton(enabled = !busy, onClick = cancel, modifier = Modifier.testTag("metadata-cancel")) { Text("Cancel") }
                    TextButton(enabled = !busy, onClick = { save(draft.filter { (key, value) -> value != fields[key] }) }, modifier = Modifier.testTag("metadata-save")) { Text("Save") }
                }
            }
            Text("Blank fields are cleared. Other artwork and metadata choices stay as selected.", fontSize = 12.sp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                fields.keys.forEach { field ->
                    OutlinedTextField(value = draft.getValue(field), onValueChange = { value ->
                        if (value.length <= if (field == "description") 16_384 else 256) draft = draft + (field to value)
                    }, enabled = !busy, singleLine = field != "description", label = { Text(field.replaceFirstChar(Char::uppercase)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("edit-$field"))
                }
            }
        }
    }
}

@Composable fun LibraryManager(roots: List<DirectoryRecord>, focus: Int, autoScan: Boolean, action: (Int) -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("library-manager"), color = Color(0xFF191817)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Library folders & search", fontSize = 24.sp)
            Text("Forgetting a folder keeps games, artwork and saves in your collection.", fontSize = 12.sp)
            val scroll = rememberScrollState()
            LaunchedEffect(focus) { scroll.animateScrollTo((focus - 3).coerceAtLeast(0) * 48) }
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                listOf("Back", "Add folder", "Rescan folders", "Verify all ROM files", if (autoScan) "Automatic scan on open: on" else "Automatic scan on open: off", "Search library", "Clear search").forEachIndexed { index, title ->
                    LibraryAction(title, focus == index, "library-action-$index") { action(index) }
                }
                roots.forEachIndexed { index, root ->
                    LibraryAction("Forget ${root.name.ifBlank { "ROM folder ${index + 1}" }} · ${dateLabel(root.lastScan)}${root.error?.let { "\n$it" }.orEmpty()}", focus == index + 7, "forget-folder-$index") { action(index + 7) }
                }
            }
            Text("↑ ↓ Choose · A Select · B Back", fontSize = 12.sp)
        }
    }
}

@Composable fun LibrarySearch(query: LibraryQuery, cancel: () -> Unit, apply: (String) -> Unit) {
    var text by remember { mutableStateOf(query.search) }
    Surface(Modifier.fillMaxSize().testTag("library-search"), color = Color(0xFF191817)) {
        Column(Modifier.padding(32.dp)) {
            Text("Find a game", fontSize = 24.sp)
            OutlinedTextField(value = text, onValueChange = { if (it.length <= 160) text = it }, singleLine = true,
                label = { Text("Title, developer or genre") }, modifier = Modifier.fillMaxWidth().testTag("library-search-text"))
            Row { TextButton(onClick = cancel) { Text("Cancel") }; TextButton(onClick = { apply(text) }, modifier = Modifier.testTag("library-search-apply")) { Text("Apply") } }
        }
    }
}
