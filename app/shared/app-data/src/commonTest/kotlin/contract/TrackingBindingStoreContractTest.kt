/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.data.tracking.contract

import me.him188.ani.app.data.tracking.TrackingBindingStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Map-like behavior every [TrackingBindingStore] must satisfy, shared by all platform test source sets.
 *
 * [newStore] must return a new instance on every call, all backed by the same storage, so the restart
 * check exercises persistence. Each test instance must start from empty storage.
 */
abstract class TrackingBindingStoreContractTest {
    abstract fun newStore(): TrackingBindingStore

    @Test
    fun getReturnsNullForMissingKey() {
        assertNull(newStore().get("missing"))
    }

    @Test
    fun putThenGetReturnsValueAndOverwritesSameKey() {
        val store = newStore()
        store.put("account:1", "first")
        assertEquals("first", store.get("account:1"))

        store.put("account:1", "second")
        assertEquals("second", store.get("account:1"))
    }

    @Test
    fun removeDeletesKeyAndIgnoresMissingKey() {
        val store = newStore()
        store.put("account:1", "a")
        store.put("account:2", "b")

        store.remove("account:1")
        store.remove("account:missing")

        assertNull(store.get("account:1"))
        assertEquals("b", store.get("account:2"))
    }

    @Test
    fun entriesReturnsSnapshotOfAllEntries() {
        val store = newStore()
        store.put("account:1", "a")

        val snapshot = store.entries()
        store.put("account:2", "b")
        store.remove("account:1")

        assertEquals(mapOf("account:1" to "a"), snapshot)
        assertEquals(mapOf("account:2" to "b"), store.entries())
    }

    @Test
    fun putAllOverwritesSameKeysAndKeepsOthers() {
        val store = newStore()
        store.put("account:1", "old")
        store.put("account:2", "kept")

        store.putAll(mapOf("account:1" to "new", "account:3" to "added"))

        assertEquals(
            mapOf("account:1" to "new", "account:2" to "kept", "account:3" to "added"),
            store.entries(),
        )
    }

    @Test
    fun valuesSurviveNewInstanceOverSameStorage() {
        newStore().put("account:1", "persisted")

        assertEquals("persisted", newStore().get("account:1"))
        assertEquals(mapOf("account:1" to "persisted"), newStore().entries())
    }
}
