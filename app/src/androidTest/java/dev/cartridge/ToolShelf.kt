package dev.cartridge

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset

internal fun ComposeTestRule.shown(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

/** Horizontal centre of the focused object's label, as a fraction of the surface: it steps left while a sheet describes it. */
internal fun MainActivity.labelCentre(): Float =
    if (coordinator.state.value.screen == dev.cartridge.session.CollectionScreen.LabelPreview) STAGED_LABEL_CENTRE else .5f
internal const val STAGED_LABEL_CENTRE = .25f

/** Return to the library from any overlay with the same on-screen controls a touch player has. */
internal fun ComposeTestRule.home() {
    var dismissed = false
    repeat(8) {
        waitForIdle()
        when {
            shown("system-menu") -> {
                // Dismissing an import result moves focus to the first new game; let that short write finish
                // so the next tap is not swallowed by the input lock a player would never notice.
                if (dismissed) { Thread.sleep(300); waitForIdle() }
                return
            }
            shown("import-done") -> onNodeWithTag("import-done").performClick()
            shown("failure-close") -> onNodeWithTag("failure-close").performClick()
            shown("back") -> onAllNodesWithTag("back").onLast().performClick()
            // The middle of a scrim lies under its dialog; a player taps beside the card.
            shown("scrim") -> onNodeWithTag("scrim").performTouchInput { click(Offset(6f, 6f)) }
            shown("scrape-back") -> onNodeWithTag("scrape-back").performClick()
            else -> return
        }
        dismissed = true
    }
    waitForIdle()
}

/**
 * Reach a control through the production interface. The names are the jobs the suites have always asked
 * for; each maps to the place that job now lives. Returns the final control, ready to click.
 */
internal fun ComposeTestRule.tool(tag: String): SemanticsNodeInteraction {
    fun through(vararg path: String): SemanticsNodeInteraction {
        home()
        // A row can depend on something still loading, as Refresh Library does on the folder list just after
        // the console opens. A player presses it when it appears; so does this.
        path.dropLast(1).forEach { step ->
            waitUntil(5_000) { shown(step) }
            onNodeWithTag(step).also { node -> runCatching { node.performScrollTo() } }.performClick()
            waitForIdle()
        }
        waitUntil(5_000) { shown(path.last()) }
        return onNodeWithTag(path.last()).also { node -> runCatching { node.performScrollTo() } }
    }
    return when (tag) {
        "choose-game" -> through("system-menu", "system-settings", "category-library", "settings-add-file")
        "choose-folder" -> through("system-menu", "system-add")
        "rescan" -> through("system-menu", "system-refresh")
        "scrape" -> through("system-menu", "system-artwork")
        "search" -> through("system-menu", "system-search")
        "console-settings" -> through("system-menu", "system-settings")
        "resume-session" -> through("system-menu", "system-resume")
        "undo-hide" -> through("system-menu", "system-undo-hide")
        "choose-label" -> through("details", "details-manage", "manage-image")
        "hide" -> through("details", "details-manage", "manage-hide")
        "motion-setting" -> through("system-menu", "system-settings", "category-appearance", "motion-setting")
        "layout-setting" -> through("system-menu", "system-settings", "category-appearance", "layout-setting")
        "shortcut-setting" -> through("system-menu", "system-settings", "category-controls", "shortcut-setting")
        "transition-setting" -> through("system-menu", "system-settings", "category-appearance", "settings-transition")
        "framerate-setting" -> through("system-menu", "system-settings", "category-advanced", "settings-framerate")
        else -> through(tag)
    }
}
