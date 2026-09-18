# Implementation Plan: AniList (Mihon-Style Tracker) Integration

> **Issue Reference**: [open-ani/animeko#3427](https://github.com/open-ani/animeko/issues/3427)
> **Status**: Ready for implementation
> **See also**: [`PLAN.md`](../../../PLAN.md) at repo root for the PR-level roadmap and what changed from the first draft.

## 1. Why sliced this way

Three PRs, each independently reviewable and each leaving the app in a working state:

- **PR 1** has no user-visible behavior at all, but is the highest-risk-of-regret part (DB schema, module
  boundaries) and is the easiest to review in isolation because it's 100% unit-testable — no real AniList account,
  no browser, no device needed to verify it.
- **PR 2** is the feature: without it PR 1 is dead code. It's also the one slice that unit tests alone cannot
  fully verify (an OAuth PIN round-trip and a real AniList write need a human once), so it gets its own manual
  smoke-test pass before merge.
- **PR 3** is additive on top of a fully working PR 2 — manual bind already works without it, so it can land
  later, separately, without blocking use of the feature.

Every implementation step below is TDD-first: write the failing test named, watch it fail for the right reason,
then write the minimum code to pass it. Test IDs (`TRK-NN`) follow this repo's own convention, e.g.
`AniDatabaseMigrationTest`'s `MIG-01`.

## 2. Module layout

New Gradle modules, mirroring the existing `datasource:api` / `datasource:bangumi` split (`settings.gradle.kts`):

```
includeProject(":tracker:api", "tracker/api")         // TrackerService interface, shared models
includeProject(":tracker:anilist", "tracker/anilist")  // AniList GraphQL client + TrackerService impl
```

`tracker:api` has no AniList-specific code in it — it's the seam a future `tracker:myanimelist` or
`tracker:kitsu` module would implement against, without touching `app-data` or `tracker:anilist`.

## 3. PR 1 — module scaffold, DB schema, AniList API client

### 3.1 `tracker:api` (no tests needed — pure interface/data-class scaffolding)

```kotlin
// tracker/api/src/commonMain/kotlin/TrackerService.kt
interface TrackerService {
    val id: String // "anilist"
    val displayName: String

    fun accountFlow(): Flow<TrackerAccount?>
    suspend fun isAuthorized(): Boolean
    suspend fun logout()

    suspend fun search(keyword: String): List<TrackerMediaSummary>
    suspend fun fetchEntry(remoteMediaId: Int): TrackerEntry?

    suspend fun syncProgress(binding: TrackerBinding, episodeNumber: Float): Result<Unit>
    suspend fun syncCollectionStatus(binding: TrackerBinding, status: UnifiedCollectionType, score: Float?): Result<Unit>
}

fun UnifiedCollectionType.toAniListStatus(): String? = when (this) {
    UnifiedCollectionType.WISH -> "PLANNING"
    UnifiedCollectionType.DOING -> "CURRENT"
    UnifiedCollectionType.DONE -> "COMPLETED"
    UnifiedCollectionType.ON_HOLD -> "PAUSED"
    UnifiedCollectionType.DROPPED -> "DROPPED"
    UnifiedCollectionType.NOT_COLLECTED -> null
}
```

- [ ] **TRK-00**: `UnifiedCollectionType.toAniListStatus()` / its inverse round-trip for every enum value including
  `NOT_COLLECTED -> null -> (no-op, never synced)`. Pure function, trivial table test, but write it before the
  hooks in PR 2 depend on it — it's the one piece of PR 1 with real branching logic.

### 3.2 Room schema (`app/shared/app-data/.../data/persistent/database/AniDatabase.kt`)

```kotlin
@Entity(tableName = "tracker_account")
data class TrackerAccountEntity(
    @PrimaryKey val trackerId: String,     // "anilist"
    val userId: Long,
    val username: String,
    val avatarUrl: String?,
    val accessToken: String,
    val scoreFormat: String,               // from AniList's own mediaListOptions.scoreFormat, per-account
)

@Entity(
    tableName = "tracker_binding",
    primaryKeys = ["subjectId", "trackerId"],
    foreignKeys = [ForeignKey(
        entity = SubjectCollectionEntity::class,
        parentColumns = ["subjectId"], childColumns = ["subjectId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TrackerBindingEntity(
    val subjectId: Int,
    val trackerId: String,
    val remoteMediaId: Int,
    val remoteTitle: String,
    val totalEpisodes: Int?,
    val lastTrackedEpisode: Float = 0f,
    val episodeOffset: Int = 0,
    val status: String? = null,
    val score: Float? = null,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "tracker_mapping",
    primaryKeys = ["bangumiId", "site"],
    indices = [Index(value = ["site", "externalId"])],
)
data class TrackerMappingEntity(
    val bangumiId: Int,
    val site: String,       // "aniList", "mal", ... matches bangumi-data's own site keys
    val externalId: String,
    val subscriptionId: String,
)
```

Version bump: `version = 22` → `23`, `AutoMigration(from = 22, to = 23, spec = Migrations.Migration_22_23::class)`
where `Migration_22_23` is an **empty** `AutoMigrationSpec` marker class — this is a purely additive change (three
new tables, no column changes to existing ones), which is the cheapest case Room's `AutoMigration` supports.
Export the new schema JSON (`app/shared/app-data/schemas/.../23.json`) via the project's existing schema-export
Gradle task, same as every prior version bump.

- [ ] **TRK-01** (`AniDatabaseMigrationTest`-style, `desktopTest`, using `MigrationTestHelper` exactly like
  `MIG-01`): build a v22 database, run the migration to v23, assert `tracker_account`, `tracker_binding`,
  `tracker_mapping` all exist with their expected columns, and assert a pre-existing `subject_collection` row
  survives the migration untouched.
- [ ] **TRK-02**: `TrackerAccountDao` insert-then-get round-trip (`commonTest`, in-memory Room DB).
- [ ] **TRK-03**: `TrackerBindingDao` — insert, `getBindingsForSubject`, and cascade-delete when the parent
  `SubjectCollectionEntity` row is removed (verifies the `ForeignKey.CASCADE`).
- [ ] **TRK-04**: `TrackerMappingDao` — upsert semantics (re-inserting the same `(bangumiId, site)` pair updates
  `externalId` rather than erroring), and a query by `site` returns only that site's rows.

### 3.3 Rate limiting

Ktor has no first-party equivalent to OkHttp's interceptor chain (what Mihon uses:
`.rateLimit(permits = 25, period = 1.minutes)`), so this is new, small, generic infrastructure — written once
in `tracker:anilist` but shaped so a future tracker module could reuse it:

