package dev.cartridge.scraping

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.RowKind
import dev.cartridge.ui.*
import kotlinx.coroutines.*
import kotlin.math.max

/**
 * The reviewed artwork workflow in the console's own components. Queue, journal and provider behaviour
 * belong to [ScrapeCoordinator] and are unchanged; row positions are its focus contract.
 */
@Composable fun ScrapePanel(state: ScrapeState, coordinator: ScrapeCoordinator, confirm: String = "A", back: String = "B") {
    val list = rememberLazyListState()
    LaunchedEffect(state.page, state.focus) { list.scrollToItem(state.focus + if (state.page == ScrapePage.Review) 1 else 0) }
    val region = ScrapePreferences.regions.first { it.code == state.preferences.region }.title
    val language = ScrapePreferences.languages.first { it.code == state.preferences.language }.title
    val source = state.candidate?.sourceName?.takeIf(String::isNotBlank) ?: if (state.current?.local == true) "Imported pack" else coordinator.provider.name
    Box(Modifier.fillMaxSize().background(Ink.Stage).testTag("scrape-panel").windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = Space.Edge, vertical = Space.L)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.XL)) {
            Column(Modifier.weight(.36f).fillMaxHeight()) {
                Txt(when (state.page) {
                    ScrapePage.Scope -> "Artwork & Info"
                    ScrapePage.Consoles -> "Choose consoles"
                    ScrapePage.Targets -> "Choose games"
                    ScrapePage.Preferences -> "Region & language"
                    ScrapePage.Matches -> "Choose a match"
                    ScrapePage.Review -> "Review"
                    ScrapePage.Artwork -> "Labels & artwork"
                    ScrapePage.Metadata -> "Details"
                }, Type.Title)
                Txt("$source · $region · $language", Type.Label, Ink.Faint, Modifier.padding(top = Space.XS), lines = 1)
                if (state.page !in setOf(ScrapePage.Scope, ScrapePage.Targets, ScrapePage.Consoles, ScrapePage.Preferences)) state.current?.let { entry ->
                    Txt(entry.game.title, Type.Heading, modifier = Modifier.padding(top = Space.L), lines = 2)
                    Txt("${state.index + 1} of ${state.entries.size} · ${state.entries.count { it.outcome == ScrapeOutcome.Applied }} applied · ${state.entries.count { it.outcome == ScrapeOutcome.Skipped }} skipped",
                        Type.Label, Ink.Soft, Modifier.padding(top = 2.dp))
                }
                Txt(when (state.page) {
                    ScrapePage.Scope -> if (state.entries.isNotEmpty()) "Pick up the saved batch, or start a new search to replace its progress. Artwork you already applied stays."
                        else "Find labels and details for your games. Nothing is changed until you choose it."
                    ScrapePage.Artwork -> "Choose a flat label to try on the real ${if (state.current?.game?.system == dev.cartridge.domain.GameSystem.PS1) "disc" else "cartridge"}, or keep other artwork alongside it." +
                        if (state.candidate?.artwork?.none { it.isLabel } == true) " This match has no flat label." else ""
                    ScrapePage.Metadata -> "Tick only the fields you want. X steps through a field's versions."
                    ScrapePage.Targets -> "${state.pickedIds.size} selected. X selects all or clears."
                    ScrapePage.Consoles -> "X selects all or clears."
                    ScrapePage.Preferences -> "Preferences decide what is offered first. Every region and language stays available."
                    ScrapePage.Matches -> if (!state.busy && state.current?.searched == true && state.current?.matches?.isEmpty() == true) "No match. Correct the search or move to the next title."
                        else "Your current label and details are kept until you choose a replacement."
                    ScrapePage.Review -> "Fill what is missing, or open every choice to replace something on purpose."
                }, Type.Body, Ink.Soft, Modifier.padding(top = Space.L))
                if (state.busy || !state.ready) {
                    ProgressLine(null, Modifier.padding(top = Space.L))
                    Txt(state.progress ?: "Working", Type.Label, Ink.Soft, Modifier.padding(top = Space.S))
                }
                state.message?.let { Txt(it, Type.Label, Ink.Warning, Modifier.padding(top = Space.M).testTag("scrape-message"), lines = 4) }
                state.current?.error?.takeIf { state.page == ScrapePage.Matches }?.let {
                    Txt(it, Type.Label, Ink.Warning, Modifier.padding(top = Space.M), lines = 3)
                    Box(Modifier.padding(top = Space.S)) { RowItem(MenuRow("retry", "Retry failed titles", tag = "scrape-retry", action = {}), false) { coordinator.retryRemaining() } }
                }
                Spacer(Modifier.weight(1f))
                HintBar(listOf(Hint(confirm, "Select"), Hint(back, if (state.busy) "Stop" else "Back", "scrape-back") { coordinator.back() }))
            }
            Column(Modifier.weight(.64f).fillMaxHeight()) {
                if (state.page == ScrapePage.Matches) {
                    val accent = LocalAccent.current
                    Row(Modifier.fillMaxWidth().padding(bottom = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(Radius.Row)).background(Ink.Surface).border(1.dp, Ink.Line, RoundedCornerShape(Radius.Row)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                            BasicTextField(state.current?.query.orEmpty(), coordinator::query, Modifier.fillMaxWidth().testTag("scrape-query"),
                                enabled = !state.busy && state.current?.local != true, textStyle = Type.Body.copy(color = Ink.Text), singleLine = true, cursorBrush = SolidColor(accent))
                        }
                        if (state.current?.local != true) Box(Modifier.clip(RoundedCornerShape(Radius.Row)).background(Ink.Raised).tap("scrape-search", !state.busy) { coordinator.searchCurrent() }
                            .padding(horizontal = 16.dp, vertical = 11.dp)) { Txt("Search", Type.Body) }
                    }
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("scrape-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when (state.page) {
                        ScrapePage.Scope -> {
                            item { Action("This game", 0, state) { coordinator.chooseScope(true) } }
                            item { Action("Choose consoles", 1, state, link = true, action = coordinator::consoles) }
                            item { Action("All games", 2, state) { coordinator.chooseScope(false) } }
                            item { Action(if (state.entries.isEmpty()) "No saved batch" else "Continue saved batch", 3, state,
                                value = if (state.entries.isEmpty()) null else "${state.index + 1} of ${state.entries.size}", action = coordinator::resumeBatch) }
                            item { Action("Choose games", 4, state, link = true, action = coordinator::targets) }
                            item { Action("Games without a label", 5, state, value = "${state.availableGames.count { it.label == null }}", action = coordinator::missingLabels) }
                            item { Action("Region & language", 6, state, link = true, action = coordinator::preferencePage) }
                            item { Action("Import an artwork pack", 7, state, action = coordinator::pickPack) }
                            item { Action("Close", 8, state, action = coordinator::back) }
                        }
                        ScrapePage.Consoles -> {
                            item { Action("Search selected consoles", 0, state, action = coordinator::beginConsoles) }
                            item { Action("Select all", 1, state) { coordinator.pickAllConsoles(true) } }
                            item { Action("Clear selection", 2, state) { coordinator.pickAllConsoles(false) } }
                            itemsIndexed(state.consoles, key = { _, system -> system.name }) { index, system ->
                                Check(system.title, "${state.availableGames.count { it.system == system }} games", system in state.pickedSystems, index + 3, state, "scrape-action-${index + 3}") { coordinator.pickConsole(system) }
                            }
                            item { Action("Back", state.consoles.size + 3, state, action = coordinator::back) }
                        }
                        ScrapePage.Targets -> {
                            item { Action("Search ${state.pickedIds.size} selected games", 0, state, action = coordinator::beginPicked) }
                            item { Action("Select all", 1, state) { coordinator.pickAll(true) } }
                            item { Action("Clear selection", 2, state) { coordinator.pickAll(false) } }
                            itemsIndexed(state.availableGames, key = { _, game -> game.id }) { index, game ->
                                Check(game.title, if (game.label == null) "No label yet" else "Has a label · kept unless you replace it", game.id in state.pickedIds,
                                    index + 3, state, "scrape-target-$index") { coordinator.pick(game.id) }
                            }
                            item { Action("Back", state.availableGames.size + 3, state, action = coordinator::back) }
                        }
                        ScrapePage.Preferences -> {
                            item { Action("Preferred label and title region", 0, state, value = region) { coordinator.cyclePreference(true) } }
                            item { Action("Preferred description language", 1, state, value = language) { coordinator.cyclePreference(false) } }
                            item { Action("Back", 2, state, action = coordinator::back) }
                        }
                        ScrapePage.Matches -> {
                            val matches = state.current?.matches.orEmpty()
                            itemsIndexed(matches, key = { _, game -> game.id }) { index, game ->
                                Action(listOfNotNull(game.title, game.metadata["year"]?.firstOrNull()?.takeIf(String::isNotBlank)).joinToString(" · "), index, state,
                                    note = "${if (game.exactHash) "Exact match for your file" else "Title match"} · ${game.artwork.count { it.isLabel }} labels · ${game.artwork.size} images") { coordinator.chooseCandidate(index) }
                            }
                            item { Action("Search this title again", matches.size, state, action = coordinator::searchCurrent) }
                            item { Action("Next title", matches.size + 1, state, action = coordinator::next) }
                            item { Action("Change what to search", matches.size + 2, state, action = coordinator::back) }
                        }
                        ScrapePage.Review -> {
                            val game = state.current?.game
                            val candidate = state.candidate
                            val label = candidate?.artwork?.firstOrNull { it.isLabel }
                            item {
                                Row(Modifier.padding(start = 14.dp, bottom = Space.M), horizontalArrangement = Arrangement.spacedBy(Space.L)) {
                                    label?.let { ProviderThumbnail(it, coordinator.provider) }
                                    Column(Modifier.weight(1f)) {
                                        Txt(candidate?.title.orEmpty(), Type.Heading, lines = 2)
                                        Txt(if (game?.label != null) "Your current label is kept" else label?.let { "Preferred label · ${regionTitle(it.region)}" } ?: "No flat label for this match",
                                            Type.Label, Ink.Soft, Modifier.padding(top = 2.dp))
                                        candidate?.metadata.orEmpty().entries.take(4).forEach { (field, values) ->
                                            Txt("${field.replaceFirstChar(Char::uppercase)}  ${game?.metadata?.get(field)?.value?.let { "$it (kept)" } ?: values.firstOrNull().orEmpty()}",
                                                Type.Label, Ink.Faint, Modifier.padding(top = 2.dp), lines = 1)
                                        }
                                    }
                                }
                            }
                            item { ReviewAction("Try the preferred label", 0, state, "review-label", label != null && game?.label == null, coordinator::preferredLabel) }
                            item { ReviewAction("Fill in missing details", 1, state, "review-fill", candidate?.metadata?.keys?.any { game?.metadata?.get(it)?.value.isNullOrBlank() } == true, coordinator::fillMissingMetadata) }
                            item { ReviewAction("All labels and artwork", 2, state, "review-media", true, coordinator::browseArtwork) }
                            item { ReviewAction("Choose details field by field", 3, state, "review-fields", true, coordinator::metadata) }
                            item { ReviewAction("Next title", 4, state, "review-next", true, coordinator::next) }
                            item { ReviewAction("Undo last change", 5, state, "review-undo", game?.canUndoEnrichment == true, coordinator::undo) }
                            item { ReviewAction("Other matches", 6, state, "review-match", true, coordinator::back) }
                        }
                        ScrapePage.Artwork -> {
                            item { Action("Choose details separately", 0, state, link = true, action = coordinator::metadata) }
                            val artwork = state.candidate?.artwork.orEmpty()
                            itemsIndexed(artwork, key = { _, asset -> asset.url }) { index, asset ->
                                val focused = state.focus == index + 1
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Row)).background(if (focused) Ink.Raised else androidx.compose.ui.graphics.Color.Transparent)
                                    .tap("scrape-asset-$index", !state.busy) { coordinator.artwork(index) }.padding(horizontal = 14.dp, vertical = Space.S),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
                                    ProviderThumbnail(asset, coordinator.provider)
                                    Column(Modifier.weight(1f)) {
                                        Txt("${mediaTitle(asset.type)} · ${regionTitle(asset.region)}", Type.Body)
                                        Txt(if (asset.isLabel) "Try it on the real object" else "Keep as extra artwork", Type.Label, Ink.Faint, Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                            item { Action("Next title", artwork.size + 1, state, action = coordinator::next) }
                            item { Action("Back to review", artwork.size + 2, state, action = coordinator::back) }
                        }
                        ScrapePage.Metadata -> {
                            val fields = state.candidate?.metadata.orEmpty().entries.toList()
                            itemsIndexed(fields, key = { _, field -> field.key }) { index, field ->
                                val choice = state.metadataChoices[field.key] ?: 0
                                val tag = state.candidate?.metadataTags?.get(field.key)?.getOrNull(choice)
                                Check(field.key.replaceFirstChar(Char::uppercase), listOfNotNull(field.value[choice],
                                    state.current?.game?.metadata?.get(field.key)?.let { "Replaces: ${it.value}" },
                                    if (field.value.size > 1) "Version ${choice + 1} of ${field.value.size}" + listOfNotNull(tag?.region, tag?.language).filter(String::isNotEmpty).joinToString(" · ").let { if (it.isEmpty()) "" else " · ${it.uppercase()}" } else null)
                                    .joinToString("\n"), field.key in state.checkedFields, index, state, "metadata-${field.key}") { coordinator.toggle(field.key) }
                            }
                            item { Action("Apply ticked fields", fields.size, state, action = coordinator::applyMetadata) }
                            item { Action("Back to review", fields.size + 1, state, action = coordinator::back) }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun ReviewAction(title: String, index: Int, state: ScrapeState, tag: String, enabled: Boolean, action: () -> Unit) =
    RowItem(MenuRow(tag, title, enabled = enabled && !state.busy, tag = tag, action = action), state.focus == index, onClick = action)
private fun mediaTitle(type: String) = when (type) {
    "support-texture" -> "Flat label"
    "support-2D" -> "Photo of the media"
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
@Composable private fun Action(title: String, index: Int, state: ScrapeState, value: String? = null, note: String? = null, link: Boolean = false, action: () -> Unit) {
    val enabled = !state.busy && state.ready && !(state.page == ScrapePage.Scope && index == 3 && state.entries.isEmpty())
    RowItem(MenuRow("action-$index", title, if (link) RowKind.Link else RowKind.Action, value = value, note = note, enabled = enabled,
        tag = "scrape-action-$index", action = action), state.focus == index, onClick = action)
}
@Composable private fun Check(title: String, note: String, checked: Boolean, index: Int, state: ScrapeState, tag: String, toggle: () -> Unit) =
    RowItem(MenuRow(tag, title, RowKind.Toggle, note = note, checked = checked, enabled = !state.busy, tag = tag, adjust = { toggle() }), state.focus == index, onClick = toggle)
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
    Box(Modifier.size(104.dp, 60.dp).clip(RoundedCornerShape(Radius.Chip)).background(Ink.Surface), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "${asset.type} ${asset.region}", modifier = Modifier.fillMaxSize()) }
            ?: Txt(if (failed) "No preview" else "Loading", Type.Label, Ink.Faint)
    }
}
