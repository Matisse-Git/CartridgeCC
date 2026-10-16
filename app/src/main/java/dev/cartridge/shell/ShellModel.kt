package dev.cartridge.shell

import dev.cartridge.domain.GameSystem
import dev.cartridge.storage.Discovery
import dev.cartridge.storage.ScanResult

enum class RowKind { Action, Link, Toggle, Choice, Info, Danger, Header }

/**
 * One line of any menu, sheet or settings page. Rows are addressed by [id], never by position, so adding a
 * row cannot change what another row does. [adjust] receives -1/+1 from left/right.
 */
class MenuRow(
    val id: String,
    val title: String,
    val kind: RowKind = RowKind.Action,
    val value: String? = null,
    val note: String? = null,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val tag: String = id,
    val warn: Boolean = false,
    val adjust: ((Int) -> Unit)? = null,
    val action: (() -> Unit)? = null,
) {
    val focusable get() = kind != RowKind.Header && (kind != RowKind.Info || action != null)
}

enum class PageStyle {
    /** A panel at the trailing edge; the stage stays visible and keeps rendering. */
    Sheet,
    /** A centred card for a result or a decision. */
    Dialog,
    /** An opaque full screen. The 3D stage is paused behind it. */
    Page,
    /** Categories at the leading edge, that category's rows beside them. */
    Settings,
    Keyboard,
    Reader,
    /** Game actions over the frozen frame. */
    Pause,
    /** The focused game's facts beside its own 3D object. */
    Details,
    /** Installed Android apps as a grid of their own icons. Its state is `apps/AppLibrary`, not rows. */
    Apps,
}

class Page(val key: String, val title: String, val rows: List<MenuRow>, val style: PageStyle,
    val subtitle: String? = null, val body: String? = null)

enum class SettingsCategory(val title: String) {
    Library("Library"), Appearance("Appearance"), Gameplay("Gameplay"), Controls("Controls"), System("System"), Advanced("Advanced")
}

class TextSpec(val title: String, val hint: String, val initial: String, val limit: Int, val tag: String,
    /** Capitalise the first letter of each word, as a title wants. */
    val titleCase: Boolean = false, val done: (String) -> Unit)

/** Where the player is. Sheets stack over the stage; opaque destinations replace it until dismissed. */
sealed class Destination(val key: String, val opaque: Boolean = false) {
    data object SystemMenu : Destination("menu")
    data object Settings : Destination("settings", true)
    data class Folder(val uri: String) : Destination("folder")
    data object Hidden : Destination("hidden", true)
    data object About : Destination("about", true)
    data class Notice(val name: String) : Destination("notice", true)
    data object BackupReview : Destination("backup", true)
    data object Details : Destination("details")
    data object Manage : Destination("manage")
    data object Options : Destination("options")
    data object Controls : Destination("controls", true)
    data object EditDetails : Destination("edit", true)
    class TextEntry(val spec: TextSpec) : Destination("text", true)
    data object Import : Destination("import")
    data object LaunchFailure : Destination("failure")
    /** A console that cannot start until its owner adds a file says so before a game is taken off the shelf. */
    data class Setup(val core: dev.cartridge.domain.Core) : Destination("setup")
    /** The library's sibling, one press of Back away from its root and one press back to it. */
    data object Apps : Destination("apps", true)
}

/** An import from the folder picker to its result. Every phase has something to read and one way out. */
sealed interface ImportFlow {
    val firstRun: Boolean
    data class Looking(val folder: String?, override val firstRun: Boolean) : ImportFlow
    data class Adding(val discovery: Discovery, val handled: Int, val current: String, override val firstRun: Boolean) : ImportFlow
    /** [setup] lists the cores whose newly added games still wait for a file only the owner can supply. */
    data class Done(val result: ScanResult, val folders: Int, val setup: List<dev.cartridge.domain.Core>, override val firstRun: Boolean, val cancelled: Boolean = false) : ImportFlow
    data class Failed(val reason: String, override val firstRun: Boolean) : ImportFlow
}

internal fun plural(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"

internal fun consoleList(systems: Map<GameSystem, Int>): String = systems.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { "${it.key.title} ${it.value}" }

/** "12 games added to PlayStation." or "18 games added across 2 consoles." Counts come from what was really listed. */
internal fun importHeadline(result: ScanResult, cancelled: Boolean = false): String {
    val added = result.addedSystems.filterValues { it > 0 }
    return when {
        added.isEmpty() && result.duplicates > 0 -> "Your library is up to date"
        added.isEmpty() && cancelled -> "Nothing was added"
        added.isEmpty() -> "No games found here"
        added.size == 1 -> "${plural(result.added, "game")} added to ${added.keys.single().title}"
        else -> "${plural(result.added, "game")} added across ${added.size} consoles"
    }
}

/** Plain reasons a folder gave fewer games than expected, most useful first. */
internal fun importNotes(result: ScanResult): List<String> = buildList {
    if (result.failed > 0) add("${plural(result.failed, "file")} could not be added" + result.errors.firstOrNull()?.let { " — $it" }.orEmpty())
    if (result.otherConsoles.isNotEmpty()) add("${plural(result.otherConsoles.values.sum(), "file")} for consoles Cartridge can't play yet: " +
        result.otherConsoles.entries.sortedByDescending { it.value }.take(4).joinToString { it.key })
    if (result.archives > 0) add("${plural(result.archives, "compressed file")} skipped. Extract .zip and .7z files first.")
    if (result.playlists > 0) add("Multi-disc playlists (.m3u) aren't supported yet. Each disc was added on its own.")
    if (!result.complete && result.failed == 0) add("Part of this folder could not be read. Refresh the library to try again.")
}
