package dev.cartridge.scraping

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cartridge.library.FillMode
import dev.cartridge.library.isCaseArt
import dev.cartridge.shell.MenuRow
import dev.cartridge.shell.RowKind
import dev.cartridge.ui.design.DesignCanvas
import dev.cartridge.ui.design.Hint
import dev.cartridge.ui.design.HintBar
import dev.cartridge.ui.design.Layout
import dev.cartridge.ui.design.Pad
import dev.cartridge.ui.design.Palette
import dev.cartridge.ui.design.Progress
import dev.cartridge.ui.design.SettingRow
import dev.cartridge.ui.design.Text
import dev.cartridge.ui.design.Typo
import dev.cartridge.ui.design.Typo.tabular
import dev.cartridge.ui.design.tap
import kotlinx.coroutines.*
import kotlin.math.max

/**
 * Artwork & Info, the reviewed artwork workflow, as a page of its own (spec 2): what it is doing at the left, its
 * choices in a card at the right, the buttons at the foot. Queue, journal and provider behaviour belong to
 * [ScrapeCoordinator] and are unchanged; row positions are its focus contract.
 */
@Composable fun ScrapePanel(state: ScrapeState, coordinator: ScrapeCoordinator, swapped: Boolean, reducedMotion: Boolean) {
    DesignCanvas(reducedMotion) {
        Box(Modifier.fillMaxSize().background(Palette.Canvas).testTag("scrape-panel")) {
            Row(Modifier.padding(start = Layout.Margin, end = Layout.Margin, top = 40.dp, bottom = Layout.HintBar).fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Story(state, coordinator, Modifier.width(340.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.page == ScrapePage.Matches) SearchField(state, coordinator)
                    Choices(state, coordinator, Modifier.weight(1f, fill = false))
                }
            }
            HintBar(hints(state, coordinator, swapped), Modifier.align(Alignment.BottomStart))
        }
    }
}

/** The left column: where the player is, the game in hand, and what the work is doing. */
@Composable private fun Story(state: ScrapeState, coordinator: ScrapeCoordinator, modifier: Modifier) {
    val region = ScrapePreferences.regions.first { it.code == state.preferences.region }.title
    val language = ScrapePreferences.languages.first { it.code == state.preferences.language }.title
    val source = state.candidate?.sourceName?.takeIf(String::isNotBlank) ?: if (state.current?.local == true) "Imported pack" else coordinator.provider.name
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(when (state.page) {
            ScrapePage.Scope -> "Artwork & Info"
            ScrapePage.Consoles -> "Choose consoles"
            ScrapePage.Targets -> "Choose games"
            ScrapePage.Preferences -> "Region & language"
            ScrapePage.Matches -> "Choose a match"
            ScrapePage.Review -> "Review"
            ScrapePage.Artwork -> "Case art & artwork"
            ScrapePage.Metadata -> "Details"
        }, Typo.Title, lines = 2)
        Text("$source · $region · $language", Typo.Body.copy(fontWeight = FontWeight.Normal), Modifier.padding(top = 8.dp), Palette.Secondary, lines = 1)
        if (state.page !in setOf(ScrapePage.Scope, ScrapePage.Targets, ScrapePage.Consoles, ScrapePage.Preferences)) state.current?.let { entry ->
            Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface2).padding(16.dp)) {
                Text(entry.game.title, Typo.Headline, lines = 2)
                Text("${state.index + 1} of ${state.entries.size} · ${state.entries.count { it.outcome == ScrapeOutcome.Applied }} applied · " +
                    "${state.entries.count { it.outcome == ScrapeOutcome.Skipped }} skipped", Typo.Caption.tabular(), Modifier.padding(top = 4.dp), Palette.Tertiary, lines = 1)
            }
        }
        Text(when (state.page) {
            ScrapePage.Scope -> if (state.entries.isNotEmpty()) "Pick up the saved batch, or start a new search to replace its progress. Artwork you already applied stays."
                else "This game and Choose games let you review each match. Consoles, All games and Games without case art apply the best match for you: " +
                    if (state.fill.mode == FillMode.Missing) "only what is missing is filled." else if (state.fill.replaceManual) "everything is replaced, your own choices too. Undo works per game."
                    else "what a scrape applied before is replaced; your own choices are kept."
            ScrapePage.Artwork -> "Choose case art to try on the real case, or keep other artwork alongside it." +
                if (state.candidate?.artwork?.none { it.isCaseArt } == true) " This match has no case art." else ""
            ScrapePage.Metadata -> "Tick only the fields you want."
            ScrapePage.Targets -> "${state.pickedIds.size} selected."
            ScrapePage.Consoles -> "Pick the consoles whose games to search."
            ScrapePage.Preferences -> "Preferences decide what is offered first. Every region and language stays available."
            ScrapePage.Matches -> if (!state.busy && state.current?.searched == true && state.current?.matches?.isEmpty() == true) "No match. Correct the search or move to the next title."
                else "Your current case art and details are kept until you choose a replacement."
            ScrapePage.Review -> "Fill what is missing, or open every choice to replace something on purpose."
        }, Typo.Callout.copy(fontWeight = FontWeight.Normal, lineHeight = 21.sp), Modifier.padding(top = 20.dp), Palette.Secondary)
        if (state.busy || !state.ready) {
            Progress(null, Modifier.padding(top = 24.dp))
            Text(state.progress ?: "Working", Typo.Caption, Modifier.padding(top = 8.dp), Palette.Tertiary, lines = 2)
        }
        state.message?.let { Text(it, Typo.Caption, Modifier.padding(top = 16.dp).testTag("scrape-message"), Palette.DestructiveText, lines = 4) }
        state.current?.error?.takeIf { state.page == ScrapePage.Matches }?.let {
            Text(it, Typo.Caption, Modifier.padding(top = 16.dp), Palette.DestructiveText, lines = 3)
            SettingRow(MenuRow("retry", "Retry failed titles", tag = "scrape-retry", action = {}), false, Modifier.padding(top = 8.dp)) { coordinator.retryRemaining() }
        }
    }
}

