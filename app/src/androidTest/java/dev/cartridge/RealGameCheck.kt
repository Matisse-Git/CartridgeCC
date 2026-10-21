package dev.cartridge

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.SessionState
import dev.cartridge.emulation.SaveBridge
import dev.cartridge.library.LibraryGame
import dev.cartridge.session.CollectionScreen
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Not part of any suite, and skipped unless it is given a game: starts one game the owner has already imported,
 * in the isolated test library, and records what the engine does with it. Nothing about the game is written
 * here or kept in the repository; the game is named on the command line by the start of its content hash or by
 * its console.
 *
 *   -e class dev.cartridge.RealGameCheck -e game <hash prefix | PS2 | DREAMCAST | GAMECUBE> [-e pick 0]
 *   [-e seconds 30] [-e every 5] [-e keys 12:BUTTON_START,15:BUTTON_A] [-e stacks 20,21] [-e sleep 20]
 *   [-e shader crt/crt-royale.glslp] [-e check 10:crt/crt-royale.glslp] [-e full true] [-e fast 10] [-e sleepWith fast|frozen]
 *   [-e discs <hash prefix>,<hash prefix>] [-e disc 1] [-e change 20:0]
 *
 * `discs` names the game's other discs, which join it in the test library as they would by hand (ADR 0032); `disc`
 * starts it on that disc by its place, and `change` opens the pause menu at that second and puts that disc in the drive.
 *
 * `shader` starts the game with a preset from the installed shader library (ADR 0027); `check` pauses the
 * game at that second, checks a preset in its own GL context as Settings will, and resumes it. `fast` counts the
 * core's frames for three seconds at normal speed, then with L3 + A held for fast-forward, then frozen with L3 + B,
 * and puts it back (ADR 0031); a screenshot of each, badge included, goes beside the pictures.
 *
 * Pictures go to files/diagnostics/<tag>-<second>.png and one line of counters per second to the log. The
 * owner's library, play history and saved sessions are not touched; the game's own memory card is the one it
 * always uses, so nothing is saved from here. A console that cannot start without its BIOS is given the copy
 * the owner left for tests (see [ownerFirmware]) and writes its memory cards in the test library's own folder.
 */
