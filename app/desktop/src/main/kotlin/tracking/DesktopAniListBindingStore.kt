/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.desktop.tracking

import java.util.prefs.Preferences
import me.him188.ani.app.data.tracking.TrackingBindingStore

/** Only non-secret account and title IDs are stored here. */
class DesktopAniListBindingStore(
    nodeName: String = "anilist-bindings",
) : TrackingBindingStore {
    private val preferences = Preferences.userRoot().node("me/him188/ani/tracking/$nodeName")

    override fun get(key: String): String? = preferences.get(key, null)

    override fun put(key: String, value: String) {
        preferences.put(key, value)
        preferences.flush()
    }

    override fun remove(key: String) {
        preferences.remove(key)
        preferences.flush()
    }

    override fun entries(): Map<String, String> = preferences.keys().associateWith { preferences.get(it, "") }

    override fun putAll(entries: Map<String, String>) {
        entries.forEach(preferences::put)
        preferences.flush()
    }
}
