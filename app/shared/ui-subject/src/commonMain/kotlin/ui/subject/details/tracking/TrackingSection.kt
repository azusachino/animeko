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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.preferredDisplayName
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.LocalIsPreviewing
import me.him188.ani.app.ui.foundation.LocalSubjectAppearanceSettings
import me.him188.ani.app.ui.foundation.tracking.TrackingAccountRegistry
import me.him188.ani.app.ui.foundation.tracking.TrackingLoginAction
import me.him188.ani.app.ui.lang.*
import me.him188.ani.tracking.api.TrackingDateField
import me.him188.ani.tracking.api.TrackingEdit
import me.him188.ani.tracking.api.TrackingMedia
import me.him188.ani.tracking.api.TrackingProviderId
import me.him188.ani.tracking.api.TrackingStatus
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TrackingSection(subjectId: Int, modifier: Modifier = Modifier) {
    // Previews and UI tests run without Koin, which the tracking sources come from.
    if (LocalIsPreviewing.current) {
        TrackingActionButton(trackedCount = 0, onClick = {}, modifier = modifier)
        return
    }
    TrackingSectionContent(subjectId, modifier)
}

@Composable
private fun TrackingActionButton(trackedCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (trackedCount == 0) {
        Button(onClick = onClick, modifier = modifier.testTag("trackingAction")) {
            Icon(Icons.Outlined.Sync, contentDescription = null)
            Text(stringResource(Lang.tracking_action_track))
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.testTag("trackingAction")) {
            Icon(Icons.Outlined.Sync, contentDescription = null)
            Text(
                if (trackedCount == 1) stringResource(Lang.tracking_action_tracked_one, trackedCount)
                else stringResource(Lang.tracking_action_tracked_many, trackedCount)
            )
        }
    }
}

