/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.lang.*
import me.him188.ani.tracking.api.TrackingProviderException
import org.jetbrains.compose.resources.stringResource

internal sealed interface TrackingUiError {
    data class Failure(val message: String) : TrackingUiError
    data class RateLimited(val retryAfterSeconds: Int?) : TrackingUiError
}

internal fun trackingUiError(failure: Throwable, fallbackText: String): TrackingUiError =
    (failure as? TrackingProviderException.RateLimited)?.let {
        TrackingUiError.RateLimited(it.retryAfterMillis?.let(::retryAfterSeconds))
    } ?: TrackingUiError.Failure(fallbackText)

@Composable
internal fun TrackingErrorMessage(error: TrackingUiError, onRetry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when (error) {
                is TrackingUiError.Failure -> error.message
                is TrackingUiError.RateLimited -> error.retryAfterSeconds?.let {
                    stringResource(Lang.tracking_error_rate_limited, it)
                } ?: stringResource(Lang.tracking_error_rate_limited_unknown)
            },
            color = MaterialTheme.colorScheme.error,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(Lang.tracking_action_retry)) }
        }
    }
}
