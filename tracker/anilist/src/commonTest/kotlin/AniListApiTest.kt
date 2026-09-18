/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AniListApiTest {

    @Test
    fun `TRK-06 viewerProfile解析出用户信息并携带Bearer token`() = runTest {
        var seenAuthHeader: String? = null
        val api = createApi { request ->
            seenAuthHeader = request.headers["Authorization"]
            respondJson(
                """
                {
                  "data": {
                    "Viewer": {
                      "id": 12345,
                      "name": "haru",
                      "avatar": { "large": "https://example.com/avatar.png" },
                      "mediaListOptions": { "scoreFormat": "POINT_10_DECIMAL" }
                    }
                  }
                }
                """.trimIndent(),
            )
        }

        val viewer = api.viewerProfile(accessToken = "token-abc")

        assertEquals("Bearer token-abc", seenAuthHeader)
        assertEquals(12345, viewer.id)
        assertEquals("haru", viewer.name)
        assertEquals("POINT_10_DECIMAL", viewer.mediaListOptions.scoreFormat)
    }

    @Test
    fun `TRK-07 searchAnime解析多条结果`() = runTest {
        val api = createApi {
            respondJson(
                """
                {
                  "data": {
                    "Page": {
                      "media": [
                        {
                          "id": 1,
                          "title": { "romaji": "Sousou no Frieren", "english": "Frieren", "native": "葬送のフリーレン" },
                          "coverImage": { "large": "https://example.com/1.png" },
                          "episodes": 28,
                          "status": "FINISHED",
                          "format": "TV"
                        },
                        {
                          "id": 2,
                          "title": { "romaji": "Frieren Part 2", "english": null, "native": null },
                          "coverImage": null,
                          "episodes": null,
                          "status": "NOT_YET_RELEASED",
                          "format": "TV"
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            )
        }

        val results = api.searchAnime("frieren", accessToken = "token-abc")

        assertEquals(2, results.size)
        assertEquals("Sousou no Frieren", results[0].title.romaji)
        assertEquals(28, results[0].episodes)
        assertNull(results[1].coverImage)
    }

    @Test
    fun `TRK-08 saveMediaListEntry请求体只包含四个字段`() = runTest {
        var capturedBody: String? = null
        val api = createApi { request ->
            capturedBody = (request.body as TextContent).text
            respondJson(
                """{"data": {"SaveMediaListEntry": {"id": 1, "mediaId": 42, "status": "CURRENT", "score": 8.5, "progress": 12}}}""",
            )
        }

        val entry = api.saveMediaListEntry(
            mediaId = 42,
            status = "CURRENT",
            score = 8.5,
            progress = 12,
            accessToken = "token-abc",
        )

        val variables = Json.parseToJsonElement(capturedBody!!).jsonObject["variables"]!!.jsonObject
        assertEquals(setOf("mediaId", "status", "score", "progress"), variables.keys)
        assertEquals(42, variables["mediaId"]!!.jsonPrimitive.int)
        assertEquals(42, entry.mediaId)
        assertEquals(12, entry.progress)
    }

    @Test
    fun `TRK-08 GraphQL errors数组导致抛出AniListGraphQLException`() = runTest {
        val api = createApi {
            respondJson(
                """{"data": null, "errors": [{"message": "Invalid token"}]}""",
            )
        }

        val exception = assertFailsWith<AniListGraphQLException> {
            api.viewerProfile(accessToken = "expired-token")
        }
        assertContains(exception.message.orEmpty(), "Invalid token")
    }

    @Test
    fun `TRK-09 超过约365天未刷新的token视为需要重新登录`() {
        val loginAt = 0L
        val justUnder = loginAt + 364.days.inWholeMilliseconds
        val justOver = loginAt + 366.days.inWholeMilliseconds

        assertTrue(!isAniListTokenLikelyExpired(loginAt, nowMillis = justUnder))
        assertTrue(isAniListTokenLikelyExpired(loginAt, nowMillis = justOver))
    }

    private fun createApi(handler: MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData): AniListApi {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return AniListApi(client.asScopedHttpClient())
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
