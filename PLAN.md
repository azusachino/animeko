# Current Project Plan: AniList (Mihon-Style Tracker) Integration

> This file is the active entrypoint for agents and developers working on the Tracker / AniList syncing feature.
> Full technical spec, TDD task list: [`docs/dev/trackers/anilist-sync-plan.md`](docs/dev/trackers/anilist-sync-plan.md).
> **GitHub Issue**: [open-ani/animeko#3427](https://github.com/open-ani/animeko/issues/3427)

## Scope

- Bangumi remains the primary catalog and metadata core. This feature makes zero changes to `ani-api-server` or
  to Bangumi's role as the canonical ID/metadata source.
- **AniList login uses the official Auth PIN flow**: the app opens `https://anilist.co/api/v2/oauth/authorize`
  in the system browser (reusing `browserNavigator.openBrowser`, the same call the existing Bangumi login uses),
  AniList's own redirect page shows the user a token, and the user pastes it into the app. This needs no
  Android intent-filter, no iOS `ASWebAuthenticationSession`, no desktop loopback HTTP listener, and no
  `ani-api-server` involvement.
- Tokens are treated as opaque with an assumed ~365-day lifetime (AniList's implicit-grant response carries no
  real `expires_in`, and AniList issues no refresh tokens); expiry is handled reactively — a failed authenticated
  call triggers relogin — never proactively.
- **ID mapping subscribes to `bangumi-data`'s JSON** (CC BY 4.0, ~93% of entries carry both a `bangumi` and an
  `aniList` site ID in one record) through animeko's existing subscription mechanism (the same shape as
  `MediaSourceSubscriptionUpdater` / `PeerFilterSubscription`, fetched via the existing
  `SubscriptionsAniApi.proxy(url)` endpoint). Mapping storage is `{bangumiId, site, externalId}`, not
  AniList-specific, so a future MAL/Kitsu tracker reuses the same subscription and table.
- The hand-rolled AniList GraphQL client has no Apollo Kotlin or other codegen dependency — only four fixed
  queries/mutations are needed.
- Bind UX is Mihon-style manual search-and-confirm, with the `bangumi-data` mapping table used as an
  auto-suggested match shown first.
- Built to actually run and be used (by the author and their circle), not a minimal demo PR — see the plan doc's
  "why sliced this way" note for how that shaped the PR boundaries below.

## PR Roadmap

### [x] PR 1 — Module scaffold, DB schema, AniList API client (no UI, fully unit-tested)
- [x] `tracker:api` + `tracker:anilist` Gradle modules (mirrors the existing `datasource:api` / `datasource:bangumi` split).
- [x] `TrackerAccountEntity`, `TrackerBindingEntity`, `TrackerMappingEntity` in `AniDatabase` v22 → v23 (`AutoMigration`, purely additive).
- [x] `AniListRateLimitPlugin` (Ktor `HttpClient` plugin, 25 req/min — Mihon's proven margin under AniList's 90/min cap).
- [x] Hand-rolled AniList GraphQL client: `ViewerProfile`, `SearchAnime`, `GetMediaEntry`, `SaveMediaListEntry`.
- Not yet built (belongs with PR 2's login flow, needs `app-data` → `tracker:anilist` wiring that doesn't exist until then): the concrete `TrackerService` implementation and `isAuthorized()`. `tracker:anilist` currently exposes the token-expiry check as a pure function (`isAniListTokenLikelyExpired`) for that future implementation to call.

### [ ] PR 2 — Login and bind-and-sync (the actual user-visible feature)
- AniList PIN-flow login UI + state machine, Settings "Trackers" card.
- `TrackerManager`, hooked into `SetEpisodeCollectionTypeUseCaseImpl`, `MarkAsWatchedExtension`,
  `SetSubjectCollectionTypeOrDeleteUseCaseImpl`.
- Subject-details tracker chip + manual search-and-bind bottom sheet, episode offset.
- Manual full-platform smoke test (Android/iOS/desktop): this PR is unusable-until-tested by unit tests alone,
  the actual browser round-trip and a real AniList write need a hands-on pass.

### [ ] PR 3 — Mapping subscription (auto-suggest on bind)
- `TrackerMappingSubscription` (mirrors `PeerFilterSubscription`'s shape exactly: built-in default URL, `updatePeriod`, `lastUpdated`, user-addable URLs).
- Wired as the auto-suggest source in PR 2's bind sheet; additive and independently mergeable since manual bind already makes PR 2 fully usable without it.

## Not doing

- Server-side changes to `ani-api-server` of any kind.
- A generic multi-tracker `TrackerService` UI shell for MAL/Kitsu before AniList ships — the interface is designed to allow it later, but no second tracker is being built now.
