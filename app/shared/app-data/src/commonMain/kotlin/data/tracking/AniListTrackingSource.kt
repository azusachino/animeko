/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.data.tracking

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.tracking.api.TrackingAccount
import me.him188.ani.tracking.api.TrackingAccountState
import me.him188.ani.tracking.api.TrackingBindingRecord
import me.him188.ani.tracking.api.TrackingDateField
import me.him188.ani.tracking.api.TrackingEdit
import me.him188.ani.tracking.api.TrackingListEntry
import me.him188.ani.tracking.api.TrackingMediaId
import me.him188.ani.tracking.api.TrackingProvider
import me.him188.ani.tracking.api.TrackingProviderException
import me.him188.ani.tracking.api.TrackingSnapshot
import me.him188.ani.tracking.api.TrackingSource
import me.him188.ani.tracking.api.TrackingSourceCapabilities
import me.him188.ani.tracking.api.TrackingStatus
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.thisLogger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** AniList's account-scoped remote list, with local title matches kept in the existing preferences. */
class AniListTrackingSource(
    private val bindings: TrackingBindingStore,
    private val provider: TrackingProvider,
    private val episodes: Lazy<EpisodeCollectionRepository>,
    private val snapshotTtl: Duration = 60.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : TrackingSource {
    private val snapshotsLock = SynchronizedObject()
    private val snapshots = mutableMapOf<Int, CachedSnapshot>()
    private val bindingsVersion = MutableStateFlow(0)

    /** Subject IDs bound under any account; guards episode sync so unbound subjects never reach the network. Guarded by [snapshotsLock]. */
    private val boundSubjects: MutableSet<Int> by lazy {
        bindings.entries().keys.mapNotNullTo(mutableSetOf()) { it.substringAfterLast(':').toIntOrNull() }
    }
    private val episodeSyncMutex = Mutex()
    private val accountRefreshMutex = Mutex()
    private val logger = thisLogger()

    override val info = provider.info
    override val connection: Flow<TrackingAccountState> = provider.accountState
    override val capabilities = TrackingSourceCapabilities(
        needsMatchSearch = true,
        canEditDates = provider.capabilities.supportsStartAndCompletionDates,
        canEditPrivacy = provider.capabilities.supportsPrivateEntries,
        canDeleteRemoteEntry = true,
    )
    override val statusOptions get() = provider.statusOptions
    override val scoreOptions get() = provider.scoreOptions

    override fun observe(subjectId: Int): Flow<TrackingSnapshot?> = flow {
        val current = snapshotState(subjectId, provider.accountState.value.cacheAccountId())
        coroutineScope {
            launch {
                combine(provider.accountState, bindingsVersion) { accountState, _ -> accountState }
                    .collectLatest { accountState ->
                        try {
                            when (accountState) {
                                is TrackingAccountState.LoggedIn -> {
                                    if (!current.isFresh(accountState.account.remoteId)) {
                                        current.store(accountState.account.remoteId, loadSnapshot(subjectId, accountState.account))
                                    }
                                }
                                is TrackingAccountState.Refreshing -> Unit
                                TrackingAccountState.LoggedOut -> current.store(null, null)
                            }
                        } catch (_: TrackingProviderException.Unauthorized) {
                            logger.warn("[AniList tracking] load snapshot failed: unauthorized")
                            current.store(null, null)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            logFailure("load snapshot", failure)
                            throw failure
                        }
                    }
            }
            launch {
                try {
                    currentAccount()
                } catch (_: TrackingProviderException.Unauthorized) {
                    logger.warn("[AniList tracking] account refresh failed: unauthorized")
                    current.store(null, null)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    logFailure("account refresh", failure)
                    throw failure
                }
            }
            current.state.collect { emit(it) }
        }
    }

    override suspend fun search(query: String) = try {
        provider.search(query)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        logFailure("search", failure)
        throw failure
    }

    override suspend fun bind(subjectId: Int, mediaId: TrackingMediaId): TrackingSnapshot = try {
        val account = currentAccount()
        val candidate = provider.prepareBinding(mediaId)
            ?: throw TrackingProviderException.Remote("AniList title is unavailable")
        val entry = candidate.listEntry ?: provider.bind(
            TrackingListEntry(candidate.media.id, TrackingStatus.PLANNING, progress = 0),
        )
        bindings.put(bindingKey(account.remoteId, subjectId), candidate.media.id.value)
        synchronized(snapshotsLock) { boundSubjects.add(subjectId) }
        TrackingSnapshot(candidate.media, entry).also { publish(subjectId, account.remoteId, it) }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        logFailure("bind", failure)
        throw failure
    }

    private fun logFailure(operation: String, failure: Exception) {
        // Log only safe categories and server retry timing; never include raw response bodies or account data.
        val detail = when (failure) {
            is TrackingProviderException.RateLimited -> "rate limited; retry after ${failure.retryAfterMillis}ms"
            is TrackingProviderException.Unauthorized -> "unauthorized"
            is TrackingProviderException.Remote -> "remote failure"
            else -> failure::class.simpleName ?: "failure"
        }
        logger.warn("[AniList tracking] $operation failed: $detail")
    }

    override suspend fun edit(subjectId: Int, edit: TrackingEdit): TrackingSnapshot {
        val account = currentAccount()
        val current = loadSnapshot(subjectId, account)
            ?: throw TrackingProviderException.Remote("AniList title is not linked")
        val mediaId = current.media.id
        val existing = current.entry

        if (edit == TrackingEdit.DeleteRemoteEntry) {
            provider.delete(mediaId)
            val refreshed = provider.refresh(mediaId)
                ?: throw TrackingProviderException.Remote("AniList title is unavailable")
            return TrackingSnapshot(refreshed.media, refreshed.listEntry)
                .also { publish(subjectId, account.remoteId, it) }
        }

        if (edit is TrackingEdit.Episode || edit == TrackingEdit.MarkAllEpisodesWatched) {
            throw UnsupportedOperationException("AniList stores cumulative progress; use a progress edit")
        }

        val base = existing ?: TrackingListEntry(mediaId, TrackingStatus.PLANNING, progress = 0)
        val updated = when (edit) {
            is TrackingEdit.Status -> base.copy(status = edit.value)
            is TrackingEdit.Progress -> base.copy(progress = edit.value)
            is TrackingEdit.Score -> base.copy(score = edit.value)
            is TrackingEdit.Date -> base.copy(
                startedAt = if (edit.field == TrackingDateField.STARTED) edit.value else base.startedAt,
                completedAt = if (edit.field == TrackingDateField.COMPLETED) edit.value else base.completedAt,
            )
            is TrackingEdit.Privacy -> base.copy(isPrivate = edit.isPrivate)
            is TrackingEdit.Episode, TrackingEdit.MarkAllEpisodesWatched, TrackingEdit.DeleteRemoteEntry -> error("Handled above")
        }

        val saved = if (existing == null) provider.bind(updated) else when (edit) {
            is TrackingEdit.Date -> provider.updateDate(existing, edit.field, edit.value)
            is TrackingEdit.Privacy -> provider.updateVisibility(existing, edit.isPrivate)
            else -> provider.update(updated)
        }
        return TrackingSnapshot(current.media, saved).also { publish(subjectId, account.remoteId, it) }
    }

    override suspend fun unlink(subjectId: Int) {
        val account = (provider.accountState.value as? TrackingAccountState.LoggedIn)?.account
            ?: throw TrackingProviderException.Unauthorized()
        bindings.remove(bindingKey(account.remoteId, subjectId))
        synchronized(snapshotsLock) {
            // Another account may still bind this subject.
            if (bindings.entries().keys.none { it.endsWith(":$subjectId") }) boundSubjects.remove(subjectId)
        }
        publish(subjectId, account.remoteId, null)
    }

    override fun invalidate(subjectId: Int) = synchronized(snapshotsLock) {
        snapshots[subjectId]?.loadedAt = null
    }

    override suspend fun episodeWatched(subjectId: Int, episodeId: Int) {
        if (synchronized(snapshotsLock) { subjectId !in boundSubjects }) {
            logger.info { "[tracking-sync] skip: subject $subjectId is not bound" }
            return
        }
        episodeSyncMutex.withLock {
            val account = try {
                currentAccount()
            } catch (error: TrackingProviderException.Unauthorized) {
                // A deliberate sign-out is not a failure to retry; a rejected session is.
                if (provider.isSignedOutByUser()) {
                    logger.info { "[tracking-sync] skip: signed out of AniList" }
                    return
                }
                logger.info { "[tracking-sync] account unavailable (unauthorized) for subject $subjectId, will retry" }
                throw error
            }
            val mediaId = bindings.get(bindingKey(account.remoteId, subjectId))?.let(::TrackingMediaId)
            if (mediaId == null) {
                logger.info { "[tracking-sync] skip: no binding for subject $subjectId under the account" }
                return
            }
            val episode = episodes.value.episodeCollectionInfoFlow(subjectId, episodeId).first().episodeInfo
            if (episode.type != null && episode.type != EpisodeType.MainStory) {
                logger.info { "[tracking-sync] skip: episode type is ${episode.type}, not MainStory" }
                return
            }
            val number = episode.ep?.number
            if (number == null) {
                logger.info { "[tracking-sync] skip: episode has no ep number (ep=${episode.ep}, sort=${episode.sort})" }
                return
            }
            val progress = number.toInt()
            if (progress <= 0 || progress.toFloat() != number) {
                logger.info { "[tracking-sync] skip: ep is not a positive integer (ep=${episode.ep}, sort=${episode.sort})" }
                return
            }

            val current = provider.refresh(mediaId)
            if (current == null) {
                logger.info { "[tracking-sync] remote title refresh returned null for subject $subjectId, will retry" }
                throw TrackingProviderException.Remote("AniList title is unavailable")
            }
            val entry = current.listEntry
            if (entry == null) {
                logger.info { "[tracking-sync] skip: no remote list entry for subject $subjectId" }
                return
            }
            if (progress > entry.progress) {
                val saved = provider.update(
                    entry.copy(progress = progress),
                    didWatchEpisode = true,
                    totalEpisodes = current.media.totalEpisodes,
                )
                publish(subjectId, account.remoteId, TrackingSnapshot(current.media, saved))
                logger.info { "[tracking-sync] updated subject $subjectId to progress $progress" }
            } else {
                logger.info { "[tracking-sync] skip: progress $progress not greater than remote ${entry.progress} for subject $subjectId" }
            }
        }
    }

    override fun exportBindings(): List<TrackingBindingRecord> = bindings.entries().mapNotNull { (key, value) ->
        val separator = key.lastIndexOf(':')
        if (separator <= 0) return@mapNotNull null
        val accountId = key.take(separator)
        val subjectId = key.substring(separator + 1).toIntOrNull()
        val mediaId = value
        if (accountId.isBlank() || subjectId == null || subjectId <= 0 || mediaId.isNullOrBlank()) null
        else TrackingBindingRecord(info.id.value, accountId, subjectId, mediaId)
    }

    override suspend fun validateBindings(records: List<TrackingBindingRecord>) {
        validateBindingFields(records)
    }

    override fun applyValidatedBindings(records: List<TrackingBindingRecord>) {
        validateBindingFields(records)
        bindings.putAll(records.associate { bindingKey(it.accountId, it.subjectId) to it.mediaId })
        synchronized(snapshotsLock) {
            snapshots.values.forEach { it.loadedAt = null }
            boundSubjects.addAll(records.map { it.subjectId })
        }
        bindingsVersion.value += 1
    }

    override fun countUnconnectedBindings(records: List<TrackingBindingRecord>): Int {
        val connected = (provider.accountState.value as? TrackingAccountState.LoggedIn)?.account?.remoteId
        return records.count { it.accountId != connected }
    }

    private fun validateBindingFields(records: List<TrackingBindingRecord>) {
        require(records.all {
            it.providerId == info.id.value && it.accountId.isNotBlank() && it.subjectId > 0 && it.mediaId.isNotBlank()
        }) { "Invalid AniList binding backup" }
    }

    private suspend fun currentAccount(): TrackingAccount = accountRefreshMutex.withLock {
        when (val state = provider.accountState.value) {
            is TrackingAccountState.LoggedIn -> state.account
            is TrackingAccountState.Refreshing -> {
                val settled = provider.accountState.first { it !is TrackingAccountState.Refreshing }
                (settled as? TrackingAccountState.LoggedIn)?.account
                    ?: throw TrackingProviderException.Unauthorized()
            }
            TrackingAccountState.LoggedOut -> provider.refreshAccount()
        }
    }

    private suspend fun loadSnapshot(subjectId: Int, account: TrackingAccount): TrackingSnapshot? {
        val mediaId = bindings.get(bindingKey(account.remoteId, subjectId))
            ?.let(::TrackingMediaId) ?: return null
        val current = provider.refresh(mediaId)
            ?: throw TrackingProviderException.Remote("AniList title is unavailable")
        return TrackingSnapshot(current.media, current.listEntry)
    }

    /** Returns the subject's cache entry, dropping any value that was read under a different account. */
    private fun snapshotState(subjectId: Int, accountId: String?): CachedSnapshot = synchronized(snapshotsLock) {
        snapshots.getOrPut(subjectId) { CachedSnapshot() }.also { cached ->
            if (cached.accountId != accountId) {
                cached.accountId = accountId
                cached.loadedAt = null
                cached.state.value = null
            }
        }
    }

    private fun publish(subjectId: Int, accountId: String, snapshot: TrackingSnapshot?) =
        snapshotState(subjectId, accountId).store(accountId, snapshot)

    private fun CachedSnapshot.isFresh(accountId: String): Boolean = synchronized(snapshotsLock) {
        this.accountId == accountId && loadedAt?.let { it.elapsedNow() < snapshotTtl } == true
    }

    private fun CachedSnapshot.store(accountId: String?, snapshot: TrackingSnapshot?) = synchronized(snapshotsLock) {
        this.accountId = accountId
        loadedAt = timeSource.markNow()
        state.value = snapshot
    }

    private fun TrackingAccountState.cacheAccountId(): String? = when (this) {
        is TrackingAccountState.LoggedIn -> account.remoteId
        is TrackingAccountState.Refreshing -> previousAccount?.remoteId
        TrackingAccountState.LoggedOut -> null
    }

    private fun bindingKey(accountId: String, subjectId: Int) = "$accountId:$subjectId"

    /** One subject's remote read, valid only for the account it was read under and for [snapshotTtl]. */
    private class CachedSnapshot {
        val state = MutableStateFlow<TrackingSnapshot?>(null)
        var accountId: String? = null
        var loadedAt: TimeMark? = null
    }
}

/** Persists non-secret account and title matches; credentials remain in TrackingCredentialStore. */
interface TrackingBindingStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun entries(): Map<String, String>
    fun putAll(entries: Map<String, String>)
}
