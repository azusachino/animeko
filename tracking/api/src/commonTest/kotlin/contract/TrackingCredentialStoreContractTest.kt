/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.tracking.api.contract

import kotlinx.coroutines.test.runTest
import me.him188.ani.tracking.api.TrackingCredentialStore
import me.him188.ani.tracking.api.TrackingLoginCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavior every [TrackingCredentialStore] must satisfy, shared by all platform test source sets.
 *
 * [newStore] must return a new instance on every call, all backed by the same storage, so the restart
 * checks exercise persistence. Each test instance must start from empty storage.
 */
abstract class TrackingCredentialStoreContractTest {
    abstract fun newStore(): TrackingCredentialStore

    private val credentials = TrackingLoginCredentials(username = "haru", secret = "synthetic-secret")

    @Test
    fun loadReturnsNullWhenEmpty() = runTest {
        assertNull(newStore().load())
    }

    @Test
    fun saveThenLoadRoundTripsSecret() = runTest {
        newStore().save(credentials)
        assertEquals(credentials.secret, newStore().load()?.secret)
    }

    @Test
    fun saveOverwritesPreviousCredentials() = runTest {
        val store = newStore()
        store.save(credentials)
        store.save(TrackingLoginCredentials(username = "haru", secret = "second-secret"))
        assertEquals("second-secret", store.load()?.secret)
    }

    @Test
    fun clearRemovesCredentialsButKeepsSignedOutFlag() = runTest {
        val store = newStore()
        store.save(credentials)
        store.setSignedOutByUser(true)

        store.clear()

        assertNull(store.load())
        assertTrue(store.isSignedOutByUser())
    }

    @Test
    fun signedOutFlagDefaultsToFalseAndRoundTrips() = runTest {
        val store = newStore()
        assertFalse(store.isSignedOutByUser())

        store.setSignedOutByUser(true)
        assertTrue(store.isSignedOutByUser())

        store.setSignedOutByUser(false)
        assertFalse(store.isSignedOutByUser())
    }

    @Test
    fun credentialsAndSignedOutFlagSurviveNewInstance() = runTest {
        newStore().apply {
            save(credentials)
            setSignedOutByUser(true)
        }

        val restarted = newStore()

        assertEquals(credentials.secret, restarted.load()?.secret)
        assertTrue(restarted.isSignedOutByUser())
    }
}
