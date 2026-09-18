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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.createMemoryTrackerAccountDao
import me.him188.ani.app.tracker.anilist.AniListApi
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * TRK-11/12/13: AniList PIN 登录流程状态机, 见 `docs/dev/trackers/anilist-sync-plan.md` 4.1.
 *
 * 复用 [AniListTrackerServiceTest] 同款 MockEngine 手法驱动真实 [AniListTrackerService], 但这里只关心
 * [AniListLoginConfigurator] 自己的状态转换, 不是 service 的持久化细节 (那部分已经在
 * [AniListTrackerServiceTest] 里覆盖).
 */
class AniListLoginConfiguratorTest {

    private class Fixture(
        val configurator: AniListLoginConfigurator,
        val service: AniListTrackerService,
    )

    private fun runConfiguratorTest(
        handler: MockRequestHandleScope.(request: HttpRequestData) -> HttpResponseData = {
            error("Unexpected request: ${it.url} -- TRK-12 的语法校验不应该发起网络请求")
        },
        block: suspend Fixture.() -> Unit,
    ) = runBlocking {
        val engine = MockEngine { request -> handler(request) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = AniListTrackerService(
            api = AniListApi(client.asScopedHttpClient()),
            accountDao = createMemoryTrackerAccountDao(),
        )
        Fixture(AniListLoginConfigurator(service), service).block()
    }

    private fun MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    @Test
    fun `TRK-11 状态从Idle经AwaitingPasteToken和Validating到Success`() = runConfiguratorTest(
        handler = {
            respondJson(
                """{"data":{"Viewer":{"id":1,"name":"haru","avatar":null,"mediaListOptions":{"scoreFormat":"POINT_10_DECIMAL"}}}}""",
            )
        },
    ) {
        assertEquals(AniListLoginState.Idle, configurator.state.value)

        configurator.startAwaitingPasteToken()
        assertEquals(AniListLoginState.AwaitingPasteToken, configurator.state.value)

        val result = configurator.submitToken("aaa.bbb.ccc")

        val success = assertIs<AniListLoginState.Success>(result)
        assertEquals("haru", success.account.username)
        assertEquals(result, configurator.state.value)
    }

    @Test
    fun `TRK-12 语法上明显无效的token在调用登录前就被拒绝且不发起网络请求`() = runConfiguratorTest {
        for (invalid in listOf("", "   ", "not-a-jwt", "a.b", "a. b.c")) {
            configurator.startAwaitingPasteToken()

            val result = configurator.submitToken(invalid)

            assertIs<AniListLoginState.Failed>(result, "token『$invalid』应当被拒绝")
        }
    }

    @Test
    fun `TRK-13 语法正确但被AniList拒绝的token转为Failed且不持久化账号`() = runConfiguratorTest(
        handler = { respondJson("""{"data":null,"errors":[{"message":"Invalid token"}]}""") },
    ) {
        configurator.startAwaitingPasteToken()

        val result = configurator.submitToken("aaa.bbb.ccc")

        assertIs<AniListLoginState.Failed>(result)
        assertNull(service.accountFlow().first())
    }
}
