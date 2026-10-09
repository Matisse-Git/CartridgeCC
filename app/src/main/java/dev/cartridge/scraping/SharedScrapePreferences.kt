package dev.cartridge.scraping

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-owned preferences, separate from durable batch progress and accepted artwork. */
class SharedScrapePreferences(context: Context, name: String = "scraping") : ScrapePreferenceStore {
    private val store = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override suspend fun load() = withContext(Dispatchers.IO) {
        ScrapePreferences(store.getString("region", "us").takeIf { code -> ScrapePreferences.regions.any { it.code == code } } ?: "us",
            store.getString("language", "en").takeIf { code -> ScrapePreferences.languages.any { it.code == code } } ?: "en")
    }
    override suspend fun save(preferences: ScrapePreferences) = withContext(Dispatchers.IO) {
        check(store.edit().putString("region", preferences.region).putString("language", preferences.language).commit()) { "Cannot save scraper preferences" }
    }
}
