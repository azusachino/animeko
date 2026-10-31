/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tracking

import kotlinx.coroutines.flow.map
import me.him188.ani.app.tracking.anilist.AniListTrackingProvider
import me.him188.ani.app.ui.foundation.tracking.DisconnectableTrackingAccount
import me.him188.ani.app.ui.foundation.tracking.TrackingAccountConnector
import me.him188.ani.app.ui.foundation.tracking.TrackingLoginAction
import me.him188.ani.app.ui.foundation.tracking.toViewState
import me.him188.ani.tracking.api.PendingLoginGate
import me.him188.ani.tracking.api.TrackingProviderException

class AniListTrackingAccountConnector(
    private val provider: AniListTrackingProvider,
    private val pendingLogin: PendingLoginGate,
) : TrackingAccountConnector, DisconnectableTrackingAccount {
    override val providerId = AniListTrackingProvider.ID
    override val displayName = "AniList"
    override val loginAction: TrackingLoginAction
        get() {
            pendingLogin.begin()
            return TrackingLoginAction.Browser(AniListTrackingProvider.AUTHORIZE_URL)
        }
    override val state = provider.accountState.map { it.toViewState() }

    override suspend fun refresh() {
        try {
            provider.refreshAccount()
        } catch (_: TrackingProviderException.Unauthorized) {
            // A disconnected account is already represented by the provider state.
        }
    }

    override suspend fun disconnect() {
        provider.logout()
    }
}
