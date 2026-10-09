package dev.cartridge

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/** Exercise the production touch shelf explicitly, including after Activity recreation. */
internal fun ComposeTestRule.tool(tag: String): SemanticsNodeInteraction {
    if (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) onNodeWithTag("toggle-tools").performClick()
    return onNodeWithTag(tag)
}
