/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings

import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsScreenBackTest {
    @Test
    fun directOpenOnTabInSinglePaneDetailPopsAppRoute() {
        assertTrue(
            shouldPopAppRouteOnBack(
                openedDirectlyOnTab = true,
                isSinglePane = true,
                currentRole = ListDetailPaneScaffoldRole.Detail,
            ),
        )
    }

    @Test
    fun listPaneDoesNotInterceptBack() {
        // Back from the list pane is already handled by the app route, so no override is needed.
        assertFalse(
            shouldPopAppRouteOnBack(
                openedDirectlyOnTab = false,
                isSinglePane = true,
                currentRole = ListDetailPaneScaffoldRole.List,
            ),
        )
    }

    @Test
    fun navigatedToAnotherTabThenBackReturnsToListInSettings() {
        // openedDirectlyOnTab is cleared by navigateToTab, so back on Detail stays inside Settings.
        assertFalse(
            shouldPopAppRouteOnBack(
                openedDirectlyOnTab = false,
                isSinglePane = true,
                currentRole = ListDetailPaneScaffoldRole.Detail,
            ),
        )
    }

    @Test
    fun noInitialTabKeepsInSettingsBackUnchanged() {
        assertFalse(
            shouldPopAppRouteOnBack(
                openedDirectlyOnTab = false,
                isSinglePane = true,
                currentRole = ListDetailPaneScaffoldRole.Detail,
            ),
        )
    }

    @Test
    fun twoPaneLayoutIsUnchanged() {
        assertFalse(
            shouldPopAppRouteOnBack(
                openedDirectlyOnTab = true,
                isSinglePane = false,
                currentRole = ListDetailPaneScaffoldRole.Detail,
            ),
        )
    }
}
