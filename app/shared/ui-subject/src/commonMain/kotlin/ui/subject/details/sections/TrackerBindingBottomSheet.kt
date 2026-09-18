/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.tracker.api.TrackerMediaSummary
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.layout.desktopTitleBar
import me.him188.ani.app.ui.foundation.layout.desktopTitleBarPadding
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_tracker_confirm
import me.him188.ani.app.ui.lang.subject_details_tracker_episode_offset
import me.him188.ani.app.ui.lang.subject_details_tracker_no_results
import me.him188.ani.app.ui.lang.subject_details_tracker_not_authorized
import me.him188.ani.app.ui.lang.subject_details_tracker_search
import me.him188.ani.app.ui.lang.subject_details_tracker_search_failed
import me.him188.ani.app.ui.lang.subject_details_tracker_search_hint
import me.him188.ani.app.ui.lang.subject_details_tracker_sheet_title
import me.him188.ani.app.ui.lang.subject_details_tracker_unbind
import me.him188.ani.app.ui.subject.details.state.TrackerBindingState
import org.jetbrains.compose.resources.stringResource

/**
 * Mihon 风格的手动搜索绑定 sheet: 预填条目标题的搜索框, 结果列表, 选中后展示集数偏移输入框和确认按钮.
 * 已绑定时额外提供"解除绑定"入口. AniList 未登录时不发起搜索, 只引导用户去设置页登录.
 */
@Composable
fun TrackerBindingBottomSheet(
    state: TrackerBindingState,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        state.checkAuthorized()
    }

    ModalBottomSheet(
        onDismissRequest,
        modifier = modifier.desktopTitleBarPadding().statusBarsPadding(),
        contentWindowInsets = {
            BottomSheetDefaults.windowInsets
                .add(WindowInsets.desktopTitleBar())
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
        },
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Lang.subject_details_tracker_sheet_title), style = MaterialTheme.typography.titleLarge)

            if (presentation.isAuthorized == false) {
                Text(
                    stringResource(Lang.subject_details_tracker_not_authorized),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = presentation.query,
                    onValueChange = state::updateQuery,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(Lang.subject_details_tracker_search_hint)) },
                    singleLine = true,
                )
                IconButton(onClick = state::search, enabled = !presentation.isSearching) {
                    if (presentation.isSearching) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(Lang.subject_details_tracker_search))
                    }
                }
            }

            if (presentation.isBound) {
                OutlinedButton(onClick = state::unbind) {
                    Text(stringResource(Lang.subject_details_tracker_unbind))
                }
            }

            if (presentation.searchFailed) {
                Text(
                    stringResource(Lang.subject_details_tracker_search_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (presentation.results.isEmpty() && !presentation.isSearching) {
                Text(
                    stringResource(Lang.subject_details_tracker_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(presentation.results) { result ->
                    TrackerSearchResultRow(
                        result,
                        selected = presentation.selected == result,
                        onClick = { state.selectResult(result) },
                    )
                }
            }

            presentation.selected?.let {
                EpisodeOffsetAndConfirm(
                    episodeOffset = presentation.episodeOffset,
                    isConfirming = presentation.isConfirming,
                    onOffsetChange = state::updateEpisodeOffset,
                    onConfirm = state::confirm,
                )
            }
        }
    }
}

@Composable
private fun TrackerSearchResultRow(
    result: TrackerMediaSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            result.coverUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
        Column {
            Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                result.totalEpisodes?.toString() ?: "?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EpisodeOffsetAndConfirm(
    episodeOffset: Int,
    isConfirming: Boolean,
    onOffsetChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = episodeOffset.toString(),
            onValueChange = { text -> text.toIntOrNull()?.let(onOffsetChange) },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(Lang.subject_details_tracker_episode_offset)) },
            singleLine = true,
        )
        Button(onClick = onConfirm, enabled = !isConfirming) {
            Text(stringResource(Lang.subject_details_tracker_confirm))
        }
    }
}
