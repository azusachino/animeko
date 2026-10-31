/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.activity

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.app.tracking.anilist.AniListTrackingProvider
import me.him188.ani.app.tracking.anilist.parseAniListOAuthRedirect
import me.him188.ani.tracking.api.PendingLoginGate
import me.him188.ani.tracking.api.TrackingLoginCredentials

internal class AniListAuthRedirectHandler(
    private val provider: AniListTrackingProvider,
    private val pendingLogin: PendingLoginGate,
) : TrackingAuthRedirectHandler {
    override val host = "anilist-auth"
    override val providerName = "AniList"

    override fun handle(intent: Intent, scope: CoroutineScope, onResult: (Boolean) -> Unit): Boolean {
        val token = intent.data?.toString()?.let(::parseAniListOAuthRedirect) ?: return false

        intent.data = null
        if (!pendingLogin.consume()) return true
        scope.launch {
            val success = try {
                provider.login(TrackingLoginCredentials(secret = token))
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            onResult(success)
        }
        return true
    }
}
