/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracker.anilist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.persistent.database.dao.TrackerAccountDao
import me.him188.ani.app.data.persistent.database.dao.TrackerAccountEntity
import me.him188.ani.app.tracker.anilist.AniListApi
import me.him188.ani.app.tracker.anilist.isAniListTokenLikelyExpired
import me.him188.ani.app.tracker.anilist.toAniListStatus
import me.him188.ani.app.tracker.api.TrackerAccount
import me.him188.ani.app.tracker.api.TrackerBinding
import me.him188.ani.app.tracker.api.TrackerEntry
import me.him188.ani.app.tracker.api.TrackerMediaSummary
import me.him188.ani.app.tracker.api.TrackerService
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.currentTimeMillis

private const val ANILIST_TRACKER_ID = "anilist"

/**
 * [TrackerService] 的 AniList 实现. `accessToken` 只存在 [TrackerAccountEntity] 里, 不做额外缓存 --
 * PIN 登录本就不频繁, 没必要为省一次 DAO 查询而引入状态同步问题.
 */
class AniListTrackerService(
    private val api: AniListApi,
    private val accountDao: TrackerAccountDao,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) : TrackerService {
    override val id: String = ANILIST_TRACKER_ID
    override val displayName: String = "AniList"

    override fun accountFlow(): Flow<TrackerAccount?> =
        accountDao.accountFlow(id).map { it?.toTrackerAccount() }

    override suspend fun isAuthorized(): Boolean {
        val account = accountDao.getAccount(id) ?: return false
        return !isAniListTokenLikelyExpired(account.updatedAtMillis, nowMillis())
    }

    override suspend fun logout() {
        accountDao.deleteAccount(id)
    }

    override suspend fun search(keyword: String): List<TrackerMediaSummary> {
        val token = requireAccessToken()
        return api.searchAnime(keyword, token).map { media ->
            TrackerMediaSummary(
                remoteMediaId = media.id,
                title = media.title.english ?: media.title.romaji ?: media.title.native.orEmpty(),
                coverUrl = media.coverImage?.large,
                totalEpisodes = media.episodes,
            )
        }
    }

    override suspend fun fetchEntry(remoteMediaId: Int): TrackerEntry? {
        val token = requireAccessToken()
        val media = api.getMediaEntry(remoteMediaId, token) ?: return null
        return TrackerEntry(
            remoteMediaId = media.id,
            title = media.title.english ?: media.title.romaji ?: media.title.native.orEmpty(),
            totalEpisodes = media.episodes,
            progress = media.mediaListEntry?.progress?.toFloat(),
            status = media.mediaListEntry?.status,
            score = media.mediaListEntry?.score?.toFloat(),
        )
    }

    override suspend fun syncProgress(binding: TrackerBinding, episodeNumber: Float): Result<Unit> = runCatching {
        val token = requireAccessToken()
        api.saveMediaListEntry(
            mediaId = binding.remoteMediaId,
            status = null,
            score = null,
            progress = episodeNumber.toInt(),
            accessToken = token,
        )
    }

    override suspend fun syncCollectionStatus(
        binding: TrackerBinding,
        status: UnifiedCollectionType,
        score: Float?,
    ): Result<Unit> = runCatching {
        val aniListStatus = status.toAniListStatus() ?: return@runCatching
        val token = requireAccessToken()
        api.saveMediaListEntry(
            mediaId = binding.remoteMediaId,
            status = aniListStatus,
            score = score?.toDouble(),
            progress = null,
            accessToken = token,
        )
    }

    /**
     * AniList Auth PIN 登录: [pin] 是用户从 AniList 授权页复制粘贴过来的 access token, 不是 OAuth code.
     */
    suspend fun login(pin: String): Result<TrackerAccount> = runCatching {
        val viewer = api.viewerProfile(pin)
        val account = TrackerAccountEntity(
            trackerId = id,
            userId = viewer.id.toLong(),
            username = viewer.name,
            avatarUrl = viewer.avatar?.large,
            accessToken = pin,
            scoreFormat = viewer.mediaListOptions.scoreFormat,
            updatedAtMillis = nowMillis(),
        )
        accountDao.upsertAccount(account)
        account.toTrackerAccount()
    }

    private suspend fun requireAccessToken(): String {
        val account = accountDao.getAccount(id)
            ?: error("Not logged in to AniList")
        return account.accessToken
    }
}

private fun TrackerAccountEntity.toTrackerAccount() = TrackerAccount(
    trackerId = trackerId,
    userId = userId,
    username = username,
    avatarUrl = avatarUrl,
)
