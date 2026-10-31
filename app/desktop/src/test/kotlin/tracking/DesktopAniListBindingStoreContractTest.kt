/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.desktop.tracking

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import me.him188.ani.app.data.tracking.TrackingBindingStore
import me.him188.ani.app.data.tracking.contract.TrackingBindingStoreContractTest

class DesktopAniListBindingStoreContractTest : TrackingBindingStoreContractTest() {
    // Each test instance gets its own Preferences node, removed after the test.
    private val nodeName = "contract-test-${UUID.randomUUID()}"
    private val nodePath = "me/him188/ani/tracking/$nodeName"

    override fun newStore(): TrackingBindingStore = DesktopAniListBindingStore(nodeName)

    @AfterTest
    fun removeNode() {
        val root = Preferences.userRoot()
        if (root.nodeExists(nodePath)) root.node(nodePath).removeNode()
    }
}
