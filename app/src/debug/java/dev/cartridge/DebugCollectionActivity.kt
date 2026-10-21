package dev.cartridge

import androidx.core.content.edit
import androidx.room.Room
import dev.cartridge.library.CollectionRepository
import dev.cartridge.library.LibraryDatabase
import dev.cartridge.library.RoomCollectionRepository
import dev.cartridge.storage.LabelStore
import dev.cartridge.scraping.ArtworkProvider
import dev.cartridge.scraping.FileScrapeJournal
import android.net.Uri
import java.io.File

/** Runs the production UI/engine/renderer against a separate test DB, never the user's collection. */
class DebugCollectionActivity : MainActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Instrumentation injects input without user activity; production lets an idle library sleep.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun createSessionStore() = dev.cartridge.storage.SessionStore(File(filesDir, "${intent.getStringExtra("testDatabase")}-session"))
    // Original diagnostics are written against the built-in BIOS; the user's firmware stays out of them.
    override fun systemDirectory() = File(filesDir, "m1-test-system")
    // A device test may name a preset inside the shader library for every game it starts, over the settings.
    override fun sessionShader(game: dev.cartridge.emulation.GameRequest) =
        intent.getStringExtra("testShader")?.let { File(shaderBase(), "library/$it") } ?: super.sessionShader(game)
    // ...and may give the console a shader library of its own, so it neither reads nor replaces the installed one.
    override fun shaderBase() = intent.getStringExtra("testShaderBase")?.let { File(filesDir, it) } ?: super.shaderBase()
    override fun createRepository(): CollectionRepository {
        val name = checkNotNull(intent.getStringExtra("testDatabase"))
        require(name.matches(Regex("m1-test-[a-f0-9-]+")))
        val database = synchronized(databases) {
            databases.getOrPut(name) { Room.databaseBuilder(applicationContext, LibraryDatabase::class.java, "$name.db")
                .addMigrations(*LibraryDatabase.MIGRATIONS).build() }
        }
        return RoomCollectionRepository(this, database)
    }
    override fun createLabelStore() = LabelStore(File(filesDir, "m1-test-labels"))
    override fun createArtworkProvider() = testProvider ?: super.createArtworkProvider()
    // A test's own login and waiting list; the user's ScreenScraper login is never read by a test that does not ask for it.
    override fun createArtworkSettings() = dev.cartridge.scraping.ArtworkSettings(this, intent.getStringExtra("artworkSettings") ?: "${intent.getStringExtra("testDatabase")}-artwork")
    override fun createPendingScrapes() = dev.cartridge.scraping.SharedPendingScrapes(this, "${intent.getStringExtra("testDatabase")}-auto-scrape")
    override fun automaticScraping() = intent.getBooleanExtra("automaticScraping", false)
    // The tour draws invented apps, so no screenshot shows what the user has installed. Tests of Android itself leave this unset.
    override fun createAppSource() = testApps ?: super.createAppSource()
    override fun createScrapeJournal() = FileScrapeJournal(File(filesDir, "${intent.getStringExtra("testDatabase")}-scrape"))
    /** `stayInMenu` keeps the pause menu open after Quick Save, Quick Load and Load, for tests that go on in it. */
    override fun presentationPreferences() = getSharedPreferences("${intent.getStringExtra("testDatabase")}-presentation", MODE_PRIVATE).also {
        if (intent.getBooleanExtra("stayInMenu", false)) it.edit(commit = true) { putBoolean("returnToGame", false) }
    }
    override fun createPackStore() = dev.cartridge.scraping.ScrapePackStore(File(filesDir, "${intent.getStringExtra("testDatabase")}-packs"))
    override fun createScrapePreferences() = dev.cartridge.scraping.SharedScrapePreferences(this, "${intent.getStringExtra("testDatabase")}-preferences")
    override fun persistDirectoryPermission(uri: Uri) {
        if (uri.authority != "$packageName.fixture-tree") super.persistDirectoryPermission(uri)
        // Own-UID, MANAGE_DOCUMENTS-protected fixture; only the system-picker grant is stubbed.
    }
    companion object {
        var testProvider: ArtworkProvider? = null
        var testApps: dev.cartridge.apps.AppSource? = null
        private val databases = mutableMapOf<String, LibraryDatabase>()
        fun closeDatabases() = synchronized(databases) { databases.values.forEach { it.close() }; databases.clear() }
    }
}