/** The title to search for, editable by hardware keyboard or touch, beside its Search button. */
@Composable private fun SearchField(state: ScrapeState, coordinator: ScrapeCoordinator) {
    val local = state.current?.local == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(14.dp)
        Box(Modifier.weight(1f).height(48.dp).clip(shape).background(Palette.Surface2).border(1.dp, Palette.Separator, shape).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart) {
            BasicTextField(state.current?.query.orEmpty(), coordinator::query, Modifier.fillMaxWidth().testTag("scrape-query"),
                enabled = !state.busy && !local, textStyle = Typo.Callout.copy(fontWeight = FontWeight.Normal, color = Palette.Label), singleLine = true,
                cursorBrush = SolidColor(Palette.Accent))
        }
        if (!local) Box(Modifier.height(48.dp).clip(shape).background(Palette.Surface3).tap("scrape-search", !state.busy) { coordinator.searchCurrent() }
            .padding(horizontal = 20.dp).alpha(if (state.busy) .42f else 1f), contentAlignment = Alignment.Center) {
            Text("Search", Typo.Callout, lines = 1)
        }
    }
}

/** One thing in the list at the right. Its position is the coordinator's focus index. */
private sealed interface Entry { val key: String }
private class RowEntry(val row: MenuRow, val focus: Int) : Entry { override val key get() = row.tag ?: row.id }
private class AssetEntry(val asset: ArtworkCandidate, val index: Int) : Entry { override val key get() = "asset:${asset.url}" }
private object ReviewHeader : Entry { override val key = "review-header" }

