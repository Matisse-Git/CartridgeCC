package dev.cartridge.environment

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
import dev.cartridge.library.LibraryAction

enum class ConsolePage { Setup, Settings, Controllers, Game, Notices }

@Composable fun ConsolePanel(page: ConsolePage, choices: List<String>, focus: Int, busy: Boolean,
    action: (Int) -> Unit, notices: String = "") {
    Surface(Modifier.fillMaxSize().testTag("console-panel"), color = Color(0xFF191817)) {
        Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(when(page) { ConsolePage.Setup -> "Welcome to Cartridge"; ConsolePage.Settings -> "Console settings"
                    ConsolePage.Controllers -> "Controller"; ConsolePage.Game -> "Game settings"; ConsolePage.Notices -> "About & licences" }, fontSize = 27.sp)
                Text(when(page) {
                    ConsolePage.Setup -> "Choose your own game folder, browse physical media and play inside Cartridge. Artwork scraping offers a choice of labels and metadata. You can set up your collection now or come back later."
                    ConsolePage.Controllers -> "Menu and gameplay buttons are configured separately. Android HOME and power controls remain system controls. A neutral stick releases navigation immediately."
                    ConsolePage.Game -> "These settings apply to the embedded GBA, GB and GBC core. Frame skipping trades smoothness for less rendering work."
                    ConsolePage.Settings -> "Choose how Cartridge feels on this device. HOME mode is optional; Android asks you before changing the default home app."
                    ConsolePage.Notices -> notices
                }, modifier = Modifier.padding(top = 20.dp), fontSize = 15.sp, color = Color(0xFFD1C9BB))
                Text("↑ ↓ Choose · A Select · B Back", modifier = Modifier.padding(top = 24.dp), fontSize = 12.sp)
            }
            val scroll = rememberScrollState()
            LaunchedEffect(focus) { scroll.animateScrollTo(if (focus >= 4) scroll.maxValue else 0) }
            Column(Modifier.width(300.dp).verticalScroll(scroll).background(Color(0xFF252320)).padding(8.dp)) {
                choices.forEachIndexed { index, title -> LibraryAction(title, focus == index, "console-action-$index", !busy) { action(index) } }
            }
        }
    }
}
