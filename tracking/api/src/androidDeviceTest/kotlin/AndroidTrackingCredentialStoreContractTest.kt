/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.tracking.api

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.him188.ani.tracking.api.contract.TrackingCredentialStoreContractTest
import java.util.UUID
import kotlin.test.AfterTest

class AndroidTrackingCredentialStoreContractTest : TrackingCredentialStoreContractTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Each test instance gets its own provider, so its Keystore key and files are not shared.
    private val providerId = TrackingProviderId("contract-${UUID.randomUUID()}")

    override fun newStore(): TrackingCredentialStore = AndroidTrackingCredentialStore(context, providerId)

    @AfterTest
    fun removeStoredState() {
        runBlocking {
            val store = newStore()
            store.clear()
            store.setSignedOutByUser(false) // clear() keeps the signed-out marker by design
        }
    }
}