/** The page's choices in one card, divided by hairlines, the focused row filled white. */
@Composable private fun Choices(state: ScrapeState, coordinator: ScrapeCoordinator, modifier: Modifier) {
    val entries = entries(state, coordinator)
    val list = rememberLazyListState()
    val offset = if (state.page == ScrapePage.Review) 1 else 0
    LaunchedEffect(state.page, state.focus) { list.scrollToItem(state.focus + offset) }
    LazyColumn(state = list, modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface2).testTag("scrape-list"),
        contentPadding = PaddingValues(4.dp)) {
        itemsIndexed(entries, key = { _, entry -> entry.key }) { position, entry ->
            val focusOf = { e: Entry? -> when (e) { is RowEntry -> e.focus; is AssetEntry -> e.index + 1; else -> -1 } }
            val here = focusOf(entry)
            val next = entries.getOrNull(position + 1)
            Column {
                when (entry) {
                    is RowEntry -> SettingRow(entry.row, state.focus == entry.focus, showNote = true) { entry.row.action?.invoke() }
                    is AssetEntry -> AssetRow(entry.asset, entry.index, state.focus == entry.index + 1, state.busy, coordinator)
                    ReviewHeader -> ReviewCard(state, coordinator)
                }
                if (next != null && here != state.focus && focusOf(next) != state.focus)
                    Box(Modifier.padding(start = 12.dp, end = 8.dp).fillMaxWidth().height(1.dp).background(Palette.Separator))
            }
        }
    }
}

