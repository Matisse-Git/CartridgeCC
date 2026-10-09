package dev.cartridge

import android.content.Intent
import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import dev.cartridge.domain.Selection
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.LibretroEngine
import dev.cartridge.input.ControllerRouter
import dev.cartridge.input.InputMode
import dev.cartridge.rendering.CartridgeSurface
import dev.cartridge.storage.ImportedGame
import dev.cartridge.storage.RomImporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import java.io.File

private enum class Screen { Library, Lifting, Handoff, Emulator }
private data class ShellState(
    val screen: Screen = Screen.Library,
    val selection: Selection = Selection(),
    val titles: List<String> = List(3) { "Cartridge study ${it + 1}" },
    val message: String? = null,
    val rendererReady: Boolean = false,
    val busy: Boolean = false,
    val menuIndex: Int = 0,
    val reducedMotion: Boolean = false,
    val alternateShortcut: Boolean = false,
)

class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    private val shell = MutableStateFlow(ShellState())
    private lateinit var engine: LibretroEngine
    private lateinit var input: ControllerRouter
    private val games = arrayOfNulls<ImportedGame>(3)
    private var libraryRelease: CompletableDeferred<Unit>? = null
    private var emulatorRelease: CompletableDeferred<Unit>? = null
    private var launchJob: Job? = null
    private var operationJob: Job? = null
    private var cartridgeSurface: CartridgeSurface? = null
    private var emulatorHost: EmulatorHost? = null
    private val menuItems = listOf("Resume", "Save state", "Load state", "Eject to collection")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        engine = LibretroEngine(this, lifecycleScope)
        input = ControllerRouter(
            navigate = { delta ->
                val state = shell.value
                if (!state.busy) {
                    if (engine.status.value.state == SessionState.Paused) {
                        shell.value = state.copy(menuIndex = (state.menuIndex + delta).coerceIn(0, menuItems.lastIndex))
                    } else shell.value = state.copy(selection = state.selection.move(delta))
                }
            }, select = { if (engine.status.value.state == SessionState.Paused) menuAction(shell.value.menuIndex) else insert() },
            back = { if (engine.status.value.state == SessionState.Paused) menuAction(0) },
            pause = { openPauseMenu() }, gameKey = engine::key, gameMotion = engine::motion,
        )
        val preferences = getPreferences(MODE_PRIVATE)
        shell.value = shell.value.copy(reducedMotion = preferences.getBoolean("reducedMotion", false),
            alternateShortcut = preferences.getBoolean("alternateShortcut", false))
        updateShortcut()
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        lifecycleScope.launch {
            engine.status.collect { status ->
                updateInputMode()
                if (status.state == SessionState.Error && !shell.value.busy) returnToLibrary(status.error)
            }
        }
        setContent { CartridgeApp() }
    }

    // The public Activity event hook inherits AndroidX core's internal annotation.
    // Intercept here so Compose and GLRetroView cannot both consume gameplay input.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (::input.isInitialized && input.key(event)) true else super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (::input.isInitialized && input.motion(event)) true else super.dispatchGenericMotionEvent(event)

    private fun updateInputMode() {
        input.mode = when {
            shell.value.busy -> InputMode.Locked
            engine.status.value.state == SessionState.Running -> InputMode.Gameplay
            engine.status.value.state == SessionState.Paused -> InputMode.Menu
            shell.value.screen == Screen.Library -> InputMode.Library
            else -> InputMode.Locked
        }
    }
    private fun updateShortcut() {
        input.pauseShortcut = if (shell.value.alternateShortcut)
            setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)
        else setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
    }
    private fun insert() {
        val state = shell.value
        if (state.busy || state.screen != Screen.Library || !state.rendererReady) return
        val game = games[state.selection.index]
        if (game == null) { shell.value = state.copy(message = "Choose a .gba file for this cartridge first."); return }
        shell.value = state.copy(screen = Screen.Lifting, busy = true, message = null)
        updateInputMode()
        launchJob = lifecycleScope.launch {
            try {
                if (!state.reducedMotion) delay(320)
                libraryRelease = CompletableDeferred()
                shell.value = shell.value.copy(screen = Screen.Handoff, rendererReady = false)
                cartridgeSurface?.close()
                cartridgeSurface = null
                withTimeout(5_000) { libraryRelease!!.await() }
                shell.value = shell.value.copy(screen = Screen.Emulator)
                engine.prepare(game.request)
                shell.value = shell.value.copy(busy = false)
                updateInputMode()
                if (engine.status.value.state == SessionState.Error) returnToLibrary(engine.status.value.error)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                returnToLibrary(failure.message)
            }
        }
    }

    private fun openPauseMenu() {
        if (shell.value.busy) return
        operationJob = lifecycleScope.launch {
            engine.pause()
            shell.value = shell.value.copy(menuIndex = 0)
        }
    }
    private fun menuAction(index: Int) {
        if (shell.value.busy) return
        shell.value = shell.value.copy(busy = true, message = null)
        updateInputMode()
        operationJob = lifecycleScope.launch {
            try {
                when (index) {
                    0 -> engine.resume()
                    1 -> { engine.saveState(); shell.value = shell.value.copy(message = "State saved") }
                    2 -> { engine.loadState(); shell.value = shell.value.copy(message = "State loaded") }
                    3 -> { returnToLibrary(); return@launch }
                }
            } catch (failure: Exception) { shell.value = shell.value.copy(message = failure.message) }
            finally { shell.value = shell.value.copy(busy = false); updateInputMode() }
        }
    }

    /** The emulator's child SurfaceView must detach before the next Filament Engine exists. */
    private suspend fun returnToLibrary(message: String? = null) {
        shell.value = shell.value.copy(busy = true)
        updateInputMode()
        val hadSurface = engine.surface.value != null
        emulatorRelease = CompletableDeferred()
        engine.stop()
        emulatorHost?.release()
        emulatorHost = null
        val error = engine.status.value.error ?: message
        if (hadSurface) withTimeout(5_000) { emulatorRelease!!.await() }
        if (engine.status.value.state == SessionState.Error) engine.stop()
        shell.value = shell.value.copy(screen = Screen.Library, busy = false, message = error, rendererReady = false)
        updateInputMode()
    }

    private fun importRom(uri: Uri) {
        if (shell.value.busy) return
        val selected = shell.value.selection.index
        shell.value = shell.value.copy(busy = true, message = "Reading game…")
        updateInputMode()
        lifecycleScope.launch {
            try {
                // Documents remain owned by the user; this M0 working copy is private and removable.
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                games[selected] = withContext(Dispatchers.IO) {
                    RomImporter(contentResolver, File(filesDir, "roms")).import(uri)
                }
                val titles = shell.value.titles.toMutableList()
                titles[selected] = games[selected]!!.title
                shell.value = shell.value.copy(titles = titles, message = null)
            } catch (failure: Exception) { shell.value = shell.value.copy(message = failure.message) }
            finally { shell.value = shell.value.copy(busy = false); updateInputMode() }
        }
    }

    @Composable private fun CartridgeApp() {
        val state by shell.collectAsStateWithLifecycle()
        val status by engine.status.collectAsStateWithLifecycle()
        val emulator by engine.surface.collectAsStateWithLifecycle()
        var glb by remember { mutableStateOf<ByteArray?>(null) }
        LaunchedEffect(Unit) {
            glb = withContext(Dispatchers.IO) { assets.open("models/gba_cartridge.glb").use { it.readBytes() } }
        }
        // Polling handles held axes and D-pad keys without relying on vendor event repeat rates.
        LaunchedEffect(Unit) {
            while (isActive) {
                withFrameNanos { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) input.tick(SystemClock.uptimeMillis()) }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importRom) }
        val lift by animateFloatAsState(if (state.screen == Screen.Lifting && !state.reducedMotion) 1f else 0f,
            label = "Cartridge lift")
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD8C7A8), background = Color(0xFF191817))) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (state.screen == Screen.Library || state.screen == Screen.Lifting) {
                    glb?.let { bytes -> LibraryViewport(bytes, state.selection.index, lift) }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.titles[state.selection.index], fontSize = 23.sp, color = Color(0xFFE9E2D6))
                        Text("← → Browse   ·   A Insert   ·   ${state.selection.index + 1} / 3", fontSize = 12.sp,
                            color = Color(0xFF9C968B), modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                        TextButton(enabled = !state.busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose .gba") }
                        TextButton(enabled = !state.busy && state.rendererReady, onClick = ::insert) { Text("Insert") }
                    }
                    Row(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                        TextButton(enabled = !state.busy, onClick = {
                            shell.value = state.copy(reducedMotion = !state.reducedMotion)
                            getPreferences(MODE_PRIVATE).edit { putBoolean("reducedMotion", !state.reducedMotion) }
                        }) { Text(if (state.reducedMotion) "Motion off" else "Motion on") }
                        TextButton(enabled = !state.busy, onClick = {
                            shell.value = state.copy(alternateShortcut = !state.alternateShortcut)
                            getPreferences(MODE_PRIVATE).edit { putBoolean("alternateShortcut", !state.alternateShortcut) }
                            updateShortcut()
                        }) { Text(if (state.alternateShortcut) "Menu: Start + Select" else "Menu: L3 + R3") }
                    }
                    Row(Modifier.align(Alignment.CenterStart).padding(12.dp)) {
                        TextButton(enabled = !state.busy, onClick = { shell.value = state.copy(selection = state.selection.move(-1)) }) { Text("‹") }
                    }
                    Row(Modifier.align(Alignment.CenterEnd).padding(12.dp)) {
                        TextButton(enabled = !state.busy, onClick = { shell.value = state.copy(selection = state.selection.move(1)) }) { Text("›") }
                    }
                } else if (state.screen == Screen.Emulator) {
                    emulator?.let { view ->
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                            EmulatorHost(view) { emulatorRelease?.complete(Unit) }.also { emulatorHost = it }
                        }, onRelease = { it.release() })
                    }
                    if (status.state == SessionState.Running) {
                        TextButton(onClick = ::openPauseMenu, modifier = Modifier.align(Alignment.TopEnd)) { Text("Menu") }
                    }
                    if (status.state == SessionState.Paused) {
                        Box(Modifier.fillMaxSize().background(Color(0xCE191817)), contentAlignment = Alignment.Center) {
                            Column(Modifier.width(280.dp)) {
                                Text("Paused", fontSize = 25.sp, color = Color(0xFFE9E2D6), modifier = Modifier.padding(12.dp))
                                menuItems.forEachIndexed { index, title ->
                                    TextButton(enabled = !state.busy, onClick = { menuAction(index) },
                                        modifier = Modifier.fillMaxWidth().background(
                                            if (index == state.menuIndex) Color(0xFF34312D) else Color.Transparent)) {
                                        Text(title, color = Color(0xFFE9E2D6))
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.busy && state.screen != Screen.Lifting) {
                    Text(if (status.state == SessionState.Preparing) "Preparing embedded core…" else "Working…",
                        color = Color(0xFFE9E2D6), modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp))
                }
                state.message?.let {
                    Text(it, color = Color(0xFFE2CFAE), modifier = Modifier.align(Alignment.BottomStart)
                        .background(Color(0xEE191817)).padding(16.dp), fontSize = 13.sp)
                }
            }
        }
    }

    @Composable private fun LibraryViewport(bytes: ByteArray, selected: Int, lift: Float) {
        var surface by remember { mutableStateOf<CartridgeSurface?>(null) }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            CartridgeSurface(it, bytes, selected, onReady = { shell.value = shell.value.copy(rendererReady = true) },
                onReleased = { libraryRelease?.complete(Unit) }).also { surface = it; cartridgeSurface = it }
        }, update = { it.selection = selected; it.lift = lift }, onRelease = { it.close() })
        DisposableEffect(surface) {
            val renderer = surface
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) { renderer?.resumeRendering() }
                override fun onPause(owner: LifecycleOwner) { renderer?.pauseRendering() }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); renderer?.pauseRendering() }
        }
    }

    override fun onPause() {
        input.reset()
        // M0 deliberately ends a session on backgrounding instead of relying on EGL preservation.
        // Resume/recreate a full session with an automatic state snapshot is an M1 hardening task.
        if (shell.value.screen != Screen.Library) {
            launchJob?.cancel()
            lifecycleScope.launch {
                operationJob?.join()
                returnToLibrary("Session ended safely when the app left the foreground.")
            }
        }
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::input.isInitialized) { input.reset(); if (::engine.isInitialized) openPauseMenu() }
    }
    override fun onDestroy() {
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        engine.releaseOnDestroy()
        emulatorHost?.release()
        cartridgeSurface?.close()
        super.onDestroy()
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = Unit
    override fun onInputDeviceRemoved(deviceId: Int) { input.reset(); openPauseMenu() }
}

@SuppressLint("ViewConstructor") // Created by Compose, never inflated from XML.
private class EmulatorHost(view: GLRetroView, private val onReleased: () -> Unit) : FrameLayout(view.context) {
    private var released = false
    init { addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    fun release() {
        if (released) return
        released = true
        removeAllViews() // GLSurfaceView waits for its renderer thread on detach.
        onReleased()
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); release() }
}