```kotlin
// tracker/anilist/src/commonMain/kotlin/AniListRateLimitPlugin.kt
val AniListRateLimit = createClientPlugin("AniListRateLimit") {
    val semaphore = Semaphore(permits = 25)
    val window = 1.minutes
    // token-bucket refill on a background loop, or a sliding-window deque like Mihon's RateLimitInterceptor —
    // pick whichever this repo's existing coroutine-utils (utils/coroutines) already has a primitive for; don't
    // add a new dependency for this.
    onRequest { _, _ -> semaphore.acquireAndScheduleRelease(window) }
}
```

- [ ] **TRK-05**: using Ktor's `MockEngine`, fire 26 requests in a tight loop against a client installed with
  `AniListRateLimit`; assert the 26th request's completion is delayed until inside the next window, and the
  first 25 are not delayed. This is the one test in this plan that's about *timing*, not just data — use a
  virtual/test clock (`kotlinx-coroutines-test`'s `TestScope`/`runTest`), not real `delay()`, so it doesn't
  make the test suite slow.

### 3.4 AniList GraphQL client

Hand-rolled `HttpClient.post("https://graphql.anilist.co") { setBody(GraphQLRequest(query, variables)) }` — no
Apollo Kotlin or other GraphQL codegen library. Justification: exactly four fixed operations (`ViewerProfile`,
`SearchAnime`, `GetMediaEntry`, `SaveMediaListEntry`), and this codebase has zero existing GraphQL precedent to
amortize a codegen toolchain's setup cost against.

- [ ] **TRK-06**: `ViewerProfile` query — `MockEngine` returns a canned success JSON, assert the parsed
  `AniListViewer` matches, and assert the request carries `Authorization: Bearer <token>`.
- [ ] **TRK-07**: `SearchAnime` query — canned multi-result JSON parses into the right number of
  `TrackerMediaSummary`, titles/episode counts/format map correctly.
- [ ] **TRK-08**: `SaveMediaListEntry` mutation — request body contains exactly `mediaId`, `status`, `score`,
  `progress` (no extra fields), and a non-2xx / GraphQL `errors[]` response surfaces as a typed failure, not a
  silently-swallowed one.
- [ ] **TRK-09**: token-expiry short-circuit — given a `TrackerAccountEntity` whose `updatedAtMillis` is more
  than 365 days old (mirroring Mihon's `ALOAuth.isExpired()` assumption, since AniList's implicit-grant response
  carries no real `expires_in`), `AniListTrackerService.isAuthorized()` returns `false` **without** making a
  network call — assert on the `MockEngine`'s captured-request count being zero.

## 4. PR 2 — login and bind-and-sync

### 4.1 AniList PIN-flow login

No redirect handling anywhere. AniList app registration's redirect URL is set once (in AniList's own developer
settings, not in animeko's code) to `https://anilist.co/api/v2/oauth/pin`. Flow:

1. User taps "连接 AniList" → `browserNavigator.openBrowser(context, "https://anilist.co/api/v2/oauth/authorize?client_id=$ID&response_type=token")` (same call the existing Bangumi login already uses).
2. AniList shows the user a token on its own page; user copies it.
3. User pastes it into a text field in animeko; app calls `ViewerProfile` (TRK-06's client) to validate it and read `mediaListOptions.scoreFormat`, then persists `TrackerAccountEntity`.

- [ ] **TRK-11**: login state machine (`Idle -> AwaitingPasteToken -> Validating -> Success`), pure unit test, no
  real network (uses TRK-06's mocked client).
- [ ] **TRK-12**: pasting a syntactically-invalid token (empty, whitespace, obviously-not-a-JWT) is rejected
  client-side before ever calling `ViewerProfile` — don't spend an API call validating garbage input.
- [ ] **TRK-13**: pasting a well-formed-but-rejected token (AniList responds 401) transitions to `Failed` with a
  user-facing message, and does **not** persist a `TrackerAccountEntity`.
- Manual (not automatable): actually complete steps 1–3 once per platform (Android, iOS, desktop) before merge —
  this is the one part of PR 2 where "unit tests pass" and "the feature works" can diverge, because it depends on
  `browserNavigator.openBrowser` actually opening a real browser on each target.

UI: a "第三方追踪服务 (Trackers)" section in Settings, mirroring the existing account-settings card layout. Base
`values/strings.xml` is English (confirmed: `app/shared/src/androidMain/res/values/strings.xml`); add new strings
there and in the `values-zh-rCN`/`values-zh-rHK` variants at minimum, even though the primary audience is
Chinese-first — matching this repo's own existing base-locale convention rather than assuming it.

### 4.2 `TrackerManager` and the three hook points

```kotlin
class TrackerManager(
    private val trackers: List<TrackerService>,   // just [aniListTrackerService] for now
    private val bindingDao: TrackerBindingDao,
    private val scope: CoroutineScope,
) {
    fun onEpisodeMarkedWatched(subjectId: Int, episodeSort: Float) { /* ... */ }
    fun onSubjectCollectionChanged(subjectId: Int, type: UnifiedCollectionType, score: Float? = null) { /* ... */ }
}
```

Hook sites (all three confirmed present in the current tree):
`app/shared/app-data/src/commonMain/kotlin/domain/episode/SetEpisodeCollectionTypeUseCase.kt`,
`app/shared/app-data/src/commonMain/kotlin/domain/player/extension/MarkAsWatchedExtension.kt`,
`app/shared/app-data/src/commonMain/kotlin/data/repository/subject/SetSubjectCollectionTypeOrDeleteUseCase.kt`.

- [ ] **TRK-14**: marking episode N watched with an active AniList binding calls `syncProgress` exactly once with
  `max(N + episodeOffset, binding.lastTrackedEpisode)` — mirrors Mihon's `SyncChapterProgressWithTrack` logic
  directly (this is a port, not a new design).
- [ ] **TRK-15**: "mark all watched" on a batch of episodes issues **exactly one** `syncProgress` call with the
  final episode number, not one call per episode — this is the coalescing behavior Mihon already proves works;
  the test should simulate marking 12 episodes watched in one batch operation and assert the mock client's
  captured-request count is 1.
- [ ] **TRK-16**: a subject with no active binding, or an unauthorized tracker, is silently skipped — no crash,
  no network call.
- [ ] **TRK-17**: a network failure during `syncProgress` is caught and logged, and does **not** block or roll
  back the local watched-mark write (matches Mihon's `BaseTracker.updateRemote`'s try/catch shape — the local
  write must never depend on the remote call succeeding).

UI: subject-details tracker chip (`AniList: 12/24`, unbound state shows `+ 绑定 AniList`) and a
`TrackerBindingBottomSheet` — Mihon-style manual search box (pre-filled with the subject's title), result list,
confirm, episode-offset field (defaults to 0).

## 5. PR 3 — mapping subscription

Mirrors `PeerFilterSubscription`'s shape exactly:

```kotlin
@Serializable
data class TrackerMappingSubscription(
    val subscriptionId: String,
    val url: String,
    val enabled: Boolean,
    val updatePeriod: Duration,
    val lastUpdated: LastUpdated?,
) {
    companion object {
        const val BUILTIN_SUBSCRIPTION_ID = "ani.builtin.tracker-mapping.bangumi-data"
        const val BUILTIN_URL = "https://cdn.jsdelivr.net/npm/bangumi-data@0/dist/data.json" // confirm final host with maintainers, see PLAN.md's open item
    }
}
```

Fetched through the existing `SubscriptionsAniApi.proxy(url)` endpoint (`MediaSourceSubscriptionRequester`'s own
pattern) — no new backend surface. Parses `bangumi-data`'s `items[].sites[]` array into `TrackerMappingEntity`
rows, keeping only `site in {"aniList", ...}` values this app's shipped trackers actually use (so the local table
doesn't carry dead weight for sites nothing binds against yet).

- [ ] **TRK-19**: parsing a `bangumi-data`-shaped fixture JSON produces one `TrackerMappingEntity` per
  `(bangumiId, site)` pair present in the source, correctly filtered to known site keys.
- [ ] **TRK-20**: a refresh that drops a previously-present `(bangumiId, site)` pair removes the corresponding
  local row (diff-and-prune, mirroring `MediaSourceSubscriptionUpdater.calculateDiff`'s `removed` case).
- [ ] **TRK-21**: an unreachable URL or malformed JSON leaves existing `tracker_mapping` rows untouched and
  records an `UpdateError` on the subscription (matches the existing `RepositoryException` → `UpdateError` shape
  in `MediaSourceSubscriptionUpdater.updateAllOutdated`) — a bad refresh must never wipe good data.
- [ ] **TRK-22**: the bind sheet queries `TrackerMappingDao` by `(bangumiId, site = "aniList")` first and
  pre-fills the search result with that match if present, but still lets the user reject it and search manually.

## 6. Definition of done (all PRs)

- `./gradlew check` green (runs `commonTest` + `androidHostTest` + formatting — matches this repo's own
  documented pre-push bar in `docs/contributing/testing.md`; `connectedCheck`/instrumented tests are CI's job,
  not required locally per that same doc).
- Every `TRK-NN` above is a real test in the tree, not a checklist fiction.
- PR 2 has a recorded manual pass (screenshot or short note in the PR description) of the PIN-flow round-trip on
  at least Android and desktop; iOS if available.
- New user-facing strings exist in `values/strings.xml` (English, base) and `values-zh-rCN` at minimum.
