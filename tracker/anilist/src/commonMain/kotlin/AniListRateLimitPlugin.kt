/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import io.ktor.client.plugins.api.createClientPlugin
import kotlin.time.Duration.Companion.minutes

/**
 * AniList GraphQL API 限流为 90 req/min. 25/min 是 Mihon 在生产中验证过的安全余量, 留有约 3.6x 缓冲.
 */
val AniListRateLimit = createClientPlugin("AniListRateLimit") {
    val limiter = RateLimiter(permits = 25, window = 1.minutes, scope = client)

    onRequest { _, _ ->
        limiter.acquire()
    }
}
