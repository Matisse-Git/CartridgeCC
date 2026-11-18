package dev.cartridge.scraping

import android.content.Context
import dev.cartridge.domain.Market
import dev.cartridge.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One automatic run as Settings and the library's notice describe it. [problem] is why it stopped early, if it did. */
/** What an automatic scrape fetches beside case art and details. */
data class ExtraMedia(val screenshots: Boolean = true, val videos: Boolean = true)

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
    /** Where downloaded gameplay videos are kept; none in tests that do not look at videos. */
    private val videos: dev.cartridge.storage.VideoStore? = null,
    /** Which extra media to fetch beside the case art: read at each game, so a change in Settings applies to the next. */
    private val extras: () -> ExtraMedia = { ExtraMedia() },
) {
    private val mutableStatus = MutableStateFlow(AutoScrapeStatus())
    val status = mutableStatus.asStateFlow()
    private val queue = LinkedHashMap<String, FillPolicy>()
    private var job: Job? = null

    /** Off under Settings › Library › Download missing art: added games wait as pending until [resume]. */
    @Volatile var paused = false
    /** Newly added games: fill what they are missing, along with any game an earlier run could not reach. */
    fun added(ids: Collection<String>) { if (ids.isNotEmpty()) enqueue(ids, FillPolicy(), start = !paused) }
    /** Download missing art is on again: the games that waited are fetched. */
    fun resume() { paused = false; enqueue(emptyList(), FillPolicy()) }
    /** A bulk scope chosen in Artwork & Info. */
    fun scrape(ids: Collection<String>, policy: FillPolicy) = enqueue(ids, policy)
    fun cancel() { job?.cancel() }

    private fun enqueue(ids: Collection<String>, policy: FillPolicy, start: Boolean = true) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val waiting = try { pending.load() } catch (failure: Exception) { if (failure is CancellationException) throw failure; emptySet() }
            try { pending.save(waiting + ids) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableStatus.value = status.value.copy(problem = failure.message); return@launch
            }
            if (!start) return@launch
            for (id in waiting) queue.putIfAbsent(id, FillPolicy())
            for (id in ids) queue[id] = policy
            if (queue.isEmpty()) return@launch
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
        // Read the game as it is now: the user may have chosen something while the run waited.
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
        // Only download what may be applied: the user's daily quota counts images too.
        val art = chooseArt(ranked.artwork, market)?.takeIf { policy.replaces(game.label?.takeIf { it.isCaseArt }?.provenance?.automatic) }
        val label = art?.let { asset ->
            val bytes = provider.image(asset)
            withContext(ioDispatcher) { importImage(asset.url, bytes) }.copy(provenance = AssetProvenance(source, ranked.id, asset.type,
                asset.region, asset.originalUrl ?: asset.url, "Artwork rights remain with their owners; provider grants no image licence. Data: ${ranked.license}",
                now, automatic = true))
        }
        var changed = repository.applyScraped(id, label, metadata, policy)
        val wanted = extras()
        // Screenshots: each one the game lacks (or an automatic one, under Fill all). One failed image never costs the rest.
        if (wanted.screenshots) {
            val shots = chooseShots(ranked.artwork, market).filter { (type, _) ->
                policy.replaces(game.media[type]?.let { it.image.provenance?.automatic ?: false }) }
            val accepted = shots.mapNotNull { (type, asset) ->
                try {
                    val bytes = provider.image(asset)
                    type to MediaSelection(withContext(ioDispatcher) { importImage(asset.url, bytes) }.copy(provenance = AssetProvenance(source, ranked.id,
                        asset.type, asset.region, asset.originalUrl ?: asset.url, "Artwork rights remain with their owners; provider grants no image licence. Data: ${ranked.license}",
                        now, automatic = true)), type, asset.region)
                } catch (failure: ProviderUnavailable) { throw failure }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { android.util.Log.w("CartridgeScrape", "A screenshot could not be fetched: ${failure.message}"); null }
            }.toMap()
            if (accepted.isNotEmpty()) changed += repository.applyScrapedMedia(id, accepted, policy)
        }
        // A gameplay video only where the game has none yet: they are large, and the user's quota counts them.
        val store = videos
        if (wanted.videos && store != null && !store.has(id)) chooseVideo(ranked.videos, market)?.let { asset ->
            try { withContext(ioDispatcher) { store.save(id) { file -> provider.download(asset, file) } }; changed++ }
            catch (failure: ProviderUnavailable) { throw failure }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { android.util.Log.w("CartridgeScrape", "A video could not be fetched: ${failure.message}") }
        }
        return if (changed > 0) Outcome.Applied else Outcome.Unchanged
    }

    companion object {
        /**
         * The case art to apply for a game of [market]: its own region's first, then worldwide, then any other; within a
         * region the whole unfolded cover before the front alone. Nothing else is downloaded (ADR 0029).
         */
        fun chooseArt(artwork: List<ArtworkCandidate>, market: Market): ArtworkCandidate? = artwork.filter { it.type in CASE_ART }
            .minWithOrNull(compareBy<ArtworkCandidate>({ market.artRegions.indexOf(it.region.lowercase()).let { rank -> if (rank < 0) market.artRegions.size else rank } },
                { if (it.type == "box-texture") 0 else 1 }))
        private fun regionRank(asset: ArtworkCandidate, market: Market) =
            market.artRegions.indexOf(asset.region.lowercase()).let { rank -> if (rank < 0) market.artRegions.size else rank }
        /** One image per screenshot type, in the market's region order; fan art only when there is no in-game screenshot. */
        fun chooseShots(artwork: List<ArtworkCandidate>, market: Market): Map<String, ArtworkCandidate> {
            val best = SHOT_TYPES.mapNotNull { type -> artwork.filter { it.type == type }.minByOrNull { regionRank(it, market) }?.let { type to it } }.toMap()
            return if ("ss" in best) best - "fanart" else best
        }
        fun chooseVideo(videos: List<ArtworkCandidate>, market: Market): ArtworkCandidate? =
            videos.minWithOrNull(compareBy<ArtworkCandidate>({ VIDEO_TYPES.indexOf(it.type) }, { regionRank(it, market) }))
        fun cleanTitle(title: String) = title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "").trim().ifEmpty { title }
    }
}
