/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.anilist

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.utils.ktor.ScopedHttpClient

private const val ANILIST_GRAPHQL_ENDPOINT = "https://graphql.anilist.co"

/**
 * 手写的 AniList GraphQL 客户端. 只有 4 个固定操作, 不引入 Apollo Kotlin 等 GraphQL codegen 依赖.
 */
class AniListApi(
    private val client: ScopedHttpClient,
) {
    suspend fun viewerProfile(accessToken: String): AniListViewer {
        return execute<ViewerProfileData>(
            query = VIEWER_PROFILE_QUERY,
            accessToken = accessToken,
        ).viewer
    }

    suspend fun searchAnime(keyword: String, accessToken: String): List<AniListMedia> {
        return execute<SearchAnimeData>(
            query = SEARCH_ANIME_QUERY,
            variables = buildJsonObject { put("search", keyword) },
            accessToken = accessToken,
        ).page.media
    }

    suspend fun getMediaEntry(mediaId: Int, accessToken: String): AniListMediaWithEntry? {
        return execute<MediaEntryData>(
            query = GET_MEDIA_ENTRY_QUERY,
            variables = buildJsonObject { put("id", mediaId) },
            accessToken = accessToken,
        ).media
    }

    suspend fun saveMediaListEntry(
        mediaId: Int,
        status: String?,
        score: Double?,
        progress: Int?,
        accessToken: String,
    ): AniListMediaListEntry {
        return execute<SaveMediaListEntryData>(
            query = SAVE_MEDIA_LIST_ENTRY_MUTATION,
            variables = buildJsonObject {
                put("mediaId", mediaId)
                status?.let { put("status", it) }
                score?.let { put("score", it) }
                progress?.let { put("progress", it) }
            },
            accessToken = accessToken,
        ).entry
    }

    private suspend inline fun <reified T> execute(
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        accessToken: String,
    ): T {
        val envelope = client.use {
            post(ANILIST_GRAPHQL_ENDPOINT) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", "Animeko (https://github.com/open-ani/animeko)")
                setBody(GraphQLRequestBody(query, variables))
            }.body<GraphQLEnvelope<T>>()
        }
        val errors = envelope.errors
        if (!errors.isNullOrEmpty()) {
            throw AniListGraphQLException(errors.joinToString(separator = "; ") { it.message })
        }
        return envelope.data ?: throw AniListGraphQLException("AniList returned neither data nor errors for: $query")
    }

    private companion object {
        val VIEWER_PROFILE_QUERY = """
            query ViewerProfile {
              Viewer {
                id
                name
                avatar { large }
                mediaListOptions { scoreFormat }
              }
            }
        """.trimIndent()

        val SEARCH_ANIME_QUERY = """
            query SearchAnime(${'$'}search: String, ${'$'}page: Int = 1, ${'$'}perPage: Int = 10) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: ANIME) {
                  id
                  title { romaji english native }
                  coverImage { large }
                  episodes
                  status
                  format
                }
              }
            }
        """.trimIndent()

        val GET_MEDIA_ENTRY_QUERY = """
            query GetMediaEntry(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                title { romaji english native }
                coverImage { large }
                episodes
                status
                format
                mediaListEntry {
                  status
                  score(format: POINT_10_DECIMAL)
                  progress
                }
              }
            }
        """.trimIndent()

        val SAVE_MEDIA_LIST_ENTRY_MUTATION = """
            mutation SaveMediaListEntry(${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}score: Float, ${'$'}progress: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, score: ${'$'}score, progress: ${'$'}progress) {
                id
                mediaId
                status
                score
                progress
              }
            }
        """.trimIndent()
    }
}