/** The rows of each page, in the coordinator's order. */
private fun entries(state: ScrapeState, coordinator: ScrapeCoordinator): List<Entry> = buildList {
    val ready = !state.busy && state.ready
    fun action(title: String, index: Int, value: String? = null, note: String? = null, link: Boolean = false, act: () -> Unit) {
        val enabled = ready && !(state.page == ScrapePage.Scope && index == 3 && state.entries.isEmpty())
        add(RowEntry(MenuRow("action-$index", title, if (link) RowKind.Link else RowKind.Action, value = value, note = note, enabled = enabled,
            tag = "scrape-action-$index", action = act), index))
    }
    fun check(title: String, note: String, checked: Boolean, index: Int, tag: String, toggle: () -> Unit) =
        add(RowEntry(MenuRow(tag, title, RowKind.Toggle, note = note, checked = checked, enabled = !state.busy, tag = tag, adjust = { toggle() }, action = toggle), index))
    fun review(title: String, index: Int, tag: String, enabled: Boolean, act: () -> Unit) =
        add(RowEntry(MenuRow(tag, title, enabled = enabled && !state.busy, tag = tag, action = act), index))
    when (state.page) {
        ScrapePage.Scope -> {
            action("This game", 0) { coordinator.chooseScope(true) }
            action("Choose consoles", 1, link = true, act = coordinator::consoles)
            action("All games", 2) { coordinator.chooseScope(false) }
            action(if (state.entries.isEmpty()) "No saved batch" else "Continue saved batch", 3,
                value = if (state.entries.isEmpty()) null else "${state.index + 1} of ${state.entries.size}", act = coordinator::resumeBatch)
            action("Choose games", 4, link = true, act = coordinator::targets)
            action("Games without case art", 5, value = "${state.availableGames.count { it.label?.isCaseArt != true }}", act = coordinator::missingLabels)
            action("Region & language", 6, link = true, act = coordinator::preferencePage)
            action("Import an artwork pack", 7, act = coordinator::pickPack)
            action("How much to change", 8, value = state.fill.mode.title, note = "For Consoles, All games and Games without case art", act = coordinator::cycleFill)
            check("Also replace my manual choices", if (state.fill.mode == FillMode.All) "Art and details you picked yourself are replaced too" else "Only with Fill all",
                state.fill.replaceManual, 9, "scrape-action-9", coordinator::toggleReplaceManual)
            action("Close", 10, act = coordinator::back)
        }
        ScrapePage.Consoles -> {
            action("Search selected consoles", 0, act = coordinator::beginConsoles)
            action("Select all", 1) { coordinator.pickAllConsoles(true) }
            action("Clear selection", 2) { coordinator.pickAllConsoles(false) }
            state.consoles.forEachIndexed { index, system ->
                check(system.title, "${state.availableGames.count { it.system == system }} games", system in state.pickedSystems, index + 3, "scrape-action-${index + 3}") { coordinator.pickConsole(system) }
            }
            action("Back", state.consoles.size + 3, act = coordinator::back)
        }
        ScrapePage.Targets -> {
            action("Search ${state.pickedIds.size} selected games", 0, act = coordinator::beginPicked)
            action("Select all", 1) { coordinator.pickAll(true) }
            action("Clear selection", 2) { coordinator.pickAll(false) }
            state.availableGames.forEachIndexed { index, game ->
                check(game.title, if (game.label == null) "No case art yet" else "Has artwork · kept unless you replace it", game.id in state.pickedIds,
                    index + 3, "scrape-target-$index") { coordinator.pick(game.id) }
            }
            action("Back", state.availableGames.size + 3, act = coordinator::back)
        }
        ScrapePage.Preferences -> {
            action("Preferred label and title region", 0, value = ScrapePreferences.regions.first { it.code == state.preferences.region }.title) { coordinator.cyclePreference(true) }
            action("Preferred description language", 1, value = ScrapePreferences.languages.first { it.code == state.preferences.language }.title) { coordinator.cyclePreference(false) }
            action("Back", 2, act = coordinator::back)
        }
        ScrapePage.Matches -> {
            val matches = state.current?.matches.orEmpty()
            matches.forEachIndexed { index, game ->
                add(RowEntry(MenuRow("match-${game.id}", listOfNotNull(game.title, game.metadata["year"]?.firstOrNull()?.takeIf(String::isNotBlank)).joinToString(" · "),
                    RowKind.Action, note = "${if (game.exactHash) "Exact match for your file" else "Title match"} · ${game.artwork.count { it.isCaseArt }} case art · ${game.artwork.size} images",
                    enabled = ready, tag = "scrape-action-$index", action = { coordinator.chooseCandidate(index) }), index))
            }
            action("Search this title again", matches.size, act = coordinator::searchCurrent)
            action("Next title", matches.size + 1, act = coordinator::next)
            action("Change what to search", matches.size + 2, act = coordinator::back)
        }
        ScrapePage.Review -> {
            val game = state.current?.game
            val candidate = state.candidate
            val label = candidate?.artwork?.firstOrNull { it.isCaseArt }
            add(ReviewHeader)
            review("Try the preferred case art", 0, "review-label", label != null && game?.label?.isCaseArt != true, coordinator::preferredLabel)
            review("Fill in missing details", 1, "review-fill", candidate?.metadata?.keys?.any { game?.metadata?.get(it)?.value.isNullOrBlank() } == true, coordinator::fillMissingMetadata)
            review("All case art and artwork", 2, "review-media", true, coordinator::browseArtwork)
            review("Choose details field by field", 3, "review-fields", true, coordinator::metadata)
            review("Next title", 4, "review-next", true, coordinator::next)
            review("Undo last change", 5, "review-undo", game?.canUndoEnrichment == true, coordinator::undo)
            review("Other matches", 6, "review-match", true, coordinator::back)
        }
        ScrapePage.Artwork -> {
            action("Choose details separately", 0, link = true, act = coordinator::metadata)
            val artwork = state.candidate?.artwork.orEmpty()
            artwork.forEachIndexed { index, asset -> add(AssetEntry(asset, index)) }
            action("Next title", artwork.size + 1, act = coordinator::next)
            action("Back to review", artwork.size + 2, act = coordinator::back)
        }
        ScrapePage.Metadata -> {
            val fields = state.candidate?.metadata.orEmpty().entries.toList()
            fields.forEachIndexed { index, field ->
                val choice = state.metadataChoices[field.key] ?: 0
                val tag = state.candidate?.metadataTags?.get(field.key)?.getOrNull(choice)
                check(field.key.replaceFirstChar(Char::uppercase), listOfNotNull(field.value[choice],
                    state.current?.game?.metadata?.get(field.key)?.let { "Replaces: ${it.value}" },
                    if (field.value.size > 1) "Version ${choice + 1} of ${field.value.size}" + listOfNotNull(tag?.region, tag?.language).filter(String::isNotEmpty).joinToString(" · ").let { if (it.isEmpty()) "" else " · ${it.uppercase()}" } else null)
                    .joinToString("\n"), field.key in state.checkedFields, index, "metadata-${field.key}") { coordinator.toggle(field.key) }
            }
            action("Apply ticked fields", fields.size, act = coordinator::applyMetadata)
            action("Back to review", fields.size + 1, act = coordinator::back)
        }
    }
}

