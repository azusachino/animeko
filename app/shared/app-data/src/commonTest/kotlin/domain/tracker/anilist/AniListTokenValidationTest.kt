/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracker.anilist

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TRK-12: [isAniListTokenSyntacticallyValid] 是纯函数, 不需要 Fixture/网络就能单测.
 */
class AniListTokenValidationTest {

    @Test
    fun `TRK-12 空白输入无效`() {
        assertFalse(isAniListTokenSyntacticallyValid(""))
        assertFalse(isAniListTokenSyntacticallyValid("   "))
        assertFalse(isAniListTokenSyntacticallyValid("\n\t"))
    }

    @Test
    fun `TRK-12 非三段式JWT无效`() {
        assertFalse(isAniListTokenSyntacticallyValid("not-a-jwt"))
        assertFalse(isAniListTokenSyntacticallyValid("a.b"))
        assertFalse(isAniListTokenSyntacticallyValid("a.b.c.d"))
        assertFalse(isAniListTokenSyntacticallyValid("a..c"))
    }

    @Test
    fun `TRK-12 内部含空白的token无效`() {
        assertFalse(isAniListTokenSyntacticallyValid("aaa.b b.ccc"))
    }

    @Test
    fun `TRK-12 三段式且首尾去空白后合法`() {
        assertTrue(isAniListTokenSyntacticallyValid("aaa.bbb.ccc"))
        assertTrue(isAniListTokenSyntacticallyValid("  aaa.bbb.ccc  "))
    }
}
