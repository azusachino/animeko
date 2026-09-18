/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tracker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.him188.ani.app.domain.tracker.anilist.AniListLoginConfigurator
import me.him188.ani.app.domain.tracker.anilist.AniListLoginState
import me.him188.ani.app.domain.tracker.anilist.AniListTrackerService
import me.him188.ani.app.tracker.api.TrackerAccount
import me.him188.ani.app.ui.foundation.AbstractViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * "Trackers" 设置卡片里 AniList 那一行的 UI 状态: 已登录账号 (若有) + 登录流程当前所处的状态.
 */
data class AniListTrackerUiState(
    val account: TrackerAccount?,
    val loginState: AniListLoginState,
) {
    companion object {
        val Initial = AniListTrackerUiState(account = null, loginState = AniListLoginState.Idle)
    }
}

class AniListTrackerViewModel : AbstractViewModel(), KoinComponent {
    private val trackerService: AniListTrackerService by inject()
    private val loginConfigurator = AniListLoginConfigurator(trackerService)

    val state: Flow<AniListTrackerUiState> = combine(
        trackerService.accountFlow(),
        loginConfigurator.state,
    ) { account, loginState -> AniListTrackerUiState(account, loginState) }

    /**
     * 用户点击 "连接 AniList" 时调用, 与打开浏览器同时发生 (打开浏览器由调用方的 Composable 负责,
     * 因为需要 `LocalContext`/`browserNavigator`, 不属于 ViewModel 的职责).
     */
    fun startLogin() {
        loginConfigurator.startAwaitingPasteToken()
    }

    suspend fun submitToken(token: String) {
        loginConfigurator.submitToken(token)
    }

    fun cancelLogin() {
        loginConfigurator.reset()
    }

    suspend fun logout() {
        trackerService.logout()
        loginConfigurator.reset()
    }
}
