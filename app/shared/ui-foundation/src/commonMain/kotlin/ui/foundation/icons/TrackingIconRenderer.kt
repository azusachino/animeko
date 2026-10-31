/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.icons

import androidx.compose.runtime.Composable
import me.him188.ani.tracking.api.TrackingProviderId

/** A provider contributes its own brand icon to every shared tracking surface. */
interface TrackingIconRenderer {
    val providerId: TrackingProviderId

    @Composable
    fun Icon()
}

class TrackingIconRegistry(renderers: List<TrackingIconRenderer>) {
    private val byProvider = renderers.associateBy { it.providerId }.also {
        require(it.size == renderers.size) { "Duplicate tracking icon provider ID" }
    }

    fun find(providerId: TrackingProviderId): TrackingIconRenderer? = byProvider[providerId]
}
