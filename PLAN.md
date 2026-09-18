# Current Project Plan: AniList (Mihon-Style Tracker) Integration

> This file is the active entrypoint for agents and developers working on the Tracker / AniList syncing feature.
> Full technical spec, TDD task list: [`docs/dev/trackers/anilist-sync-plan.md`](docs/dev/trackers/anilist-sync-plan.md).
> **GitHub Issue**: [open-ani/animeko#3427](https://github.com/open-ani/animeko/issues/3427)
>
> This supersedes an earlier draft of this file. Two premises in that draft turned out to be wrong after checking
> against Mihon's actual shipping tracker code, animeko's own source, and AniList's own docs — see
> "corrected from the first draft" below before reading anything else in this repo that still assumes them.

## Corrected from the first draft

1. **OAuth is not a native per-platform redirect problem.** AniList has an official **Auth PIN flow**
   (`docs.anilist.co/guide/auth/`) built for exactly this case: set the app's redirect URL to
   `https://anilist.co/api/v2/oauth/pin`, open it in the system browser (reusing `browserNavigator.openBrowser`,
   already used by the existing Bangumi login), and the user pastes the resulting token into the app. Zero
   Android intent-filters, zero iOS `ASWebAuthenticationSession`, zero desktop loopback HTTP listener. Extending
   the existing Bangumi `OAuthClient`/`OAuthConfigurator` polling-relay pattern was considered and rejected:
   `ani-api-server` is not a public repo in the `open-ani` org, so a contributor can neither build nor deploy
   that half.
2. **ID-mapping does not need `anime-offline-database` or MAL-Sync** (the two sources the GitHub issue names).
   Neither actually covers Bangumi: `anime-offline-database` has no Bangumi cross-reference at all, and
   MAL-Sync's own repo is DMCA-blocked and inaccessible. Use `bangumi-data`'s JSON instead (already forked into
   `open-ani/bangumi-data`, CC BY 4.0, ~93% of entries carry both a `bangumi` and an `aniList` site ID in one
   record) via animeko's **existing subscription mechanism** (the same shape as `MediaSourceSubscriptionUpdater`
   / `PeerFilterSubscription`), fetched through the existing `SubscriptionsAniApi.proxy(url)` endpoint — no new
   backend work needed for this part either.
3. **AniList tokens don't need urgent expiry handling.** No refresh tokens exist (confirmed in AniList's own
   docs), but the practical lifetime is ~365 days (Mihon hardcodes this assumption in `ALOAuth.kt` since AniList's
   implicit-grant response carries no real `expires_in`). Reactive relogin-on-expiry is correct and sufficient;
   don't design around frequent reauth.

## Scope

- Bangumi remains the primary catalog and metadata core (zero breaking changes to existing architecture, zero
  `ani-api-server` changes required by this plan).
- AniList integration is entirely client-side: PIN-flow login, hand-rolled GraphQL client (no Apollo Kotlin —
  only 4 fixed queries/mutations, zero existing GraphQL precedent in this codebase to justify the dependency).
- Bind UX is Mihon-style manual search-and-confirm, with a `bangumi-data`-subscription-backed auto-suggested
  match shown first. Mapping storage is `{bangumiId, site, externalId}`, not AniList-specific, so a future
  MAL/Kitsu tracker reuses the same subscription and table.
- Built to actually run and be used (by the author and their circle), not a minimal demo PR — see the plan doc's
  "why sliced this way" note for how that shaped the PR boundaries below.

## PR Roadmap

### [ ] PR 1 — Module scaffold, DB schema, AniList API client (no UI, fully unit-tested)
- `tracker:api` + `tracker:anilist` Gradle modules (mirrors the existing `datasource:api` / `datasource:bangumi` split).
- `TrackerAccountEntity`, `TrackerBindingEntity`, `TrackerMappingEntity` in `AniDatabase` v22 → v23 (`AutoMigration`, purely additive).
- `AniListRateLimitPlugin` (Ktor `HttpClient` plugin, 25 req/min — Mihon's proven margin under AniList's 90/min cap).
- Hand-rolled AniList GraphQL client: `ViewerProfile`, `SearchAnime`, `GetMediaEntry`, `SaveMediaListEntry`.

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

## Not doing (dropped from the original issue's roadmap)

- Server-side changes to `ani-api-server` of any kind.
- A generic multi-tracker `TrackerService` UI shell for MAL/Kitsu before AniList ships — the interface is designed to allow it later, but no second tracker is being built now.
