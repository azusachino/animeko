/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TRK-00: [UnifiedCollectionType] <-> AniList `MediaListStatus` 映射表, 覆盖每一个枚举值.
 */
class AniListStatusMappingTest {

    @Test
    fun `TRK-00 每个UnifiedCollectionType都有确定的AniList状态或null`() {
        assertEquals("PLANNING", UnifiedCollectionType.WISH.toAniListStatus())
        assertEquals("CURRENT", UnifiedCollectionType.DOING.toAniListStatus())
        assertEquals("COMPLETED", UnifiedCollectionType.DONE.toAniListStatus())
        assertEquals("PAUSED", UnifiedCollectionType.ON_HOLD.toAniListStatus())
        assertEquals("DROPPED", UnifiedCollectionType.DROPPED.toAniListStatus())
        assertNull(UnifiedCollectionType.NOT_COLLECTED.toAniListStatus())
    }

    @Test
    fun `TRK-00 toAniListStatus的每个非null结果都能转换回原始值`() {
        for (type in UnifiedCollectionType.entries) {
            val mapped = type.toAniListStatus() ?: continue
            assertEquals(type, mapped.toUnifiedCollectionTypeFromAniList())
        }
    }

    @Test
    fun `TRK-00 未知的AniList状态字符串转换为null`() {
        assertNull("SOME_FUTURE_STATUS".toUnifiedCollectionTypeFromAniList())
    }
}
