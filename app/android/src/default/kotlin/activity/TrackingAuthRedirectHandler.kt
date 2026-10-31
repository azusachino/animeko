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

internal interface TrackingAuthRedirectHandler {
    val host: String
    val providerName: String

    fun handle(intent: Intent, onResult: (Boolean) -> Unit): Boolean
}

internal class TrackingAuthRedirectRouter(handlers: List<TrackingAuthRedirectHandler>) {
    private val byHost = handlers.associateBy { it.host }.also {
        require(it.size == handlers.size) { "Duplicate tracking auth redirect host" }
    }

    fun handlerFor(host: String?): TrackingAuthRedirectHandler? = byHost[host]
}
