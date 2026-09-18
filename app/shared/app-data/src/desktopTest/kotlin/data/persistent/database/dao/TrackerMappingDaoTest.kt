/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackerMappingDaoTest {
    private fun runDatabaseTest(block: suspend (AniDatabase) -> Unit) = runBlocking {
        val database = createTestAniDatabase()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun mapping(bangumiId: Int, site: String = "aniList", externalId: String = "12345") =
        TrackerMappingEntity(
            bangumiId = bangumiId,
            site = site,
            externalId = externalId,
            subscriptionId = "ani.builtin.tracker-mapping.bangumi-data",
        )

    @Test
    fun `TRK-04 再次upsert同一bangumiId加site更新externalId而不是报错`() = runDatabaseTest { database ->
        val dao = database.trackerMappingDao()
        dao.upsertMapping(mapping(400602, externalId = "111"))

        dao.upsertMapping(mapping(400602, externalId = "222"))

        assertEquals("222", dao.getMapping(400602, "aniList")?.externalId)
    }

    @Test
    fun `TRK-04 按site查询只返回该site的行`() = runDatabaseTest { database ->
        val dao = database.trackerMappingDao()
        dao.upsertMappings(
            listOf(
                mapping(400602, site = "aniList", externalId = "1"),
                mapping(400602, site = "mal", externalId = "2"),
                mapping(9, site = "aniList", externalId = "3"),
            ),
        )

        val aniListRows = dao.getMappingsBySite("aniList")
        assertEquals(setOf(400602, 9), aniListRows.map { it.bangumiId }.toSet())
        assertEquals(1, dao.getMappingsBySite("mal").size)
    }

    @Test
    fun `TRK-04 deleteStale只删除指定subscription加site中不在保留集合的行`() = runDatabaseTest { database ->
        val dao = database.trackerMappingDao()
        dao.upsertMappings(
            listOf(
                TrackerMappingEntity(400602, "aniList", "1", subscriptionId = "sub-a"),
                TrackerMappingEntity(9, "aniList", "2", subscriptionId = "sub-a"),
                TrackerMappingEntity(1, "aniList", "3", subscriptionId = "sub-b"), // 不同订阅, 不应被删除
            ),
        )

        dao.deleteStale("sub-a", site = "aniList", keepBangumiIds = listOf(400602))

        assertEquals("1", dao.getMapping(400602, "aniList")?.externalId)
        assertNull(dao.getMapping(9, "aniList"))
        assertEquals("3", dao.getMapping(1, "aniList")?.externalId) // sub-b 不受影响
    }

    @Test
    fun `TRK-04 deleteStale按site隔离, 同一bangumiId在其他site的行不受影响`() = runDatabaseTest { database ->
        val dao = database.trackerMappingDao()
        // 一次 bangumi-data 刷新同时覆盖多个 site: bangumiId=400602 在 aniList 和 mal 下都有映射,
        // 刷新后 mal 的映射消失 (远程不再提供), 但 aniList 的映射仍然有效.
        dao.upsertMappings(
            listOf(
                TrackerMappingEntity(400602, "aniList", "1", subscriptionId = "sub-a"),
                TrackerMappingEntity(400602, "mal", "2", subscriptionId = "sub-a"),
            ),
        )

        // mal 侧刷新结果中已经没有 400602 了
        dao.deleteStale("sub-a", site = "mal", keepBangumiIds = emptyList())

        assertNull(dao.getMapping(400602, "mal"))
        assertEquals("1", dao.getMapping(400602, "aniList")?.externalId) // aniList 不受影响
    }
}