/** The match in hand: its preferred case art and the first of its details, beside what the game already has. */
@Composable private fun ReviewCard(state: ScrapeState, coordinator: ScrapeCoordinator) {
    val game = state.current?.game
    val candidate = state.candidate
    val label = candidate?.artwork?.firstOrNull { it.isCaseArt }
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        label?.let { ProviderThumbnail(it, coordinator.provider, 120.dp, 90.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(candidate?.title.orEmpty(), Typo.Headline, lines = 2)
            Text(if (game?.label?.isCaseArt == true) "Your current case art is kept" else label?.let { "Preferred case art · ${regionTitle(it.region)}" } ?: "No case art for this match",
                Typo.Caption, color = Palette.Secondary, lines = 1)
            candidate?.metadata.orEmpty().entries.take(4).forEach { (field, values) ->
                Text("${field.replaceFirstChar(Char::uppercase)}  ${game?.metadata?.get(field)?.value?.let { "$it (kept)" } ?: values.firstOrNull().orEmpty()}",
                    Typo.Small, color = Palette.Tertiary, lines = 1)
            }
        }
    }
}

/** A piece of artwork the match offers, drawn as a settings row with its picture at the leading edge. */
@Composable private fun AssetRow(asset: ArtworkCandidate, index: Int, focused: Boolean, busy: Boolean, coordinator: ScrapeCoordinator) {
    val fill by animateColorAsState(if (focused) Palette.Label else Color.Transparent, label = "asset-fill")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fill).tap("scrape-asset-$index", !busy) { coordinator.artwork(index) }
        .semantics { selected = focused }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ProviderThumbnail(asset, coordinator.provider)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${mediaTitle(asset.type)} · ${regionTitle(asset.region)}", Typo.Callout, color = if (focused) Palette.Canvas else Palette.Label, lines = 1)
            Text(if (asset.isCaseArt) "Try it on the real object" else "Keep as extra artwork", Typo.Small,
                color = if (focused) Palette.OnLabelSecondary else Palette.Tertiary, lines = 1)
        }
    }
}

@Composable private fun hints(state: ScrapeState, coordinator: ScrapeCoordinator, swapped: Boolean): List<Hint> = buildList {
    add(Hint(Pad.confirm(swapped), "Select", primary = true, tag = "scrape-select") { coordinator.select() })
    when (state.page) {
        ScrapePage.Targets -> add(Hint(Pad.X, if (state.pickedIds.size == state.availableGames.size) "Clear all" else "Select all"))
        ScrapePage.Consoles -> add(Hint(Pad.X, if (state.pickedSystems.size == state.consoles.size) "Clear all" else "Select all"))
        ScrapePage.Metadata -> state.candidate?.metadata?.values?.elementAtOrNull(state.focus)?.takeIf { it.size > 1 }?.let { add(Hint(Pad.X, "Other version")) }
        else -> Unit
    }
    add(Hint(Pad.back(swapped), if (state.busy) "Stop" else "Back", tag = "scrape-back") { coordinator.back() })
}

private fun mediaTitle(type: String) = when (type) {
    "support-texture" -> "Flat label"
    "support-2D" -> "Photo of the media"
    "box-2D" -> "Front cover"
    "box-texture" -> "Whole cover"
    "ss", "screenshot" -> "Screenshot"
    "sstitle" -> "Title screen"
    "wheel" -> "Title logo"
    else -> type
}
private fun regionTitle(region: String) = when (region.lowercase()) {
    "us" -> "United States"
    "eu" -> "Europe"
    "uk" -> "United Kingdom"
    "jp" -> "Japan"
    "wor" -> "Worldwide"
    "" -> "Unspecified region"
    else -> region.uppercase()
}

/** The provider's picture, read off the main thread and scaled down; until then, or when it cannot be read, the frame says so. */
@Composable private fun ProviderThumbnail(asset: ArtworkCandidate, provider: ArtworkProvider, width: androidx.compose.ui.unit.Dp = 96.dp, height: androidx.compose.ui.unit.Dp = 54.dp) {
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
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(width, height).clip(shape).background(Palette.Canvas).border(1.dp, Palette.Separator, shape), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "${asset.type} ${asset.region}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text(if (failed) "No preview" else "Loading", Typo.Small, color = Palette.Tertiary, lines = 1)
    }
}
