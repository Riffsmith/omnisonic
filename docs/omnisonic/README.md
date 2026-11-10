# Omnisonic project documentation — `docs/omnisonic/`

Start here. This directory is the permanent, subject-organized documentation for
the app's behavior, architecture, and database design. Each document is
self-contained and tagged: claims are marked `[VERIFIED]` (asserted by a named
test or checked against cited sources), `[INFERRED]` (basis stated), or
`[UNKNOWN]` (needs a device, emulator, or live server).

## Where to look

| Subject | Documents |
|---|---|
| **Decisions** (what was chosen, when, why) | `decisions.md` — the authoritative decision record; the entry point for every "why is it built this way" question |
| **Database & schema** | `storage/schema-v2.md` (clean v1 baseline, column vocabulary, tables, triggers, migration-harness contract) · `storage/library-sync-ownership.md` (server/local column ownership, sync run stamps, sync transactions, resume merge, stamp-based pruning/tombstones, upsert contract) · `storage/outbox-contract.md` (pending-change wire contract, ordering, concurrent-enqueue safety) · `storage/sqlite-api29-floor.md` (SQL feature floor, 999-variable bound, DAO scanners) · `storage/aggregate-null-semantics.md` (empty-album aggregate rules) |
| **Architecture & playback** | `playback/exposed-player.md` (stable exposed player, queue timeline, gapless) · `playback/controller-and-events.md` (controller hub, event bus, position persistence) · `playback/service-and-notifications.md` (service discovery, session activity, notification taps) · `playback/stream-resolution.md` (resolver, resume-point fallback, RG source, queue-source seams, scrobble points) · `playback/sink-processors.md` (audio processor chains) · `playback/stream-cache-identity.md` (cache keys, stream freshness, transports) · `playback/per-provider-media-clients.md` (media data-source path) · `playback/wake-mode.md` · `playback/offload-and-extensions.md` · `playback/settings-persistence.md` · `playback/audio-focus.md` |
| **Network & protocol** | `network/client-profiles.md` (API vs MEDIA OkHttp profiles) · `network/cleartext-policy.md` (cleartext policy + private-host warning) · `protocol/subsonic-auth.md` (auth parameters) · `protocol/scrobble-timestamps.md` (backdating policy) · `protocol/cancellation-contract.md` (coroutine cancellation rule) |
| **Providers & sync** | `providers/provider-connections.md` (kinds, endpoints, capability traits, availability gate, mTLS, downloads) · `providers/client-lifecycle.md` (client caching + connection-block failover) · `providers/credential-handling.md` (secrets, unreadable credentials, log redaction, backup exclusions) · `providers/local-mediastore.md` (device library access) · `providers/library-sync-ux.md` (live-device findings, sync-progress surface, library invalidation, resume-on-launch, first-run sync step + settings sync manager — D-16/D-17, tasks 4b/4c) |
| **Search** | `search/data-layer.md` (tokenised LIKE search, ranking, paging, ProfileScope provider exclusion, preference stores, catalog-query wrapper convention) |
| **Toolchain & build** | `toolchain/build-stack.md` (pinned versions, release minification, media3 upgrade notes) |
| **UI** | `ui/style-tokens.md` (StyleTokens: the single source of colors/sizes/layout and the Phase 9 source seam) · `ui/gesture-contract.md` (SwipeZone ownership rules, tab+pager pairing, edge exclusion, button-twin audit) · `ui/provider-setup.md` (first-run form, validation, probe-before-save, onboarding hand-off and initial sync) · `ui/library-browse.md` (library hub + paged sections, browse queries, load-more paging, cover pipeline) · `ui/detail-screens.md` (album/artist/genre/playlist detail: scoped paged bodies, link-table reads, header model, playback actions) · `ui/scroll-performance.md` (cold-start scroll-stall investigation, measurement protocol, fix plan 4e) |

## Development guidance

Development instructions (build commands, module layout, project rules, and the
phase-based workflow) live in `AGENTS.md` at the repository root and
`.agents/rules/project-rules.md`. The document template and tagging convention
for files in this directory are defined in `00-template.md`.

## Conventions

- One authoritative document per subject; link to it instead of duplicating.
- When a behavior changes, update its document in the same change (derivation,
  confidence, date, links per `00-template.md`).
- New decisions go in `decisions.md` with a one-line rationale and a link to the
  document that details them.
