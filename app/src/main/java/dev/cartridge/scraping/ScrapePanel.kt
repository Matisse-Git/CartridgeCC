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
    LaunchedEffect(state.page, state.focus) { list.scrollToItem(state.focus + if (state.page == ScrapePage.Review) 1 else 0) }
    Surface(Modifier.fillMaxSize().testTag("scrape-panel"), color = Color(0xFF191817)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Scrape artwork & metadata", fontSize = 24.sp)
                    Text("${state.candidate?.sourceName?.takeIf(String::isNotBlank) ?: if (state.current?.local == true) "Imported pack" else coordinator.provider.name} · ${ScrapePreferences.regions.first { it.code == state.preferences.region }.title} · ${ScrapePreferences.languages.first { it.code == state.preferences.language }.title}", fontSize = 12.sp)
                }
                TextButton(onClick = coordinator::back, modifier = Modifier.testTag("scrape-back")) { Text(if (state.busy) "Cancel" else "Back") }
            }
            if (state.page !in setOf(ScrapePage.Scope, ScrapePage.Targets, ScrapePage.Preferences)) state.current?.let {
                Text("${state.index + 1} / ${state.entries.size} · ${it.game.title} · ${state.entries.count { entry -> entry.outcome == ScrapeOutcome.Applied }} applied · ${state.entries.count { entry -> entry.outcome == ScrapeOutcome.Skipped }} skipped", modifier = Modifier.padding(vertical = 8.dp)) }
            if (state.busy || !state.ready) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.progress ?: "Working…", fontSize = 12.sp) }
            state.message?.let { Text(it, color = Color(0xFFE2CFAE), modifier = Modifier.padding(vertical = 8.dp).testTag("scrape-message")) }
            if (state.page == ScrapePage.Scope && state.entries.isNotEmpty()) Text("Resume the saved batch, or start a new search to replace its progress. Applied artwork stays.", fontSize = 12.sp)
            if (state.page == ScrapePage.Matches) {
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = state.current?.query.orEmpty(), onValueChange = coordinator::query,
                        enabled = !state.busy && state.current?.local != true, singleLine = true, label = { Text(if (state.current?.local == true) "Offline pack candidates" else "Correct title search") },
                        modifier = Modifier.weight(1f).testTag("scrape-query"))
                    if (state.current?.local != true) TextButton(enabled = !state.busy, onClick = coordinator::searchCurrent, modifier = Modifier.testTag("scrape-search")) { Text("Search") }
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
            if (state.page == ScrapePage.Targets) Text("${state.pickedIds.size} selected · A toggles a title · X selects all / clears", fontSize = 12.sp)
            if (state.page == ScrapePage.Preferences) Text("Preferences rank choices. Every region and language remains available.", fontSize = 12.sp)
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("scrape-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.page) {
                    ScrapePage.Scope -> {
                        item { Action("This title", 0, state) { coordinator.chooseScope(true) } }
                        item { Action("GBA collection", 1, state) { coordinator.chooseScope(false) } }
                        item { Action("All games", 2, state) { coordinator.chooseScope(false) } }
                        item { Action(if (state.entries.isEmpty()) "No saved batch" else "Resume saved batch · ${state.index + 1} / ${state.entries.size}", 3, state, coordinator::resumeBatch) }
                        item { Action("Choose games…", 4, state, coordinator::targets) }
                        item { Action("Games missing labels · ${state.availableGames.count { it.label == null }}", 5, state, coordinator::missingLabels) }
                        item { Action("Region & language", 6, state, coordinator::preferencePage) }
                        item { Action("Import scrape pack…", 7, state, coordinator::pickPack) }
                        item { Action("Close", 8, state, coordinator::back) }
                    }
                    ScrapePage.Targets -> {
                        item { Action("Search ${state.pickedIds.size} selected games", 0, state, coordinator::beginPicked) }
                        item { Action("Select all", 1, state) { coordinator.pickAll(true) } }
                        item { Action("Clear selection", 2, state) { coordinator.pickAll(false) } }
                        itemsIndexed(state.availableGames, key = { _, game -> game.id }) { index, game ->
                            Row(Modifier.fillMaxWidth().background(if (state.focus == index + 3) Color(0xFF34312D) else Color.Transparent)) {
                                Checkbox(checked = game.id in state.pickedIds, enabled = !state.busy,
                                    onCheckedChange = { coordinator.pick(game.id) }, modifier = Modifier.testTag("scrape-target-$index"))
                                Column(Modifier.weight(1f).padding(8.dp)) {
                                    Text(game.title); Text(if (game.label == null) "No label assigned" else "Label assigned · current choice kept", fontSize = 12.sp)
                                }
                            }
                        }
                        item { Action("Back", state.availableGames.size + 3, state, coordinator::back) }
                    }
                    ScrapePage.Preferences -> {
                        item { Action("Preferred label / title region: ${ScrapePreferences.regions.first { it.code == state.preferences.region }.title}", 0, state) { coordinator.cyclePreference(true) } }
                        item { Action("Preferred description language: ${ScrapePreferences.languages.first { it.code == state.preferences.language }.title}", 1, state) { coordinator.cyclePreference(false) } }
                        item { Action("Back", 2, state, coordinator::back) }
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
                    ScrapePage.Review -> {
                        val game = state.current?.game
                        val candidate = state.candidate
                        val label = candidate?.artwork?.firstOrNull { it.isLabel }
                        item {
                            Row(Modifier.padding(vertical = 8.dp)) {
                                label?.let { ProviderThumbnail(it, coordinator.provider) }
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text(candidate?.title.orEmpty(), fontSize = 20.sp)
                                    Text(if (game?.label != null) "Current label kept · alternatives allow explicit replacement"
                                        else label?.let { "Preferred flat label · ${regionTitle(it.region)}" } ?: "No flat label for this match", fontSize = 12.sp)
                                    candidate?.metadata.orEmpty().forEach { (field, values) ->
                                        Text("${field.replaceFirstChar(Char::uppercase)}: ${game?.metadata?.get(field)?.value?.let { "Keep $it" } ?: values.firstOrNull().orEmpty()}",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        item { ReviewAction("Preview preferred label", 0, state, "review-label", label != null && game?.label == null, coordinator::preferredLabel) }
                        item { ReviewAction("Fill missing metadata", 1, state, "review-fill", candidate?.metadata?.keys?.any { game?.metadata?.get(it)?.value.isNullOrBlank() } == true, coordinator::fillMissingMetadata) }
                        item { ReviewAction("All label / media choices", 2, state, "review-media", true, coordinator::browseArtwork) }
                        item { ReviewAction("Choose metadata fields / variants", 3, state, "review-fields", true, coordinator::metadata) }
                        item { ReviewAction("Next title / finish", 4, state, "review-next", true, coordinator::next) }
                        item { ReviewAction("Undo last artwork / metadata change", 5, state, "review-undo", game?.canUndoEnrichment == true, coordinator::undo) }
                        item { ReviewAction("Other title matches", 6, state, "review-match", true, coordinator::back) }
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
                        item { Action("Back to review", artwork.size + 2, state, coordinator::back) }
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
                                    state.current?.game?.metadata?.get(field.key)?.let { Text("Replaces: ${it.value}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    state.candidate?.metadataTags?.get(field.key)?.getOrNull(state.metadataChoices[field.key] ?: 0)?.let { tag ->
                                        if (tag.region.isNotEmpty() || tag.language.isNotEmpty()) Text(listOf(tag.region, tag.language).filter(String::isNotEmpty).joinToString(" · ").uppercase(), fontSize = 11.sp)
                                    }
                                }
                                if (field.value.size > 1) TextButton(enabled = !state.busy, onClick = { coordinator.variant(field.key) }) { Text("Variant ${(state.metadataChoices[field.key] ?: 0) + 1}/${field.value.size}") }
                            }
                        }
                        item { Action("Apply checked fields", fields.size, state, coordinator::applyMetadata) }
                        item { Action("Back to review", fields.size + 1, state, coordinator::back) }
                    }
                }
            }
            Text("↑ ↓ Choose · A Select · B Back / cancel · Label, media and metadata remain independent", fontSize = 12.sp)
        }
    }
}
@Composable private fun ReviewAction(title: String, index: Int, state: ScrapeState, tag: String, enabled: Boolean, action: () -> Unit) {
    TextButton(enabled = enabled && !state.busy, onClick = action, modifier = Modifier.fillMaxWidth().testTag(tag)
        .background(if (state.focus == index) Color(0xFF34312D) else Color.Transparent)) { Text(title, modifier = Modifier.fillMaxWidth()) }
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
    TextButton(enabled = !state.busy && state.ready && !(state.page == ScrapePage.Scope && index == 3 && state.entries.isEmpty()), onClick = action, modifier = Modifier.fillMaxWidth().testTag("scrape-action-$index")
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
