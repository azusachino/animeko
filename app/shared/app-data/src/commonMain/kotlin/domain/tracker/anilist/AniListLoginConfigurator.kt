/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracker.anilist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.app.tracker.api.TrackerAccount

/**
 * AniList PIN 登录流程的状态机, 镜像 [me.him188.ani.app.domain.session.auth.OAuthConfigurator] 的形状:
 * 一个纯粹的、可独立单测的状态持有者, 由上层 ViewModel 包装后供 Compose 观察.
 *
 * 流程: [Idle] -(用户点击连接, 打开浏览器)-> [AwaitingPasteToken] -(用户粘贴并确认)-> [Validating]
 * -(校验通过)-> [Success] / -(校验失败)-> [Failed].
 */
sealed interface AniListLoginState {
    data object Idle : AniListLoginState
    data object AwaitingPasteToken : AniListLoginState
    data object Validating : AniListLoginState
    data class Success(val account: TrackerAccount) : AniListLoginState
    data class Failed(val message: String) : AniListLoginState
}

/**
 * 一个语法上明显不是 AniList access token 的输入 (空白, 或不是三段式 JWT), 在花一次 API 调用去校验它
 * 之前就应该被拒绝.
 */
fun isAniListTokenSyntacticallyValid(token: String): Boolean {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it.isWhitespace() }) return false
    val segments = trimmed.split(".")
    return segments.size == 3 && segments.all { it.isNotEmpty() }
}

class AniListLoginConfigurator(
    private val trackerService: AniListTrackerService,
) {
    private val _state = MutableStateFlow<AniListLoginState>(AniListLoginState.Idle)
    val state: StateFlow<AniListLoginState> = _state

    /**
     * 用户点击 "连接 AniList" 后调用, 在打开浏览器的同时把状态切到等待粘贴 token.
     */
    fun startAwaitingPasteToken() {
        _state.value = AniListLoginState.AwaitingPasteToken
    }

    /**
     * 用户粘贴 token 并确认后调用. 语法上明显无效的 token 在调用 [AniListTrackerService.login]
     * (进而触发一次真实网络请求) 之前就被拒绝.
     */
    suspend fun submitToken(token: String): AniListLoginState {
        val trimmed = token.trim()
        if (!isAniListTokenSyntacticallyValid(trimmed)) {
            _state.value = AniListLoginState.Failed("Token 格式不正确")
            return _state.value
        }

        _state.value = AniListLoginState.Validating
        val result = trackerService.login(trimmed)
        _state.value = result.fold(
            onSuccess = { AniListLoginState.Success(it) },
            onFailure = { AniListLoginState.Failed(it.message ?: "登录失败, 请检查 token 是否正确") },
        )
        return _state.value
    }

    fun reset() {
        _state.value = AniListLoginState.Idle
    }
}
