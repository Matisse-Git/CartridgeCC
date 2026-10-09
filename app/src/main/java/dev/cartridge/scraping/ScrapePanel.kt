package dev.cartridge.scraping

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.math.max

@Composable fun ScrapePanel(state: ScrapeState, coordinator: ScrapeCoordinator) {
    val list = rememberLazyListState()
    LaunchedEffect(state.page, state.focus) { list.scrollToItem(state.focus) }
    Surface(Modifier.fillMaxSize().testTag("scrape-panel"), color = Color(0xFF191817)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Scrape artwork & metadata", fontSize = 24.sp)
                    Text("Data: ${coordinator.provider.name} · reviewed choices · no automatic replacements", fontSize = 12.sp)
                }
                TextButton(onClick = coordinator::back, modifier = Modifier.testTag("scrape-back")) { Text(if (state.busy) "Cancel" else "Back") }
            }
            state.current?.let { Text("${state.index + 1} / ${state.entries.size} · ${it.game.title}", modifier = Modifier.padding(vertical = 8.dp)) }
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progress ?: "Working…", fontSize = 12.sp) }
            state.message?.let { Text(it, color = Color(0xFFE2CFAE), modifier = Modifier.padding(vertical = 8.dp).testTag("scrape-message")) }
            if (state.page == ScrapePage.Matches) {
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = state.current?.query.orEmpty(), onValueChange = coordinator::query,
                        enabled = !state.busy, singleLine = true, label = { Text("Correct title search") },
                        modifier = Modifier.weight(1f).testTag("scrape-query"))
                    TextButton(enabled = !state.busy, onClick = coordinator::searchCurrent, modifier = Modifier.testTag("scrape-search")) { Text("Search") }
                }
                state.current?.error?.let { Text(it, color = Color(0xFFE2CFAE)); TextButton(onClick = coordinator::retryRemaining) { Text("Retry failed titles") } }
                if (!state.busy && state.current?.searched == true && state.current?.matches?.isEmpty() == true) Text("No match. Correct the search or skip this title.")
            }
            if (state.page == ScrapePage.Artwork) {
                Text(state.candidate?.title.orEmpty(), fontSize = 20.sp)
                Text("Choose a flat label to preview on the cartridge, or save other artwork separately.", fontSize = 12.sp)
                if (state.candidate?.artwork?.none { it.isLabel } == true) Text("This record has no flat label. Choose another match or keep the current label.", color = Color(0xFFE2CFAE))
            }
            if (state.page == ScrapePage.Metadata) Text("Check only the fields you want to apply. X cycles the focused field's variants.", fontSize = 12.sp)
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.page) {
                    ScrapePage.Scope -> {
                        item { Action("This title", 0, state) { coordinator.chooseScope(true) } }
                        item { Action("GBA collection", 1, state) { coordinator.chooseScope(false) } }
                        item { Action("All games", 2, state) { coordinator.chooseScope(false) } }
                        item { Action("Close", 3, state, coordinator::back) }
                    }
                    ScrapePage.Matches -> {
                        val matches = state.current?.matches.orEmpty()
                        itemsIndexed(matches, key = { _, game -> game.id }) { index, game ->
                            Action("${game.title} · ${game.metadata["year"]?.firstOrNull().orEmpty()} · ${if (game.exactHash) "ROM hash match" else "Title candidate"}\n${game.artwork.count { it.isLabel }} flat labels · ${game.artwork.size} media options",
                                index, state) { coordinator.chooseCandidate(index) }
                        }
                        item { Action("Search corrected title", matches.size, state, coordinator::searchCurrent) }
                        item { Action("Next title / finish", matches.size + 1, state, coordinator::next) }
                        item { Action("Choose scope", matches.size + 2, state, coordinator::back) }
                    }
                    ScrapePage.Artwork -> {
                        item { Action("Choose metadata separately", 0, state, coordinator::metadata) }
                        val artwork = state.candidate?.artwork.orEmpty()
                        itemsIndexed(artwork, key = { _, asset -> asset.url }) { index, asset ->
                            TextButton(enabled = !state.busy, onClick = { coordinator.artwork(index) }, modifier = Modifier.fillMaxWidth()
                                .testTag("scrape-asset-$index").background(if (state.focus == index + 1) Color(0xFF34312D) else Color.Transparent)) {
                                ProviderThumbnail(asset, coordinator.provider)
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text("${mediaTitle(asset.type)} · ${regionTitle(asset.region)}", fontSize = 16.sp)
                                    Text(if (asset.isLabel) "Preview label on cartridge" else "Save as additional media", fontSize = 12.sp)
                                }
                            }
                        }
                        item { Action("Next title / finish", artwork.size + 1, state, coordinator::next) }
                        item { Action("Other title matches", artwork.size + 2, state, coordinator::back) }
                    }
                    ScrapePage.Metadata -> {
                        val fields = state.candidate?.metadata.orEmpty().entries.toList()
                        itemsIndexed(fields, key = { _, field -> field.key }) { index, field ->
                            Row(Modifier.fillMaxWidth().background(if (state.focus == index) Color(0xFF34312D) else Color.Transparent)) {
                                Checkbox(checked = field.key in state.checkedFields, enabled = !state.busy,
                                    onCheckedChange = { coordinator.toggle(field.key) }, modifier = Modifier.testTag("metadata-${field.key}"))
                                Column(Modifier.weight(1f).padding(8.dp)) {
                                    Text(field.key.replaceFirstChar(Char::uppercase), fontSize = 14.sp)
                                    Text(field.value[state.metadataChoices[field.key] ?: 0], fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (field.value.size > 1) TextButton(enabled = !state.busy, onClick = { coordinator.variant(field.key) }) { Text("Variant ${(state.metadataChoices[field.key] ?: 0) + 1}/${field.value.size}") }
                            }
                        }
                        item { Action("Apply checked fields", fields.size, state, coordinator::applyMetadata) }
                        item { Action("Back to artwork", fields.size + 1, state, coordinator::back) }
                    }
                }
            }
            Text("↑ ↓ Choose · A Select · B Back / cancel · Label, media and metadata remain independent", fontSize = 12.sp)
        }
    }
}
private fun mediaTitle(type: String) = when (type) {
    "support-texture" -> "Flat cartridge label"
    "support-2D" -> "Cartridge image"
    "box-2D" -> "Box artwork"
    "ss", "screenshot" -> "Screenshot"
    "sstitle" -> "Title screen"
    "wheel" -> "Title logo"
    else -> type
}
private fun regionTitle(region: String) = when (region.lowercase()) {
    "us" -> "United States"
    "eu" -> "Europe"
    "jp" -> "Japan"
    "wor" -> "Worldwide"
    "" -> "Unspecified region"
    else -> region.uppercase()
}
@Composable private fun Action(title: String, index: Int, state: ScrapeState, action: () -> Unit) {
    TextButton(enabled = !state.busy, onClick = action, modifier = Modifier.fillMaxWidth().testTag("scrape-action-$index")
        .background(if (state.focus == index) Color(0xFF34312D) else Color.Transparent)) { Text(title, modifier = Modifier.fillMaxWidth()) }
}
@Composable private fun ProviderThumbnail(asset: ArtworkCandidate, provider: ArtworkProvider) {
    var bitmap by remember(asset.url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(asset.url) { mutableStateOf(false) }
    LaunchedEffect(asset.url) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val bytes = provider.image(asset)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && max(bounds.outWidth, bounds.outHeight) <= 32768 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (failure: Exception) { if (failure is CancellationException) throw failure; failed = true }
    }
    Box(Modifier.size(100.dp, 58.dp)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "${asset.type} ${asset.region}", modifier = Modifier.fillMaxSize()) }
            ?: Text(if (failed) "No preview" else "Loading…", fontSize = 11.sp)
    }
}
