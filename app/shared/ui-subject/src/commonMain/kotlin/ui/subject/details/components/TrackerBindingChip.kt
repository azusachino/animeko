/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.components

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_tracker_bind_anilist
import me.him188.ani.app.ui.lang.subject_details_tracker_bound_anilist
import me.him188.ani.app.ui.lang.subject_details_tracker_bound_anilist_unknown_total
import me.him188.ani.app.ui.subject.details.state.TrackerBindingState
import org.jetbrains.compose.resources.stringResource

/**
 * 条目详情页的 tracker 绑定芯片: 未绑定时显示 "+ 绑定 AniList", 已绑定时显示 "AniList: 12/24".
 * 两种状态点击都打开 [me.him188.ani.app.ui.subject.details.sections.TrackerBindingBottomSheet]
 * (已绑定时允许重新绑定/修改集数偏移), 与 Mihon 的约定一致.
 */
@Composable
fun TrackerBindingChip(
    state: TrackerBindingState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    val binding = presentation.binding

    OutlinedButton(onClick = onClick, modifier = modifier) {
        val totalEpisodes = binding?.totalEpisodes
        Text(
            if (binding == null) {
                stringResource(Lang.subject_details_tracker_bind_anilist)
            } else if (totalEpisodes != null) {
                stringResource(
                    Lang.subject_details_tracker_bound_anilist,
                    binding.lastTrackedEpisode.toInt(),
                    totalEpisodes,
                )
            } else {
                stringResource(
                    Lang.subject_details_tracker_bound_anilist_unknown_total,
                    binding.lastTrackedEpisode.toInt(),
                )
            },
        )
    }
}
