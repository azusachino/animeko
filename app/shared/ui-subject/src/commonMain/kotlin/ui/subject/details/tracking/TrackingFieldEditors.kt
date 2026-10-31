/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.ui.lang.*
import me.him188.ani.tracking.api.TrackingDate
import me.him188.ani.tracking.api.TrackingEdit
import me.him188.ani.tracking.api.TrackingScore
import me.him188.ani.tracking.api.TrackingSnapshot
import me.him188.ani.tracking.api.TrackingStatus
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
internal fun TrackingStatus.displayLabel(): String = when (this) {
    TrackingStatus.PLANNING -> stringResource(Lang.tracking_status_planning)
    TrackingStatus.CURRENT -> stringResource(Lang.tracking_status_current)
    TrackingStatus.COMPLETED -> stringResource(Lang.tracking_status_completed)
    TrackingStatus.PAUSED -> stringResource(Lang.tracking_status_paused)
    TrackingStatus.DROPPED -> stringResource(Lang.tracking_status_dropped)
    TrackingStatus.REPEATING -> stringResource(Lang.tracking_status_repeating)
}

@Composable
internal fun TrackingChoiceDialog(card: TrackingCardModel, snapshot: TrackingSnapshot, field: Field,
    onDismiss: () -> Unit, onSelect: (TrackingEdit) -> Unit) {
    val entry = checkNotNull(snapshot.entry)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (field) {
                    Field.STATUS -> stringResource(Lang.tracking_dialog_title_status)
                    Field.PROGRESS -> stringResource(Lang.tracking_dialog_title_progress)
                    else -> stringResource(Lang.tracking_dialog_title_score)
                }, style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    when (field) {
                        Field.STATUS -> items(card.statusOptions) { option ->
                            TrackingChoice(option.status.displayLabel(), entry.status == option.status) { onSelect(TrackingEdit.Status(option.status)) }
                        }
                        Field.PROGRESS -> {
                            val episodes = snapshot.episodes
                            if (episodes != null) items(episodes) { episode ->
                                TrackingChoice(stringResource(Lang.tracking_choice_episode_format, episode.number), episode.watched) {
                                    onSelect(TrackingEdit.Episode(episode.id, !episode.watched))
                                }
                            } else items((0..(snapshot.media.totalEpisodes ?: maxOf(entry.progress + 20, 100))).toList()) { count ->
                                TrackingChoice(count.toString(), entry.progress == count) { onSelect(TrackingEdit.Progress(count)) }
                            }
                        }
                        Field.SCORE -> {
                            items((10 downTo 1).toList()) { score ->
                                TrackingChoice(score.toString(), (entry.score.value + 5) / 10 == score) {
                                    onSelect(TrackingEdit.Score(TrackingScore(score * 10)))
                                }
                            }
                            item { TrackingChoice(stringResource(Lang.tracking_choice_clear_score), entry.score == TrackingScore.Unrated) {
                                onSelect(TrackingEdit.Score(TrackingScore.Unrated))
                            } }
                        }
                        else -> Unit
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Lang.subject_collection_cancel)) }
            }
        }
    }
}

@Composable
private fun TrackingChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Text(label, Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Composable
internal fun TrackingDateEditor(title: String, date: TrackingDate?, onDismiss: () -> Unit, onSelect: (TrackingDate?) -> Unit) {
    val initial = remember(date) {
        runCatching { LocalDate(requireNotNull(date?.year), requireNotNull(date.month), requireNotNull(date.day))
            .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = {
            val selected = state.selectedDateMillis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
            onSelect(selected?.let { TrackingDate(it.year, it.monthNumber, it.dayOfMonth) })
        }) { Text(stringResource(Lang.tracking_action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Lang.subject_collection_cancel)) } }) { DatePicker(state) }
}
