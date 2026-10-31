/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracking.anilist

import io.ktor.http.decodeURLQueryComponent

/** Extracts an AniList implicit-flow token only from the registered custom-scheme redirect. */
fun parseAniListOAuthRedirect(url: String): String? {
    val fragmentStart = url.indexOf('#')
    if (fragmentStart < 0 || url.substring(0, fragmentStart) != REDIRECT_URI) return null

    val fragment = url.substring(fragmentStart + 1)
    for (field in fragment.split('&')) {
        val separator = field.indexOf('=')
        if (separator < 0) continue
        val name = runCatching { field.substring(0, separator).decodeURLQueryComponent() }.getOrNull()
        if (name != "access_token") continue
        return runCatching { field.substring(separator + 1).decodeURLQueryComponent() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }
    return null
}

private const val REDIRECT_URI = "ani://anilist-auth"
