/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import kotlin.time.Duration.Companion.days

/**
 * AniList implicit grant 的响应不带真实 `expires_in`, 也没有 refresh token; 只能假设约 365 天有效期
 * (与 Mihon `ALOAuth.kt` 的假设一致). 超过这个窗口后应视为需要重新登录, 而不是尝试静默刷新
 * (AniList 不提供这个能力).
 */
val ANILIST_ASSUMED_TOKEN_LIFETIME = 365.days

fun isAniListTokenLikelyExpired(updatedAtMillis: Long, nowMillis: Long): Boolean {
    return (nowMillis - updatedAtMillis) > ANILIST_ASSUMED_TOKEN_LIFETIME.inWholeMilliseconds
}
