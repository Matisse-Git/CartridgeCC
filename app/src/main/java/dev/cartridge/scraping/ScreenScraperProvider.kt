package dev.cartridge.scraping

import dev.cartridge.domain.GameSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

/**
 * ScreenScraper's API v2 (https://www.screenscraper.fr/webapi2.php, read 2026-10-17). A cartridge is looked up by its
 * checksums first (`jeuInfos`); a disc, or a cartridge it does not know, by name (`jeuRecherche`). Only case art and
 * text details are taken (ADR 0029). The API puts the login into every media link it returns: links are stored
 * without it and it is added again only for the download, so no stored URL, journal or backup carries it.
 */
class ScreenScraperProvider(private val login: () -> ScreenScraperLogin) : ArtworkProvider {
    override val name = "ScreenScraper"
    private val requests = Mutex()
    private var lastRequest = 0L

    override suspend fun search(query: String, rom: File?, system: GameSystem): List<GameCandidate> = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..180) { "Enter a title between 2 and 180 characters" }
        val account = account()
        val exact = rom?.takeIf(File::isFile)?.let { file ->
            val sums = checksums(file)
            val response = call("jeuInfos.php", account, linkedMapOf("crc" to sums.crc, "md5" to sums.md5, "sha1" to sums.sha1,
                "romtaille" to file.length().toString(), "romnom" to romName(query, system), "systemeid" to system.screenScraper.toString(),
                "romtype" to "rom"), allowMissing = true)
            response?.optJSONObject("response")?.optJSONObject("jeu")?.let { parseGame(it, sums) }
        }
        // A checksum match is the game; a second request would only spend the owner's daily quota.
        if (exact?.exactHash == true) return@withContext listOf(exact)
        val found = call("jeuRecherche.php", account, linkedMapOf("recherche" to query.trim(), "systemeid" to system.screenScraper.toString()), allowMissing = true)
        val games = found?.optJSONObject("response")?.optJSONArray("jeux")
        buildList {
            exact?.let(::add)
            games?.objects()?.forEach { game -> parseGame(game)?.let(::add) }
        }.distinctBy { it.id }
    }

    override suspend fun image(candidate: ArtworkCandidate): ByteArray = withContext(Dispatchers.IO) {
        require(offered(candidate.url)) { "Unsupported provider image host" }
        val account = account()
        val bytes = requests.withLock {
            pace()
            fetch(withLogin(candidate.url, account, linkedMapOf("maxwidth" to MAX_WIDTH.toString())), 12 * 1024 * 1024)
        } ?: error("This image is unavailable")
        // The media endpoint answers a missing image with a short word instead of a status.
        require(bytes.size > 64) { "This image is unavailable" }
        bytes
    }

    private fun account() = login().also { if (!it.complete) throw ProviderUnavailable("Add ${it.missing.joinToString(" and ")} under Settings › Library › Artwork") }

    internal fun parseGame(game: JSONObject, sums: Checksums? = null): GameCandidate? {
        val id = game.optString("id")
        if (!id.matches(Regex("[0-9]{1,10}"))) return null // An empty search answers with one empty game.
        val regional = game.optJSONArray("noms")?.objects().orEmpty()
            .mapNotNull { name -> name.text("text")?.let { it to name.optString("region") } }
            .sortedBy { (_, region) -> REGION_ORDER.indexOf(region).let { if (it < 0) REGION_ORDER.size else it } }
            .distinctBy { it.first }
        if (regional.isEmpty()) return null
        val metadata = linkedMapOf("title" to regional.map { it.first })
        val tags = linkedMapOf("title" to regional.map { MetadataTags(region = it.second) })
        val years = game.optJSONArray("dates")?.objects().orEmpty()
            .mapNotNull { date -> date.text("text")?.take(4)?.takeIf { it.matches(Regex("[12][0-9]{3}")) }?.let { it to date.optString("region") } }
            .sortedBy { (_, region) -> REGION_ORDER.indexOf(region).let { if (it < 0) REGION_ORDER.size else it } }
            .distinctBy { it.first }
        if (years.isNotEmpty()) { metadata["year"] = years.map { it.first }; tags["year"] = years.map { MetadataTags(region = it.second) } }
        game.optJSONObject("developpeur")?.text("text")?.let { metadata["developer"] = listOf(it) }
        game.optJSONObject("editeur")?.text("text")?.let { metadata["publisher"] = listOf(it) }
        val genres = game.optJSONArray("genres")?.objects().orEmpty()
        val genreNames = genres.filter { it.optString("principale") == "1" }.ifEmpty { genres }.mapNotNull { genre ->
            val names = genre.optJSONArray("noms")?.objects().orEmpty()
            (names.firstOrNull { it.optString("langue") == "en" } ?: names.firstOrNull())?.text("text")
        }.distinct()
        if (genreNames.isNotEmpty()) metadata["genre"] = listOf(genreNames.joinToString(", ").take(400))
        game.optJSONObject("joueurs")?.text("text")?.let { metadata["players"] = listOf(it.take(40)) }
        val synopses = game.optJSONArray("synopsis")?.objects().orEmpty()
            .mapNotNull { synopsis -> synopsis.text("text")?.let { it to synopsis.optString("langue") } }.distinctBy { it.first }
        if (synopses.isNotEmpty()) { metadata["description"] = synopses.map { it.first }; tags["description"] = synopses.map { MetadataTags(language = it.second) } }
        val artwork = game.optJSONArray("medias")?.objects().orEmpty().mapNotNull { media ->
            val type = media.optString("type")
            val url = media.text("url")?.let(::withoutLogin)
            if (type !in dev.cartridge.library.CASE_ART || url == null || !offered(url)) null
            else ArtworkCandidate(type, media.optString("region"), url, media.text("md5"))
        }.distinctBy { it.url }
        val rom = game.optJSONObject("rom")
        val exact = sums != null && rom != null && (rom.optString("romsha1").equals(sums.sha1, true) ||
            rom.optString("rommd5").equals(sums.md5, true) || rom.optString("romcrc").equals(sums.crc, true))
        return GameCandidate(id, regional.first().first, game.optJSONObject("systeme")?.text("text") ?: "", regional.map { it.first },
            metadata, artwork, "https://www.screenscraper.fr/gameinfos.php?gameid=$id",
            "ScreenScraper community data; artwork rights remain with their owners", exact, tags, name)
    }

    private suspend fun call(endpoint: String, account: ScreenScraperLogin, parameters: Map<String, String>, allowMissing: Boolean = false): JSONObject? =
        requests.withLock {
            pace()
            val bytes = fetch(withLogin("$BASE/$endpoint", account, parameters + ("output" to "json")), 8 * 1024 * 1024, allowMissing) ?: return@withLock null
            runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse { error("ScreenScraper sent an answer Cartridge cannot read") }
        }

    /** A free account may run one request at a time and a limited number a minute. */
    private suspend fun pace() {
        val elapsed = System.nanoTime() / 1_000_000 - lastRequest
        if (elapsed < SPACING_MS) delay(SPACING_MS - elapsed)
        lastRequest = System.nanoTime() / 1_000_000
    }

    private suspend fun fetch(url: String, limit: Int, allowMissing: Boolean = false): ByteArray? {
        coroutineContext.ensureActive()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "ProjectCartridge/0.8 (Android; personal collection)")
        try {
            val status = try { connection.responseCode } catch (failure: IOException) { throw ProviderUnavailable("No connection to ScreenScraper. Check Wi-Fi and try again.") }
            when (status) {
                in 200..299 -> Unit
                404 -> if (allowMissing) return null else error("ScreenScraper does not have this")
                400 -> error("ScreenScraper refused this request")
                401 -> throw ProviderUnavailable("ScreenScraper is open to members only right now. Try again later.")
                403 -> throw ProviderUnavailable("ScreenScraper refused the login or developer ID. Check them under Settings › Library › Artwork.")
                423 -> throw ProviderUnavailable("ScreenScraper is closed for maintenance. Try again later.")
                426 -> throw ProviderUnavailable("ScreenScraper no longer accepts this program. A newer Cartridge is needed.")
                429 -> throw ProviderUnavailable("ScreenScraper asked to slow down. Try again in a minute.")
                430 -> throw ProviderUnavailable("Today's ScreenScraper quota is used up. Try again tomorrow.")
                431 -> throw ProviderUnavailable("ScreenScraper stopped looking up unknown games for today. Try again tomorrow.")
                else -> throw ProviderUnavailable("ScreenScraper is not answering (HTTP $status). Try again later.")
            }
            require(connection.contentLengthLong <= limit) { "Provider response is too large" }
            return try {
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size().toLong() + count <= limit) { "Provider response is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (failure: IOException) { throw ProviderUnavailable("The connection to ScreenScraper dropped. Try again.") }
        } finally { connection.disconnect() }
    }

    internal class Checksums(val crc: String, val md5: String, val sha1: String)
    private suspend fun checksums(file: File): Checksums {
        val crc = CRC32(); val md5 = MessageDigest.getInstance("MD5"); val sha1 = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer); if (count < 0) break
                crc.update(buffer, 0, count); md5.update(buffer, 0, count); sha1.update(buffer, 0, count)
            }
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        return Checksums("%08x".format(crc.value), hex(md5.digest()), hex(sha1.digest()))
    }

    companion object {
        private const val BASE = "https://api.screenscraper.fr/api2"
        private const val SPACING_MS = 1100L
        /** Case art is fetched at most this wide (ADR 0029); the GPU budget of the visible window stays bounded. */
        const val MAX_WIDTH = 1024
        private val REGION_ORDER = listOf("us", "eu", "uk", "ss", "wor", "jp")
        private val LOGIN = setOf("devid", "devpassword", "softname", "ssid", "sspassword", "output", "maxwidth", "maxheight")
        private const val SOFTWARE = "ProjectCartridge"

        /** An image link this provider gave out: https on ScreenScraper's own hosts, nothing else. */
        fun offered(url: String) = url.length <= 4096 && runCatching { URI(url).let {
            it.scheme == "https" && it.userInfo == null && it.port == -1 && (it.host == "screenscraper.fr" || it.host.endsWith(".screenscraper.fr"))
        } }.getOrDefault(false)

        /** The link without any login, developer ID or size it was given. */
        internal fun withoutLogin(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return url
            val query = uri.rawQuery?.split('&')?.filter { it.substringBefore('=').lowercase() !in LOGIN }?.joinToString("&")
            return URI(uri.scheme, uri.rawAuthority, uri.path, null, null).toString() + if (query.isNullOrEmpty()) "" else "?$query"
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun withLogin(url: String, account: ScreenScraperLogin, extra: Map<String, String>): String {
            val parameters = linkedMapOf("devid" to account.devId, "devpassword" to account.devPassword, "softname" to SOFTWARE,
                "ssid" to account.user, "sspassword" to account.password) + extra
            return url + (if ('?' in url) "&" else "?") + parameters.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        }
        /** What ScreenScraper calls the file: the title with the console's usual extension, never a path. */
        private fun romName(query: String, system: GameSystem) = query.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(160) + "." + system.extension

        private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().take(16_384).takeIf { it.isNotEmpty() && it != "null" }
    }
}
