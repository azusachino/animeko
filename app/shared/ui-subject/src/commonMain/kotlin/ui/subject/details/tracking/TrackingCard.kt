/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.tracking

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.icons.TrackingIconRegistry
import me.him188.ani.app.ui.lang.*
import me.him188.ani.tracking.api.TrackingDate
import me.him188.ani.tracking.api.TrackingMediaId
import org.jetbrains.compose.resources.stringResource

internal enum class Field { STATUS, PROGRESS, SCORE, START_DATE, FINISH_DATE }

@Composable
internal fun TrackingCard(
    card: TrackingCardModel,
    updating: Boolean,
    onField: (Field) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: (TrackingMediaId) -> Unit,
    onOpen: (String) -> Unit,
    onUnlink: () -> Unit,
    onDelete: () -> Unit,
    onPrivacy: (Boolean) -> Unit,
) {
    val snapshot = (card.load as? TrackingLoad.Ready)?.snapshot
    val entry = snapshot?.entry
    var menuOpen by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val icon = remember(card.providerId) {
                    GlobalKoin.get<TrackingIconRegistry>().find(card.providerId)
                }
                if (icon != null) icon.Icon() else Text(card.providerName.take(1), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f)) {
                    Text(card.providerName, style = MaterialTheme.typography.titleMedium)
                    Text(snapshot?.media?.title ?: if (card.capabilities.needsMatchSearch) stringResource(Lang.tracking_card_not_matched) else stringResource(Lang.tracking_card_collection),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (card.load is TrackingLoad.Loading || updating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(Lang.tracking_options_menu_description)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        snapshot?.media?.siteUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            DropdownMenuItem(text = { Text(stringResource(Lang.tracking_menu_open_on_provider, card.providerName)) }, onClick = { menuOpen = false; onOpen(url) })
                        }
                        DropdownMenuItem(text = { Text(stringResource(Lang.tracking_menu_refresh)) }, onClick = { menuOpen = false; onRefresh() })
                        if (card.capabilities.needsMatchSearch) {
                            DropdownMenuItem(text = { Text(stringResource(Lang.tracking_menu_change_match)) }, onClick = { menuOpen = false; onSearch() })
                            if (snapshot != null) DropdownMenuItem(text = { Text(stringResource(Lang.tracking_menu_unlink)) }, onClick = { menuOpen = false; onUnlink() })
                        }
                        if (entry != null && card.capabilities.canEditPrivacy) {
                            DropdownMenuItem(text = { Text(if (entry.isPrivate) stringResource(Lang.tracking_menu_make_public) else stringResource(Lang.tracking_menu_make_private)) },
                                onClick = { menuOpen = false; onPrivacy(!entry.isPrivate) })
                        }
                        if (entry != null && card.capabilities.canDeleteRemoteEntry) {
                            DropdownMenuItem(text = { Text(stringResource(Lang.tracking_menu_delete_remote)) }, onClick = { menuOpen = false; onDelete() })
                        }
                    }
                }
            }
            when {
                card.load is TrackingLoad.RateLimited -> TrackingErrorMessage(
                    TrackingUiError.RateLimited(card.load.retryAfterSeconds),
                    onRetry = onRefresh,
                )
                card.load is TrackingLoad.Failed ->
                    Text(stringResource(Lang.tracking_error_load_failed), color = MaterialTheme.colorScheme.error)
                (snapshot == null && card.capabilities.needsMatchSearch) || entry == null ->
                    TextButton(onClick = { snapshot?.media?.id?.let(onAdd) ?: onSearch() }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(stringResource(Lang.tracking_card_add_tracking))
                    }
                else -> {
                    Row(Modifier.fillMaxWidth()) {
                        if (card.capabilities.canEditStatus) TrackingFieldCell(
                            entry.status?.displayLabel() ?: stringResource(Lang.tracking_field_status),
                            Modifier.weight(1f), !updating) { onField(Field.STATUS) }
                        if (card.capabilities.canEditProgress) TrackingFieldCell(
                            "${entry.progress}${snapshot.media.totalEpisodes?.let { " / $it" } ?: ""}",
                            Modifier.weight(1f), !updating) { onField(Field.PROGRESS) }
                        if (card.capabilities.canEditScore) TrackingFieldCell(
                            if (entry.score.value == 0) stringResource(Lang.tracking_field_score) else "${(entry.score.value + 5) / 10} / 10",
                            Modifier.weight(1f), !updating) { onField(Field.SCORE) }
                    }
                    if (card.capabilities.canEditDates) Row(Modifier.fillMaxWidth()) {
                        TrackingFieldCell(entry.startedAt.dateLabel(stringResource(Lang.tracking_field_start_date)), Modifier.weight(1f), !updating) { onField(Field.START_DATE) }
                        TrackingFieldCell(entry.completedAt.dateLabel(stringResource(Lang.tracking_field_finish_date)), Modifier.weight(1f), !updating) { onField(Field.FINISH_DATE) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingFieldCell(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier.height(48.dp).clickable(enabled = enabled, onClick = onClick)) {
        Text(label, Modifier.align(Alignment.Center), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
    }
}

private fun TrackingDate?.dateLabel(placeholder: String): String =
    this?.let { listOfNotNull(year?.toString(), month?.toString()?.padStart(2, '0'), day?.toString()?.padStart(2, '0'))
        .joinToString("-").ifEmpty { placeholder } } ?: placeholder
