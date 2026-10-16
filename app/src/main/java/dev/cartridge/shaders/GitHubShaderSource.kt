package dev.cartridge.shaders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * libretro/glsl-shaders on GitHub: the newest commit of its master branch from GitHub's API, and that
 * commit's archive from codeload. Asked only when the player presses Update; nothing is fetched otherwise.
 */
class GitHubShaderSource(private val repository: String = "libretro/glsl-shaders", private val branch: String = "master") : ShaderSource {
    override suspend fun newest(): ShaderSource.Commit = withContext(Dispatchers.IO) {
        val connection = open("https://api.github.com/repos/$repository/branches/$branch")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            check(connection.responseCode == 200) { failure(connection.responseCode) }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    require(output.size() <= 1024 * 1024) { "GitHub's answer was larger than expected" }
                }
                output.toByteArray()
            }
            val commit = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("commit")
            ShaderSource.Commit(commit.getString("sha"), commit.getJSONObject("commit").getJSONObject("committer").optString("date"))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun archive(commit: String): ShaderSource.Download = withContext(Dispatchers.IO) {
        require(Regex("[0-9a-f]{40}").matches(commit)) { "Not a commit" }
        val connection = open("https://codeload.github.com/$repository/zip/$commit")
        try {
            check(connection.responseCode == 200) { failure(connection.responseCode) }
            ShaderSource.Download(connection.inputStream, connection.contentLengthLong) { connection.disconnect() }
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    private fun open(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "ProjectCartridge/0.1 (Android; personal collection)")
    }

    private fun failure(status: Int) = when (status) {
        403, 429 -> "GitHub is limiting requests from this network for now. Try again later."
        404 -> "The shader library was not found on GitHub."
        else -> "GitHub did not answer as expected (HTTP $status). Try again later."
    }
}
