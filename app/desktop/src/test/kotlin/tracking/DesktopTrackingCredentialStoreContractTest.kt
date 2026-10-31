/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.desktop.tracking

import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.tracking.api.TrackingCredentialStore
import me.him188.ani.tracking.api.TrackingProviderId
import me.him188.ani.tracking.api.contract.TrackingCredentialStoreContractTest

class DesktopTrackingCredentialStoreContractTest : TrackingCredentialStoreContractTest() {
    private val dataStore = MemoryDataStore(emptyMap<String, String>())

    override fun newStore(): TrackingCredentialStore =
        DesktopTrackingCredentialStore(dataStore, TrackingProviderId("anilist"))
}
