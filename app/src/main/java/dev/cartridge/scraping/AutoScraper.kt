package dev.cartridge.scraping

import android.content.Context
import dev.cartridge.domain.Market
import dev.cartridge.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One automatic run as Settings and the library's notice describe it. [problem] is why it stopped early, if it did. */
data class AutoScrapeStatus(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val applied: Int = 0,
    val notFound: Int = 0, val failed: Int = 0, val current: String? = null, val problem: String? = null) {
    val summary get() = buildList {
        if (applied > 0) add("${applied} updated")
        if (done - applied - notFound - failed > 0) add("${done - applied - notFound - failed} already complete")
        if (notFound > 0) add("$notFound not found")
        if (failed > 0) add("$failed failed")
    }.joinToString(" · ")
}

/** Games still waiting for an automatic scrape. Written before any network work, so nothing is lost if the app goes away. */
interface PendingScrapes {
    suspend fun load(): Set<String>
    suspend fun save(ids: Set<String>)
}
class SharedPendingScrapes(context: Context, name: String = "auto-scrape") : PendingScrapes {
    private val store = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override suspend fun load(): Set<String> = withContext(Dispatchers.IO) { store.getStringSet("pending", emptySet()).orEmpty().toSet() }
    override suspend fun save(ids: Set<String>) = withContext(Dispatchers.IO) {
        check(store.edit().putStringSet("pending", ids).commit()) { "Cannot save the games waiting for artwork" }
    }
}
class MemoryPendingScrapes(var ids: Set<String> = emptySet()) : PendingScrapes {
    override suspend fun load() = ids
    override suspend fun save(ids: Set<String>) { this.ids = ids }
}

/**
 * Applies the best match without a review (ADR 0029): after games are added, and for every bulk scope. One title at a
 * time; [FillPolicy] decides what may be replaced, and every change is one Undo. A run that cannot reach the provider
 * (offline, no login, quota) stops and leaves its games pending for the next add or bulk scrape. Nothing starts
 * when the app opens. Reviewing one game stays manual ([ScrapeCoordinator]) and is how a wrong match is corrected.
 */
class AutoScraper(
    private val provider: ArtworkProvider,
    private val repository: CollectionRepository,
    private val roms: File,
    private val scope: CoroutineScope,
    private val pending: PendingScrapes,
    private val preferences: ScrapePreferenceStore,
    /** Copies a downloaded image into the accepted store and names it. Runs on [ioDispatcher]. */
    private val importImage: (source: String, bytes: ByteArray) -> LabelRef,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableStatus = MutableStateFlow(AutoScrapeStatus())
    val status = mutableStatus.asStateFlow()
    private val queue = LinkedHashMap<String, FillPolicy>()
    private var job: Job? = null

    /** Newly added games: fill what they are missing, along with any game an earlier run could not reach. */
    fun added(ids: Collection<String>) { if (ids.isNotEmpty()) enqueue(ids, FillPolicy()) }
    /** A bulk scope chosen in Artwork & Info. */
    fun scrape(ids: Collection<String>, policy: FillPolicy) = enqueue(ids, policy)
    fun cancel() { job?.cancel() }

    private fun enqueue(ids: Collection<String>, policy: FillPolicy) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val waiting = try { pending.load() } catch (failure: Exception) { if (failure is CancellationException) throw failure; emptySet() }
            try { pending.save(waiting + ids) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableStatus.value = status.value.copy(problem = failure.message); return@launch
            }
            for (id in waiting) queue.putIfAbsent(id, FillPolicy())
            for (id in ids) queue[id] = policy
            if (job?.isActive != true) {
                mutableStatus.value = AutoScrapeStatus(running = true, total = queue.size)
                job = scope.launch { run() }
            } else mutableStatus.value = status.value.copy(total = status.value.done + queue.size)
        }
    }

    private suspend fun run() {
        try {
            while (true) {
                val (id, policy) = queue.entries.firstOrNull()?.toPair() ?: break
                val outcome = try { one(id, policy) }
                catch (failure: ProviderUnavailable) {
                    queue.clear()
                    mutableStatus.value = status.value.copy(problem = failure.message, current = null)
                    return
                }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    android.util.Log.w("CartridgeScrape", "Automatic scrape of one game failed: ${failure.message}")
                    Outcome.Failed
                }
                queue.remove(id)
                pending.save(pending.load() - id)
                val now = status.value
                mutableStatus.value = now.copy(done = now.done + 1, applied = now.applied + if (outcome == Outcome.Applied) 1 else 0,
                    notFound = now.notFound + if (outcome == Outcome.NotFound) 1 else 0, failed = now.failed + if (outcome == Outcome.Failed) 1 else 0,
                    problem = null)
            }
        } finally {
            mutableStatus.value = status.value.copy(running = false, current = null)
        }
    }

    private enum class Outcome { Applied, Unchanged, NotFound, Failed }

    private suspend fun one(id: String, policy: FillPolicy): Outcome {
        // Read the game as it is now: the owner may have chosen something while the run waited.
        val game = repository.initialize().slots.filterNotNull().firstOrNull { it.id == id } ?: return Outcome.Unchanged
        mutableStatus.value = status.value.copy(current = game.title)
        val market = Market.of(game.fileName)
        val file = if (game.system.fingerprint) withContext(ioDispatcher) {
            dev.cartridge.storage.GameFiles.resolve(roms, id, game.system).takeIf(File::isFile) } else null
        val matches = provider.search(cleanTitle(game.fileName), file, game.system)
        val best = matches.firstOrNull { it.exactHash } ?: matches.firstOrNull() ?: return Outcome.NotFound
        val ranked = preferences.load().copy(region = market.code).rank(best)
        val source = ranked.sourceName.ifBlank { provider.name }
        val now = System.currentTimeMillis()
        val metadata = ranked.metadata.filterKeys { it in METADATA_FIELDS }.mapNotNull { (field, values) ->
            values.firstOrNull()?.let { field to MetadataValue(it, source, ranked.id, ranked.sourceUrl, ranked.license, now, automatic = true) } }.toMap()
        // Only download what may be applied: the owner's daily quota counts images too.
        val art = chooseArt(ranked.artwork, market)?.takeIf { policy.replaces(game.label?.takeIf { it.isCaseArt }?.provenance?.automatic) }
        val label = art?.let { asset ->
            val bytes = provider.image(asset)
            withContext(ioDispatcher) { importImage(asset.url, bytes) }.copy(provenance = AssetProvenance(source, ranked.id, asset.type,
                asset.region, asset.originalUrl ?: asset.url, "Artwork rights remain with their owners; provider grants no image licence. Data: ${ranked.license}",
                now, automatic = true))
        }
        return if (repository.applyScraped(id, label, metadata, policy) > 0) Outcome.Applied else Outcome.Unchanged
    }

    companion object {
        /**
         * The case art to apply for a game of [market]: its own region's first, then worldwide, then any other; within a
         * region the whole unfolded cover before the front alone. Nothing else is downloaded (ADR 0029).
         */
        fun chooseArt(artwork: List<ArtworkCandidate>, market: Market): ArtworkCandidate? = artwork.filter { it.type in CASE_ART }
            .minWithOrNull(compareBy<ArtworkCandidate>({ market.artRegions.indexOf(it.region.lowercase()).let { rank -> if (rank < 0) market.artRegions.size else rank } },
                { if (it.type == "box-texture") 0 else 1 }))
        fun cleanTitle(title: String) = title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "").trim().ifEmpty { title }
    }
}
