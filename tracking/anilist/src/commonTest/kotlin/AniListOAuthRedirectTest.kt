/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracking.anilist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AniListOAuthRedirectTest {
    @Test
    fun extractsTokenFromRegisteredRedirect() {
        assertEquals(
            "token+value",
            parseAniListOAuthRedirect("ani://anilist-auth#state=opaque&access_token=token%2Bvalue&token_type=Bearer"),
        )
    }

    @Test
    fun rejectsRedirectLookalikes() {
        val lookalikes = listOf(
            "ani://anilist-auth.evil#access_token=token",
            "ani://anilist-auth@evil#access_token=token",
            "ani://anilist-auth:443#access_token=token",
            "ani://anilist-auth/path#access_token=token",
            "ani://anilist-auth?next=evil#access_token=token",
            "ani://anilist-auth.evil/path#access_token=token",
        )
        lookalikes.forEach { assertNull(parseAniListOAuthRedirect(it), it) }
    }

    @Test
    fun rejectsMissingOrMalformedTokens() {
        assertNull(parseAniListOAuthRedirect("ani://anilist-auth"))
        assertNull(parseAniListOAuthRedirect("ani://anilist-auth#state=opaque"))
        assertNull(parseAniListOAuthRedirect("ani://anilist-auth#access_token="))
        assertNull(parseAniListOAuthRedirect("ani://anilist-auth#access_token=%ZZ"))
    }
}
