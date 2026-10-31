/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tracking

import android.content.Context
import me.him188.ani.app.data.tracking.TrackingBindingStore

class AndroidAniListBindingStore(
    context: Context,
    storageName: String = "anilist-bindings",
) : TrackingBindingStore {
    private val preferences = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "Could not save AniList title binding" }
    }

    override fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) { "Could not remove AniList title binding" }
    }

    override fun entries(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()

    override fun putAll(entries: Map<String, String>) {
        val editor = preferences.edit()
        entries.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "Could not restore AniList title bindings" }
    }
}
