/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.icons.TrackingIconRegistry
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.tracking.TrackingAccountConnector
import me.him188.ani.app.ui.foundation.tracking.TrackingAccountStatus
import me.him188.ani.app.ui.foundation.tracking.TrackingLoginAction
import me.him188.ani.app.ui.lang.*
import me.him188.ani.tracking.api.TrackingMedia
import me.him188.ani.tracking.api.TrackingMediaId
import me.him188.ani.tracking.api.TrackingProviderId
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TrackingSearchDialog(
    providerId: TrackingProviderId,
    query: String,
    results: List<TrackingMedia>,
    busy: TrackingProviderId?,
    error: TrackingUiError?,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (TrackingMediaId) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Lang.tracking_search_dialog_title), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, onQueryChange,
                        modifier = Modifier.weight(1f), placeholder = { Text(stringResource(Lang.tracking_search_placeholder)) }, singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(Lang.tracking_search_clear_description))
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (busy == null) onSearch() }),
                    )
                    IconButton(onClick = onSearch, enabled = busy == null && query.isNotBlank()) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(Lang.tracking_search_icon_description))
                    }
                }
                if (busy == providerId) CircularProgressIndicator(Modifier.size(20.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(results, key = { it.id.value }) { media ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = busy == null) { onPick(media.id) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            media.coverImageUrl?.takeIf { it.isNotBlank() }?.let { cover ->
                                AsyncImage(cover, contentDescription = null, modifier = Modifier.width(44.dp).height(62.dp),
                                    contentScale = ContentScale.Crop)
                            }
                            Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                error?.let { TrackingErrorMessage(it) }
            }
        }
    }
}

/** Offers the provider's own sign-in so a guest never has to go through a generic Animeko login first. */
@Composable
internal fun TrackingSignInRow(connector: TrackingAccountConnector, onLogin: (TrackingLoginAction) -> Unit) {
    val state by connector.state.collectAsStateWithLifecycle(null)
    val current = state ?: return
    if (current.connected || current.refreshing) return
    val icon = remember(connector.providerId) {
        GlobalKoin.get<TrackingIconRegistry>().find(connector.providerId)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.Icon()
        TextButton(onClick = { onLogin(connector.loginAction) }) {
            Text(
                if (current.status == TrackingAccountStatus.SignInWithProvider) {
                    stringResource(Lang.settings_tracking_sign_in_with, connector.displayName)
                } else {
                    stringResource(Lang.tracking_connect_provider, connector.displayName)
                },
            )
        }
    }
}