@Composable
private fun TrackingSectionContent(subjectId: Int, modifier: Modifier) {
    val coordinator = remember { GlobalKoin.get<TrackingCoordinator>() }
    val accountConnectors = remember { GlobalKoin.get<TrackingAccountRegistry>().connectors }
    val cards by remember(coordinator, subjectId) { coordinator.observe(subjectId) }
        .collectAsStateWithLifecycle(emptyList())
    val subjectInfo by remember(coordinator, subjectId) { coordinator.subjectInfo(subjectId) }
        .collectAsStateWithLifecycle(null as SubjectInfo?)
    val useOriginalTitle = LocalSubjectAppearanceSettings.current.useOriginalTitle
    val preferredTitle = subjectInfo?.preferredDisplayName(useOriginalTitle).orEmpty()
    val originalTitle = subjectInfo?.name.orEmpty()
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Pair<TrackingProviderId, Field>?>(null) }
    var searching by remember { mutableStateOf<TrackingProviderId?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TrackingMedia>>(emptyList()) }
    var searchRevision by remember { mutableStateOf(0) }
    var initialSearchTitle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<TrackingProviderId?>(null) }
    var error by remember { mutableStateOf<TrackingUiError?>(null) }
    var confirm by remember { mutableStateOf<Pair<TrackingProviderId, Boolean>?>(null) }
    var markAllPrompt by remember { mutableStateOf<TrackingProviderId?>(null) }
    val connectedCards = cards.filter { it.isConnected }
    val visibleCards = cards.filter { it.shouldShowInTrackingSheet }
    val trackedCount = connectedCards.count { it.isTracked }

    val updateFailedText = stringResource(Lang.tracking_error_update_failed)
    val searchFailedText = stringResource(Lang.tracking_error_search_failed)
    val addFailedText = stringResource(Lang.tracking_error_add_failed)
    val linkFailedText = stringResource(Lang.tracking_error_link_failed)
    val unlinkFailedText = stringResource(Lang.tracking_error_unlink_failed)
    val startDateTitle = stringResource(Lang.tracking_field_start_date)
    val finishDateTitle = stringResource(Lang.tracking_field_finish_date)

    fun runEdit(id: TrackingProviderId, edit: TrackingEdit, onSuccess: () -> Unit = {}) {
        scope.launch {
            busy = id
            error = null
            try { coordinator.edit(subjectId, id, edit); onSuccess() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = trackingUiError(failure, updateFailedText) }
            finally { busy = null }
        }
    }

    fun runSearch() {
        val id = searching ?: return
        if (query.isBlank()) return
        val requestedQuery = query.trim()
        val requestedRevision = searchRevision
        val shouldFallback = requestedQuery == initialSearchTitle && originalTitle.isNotBlank() && originalTitle != initialSearchTitle
        scope.launch {
            busy = id
            error = null
            try {
                val found = coordinator.search(id, requestedQuery)
                if (requestedRevision != searchRevision || searching != id) return@launch
                val matches = if (found.isEmpty() && shouldFallback) {
                    coordinator.search(id, originalTitle)
                } else found
                if (requestedRevision == searchRevision && searching == id) results = matches
            }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (requestedRevision == searchRevision && searching == id) error = trackingUiError(failure, searchFailedText)
            }
            finally { busy = null }
        }
    }

    TrackingActionButton(trackedCount, onClick = { showSheet = true }, modifier = modifier)

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Lang.tracking_sheet_title), style = MaterialTheme.typography.titleLarge)
                visibleCards.forEach { card ->
                    TrackingCard(card, busy == card.providerId,
                        onField = { editor = card.providerId to it },
                        onSearch = {
                            searchRevision++
                            searching = card.providerId
                            query = preferredTitle.ifBlank { (card.load as? TrackingLoad.Ready)?.snapshot?.media?.title.orEmpty() }
                            initialSearchTitle = query
                            results = emptyList()
                            error = null
                            runSearch()
                        },
                        onRefresh = { coordinator.refresh(subjectId) },
                        onAdd = { mediaId ->
                            scope.launch {
                                busy = card.providerId
                                try { coordinator.bind(subjectId, card.providerId, mediaId); error = null }
                                catch (failure: CancellationException) { throw failure }
                                catch (failure: Exception) { error = trackingUiError(failure, addFailedText) }
                                finally { busy = null }
                            }
                        },
                        onOpen = { uriHandler.openUri(it) },
                        onUnlink = { confirm = card.providerId to false },
                        onDelete = { confirm = card.providerId to true },
                        onPrivacy = { runEdit(card.providerId, TrackingEdit.Privacy(it)) },
                    )
                }
                if (visibleCards.isEmpty()) {
                    Text(stringResource(Lang.tracking_no_accounts_connected))
                }
                accountConnectors
                    .filter { connector -> connectedCards.none { it.providerId == connector.providerId } }
                    .forEach { connector ->
                        TrackingSignInRow(connector) { action ->
                            showSheet = false
                            when (action) {
                                is TrackingLoginAction.Browser -> uriHandler.openUri(action.url)
                                is TrackingLoginAction.OAuth -> navigator.navigateOAuthAuthorize(action.platform.id)
                            }
                        }
                    }
                error?.let { TrackingErrorMessage(it) }
            }
        }
    }

    searching?.let { id ->
        TrackingSearchDialog(
            providerId = id,
            query = query,
            results = results,
            busy = busy,
            error = error,
            onDismiss = { searchRevision++; searching = null },
            onQueryChange = { searchRevision++; query = it; results = emptyList(); error = null },
            onSearch = { runSearch() },
            onPick = { mediaId ->
                scope.launch {
                    busy = id
                    try { coordinator.bind(subjectId, id, mediaId); searching = null; error = null }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { error = trackingUiError(failure, linkFailedText) }
                    finally { busy = null }
                }
            },
        )
    }

    editor?.let { (id, field) ->
        val card = cards.firstOrNull { it.providerId == id }
        val snapshot = (card?.load as? TrackingLoad.Ready)?.snapshot
        val entry = snapshot?.entry
        if (card != null && snapshot != null && entry != null) {
            if (field == Field.START_DATE || field == Field.FINISH_DATE) {
                val dateField = if (field == Field.START_DATE) TrackingDateField.STARTED else TrackingDateField.COMPLETED
                TrackingDateEditor(if (field == Field.START_DATE) startDateTitle else finishDateTitle,
                    if (field == Field.START_DATE) entry.startedAt else entry.completedAt,
                    onDismiss = { editor = null }, onSelect = { date ->
                        editor = null
                        runEdit(id, TrackingEdit.Date(dateField, date))
                    })
            } else {
                TrackingChoiceDialog(card, snapshot, field, onDismiss = { editor = null }) { edit ->
                    editor = null
                    runEdit(id, edit) {
                        if (edit == TrackingEdit.Status(TrackingStatus.COMPLETED) && snapshot.episodes?.any { !it.watched } == true) {
                            markAllPrompt = id
                        }
                    }
                }
            }
        } else editor = null
    }

    confirm?.let { (id, deleteRemote) ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = {
                Text(
                    if (deleteRemote) stringResource(Lang.tracking_confirm_delete_title)
                    else stringResource(Lang.tracking_confirm_unlink_title)
                )
            },
            text = {
                Text(
                    if (deleteRemote) stringResource(Lang.tracking_confirm_delete_message)
                    else stringResource(Lang.tracking_confirm_unlink_message)
                )
            },
            confirmButton = { TextButton(onClick = {
                confirm = null
                if (deleteRemote) runEdit(id, TrackingEdit.DeleteRemoteEntry)
                else scope.launch {
                    try { coordinator.unlink(subjectId, id); error = null }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { error = trackingUiError(failure, unlinkFailedText) }
                }
            }) {
                Text(
                    if (deleteRemote) stringResource(Lang.tracking_action_delete)
                    else stringResource(Lang.tracking_menu_unlink)
                )
            } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(Lang.subject_collection_cancel)) } },
        )
    }

    markAllPrompt?.let { id ->
        AlertDialog(onDismissRequest = { markAllPrompt = null },
            title = { Text(stringResource(Lang.tracking_confirm_mark_all_title)) },
            text = { Text(stringResource(Lang.tracking_confirm_mark_all_message)) },
            confirmButton = { TextButton(onClick = {
                markAllPrompt = null
                runEdit(id, TrackingEdit.MarkAllEpisodesWatched)
            }) { Text(stringResource(Lang.tracking_confirm_mark_all_action)) } },
            dismissButton = { TextButton(onClick = { markAllPrompt = null }) { Text(stringResource(Lang.tracking_confirm_keep_episodes_action)) } },
        )
    }
}
