/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackerAccountDaoTest {
    private fun runDatabaseTest(block: suspend (AniDatabase) -> Unit) = runBlocking {
        val database = createTestAniDatabase()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun account(trackerId: String = "anilist") = TrackerAccountEntity(
        trackerId = trackerId,
        userId = 1000,
        username = "haru",
        avatarUrl = null,
        accessToken = "token-abc",
        scoreFormat = "POINT_10_DECIMAL",
        updatedAtMillis = 0,
    )

    @Test
    fun `TRK-02 插入后可通过get和flow读回`() = runDatabaseTest { database ->
        val dao = database.trackerAccountDao()
        assertNull(dao.getAccount("anilist"))

        dao.upsertAccount(account())

        assertEquals("haru", dao.getAccount("anilist")?.username)
        assertEquals("haru", dao.accountFlow("anilist").first()?.username)
    }

    @Test
    fun `TRK-02 再次upsert覆盖原有账号`() = runDatabaseTest { database ->
        val dao = database.trackerAccountDao()
        dao.upsertAccount(account())

        dao.upsertAccount(account().copy(username = "haru2", accessToken = "token-xyz"))

        val result = dao.getAccount("anilist")
        assertEquals("haru2", result?.username)
        assertEquals("token-xyz", result?.accessToken)
    }

    @Test
    fun `TRK-02 delete后account不再存在`() = runDatabaseTest { database ->
        val dao = database.trackerAccountDao()
        dao.upsertAccount(account())

        dao.deleteAccount("anilist")

        assertNull(dao.getAccount("anilist"))
    }
}
