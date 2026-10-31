/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.desktop.tracking

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAniListRedirectTest {
    @Test
    fun acceptsOnlyAniListCallbackToken() {
        assertEquals("synthetic-token", aniListTokenFromRedirect(URI("ani://anilist-auth#access_token=synthetic-token&token_type=Bearer")))
        assertNull(aniListTokenFromRedirect(URI("ani://other-host#access_token=synthetic-token")))
        assertNull(aniListTokenFromRedirect(URI("ani://anilist-auth#token_type=Bearer")))
    }
}
