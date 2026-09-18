/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tracker

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.tracker.anilist.AniListLoginState
import me.him188.ani.app.domain.tracker.anilist.isAniListTokenSyntacticallyValid
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.navigation.rememberAsyncBrowserNavigator
import me.him188.ani.app.ui.foundation.rememberAsyncHandler
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_tracker_anilist_connect
import me.him188.ani.app.ui.lang.settings_tracker_anilist_disconnect
import me.him188.ani.app.ui.lang.settings_tracker_anilist_login_failed
import me.him188.ani.app.ui.lang.settings_tracker_anilist_not_connected
import me.him188.ani.app.ui.lang.settings_tracker_anilist_paste_token_hint
import me.him188.ani.app.ui.lang.settings_tracker_anilist_paste_token_title
import me.him188.ani.app.ui.lang.settings_tracker_anilist_validating
import me.him188.ani.app.ui.lang.settings_tracker_group_title
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import me.him188.ani.app.ui.settings.framework.components.TextItem
import org.jetbrains.compose.resources.stringResource

/**
 * Settings 里的 "Trackers" 卡片: 目前只有 AniList 一行. 与 [me.him188.ani.app.ui.settings.account.ProfileGroup]
 * 里 Bangumi 所在的 "第三方账号" 分组是不同的卡片 -- AniList 是进度同步的 tracker, 不是登录账号,
 * 语义上不应该和 Bangumi 混在一起.
 */
@Composable
fun SettingsScope.TrackerGroup(
    vm: AniListTrackerViewModel = viewModel<AniListTrackerViewModel> { AniListTrackerViewModel() },
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle(initialValue = AniListTrackerUiState.Initial)
    val asyncHandler = rememberAsyncHandler()
    val scope = rememberCoroutineScope()
    val browserNavigator = rememberAsyncBrowserNavigator()
    val context = LocalContext.current

    TrackerGroupImpl(
        state = state,
        onClickConnect = {
            scope.launch { browserNavigator.openBrowser(context, ANILIST_AUTHORIZE_URL) }
            vm.startLogin()
        },
        onSubmitToken = { token -> asyncHandler.launch { vm.submitToken(token) } },
        onLogout = { asyncHandler.launch { vm.logout() } },
        modifier = modifier,
    )
}

@Composable
internal fun SettingsScope.TrackerGroupImpl(
    state: AniListTrackerUiState,
    onClickConnect: () -> Unit,
    onSubmitToken: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loginState = state.loginState
    val isAwaitingToken = state.account == null &&
            (loginState is AniListLoginState.AwaitingPasteToken ||
                    loginState is AniListLoginState.Validating ||
                    loginState is AniListLoginState.Failed)

    Group(title = { Text(stringResource(Lang.settings_tracker_group_title)) }, modifier = modifier) {
        TextItem(
            title = { Text("AniList") },
            description = { Text(state.account?.username ?: stringResource(Lang.settings_tracker_anilist_not_connected)) },
            action = {
                if (state.account != null) {
                    TextButton(onClick = onLogout) {
                        Text(stringResource(Lang.settings_tracker_anilist_disconnect))
                    }
                } else if (loginState is AniListLoginState.Idle) {
                    TextButton(onClick = onClickConnect) {
                        Text(stringResource(Lang.settings_tracker_anilist_connect))
                    }
                }
            },
        )

        if (isAwaitingToken) {
            TextFieldItem(
                value = "",
                title = { Text(stringResource(Lang.settings_tracker_anilist_paste_token_title)) },
                description = {
                    when (loginState) {
                        is AniListLoginState.Validating -> Text(stringResource(Lang.settings_tracker_anilist_validating))
                        is AniListLoginState.Failed -> Text(stringResource(Lang.settings_tracker_anilist_login_failed))
                        else -> Text(stringResource(Lang.settings_tracker_anilist_paste_token_hint))
                    }
                },
                onValueChangeCompleted = onSubmitToken,
                isErrorProvider = { !isAniListTokenSyntacticallyValid(it) },
                sanitizeValue = { it },
            )
        }
    }
}
