/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tracking

import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.domain.session.auth.OAuthPlatform
import me.him188.ani.tracking.api.TrackingAccountState
import me.him188.ani.tracking.api.TrackingProviderId

sealed interface TrackingAccountStatus {
    data class Account(val name: String) : TrackingAccountStatus
    data object Checking : TrackingAccountStatus
    data object Connecting : TrackingAccountStatus
    data object NotConnected : TrackingAccountStatus
    /** Connecting also signs in to Animeko when there is no Animeko session. */
    data object SignInWithProvider : TrackingAccountStatus
}

data class TrackingAccountViewState(
    val status: TrackingAccountStatus,
    val connected: Boolean,
    val refreshing: Boolean = false,
)

fun TrackingAccountState.toViewState(): TrackingAccountViewState = when (this) {
    is TrackingAccountState.LoggedIn -> TrackingAccountViewState(
        TrackingAccountStatus.Account(account.displayName), connected = true,
    )
    is TrackingAccountState.Refreshing -> TrackingAccountViewState(
        previousAccount?.let { TrackingAccountStatus.Account(it.displayName) } ?: TrackingAccountStatus.Connecting,
        connected = previousAccount != null,
        refreshing = true,
    )
    TrackingAccountState.LoggedOut -> TrackingAccountViewState(TrackingAccountStatus.NotConnected, connected = false)
}

sealed interface TrackingLoginAction {
    data class Browser(val url: String) : TrackingLoginAction
    data class OAuth(val platform: OAuthPlatform) : TrackingLoginAction
}

/** A service the user can connect; settings rows and the tracking sheet share it. */
interface TrackingAccountConnector {
    val providerId: TrackingProviderId
    val displayName: String
    val state: Flow<TrackingAccountViewState>
    val loginAction: TrackingLoginAction

    suspend fun refresh() {}
}

/**
 * A connection this device can remove by itself, without changing any Animeko account.
 * Links held by the Animeko account (Bangumi) are managed from the profile instead, because unlinking
 * them affects every device and can leave a later Bangumi sign-in creating a separate Animeko account.
 */
interface DisconnectableTrackingAccount {
    suspend fun disconnect()
}

class TrackingAccountRegistry(connectors: List<TrackingAccountConnector>) {
    val connectors = connectors.also {
        require(it.map(TrackingAccountConnector::providerId).distinct().size == it.size) {
            "Duplicate tracking account provider ID"
        }
    }
}
