package com.myhealth.data.video

import com.myhealth.data.off.OffClient
import com.myhealth.domain.engine.strength.video.VideoLink
import com.myhealth.domain.repository.VideoTitleSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * YouTube's public oEmbed endpoint (P26.4): `GET https://www.youtube.com/oembed?url=…&format=json`
 * answers `{"title": …}` without a key. Only YouTube links are asked; everything else is `null`.
 */
class OEmbedTitleSource(
    private val client: () -> HttpClient = { OffClient.defaultHttpClient() },
) : VideoTitleSource {

    private val http by lazy(client)

    override suspend fun titleOf(url: String): String? {
        if (!VideoLink.isYouTube(url)) return null
        return try {
            val response = http.get(ENDPOINT) {
                parameter("url", VideoLink.normalise(url))
                parameter("format", "json")
            }
            if (!response.status.isSuccess()) return null
            parse(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class OEmbed(val title: String? = null)

    companion object {
        private const val ENDPOINT = "https://www.youtube.com/oembed"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(body: String): String? = try {
            JSON.decodeFromString(OEmbed.serializer(), body).title?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