class RealGameCheck {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(scenario: ActivityScenario<DebugCollectionActivity>, timeout: Long = 30_000, check: (DebugCollectionActivity) -> Boolean) {
        ui.waitUntil(timeout) { var ready = false; scenario.onActivity { ready = check(it) }; ready }
    }
    private fun picture(scenario: ActivityScenario<DebugCollectionActivity>, file: File): String {
        val result = CompletableFuture<String>()
        scenario.onActivity { activity ->
            val view = activity.engine.surface.value ?: return@onActivity run { result.complete("no surface") }
            // -e full true keeps every pixel, for looking at what a shader draws; otherwise half size.
            val scale = if (InstrumentationRegistry.getArguments().getString("full") == "true") 1 else 2
            val bitmap = Bitmap.createBitmap(view.width / scale, view.height / scale, Bitmap.Config.ARGB_8888)
            PixelCopy.request(view, bitmap, { status ->
                if (status == PixelCopy.SUCCESS) {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val sample = pixels.filterIndexed { index, _ -> index % 97 == 0 }
                    result.complete("colours=${sample.toSet().size} lit=${sample.count { it and 0xFFFFFF != 0 } * 100 / sample.size}%")
                } else result.complete("copy failed $status")
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return result.get(5, TimeUnit.SECONDS)
    }
    private fun stats(scenario: ActivityScenario<DebugCollectionActivity>, core: String): LongArray {
        val result = CompletableFuture<LongArray>()
        scenario.onActivity { activity ->
            val view = activity.engine.surface.value ?: return@onActivity run { result.complete(LongArray(0)) }
            view.queueEvent { result.complete(try { SaveBridge.stats(core) } catch (failure: Throwable) { LongArray(0) }) }
        }
        return try { result.get(5, TimeUnit.SECONDS) } catch (failure: Exception) { LongArray(0) }
    }

    /** The core's video frames, counted on the game's own thread as the engine counts them: a core may not be found from another. */
    private fun frames(scenario: ActivityScenario<DebugCollectionActivity>, core: String): Long {
        val result = CompletableFuture<Long>()
        scenario.onActivity { activity -> activity.engine.surface.value?.queueEvent { result.complete(try { SaveBridge.frameCount(core) } catch (failure: Throwable) { -1L }) }
            ?: result.complete(-1L) }
        return result.get(5, TimeUnit.SECONDS).also { check(it >= 0) { "The core's frames could not be counted" } }
    }

    @Test fun runsAnImportedGameAndRecordsWhatItShows() {
        val arguments = InstrumentationRegistry.getArguments()
        val wanted = arguments.getString("game").orEmpty()
        assumeTrue("Name a game with -e game", wanted.isNotEmpty())
        val seconds = arguments.getString("seconds")?.toInt() ?: 30
        val every = arguments.getString("every")?.toInt() ?: 5
        val keys = arguments.getString("keys").orEmpty().split(',').filter { it.isNotBlank() }.associate { it.substringBefore(':').toInt() to it.substringAfter(':') }
        // Seconds at which to log where every engine thread is (tag CartridgeStacks), for a game that runs and shows nothing.
        val stacks = arguments.getString("stacks").orEmpty().split(',').filter { it.isNotBlank() }.map { it.toInt() }.toSet()
        // A second at which the device is really put to sleep and woken five seconds later, as its power button does.
        val sleep = arguments.getString("sleep")?.toInt()
        val shader = arguments.getString("shader")
        val checkAt = arguments.getString("check")?.substringBefore(':')?.toInt()
        val checkPreset = arguments.getString("check")?.substringAfter(':')
        // A second at which the quick save combo is held during play, and three seconds later the quick load combo (ADR 0028).
        val quick = arguments.getString("quick")?.toInt()
        val fast = arguments.getString("fast")?.toInt()
        val others = arguments.getString("discs").orEmpty().split(',').filter { it.isNotBlank() }
        val startDisc = arguments.getString("disc")?.toInt()
        val changeAt = arguments.getString("change")?.substringBefore(':')?.toInt()
        val changeTo = arguments.getString("change")?.substringAfter(':')?.toInt()
        val roms = File(context.filesDir, "roms")
        val system = GameSystem.entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        // Games, not this project's own probes: those are a few hundred kilobytes at most. A game named by its hash
        // may also be a cartridge, the one file the importer copied.
        val bundles = roms.listFiles { file -> Regex("[a-f0-9]{64}\\.[a-z0-9]+").matches(file.name) &&
            (if (system != null) file.isDirectory && file.name.endsWith(".${system.extension}") &&
                file.walkTopDown().filter(File::isFile).sumOf(File::length) > 8L * 1024 * 1024 else file.name.startsWith(wanted)) }
            .orEmpty().sortedBy { it.name }
        val bundle = bundles.getOrNull(arguments.getString("pick")?.toInt() ?: 0)
        assumeTrue("No imported game matches $wanted", bundle != null)
        val id = bundle!!.name.substringBefore('.')
        val console = GameSystem.entries.first { it.bundle == bundle.isDirectory && it.extension == bundle.name.substringAfter('.') }
        val tag = "${console.name.lowercase()}-${id.take(6)}"
        if (console.core.firmwareRequired) assumeTrue("${console.title} needs its owner's BIOS in files/m1-owner-firmware", ownerFirmware(context, console.core))
        val shots = File(context.filesDir, "diagnostics").apply { mkdirs() }
        shots.listFiles { file -> file.name.startsWith(tag) }?.forEach { it.delete() }
        val core = File(context.applicationInfo.nativeLibraryDir, console.coreFile).path
        val name = "m1-test-${UUID.randomUUID()}"
        // The quick combos write to the owner's own quick save and list for this game. What is there is set aside first and
        // put back exactly when the check ends, whatever happens in between.
        val saves = File(context.filesDir, "saves")
        val kept = File(shots, "$tag-kept-saves")
        if (quick != null) {
            kept.deleteRecursively(); kept.mkdirs()
            copyKeepingTimes(File(saves, "$id.state"), File(kept, "$id.state")); copyKeepingTimes(File(saves, "$id.slots"), File(kept, "$id.slots"))
        }
        android.util.Log.i(TAG, "$tag: ${console.title}, ${bundles.size} candidate(s), $seconds s")
        // An engine that stops answering can take the main thread with it, and with that every step below. A thread
        // of its own watches for a step that takes too long, says where every engine thread is, and ends the process.
        val beat = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val watch = Thread {
            while (true) {
                try { Thread.sleep(1000) } catch (stopped: InterruptedException) { return@Thread }
                if (System.nanoTime() - beat.get() > 100_000_000_000L) {
                    android.util.Log.e(TAG, "$tag made no progress for 100 s")
                    SaveBridge.dumpThreads(); Thread.sleep(1500)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            ActivityScenario.launch<DebugCollectionActivity>(Intent(context, DebugCollectionActivity::class.java).putExtra("testDatabase", name)
                .apply { if (shader != null) putExtra("testShader", shader) }).use { scenario ->
                await(scenario) { it.coordinator.state.value.loaded && it.coordinator.state.value.rendererReady }
                scenario.onActivity { it.coordinator.addGame { LibraryGame(id, "Imported game", "content://$FIXTURE/none", system = console) } }
                await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.id == id }
                // The game's other discs join it here as the owner would join them by hand; the owner's library is not touched.
                val members = others.map { prefix ->
                    roms.listFiles { file -> file.isDirectory && file.name.startsWith(prefix) && file.name.endsWith(".${console.extension}") }.orEmpty().single().name.substringBefore('.')
                }
                members.forEachIndexed { index, member ->
                    scenario.onActivity { it.coordinator.addGame { LibraryGame(member, "Imported disc ${index + 2}", "content://$FIXTURE/none", system = console) } }
                    await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.id == member }
                }
                if (members.isNotEmpty()) {
                    scenario.onActivity { it.coordinator.reveal(id) }
                    await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.id == id }
                    members.forEach { member ->
                        scenario.onActivity { it.coordinator.addDisc(member) }
                        await(scenario) { !it.coordinator.state.value.busy && it.coordinator.state.value.selectedGame?.discs?.any { disc -> disc.id == member } == true }
                    }
                    scenario.onActivity { android.util.Log.i(TAG, "$tag discs: ${it.coordinator.state.value.selectedGame!!.discs.joinToString { disc -> "${disc.number}=${disc.id.take(6)}" }}") }
                }
                if (startDisc != null) scenario.onActivity { it.coordinator.insert(startDisc) } else ui.tool("insert").performClick()
                try { await(scenario, 45_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running } }
                catch (failure: Throwable) {
                    SaveBridge.dumpThreads()
                    scenario.onActivity { android.util.Log.e(TAG, "$tag did not start: ${it.engine.status.value} ${it.coordinator.state.value.launchFailure}") }
                    throw failure
                }
                scenario.onActivity { android.util.Log.i(TAG, "$tag started: disc ${it.engine.status.value.disc + 1} of ${it.engine.status.value.discs} in the engine") }
                var last = LongArray(0); val began = System.nanoTime()
                for (second in 1..seconds) {
                    Thread.sleep(1000); beat.set(System.nanoTime())
                    keys[second]?.let { key ->
                        val code = KeyEvent.keyCodeFromString("KEYCODE_$key")
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }; Thread.sleep(120)
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
                        android.util.Log.i(TAG, "$tag t=$second pressed $key")
                    }
                    if (second in stacks) { android.util.Log.i(TAG, "$tag t=$second stacks"); SaveBridge.dumpThreads(); Thread.sleep(300) }
                    if (second == checkAt && checkPreset != null) {
                        // What Settings will do when a preset is picked while a game is paused: build it in a context
                        // of its own on another thread. The paused game must carry on as it was.
                        var engine: dev.cartridge.emulation.LibretroEngine? = null
                        scenario.onActivity { engine = it.engine }
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) { engine!!.pause() }
                        val began = System.nanoTime()
                        val outcome = java.util.concurrent.Executors.newSingleThreadExecutor().let { thread ->
                            try { thread.submit<String?> {
                                val library = dev.cartridge.shaders.ShaderLibrary.root(context)
                                dev.cartridge.shaders.ShaderLibrary.check(dev.cartridge.shaders.ShaderLibrary.load(library, File(library, checkPreset)))
                            }.get(60, TimeUnit.SECONDS) } finally { thread.shutdown() }
                        }
                        android.util.Log.i(TAG, "$tag t=$second checked $checkPreset while paused in ${(System.nanoTime() - began) / 1_000_000} ms: ${outcome ?: "works"}")
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) { engine!!.resume() }
                        beat.set(System.nanoTime())
                    }
                    fun combo(code: Int, expected: List<String>): String? {
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)) }; Thread.sleep(40)
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
                        val began = System.nanoTime()
                        var said: String? = null
                        try { await(scenario, 60_000) { said = it.coordinator.state.value.message; !it.coordinator.state.value.busy && expected.any { e -> said?.startsWith(e) == true } } }
                        catch (failure: Throwable) { scenario.onActivity { android.util.Log.e(TAG, "$tag combo said ${it.coordinator.state.value.message} ${it.engine.status.value}") }; throw failure }
                        val took = (System.nanoTime() - began) / 1_000_000
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
                        beat.set(System.nanoTime())
                        var state = SessionState.Idle; scenario.onActivity { state = it.engine.status.value.state }
                        android.util.Log.i(TAG, "$tag t=$second combo answered \"$said\" in $took ms; the game is $state")
                        check(state == SessionState.Running) { "The game stopped after a quick combo: $state" }
                        return said
                    }
                    if (second == quick) {
                        val said = combo(KeyEvent.KEYCODE_BUTTON_Y, listOf("Quick saved", "Can't save states"))
                        if (said == "Quick saved") {
                            val slot = dev.cartridge.storage.StateSlots(saves).list(id, console).first { it.kind == dev.cartridge.storage.SlotKind.Quick }
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true; slot.thumbnail?.let { file -> android.graphics.BitmapFactory.decodeFile(file.path, this) } }
                            slot.thumbnail?.copyTo(File(shots, "$tag-quick-thumbnail.jpg"), overwrite = true)
                            android.util.Log.i(TAG, "$tag quick save: ${slot.size} bytes, thumbnail ${bounds.outWidth}x${bounds.outHeight}")
                        } else check(console.core.snapshots == dev.cartridge.domain.Snapshots.Never) { "${console.title} refused a quick save: $said" }
                    }
                    if (quick != null && second == quick + 3) {
                        val said = combo(KeyEvent.KEYCODE_BUTTON_X, listOf("Quick loaded", "Can't save states"))
                        if (said != "Quick loaded") check(console.core.snapshots == dev.cartridge.domain.Snapshots.Never) { "${console.title} refused a quick load: $said" }
                    }
                    /** L3 with [code], held until [done], as the freeze and fast-forward combos are pressed (ADR 0031). */
                    fun hold(code: Int, done: (DebugCollectionActivity) -> Boolean) {
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)) }; Thread.sleep(40)
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
                        await(scenario, 60_000) { !it.coordinator.state.value.busy && done(it) }
                        scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
                        beat.set(System.nanoTime())
                    }
                    if (second == fast) {
                        fun frames() = frames(scenario, core)
                        fun rate(label: String): Long {
                            val from = frames(); Thread.sleep(3000); beat.set(System.nanoTime())
                            return ((frames() - from) / 3).also { android.util.Log.i(TAG, "$tag $label: $it core frames a second") }
                        }
                        fun screen(name: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { shot ->
                            File(shots, "$tag-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
                        }
                        val normal = rate("normal speed")
                        var speed = 1; var said: String? = null
                        if (console.core.fastForward) {
                            hold(KeyEvent.KEYCODE_BUTTON_A) { it.engine.status.value.speed > 1 }
                            scenario.onActivity { speed = it.engine.status.value.speed }
                            val faster = rate("fast-forward ${speed}x")
                            screen("fast")
                            android.util.Log.i(TAG, "$tag fast-forward ${speed}x ran ${"%.2f".format(faster.toDouble() / normal.coerceAtLeast(1))} times as many frames")
                            check(faster > normal) { "Fast-forward did not run the game faster: $normal then $faster frames a second" }
                        } else {
                            hold(KeyEvent.KEYCODE_BUTTON_A) { it.coordinator.state.value.message?.startsWith("Fast-forward isn't available") == true }
                            scenario.onActivity { speed = it.engine.status.value.speed; said = it.coordinator.state.value.message }
                            check(speed == 1) { "${console.title} was run faster" }
                            android.util.Log.i(TAG, "$tag fast-forward refused: $said")
                        }
                        // A frozen game's thread takes no work until it goes on: count just before the freeze and just after it.
                        val before = frames()
                        hold(KeyEvent.KEYCODE_BUTTON_B) { it.engine.status.value.frozen }
                        var menu: Any? = null; scenario.onActivity { menu = it.shell.page() }
                        check(menu == null) { "A frozen game showed a menu" }
                        Thread.sleep(3000); beat.set(System.nanoTime())
                        screen("frozen")
                        hold(KeyEvent.KEYCODE_BUTTON_B) { it.engine.status.value.state == SessionState.Running && it.engine.status.value.speed == speed }
                        val frozen = frames() - before
                        android.util.Log.i(TAG, "$tag frozen for 3 s: $frozen core frames from just before the freeze to just after it")
                        check(frozen < normal) { "A frozen game ran $frozen frames in 3 s" }
                        if (speed > 1) hold(KeyEvent.KEYCODE_BUTTON_A) { it.engine.status.value.speed == 1 }
                        val again = rate("normal speed again")
                        check(again > 0) { "The game did not go on after the freeze" }
                        last = stats(scenario, core)
                    }
                    if (second == changeAt && changeTo != null) {
                        // Change Disc from the pause menu, as the player does it (ADR 0032); the game must go on drawing.
                        val from = frames(scenario, core)
                        ui.onNodeWithTag("pause").performClick(); await(scenario, 60_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                        ui.onNodeWithTag("menu-6").performClick(); ui.waitUntil(5_000) { ui.shown("disc-$changeTo") }
                        ui.onNodeWithTag("disc-$changeTo").performClick()
                        await(scenario, 30_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running && it.engine.status.value.disc == changeTo }
                        val badge = ui.shown("disc-badge")
                        Thread.sleep(1500); beat.set(System.nanoTime())
                        val to = frames(scenario, core)
                        android.util.Log.i(TAG, "$tag t=$second changed to disc ${changeTo + 1}: badge=$badge, ${to - from} core frames since, ${frames(scenario, core) - to} in the next moment")
                        check(to > from) { "The game did not go on after the disc change" }
                        last = stats(scenario, core)
                    }
                    if (second == sleep) {
                        val before = stats(scenario, core)
                        // -e sleepWith fast|frozen: the game is put down fast-forwarding or frozen, and must come back at normal speed.
                        when (arguments.getString("sleepWith")) {
                            "fast" -> if (console.core.fastForward) hold(KeyEvent.KEYCODE_BUTTON_A) { it.engine.status.value.speed > 1 }
                            "frozen" -> hold(KeyEvent.KEYCODE_BUTTON_B) { it.engine.status.value.frozen }
                        }
                        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
                        shell.executeShellCommand("input keyevent KEYCODE_SLEEP").close(); Thread.sleep(5000); beat.set(System.nanoTime())
                        shell.executeShellCommand("input keyevent KEYCODE_WAKEUP").close(); Thread.sleep(2500); beat.set(System.nanoTime())
                        var after = SessionState.Idle; var kept = false
                        scenario.onActivity { after = it.engine.status.value.state; kept = it.engine.surface.value != null }
                        android.util.Log.i(TAG, "$tag t=$second slept for 5 s and woke: $after, game still loaded=$kept, ${console.core.snapshots}")
                        if (kept) scenario.onActivity { check(!it.engine.status.value.frozen && it.engine.status.value.speed == 1) { "The game came back frozen or fast: ${it.engine.status.value}" } }
                        if (console.core.snapshots == dev.cartridge.domain.Snapshots.Never) {
                            // Nothing to restore from: the same loaded game has to be there, paused, and carry on when asked.
                            check(after == SessionState.Paused && kept) { "The game did not survive sleep: $after" }
                            ui.onNodeWithTag("menu-0").performClick()
                            await(scenario, 30_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                            Thread.sleep(1500); beat.set(System.nanoTime())
                            val later = stats(scenario, core)
                            check(before.isNotEmpty() && later.isNotEmpty() && later[1] > before[1] + 30) { "The game did not carry on after sleep: ${before.getOrNull(1)} then ${later.getOrNull(1)} core runs" }
                            android.util.Log.i(TAG, "$tag carried on after sleep: ${before[1]} core runs before, ${later[1]} after")
                            last = later
                        } else {
                            // A console with states is written to a checkpoint when the app is left, and comes back from it
                            // paused, behind the same menu.
                            try { await(scenario, 45_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused } }
                            catch (failure: Throwable) {
                                SaveBridge.dumpThreads()
                                scenario.onActivity { android.util.Log.e(TAG, "$tag was not restored after sleep: ${it.engine.status.value} busy=${it.coordinator.state.value.busy} " +
                                    "screen=${it.coordinator.state.value.screen} message=${it.coordinator.state.value.message}") }
                                throw failure
                            }
                            ui.onNodeWithTag("menu-0").performClick()
                            await(scenario, 30_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Running }
                            // Frames rather than the shim's counters, which only the engines that draw for themselves have.
                            val from = frames(scenario, core)
                            Thread.sleep(1500); beat.set(System.nanoTime())
                            val to = frames(scenario, core)
                            check(to > from + 30) { "The restored game is not running: $from then $to frames" }
                            last = stats(scenario, core)
                            scenario.onActivity { check(it.engine.status.value.speed == 1) { "The restored game is fast: ${it.engine.status.value}" } }
                            android.util.Log.i(TAG, "$tag was restored from its checkpoint after sleep and carried on")
                        }
                    }
                    scenario.onActivity { activity -> activity.engine.status.value.notice?.let { android.util.Log.i(TAG, "$tag t=$second notice: $it") } }
                    val now = stats(scenario, core)
                    var gone = false
                    scenario.onActivity { gone = it.engine.surface.value == null }
                    if (gone) {
                        // Not the engine: Android stopped the Activity (a system dialog, the screen) and the session went with it.
                        scenario.onActivity { android.util.Log.e(TAG, "$tag t=$second the Activity was interrupted and the game's surface is gone: ${it.engine.status.value.state}") }
                        throw AssertionError("The Activity was interrupted at second $second; run the check again")
                    }
                    if (now.isEmpty() && last.isNotEmpty()) {
                        // The thread the engine runs on no longer answers. Say where every engine thread is, and end
                        // the process: a session that cannot be released would hold this run for ever.
                        android.util.Log.e(TAG, "$tag t=$second the engine stopped answering")
                        SaveBridge.dumpThreads(); Thread.sleep(1500)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    val delta = if (last.size == now.size) LongArray(now.size) { now[it] - last[it] } else now
                    last = now
                    var state = SessionState.Idle
                    scenario.onActivity { state = it.engine.status.value.state }
                    val seen = (if (second % every == 0 || second == 1) picture(scenario, File(shots, "$tag-${second.toString().padStart(3, '0')}.png")) else "") +
                        (if (members.isNotEmpty() || console.core.discs) { var drive = ""; scenario.onActivity { drive = " disc=${it.engine.status.value.disc + 1}/${it.engine.status.value.discs}" }; drive } else "")
                    if (now.size >= 12) android.util.Log.i(TAG, "$tag t=$second $state runs=${delta[0]} core=${delta[1]} drawn=${delta[2]} repeated=${delta[3]} silent=${delta[4]} " +
                        "audioIn=${delta[5]} calls=${delta[6]} elsewhere=${delta[7]} largest=${now[8]} audioOut=${delta[9]} dropped=${delta.getOrElse(12) { 0 }} bound=${delta[10]} size=${now[11] shr 16}x${now[11] and 0xFFFF} $seen")
                    else android.util.Log.i(TAG, "$tag t=$second $state no counters $seen")
                    if (state != SessionState.Running) break
                }
                android.util.Log.i(TAG, "$tag ran ${(System.nanoTime() - began) / 1_000_000_000} s"); beat.set(System.nanoTime())
                var running = false
                scenario.onActivity { running = it.engine.status.value.state == SessionState.Running }
                if (running) {
                    ui.onNodeWithTag("pause").performClick(); await(scenario, 60_000) { !it.coordinator.state.value.busy && it.engine.status.value.state == SessionState.Paused }
                    ui.onNodeWithTag("menu-3").performClick()
                    await(scenario, 60_000) { !it.coordinator.state.value.busy && it.coordinator.state.value.screen == CollectionScreen.Library && it.coordinator.state.value.rendererReady }
                }
            }
        } finally {
            watch.interrupt()
            if (quick != null) {
                File(saves, "$id.state").delete(); File(saves, "$id.slots").deleteRecursively()
                copyKeepingTimes(File(kept, "$id.state"), File(saves, "$id.state")); copyKeepingTimes(File(kept, "$id.slots"), File(saves, "$id.slots"))
                kept.deleteRecursively()
                android.util.Log.i(TAG, "$tag the game's own quick save and list were put back")
            }
            DebugCollectionActivity.closeDatabases(); context.deleteDatabase("$name.db")
            for (folder in listOf("session", "scrape")) File(context.filesDir, "$name-$folder").deleteRecursively()
        }
    }
    /** A file or folder copied with each file's time, which is when a save says it was made. Nothing happens if [from] is not there. */
    private fun copyKeepingTimes(from: File, to: File) {
        if (!from.exists()) return
        from.walkTopDown().forEach { source ->
            val target = File(to, source.relativeTo(from).path)
            if (source.isDirectory) target.mkdirs()
            else java.nio.file.Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private companion object { const val TAG = "CartridgeCheck" }
}
