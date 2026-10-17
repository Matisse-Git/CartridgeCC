package dev.cartridge.scraping

import android.content.Context
import java.io.File

/** Where artwork and details come from. Names are stored: append, never rename. */
enum class ArtworkSource(val title: String) { ScreenScraper("ScreenScraper"), RetroBase("RetroBase") }

/**
 * The owner's ScreenScraper account and the developer ID the API asks every program for. Typed on the device,
 * kept only in this app's private preferences: never in Git, a backup, a scrape pack or a stored URL (ADR 0029).
 */
data class ScreenScraperLogin(val user: String = "", val password: String = "", val devId: String = "", val devPassword: String = "") {
    val complete get() = user.isNotBlank() && password.isNotBlank() && devId.isNotBlank() && devPassword.isNotBlank()
    /** What is still missing, in the words Settings uses. */
    val missing get() = buildList {
        if (user.isBlank() || password.isBlank()) add("your ScreenScraper login")
        if (devId.isBlank() || devPassword.isBlank()) add("a developer ID")
    }
    override fun toString() = "ScreenScraperLogin(user=${user.isNotBlank()}, developer=${devId.isNotBlank()})"
}

/** A search or download that cannot happen now for a reason that is not the game's: offline, no login, quota. */
class ProviderUnavailable(message: String) : Exception(message)

class ArtworkSettings(context: Context, name: String = "artwork-source") {
    private val store = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    var source: ArtworkSource
        get() = ArtworkSource.entries.firstOrNull { it.name == store.getString("source", null) } ?: ArtworkSource.ScreenScraper
        set(value) { store.edit().putString("source", value.name).apply() }
    var login: ScreenScraperLogin
        get() = ScreenScraperLogin(store.getString("user", "").orEmpty(), store.getString("password", "").orEmpty(),
            store.getString("devId", "").orEmpty(), store.getString("devPassword", "").orEmpty())
        set(value) { store.edit().putString("user", value.user.trim()).putString("password", value.password)
            .putString("devId", value.devId.trim()).putString("devPassword", value.devPassword).apply() }
}

/** The provider Settings names, read at every call so a change applies to the next search. */
class ChosenProvider(private val settings: ArtworkSettings, cache: File) : ArtworkProvider {
    private val screenScraper = ScreenScraperProvider { settings.login }
    private val retroBase = RetroBaseProvider(cache)
    private val current get() = if (settings.source == ArtworkSource.RetroBase) retroBase else screenScraper
    override val name get() = current.name
    override suspend fun search(query: String, rom: File?, system: dev.cartridge.domain.GameSystem) = current.search(query, rom, system)
    // An accepted image is fetched from whoever offered it, whatever is chosen now.
    override suspend fun image(candidate: ArtworkCandidate) =
        if (ScreenScraperProvider.offered(candidate.url)) screenScraper.image(candidate) else retroBase.image(candidate)
}
