/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.state

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.app.data.persistent.database.dao.TrackerBindingDao
import me.him188.ani.app.data.persistent.database.dao.TrackerBindingEntity
import me.him188.ani.app.data.persistent.database.dao.createMemoryTrackerBindingDao
import me.him188.ani.app.tools.MonoTasker
import me.him188.ani.app.tracker.api.TrackerAccount
import me.him188.ani.app.tracker.api.TrackerBinding
import me.him188.ani.app.tracker.api.TrackerEntry
import me.him188.ani.app.tracker.api.TrackerMediaSummary
import me.him188.ani.app.tracker.api.TrackerService
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.annotations.TestOnly
import me.him188.ani.utils.platform.currentTimeMillis

/**
 * 驱动条目详情页的 tracker 绑定芯片 ([me.him188.ani.app.ui.subject.details.components.TrackerBindingChip])
 * 与手动搜索绑定 sheet ([me.him188.ani.app.ui.subject.details.sections.TrackerBindingBottomSheet]) 的共享状态,
 * 与 [me.him188.ani.app.ui.subject.collection.components.EditableSubjectCollectionTypeState] 一样,
 * 单个状态对象同时承载"按钮显示"和"弹出内容交互"两部分.
 */
@Stable
class TrackerBindingState(
    bindingFlow: Flow<TrackerBindingEntity?>,
    private val trackerService: TrackerService,
    private val bindingDao: TrackerBindingDao,
    private val subjectId: Int,
    initialQuery: String,
    private val backgroundScope: CoroutineScope,
    private val trackerId: String = "anilist",
) {
    data class Presentation(
        val binding: TrackerBindingEntity? = null,
        val query: String = "",
        val isAuthorized: Boolean? = null, // null: 尚未检查
        val isSearching: Boolean = false,
        val results: List<TrackerMediaSummary> = emptyList(),
        val selected: TrackerMediaSummary? = null,
        val episodeOffset: Int = 0,
        val isConfirming: Boolean = false,
        val searchFailed: Boolean = false,
    ) {
        val isBound get() = binding != null
    }

    private val searchTasker = MonoTasker(backgroundScope)
    private val confirmTasker = MonoTasker(backgroundScope)

    private val mutablePresentation = MutableStateFlow(Presentation(query = initialQuery))

    val presentationFlow: StateFlow<Presentation> = mutablePresentation.asStateFlow()

    init {
        backgroundScope.launch {
            bindingFlow.collect { binding ->
                mutablePresentation.update { it.copy(binding = binding) }
            }
        }
    }

    fun updateQuery(query: String) {
        mutablePresentation.update { it.copy(query = query) }
    }

    /**
     * 在 sheet 展示时调用一次, 提前检查是否已登录 AniList, 避免用户对着一个注定失败的搜索框操作.
     */
    suspend fun checkAuthorized() {
        val authorized = trackerService.isAuthorized()
        mutablePresentation.update { it.copy(isAuthorized = authorized) }
    }

    fun search() {
        val keyword = mutablePresentation.value.query
        searchTasker.launch {
            mutablePresentation.update { it.copy(isSearching = true, searchFailed = false) }
            if (!trackerService.isAuthorized()) {
                mutablePresentation.update {
                    it.copy(isSearching = false, isAuthorized = false, results = emptyList())
                }
                return@launch
            }
            try {
                val results = trackerService.search(keyword)
                mutablePresentation.update {
                    it.copy(isSearching = false, isAuthorized = true, results = results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutablePresentation.update { it.copy(isSearching = false, searchFailed = true) }
            }
        }
    }

    fun selectResult(result: TrackerMediaSummary) {
        mutablePresentation.update {
            it.copy(selected = result, episodeOffset = it.binding?.episodeOffset ?: 0)
        }
    }

    fun updateEpisodeOffset(offset: Int) {
        mutablePresentation.update { it.copy(episodeOffset = offset) }
    }

    fun confirm() {
        val presentation = mutablePresentation.value
        val selected = presentation.selected ?: return
        confirmTasker.launch {
            mutablePresentation.update { it.copy(isConfirming = true) }
            bindingDao.upsertBinding(
                TrackerBindingEntity(
                    subjectId = subjectId,
                    trackerId = trackerId,
                    remoteMediaId = selected.remoteMediaId,
                    remoteTitle = selected.title,
                    totalEpisodes = selected.totalEpisodes,
                    lastTrackedEpisode = presentation.binding?.lastTrackedEpisode ?: 0f,
                    episodeOffset = presentation.episodeOffset,
                    status = presentation.binding?.status,
                    score = presentation.binding?.score,
                    updatedAtMillis = currentTimeMillis(),
                ),
            )
            mutablePresentation.update { it.copy(isConfirming = false, selected = null) }
        }
    }

    fun unbind() {
        backgroundScope.launch {
            bindingDao.deleteBinding(subjectId, trackerId)
            mutablePresentation.update { it.copy(selected = null) }
        }
    }
}

@TestOnly
private class NoopTrackerService : TrackerService {
    override val id: String = "anilist"
    override val displayName: String = "AniList"
    override fun accountFlow(): Flow<TrackerAccount?> = flowOf(null)
    override suspend fun isAuthorized(): Boolean = false
    override suspend fun logout() {}
    override suspend fun search(keyword: String): List<TrackerMediaSummary> = emptyList()
    override suspend fun fetchEntry(remoteMediaId: Int): TrackerEntry? = null
    override suspend fun syncProgress(binding: TrackerBinding, episodeNumber: Float): Result<Unit> =
        Result.success(Unit)

    override suspend fun syncCollectionStatus(
        binding: TrackerBinding,
        status: UnifiedCollectionType,
        score: Float?,
    ): Result<Unit> = Result.success(Unit)
}

@TestOnly
fun createTestTrackerBindingState(
    backgroundScope: CoroutineScope,
    subjectId: Int = 0,
    initialQuery: String = "",
): TrackerBindingState = TrackerBindingState(
    bindingFlow = flowOf(null),
    trackerService = NoopTrackerService(),
    bindingDao = createMemoryTrackerBindingDao(),
    subjectId = subjectId,
    initialQuery = initialQuery,
    backgroundScope = backgroundScope,
)
