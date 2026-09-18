/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class GraphQLRequestBody(
    val query: String,
    val variables: JsonObject,
)

@Serializable
data class GraphQLError(val message: String)

@Serializable
internal data class GraphQLEnvelope<T>(
    val data: T? = null,
    val errors: List<GraphQLError>? = null,
)

class AniListGraphQLException(message: String) : Exception(message)

@Serializable
internal data class ViewerProfileData(@SerialName("Viewer") val viewer: AniListViewer)

@Serializable
data class AniListViewer(
    val id: Int,
    val name: String,
    val avatar: AniListAvatar? = null,
    val mediaListOptions: AniListMediaListOptions,
)

@Serializable
data class AniListAvatar(val large: String? = null)

@Serializable
data class AniListMediaListOptions(val scoreFormat: String)

@Serializable
internal data class SearchAnimeData(@SerialName("Page") val page: AniListPage)

@Serializable
data class AniListPage(val media: List<AniListMedia>)

@Serializable
data class AniListMedia(
    val id: Int,
    val title: AniListMediaTitle,
    val coverImage: AniListCoverImage? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val format: String? = null,
)

@Serializable
data class AniListMediaTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AniListCoverImage(val large: String? = null)

@Serializable
internal data class SaveMediaListEntryData(@SerialName("SaveMediaListEntry") val entry: AniListMediaListEntry)

@Serializable
data class AniListMediaListEntry(
    val id: Int,
    val mediaId: Int,
    val status: String? = null,
    val score: Double? = null,
    val progress: Int? = null,
)
