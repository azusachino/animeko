/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracker.anilist

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
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.TrackerAccountDao
import me.him188.ani.app.data.persistent.database.dao.TrackerAccountEntity
import me.him188.ani.app.tracker.anilist.AniListApi
import me.him188.ani.app.tracker.api.TrackerBinding
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AniListTrackerServiceTest {

    private class Fixture(
        val service: AniListTrackerService,
        val accountDao: TrackerAccountDao,
        val seenRequests: MutableList<HttpRequestData>,
        private val closeDatabase: () -> Unit,
    ) {
        suspend fun seedAccount(updatedAtMillis: Long = 0L) {
            accountDao.upsertAccount(
                TrackerAccountEntity(
                    trackerId = "anilist",
                    userId = 1,
                    username = "haru",
                    avatarUrl = null,
                    accessToken = "seeded-token",
                    scoreFormat = "POINT_10_DECIMAL",
                    updatedAtMillis = updatedAtMillis,
                ),
            )
        }

        fun close() = closeDatabase()
    }

    private fun runDatabaseTest(
        nowMillis: () -> Long = { 0L },
        handler: MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData = {
            error("Unexpected request: ${it.url}")
        },
        block: suspend Fixture.() -> Unit,
    ) = runBlocking {
        val database = createTestAniDatabase()
        val seenRequests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seenRequests += request
            handler(request)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = AniListTrackerService(
            api = AniListApi(client.asScopedHttpClient()),
            accountDao = database.trackerAccountDao(),
            nowMillis = nowMillis,
        )
        val fixture = Fixture(service, database.trackerAccountDao(), seenRequests) { database.close() }
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    @Test
    fun `TRK-11 未登录时isAuthorized为false`() = runDatabaseTest {
        assertFalse(service.isAuthorized())
    }

    @Test
    fun `TRK-11 login成功后持久化账号且isAuthorized为true`() = runDatabaseTest(
        handler = {
            respondJson(
                """
                {"data":{"Viewer":{"id":12345,"name":"haru","avatar":{"large":"a.png"},
                "mediaListOptions":{"scoreFormat":"POINT_10_DECIMAL"}}}}
                """.trimIndent(),
            )
        },
    ) {
        val result = service.login("pin-token")

        assertTrue(result.isSuccess)
        assertEquals("haru", result.getOrNull()?.username)
        assertTrue(service.isAuthorized())
    }

    @Test
    fun `TRK-13 login收到GraphQL错误时返回失败且不持久化账号`() = runDatabaseTest(
        handler = { respondJson("""{"data":null,"errors":[{"message":"Invalid token"}]}""") },
    ) {
        val result = service.login("garbage-pin")

        assertTrue(result.isFailure)
        assertFalse(service.isAuthorized())
    }

    @Test
    fun `TRK-09 超过约365天未刷新的账号isAuthorized为false且不发起网络请求`() = runDatabaseTest(
        nowMillis = { 400.days.inWholeMilliseconds },
    ) {
        seedAccount(updatedAtMillis = 0L)

        assertFalse(service.isAuthorized())
        assertTrue(seenRequests.isEmpty())
    }

    @Test
    fun `TRK-14 syncProgress只提交progress字段`() = runDatabaseTest(
        handler = { respondJson("""{"data":{"SaveMediaListEntry":{"id":1,"mediaId":42,"progress":12}}}""") },
    ) {
        seedAccount()

        val result = service.syncProgress(
            binding = TrackerBinding(
                subjectId = 1, trackerId = "anilist", remoteMediaId = 42,
                episodeOffset = 0, lastTrackedEpisode = 0f,
            ),
            episodeNumber = 12f,
        )

        assertTrue(result.isSuccess)
        assertEquals(1, seenRequests.size)
    }

    @Test
    fun `TRK-14 syncCollectionStatus为NOT_COLLECTED时跳过网络请求`() = runDatabaseTest {
        seedAccount()

        val result = service.syncCollectionStatus(
            binding = TrackerBinding(
                subjectId = 1, trackerId = "anilist", remoteMediaId = 42,
                episodeOffset = 0, lastTrackedEpisode = 0f,
            ),
            status = UnifiedCollectionType.NOT_COLLECTED,
            score = null,
        )

        assertTrue(result.isSuccess)
        assertTrue(seenRequests.isEmpty())
    }

    @Test
    fun `TRK-07 fetchEntry在mediaListEntry存在时解析出progress`() = runDatabaseTest(
        handler = {
            respondJson(
                """
                {"data":{"Media":{"id":42,"title":{"romaji":"Frieren","english":null,"native":null},
                "coverImage":null,"episodes":28,"status":"RELEASING","format":"TV",
                "mediaListEntry":{"status":"CURRENT","score":8.5,"progress":12}}}}
                """.trimIndent(),
            )
        },
    ) {
        seedAccount()

        val entry = service.fetchEntry(42)

        assertEquals(12f, entry?.progress)
        assertEquals("CURRENT", entry?.status)
    }

    @Test
    fun `TRK-07 fetchEntry在Media不存在时返回null`() = runDatabaseTest(
        handler = { respondJson("""{"data":{"Media":null}}""") },
    ) {
        seedAccount()

        assertNull(service.fetchEntry(999999))
    }
}
