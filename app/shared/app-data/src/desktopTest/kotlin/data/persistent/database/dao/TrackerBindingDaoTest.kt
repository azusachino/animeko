/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.sqlite.SQLiteException
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerBindingDaoTest {
    private fun runDatabaseTest(block: suspend (AniDatabase) -> Unit) = runBlocking {
        val database = createTestAniDatabase()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun subjectCollection(subjectId: Int) = SubjectCollectionEntity(
        subjectId = subjectId,
        name = "test",
        nameCn = "测试",
        summary = "",
        nsfw = false,
        imageLarge = "",
        totalEpisodes = 12,
        airDate = PackedDate.Invalid,
        aliases = emptyList(),
        tags = emptyList(),
        collectionStats = SubjectCollectionStats.Zero,
        ratingInfo = RatingInfo.Empty,
        completeDate = PackedDate.Invalid,
        selfRatingInfo = SelfRatingInfo.Empty,
        collectionType = UnifiedCollectionType.DOING,
        recurrence = null,
        lastUpdated = 0,
        lastFetched = 0,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )

    private fun binding(subjectId: Int, trackerId: String = "anilist") = TrackerBindingEntity(
        subjectId = subjectId,
        trackerId = trackerId,
        remoteMediaId = 12345,
        remoteTitle = "Sousou no Frieren",
        totalEpisodes = 28,
        lastTrackedEpisode = 0f,
        episodeOffset = 0,
        status = "CURRENT",
        score = null,
        updatedAtMillis = 0,
    )

    @Test
    fun `TRK-03 插入后可通过getBindingsForSubject读回`() = runDatabaseTest { database ->
        database.subjectCollection().upsert(subjectCollection(1))
        val dao = database.trackerBindingDao()

        dao.upsertBinding(binding(1))

        val bindings = dao.getBindingsForSubject(1)
        assertEquals(1, bindings.size)
        assertEquals("anilist", bindings.single().trackerId)
        assertEquals(binding(1), dao.getBinding(1, "anilist"))
    }

    @Test
    fun `TRK-03 subjectId不在收藏表时插入抛FK异常`() = runDatabaseTest { database ->
        val dao = database.trackerBindingDao()

        val exception = assertFailsWith<SQLiteException> {
            dao.upsertBinding(binding(42))
        }
        assertContains(exception.message.orEmpty(), "FOREIGN KEY")
    }

    @Test
    fun `TRK-03 删除收藏级联删除绑定`() = runDatabaseTest { database ->
        database.subjectCollection().upsert(subjectCollection(1))
        val dao = database.trackerBindingDao()
        dao.upsertBinding(binding(1))

        dao.bindingsForSubjectFlow(1).test {
            assertEquals(1, awaitItem().size)

            database.subjectCollection().delete(1)

            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(dao.getBinding(1, "anilist"))
    }

    @Test
    fun `TRK-03 同一subject可绑定多个tracker互不影响`() = runDatabaseTest { database ->
        database.subjectCollection().upsert(subjectCollection(1))
        val dao = database.trackerBindingDao()

        dao.upsertBinding(binding(1, trackerId = "anilist"))
        dao.upsertBinding(binding(1, trackerId = "myanimelist"))

        assertEquals(2, dao.getBindingsForSubject(1).size)

        dao.deleteBinding(1, "anilist")

        val remaining = dao.getBindingsForSubject(1)
        assertEquals(1, remaining.size)
        assertEquals("myanimelist", remaining.single().trackerId)
    }
}
