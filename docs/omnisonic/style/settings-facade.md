# Settings facade — `settings-facade.md`

Purpose: pin the per-key reactive settings facade — one typed `Flow` per settings key over the existing settings DataStores (shell, search, history, download, transcode), so a screen collects a key instead of doing per-store plumbing. Style values and the active-profile pointer never pass through it.

How derived: own design for Phase 9 TODO 2d (`.agents/plans/phase-9-tasks.md` TODO 2d), following the DataStore conventions of the existing stores (`SearchPreferencesStore`, `HistoryPreferencesStore`, `DownloadQueueSettingsStore`, `TranscodeSettingsStore`, `ProviderVisibilityStore`) and of D-73's `StyleProfileStore`. The studied app's per-key StateFlow holder pattern is noted only as a behavior category in `reference/docs/THEMING.md` §3 item 4; no studied name, key or code enters this design.

Confidence: `[VERIFIED]` against `SettingsFacade` source and the 13 `SettingsFacadeTest` cases (per-key emission, non-emission, defaults, read-error fallback, shared and atomically-memoized upstream, flow identity, persisted snapshot while the shared flow is paused, concurrent first access, real-DataStore round-trip). `[INFERRED]` for the app-layer wiring (one DataStore instance per file shared between the owning store and the facade). `[UNKNOWN]` for every on-device result.

Date: 2026-11-23

Links: `style-loader.md` §4 (style values stay with `StyleProfileStore`, D-73), `decisions.md` D-74, `.agents/plans/phase-9-tasks.md` TODO 2d, `README.md` (Style system row).

---

## 1. Scope and boundaries

- [VERIFIED — source] The facade serves the existing settings DataStores — `omnisonic_shell`, `omnisonic_search`, `omnisonic_history`, `omnisonic_downloads`, `omnisonic_transcode` (and any later settings DataStore wired the same way). It is generic over `DataStore<Preferences>`: the one instance per file is **passed in**, never created inside the facade, so DataStore's single-active-instance-per-file rule holds ("no store instantiated twice").
- [VERIFIED — source] No style value and no active-profile pointer passes through it. Style state goes through `app.omnisonic.core.data.style.StyleProfileStore` (`style-loader.md` §4, D-73); the facade has no reference to the style package.
- [VERIFIED — source] No existing store is modified: key names, write semantics and clamping rules stay exactly as they are. The facade reads raw keys only; read-side sanitization (page-size clamp, worker-count clamp, enum fallback) remains the owning store's job in its aggregate flows.
- [VERIFIED — source] No Hilt binding and no `:app` change in this task: every dependency (the scope, the DataStore instances) is a constructor/call parameter. Concrete `SettingsKey` specs are declared at the wiring site (later TODO), next to the store that owns the key.

## 2. API

- [VERIFIED — source] `SettingsKey<T>(name, key: Preferences.Key<out T>, default: T)` — one typed key spec: the stored key plus the documented default an absent key emits. Companion factories cover every key type the five stores use: `int`, `long`, `boolean`, `string`, `stringOrNull` (absent → `null`, for override-style keys such as the shell nav overrides), `stringSet`.
- [VERIFIED — source] `SettingsFacade(scope).flow(store, key): Flow<T>` — the one shared flow for that (store, key) pair; repeated calls return the same instance, created atomically with `ConcurrentHashMap.computeIfAbsent` so concurrent first access cannot start two upstreams. `current(store, key)` is the suspend persisted-snapshot operation: it reads `store.data` directly with the same `catch { emit(emptyPreferences()) }` and default mapping, so it cannot return a stale replay from the shared flow. `SettingsFacade.create(scope)` mirrors the stores' factory style.

## 3. Behavior contract

| Behaviour | Claim | Evidence |
|---|---|---|
| Write → emission | Writing a key re-emits that key's flow with the new value, and only that key's flow | [VERIFIED — test] `a key emits the written value on its own flow`, `a write to one key emits nothing on another key's flow`, `a write reaches only the flows of its own store` |
| Idempotent writes | Rewriting the same value does not re-emit (`distinctUntilChanged` on the mapped value) | [VERIFIED — test] `rewriting the same value does not re-emit` |
| Absent key | Emits the key's documented default (incl. `null` for `stringOrNull`, empty set for `stringSet`) | [VERIFIED — test] `absent keys emit their documented defaults` |
| Read error | `store.data.catch { emit(emptyPreferences()) }` — the same fallback the owning stores use (search/history/visibility/style) — so every key of that store emits its default instead of failing collection | [VERIFIED — test] `a DataStore read error emits each key's default instead of failing` |
| Shared upstream | Each store's `data` is collected exactly once, into `shareIn(scope, SharingStarted.Eagerly, replay = 1)`, behind every key flow of that store; the share starts at the first `flow(store, …)` call and is memoized atomically (`computeIfAbsent`), so concurrent first access still starts one upstream | [VERIFIED — test] `all key flows over one store share a single upstream collection` (counting fake: 3 key flows, 1 subscription), `each store gets its own shared upstream`, `concurrent first access starts one upstream per store` (barrier-synchronized, no sleeps) |
| One flow per key | `flow(store, key)` is memoized per (store, key): the same pair returns the same `Flow` instance; different keys or stores do not | [VERIFIED — test] `one flow per key for a store and key pair` |
| Snapshot | `current(store, key)` reads `store.data` directly and returns the persisted value or the default, independent of the shared upstream's progress | [VERIFIED — test] `current returns the persisted value or the documented default`, `current returns the persisted value while the shared flow is paused` |
| Real DataStore | End-to-end over `PreferenceDataStoreFactory`: a write re-emits the key flow and `current` sees the persisted value | [VERIFIED — test] `a real DataStore write re-emits the key flow` |

- [INFERRED] Eager sharing (not `WhileSubscribed`) is deliberate: with a stop-grace window, a collector subscribing during the grace can receive the stale replayed value before the fresh upstream emission arrives; an eagerly shared, always-live upstream has no such window, and collecting `DataStore.data` for the process lifetime is what the stores' own flows already do once collected.
- [INFERRED] The facade is store-agnostic on purpose: `:core-data` cannot see the shell store type (it lives in `:feature-settings`), so the shell DataStore is passed in like the other four. The owning stores' constructors already accept an external `DataStore<Preferences>`, so the wiring site can hand the same instance to both the store and the facade.

## 4. Wiring (not done in TODO 2d)

- [INFERRED] The app layer (Hilt module in `:app`, a later child) creates one `DataStore<Preferences>` per file, constructs the owning store with it, and registers `SettingsFacade(scope)` with it; screens then inject only `flow(store, key)`-shaped values instead of per-store plumbing. Key specs reuse the owning stores' documented key names and defaults (e.g. `search.page_size` → 30).
- [UNKNOWN] On-device collection behavior, DataStore startup cost with five eagerly shared upstreams, and any screen migration are unverified until the wiring child runs.

## 5. Evidence and boundaries

- [VERIFIED JVM 2026-11-23] 13 tests in `:core-data` `SettingsFacadeTest`; serial gate results are recorded in `phase-9-task-1-3-audit-gate.log` / `phase-9-task-1-3-audit-xml-totals.txt` after the TODO 1–3 audit. Banned-name scan clean over every touched file; new public names (`SettingsFacade`, `SettingsKey`, factories/members) diffed against all 241 `reference/extracted/theme/theme_keys.json` strings and `THEMING.md` §4 with no collision.
- [VERIFIED — source] Out of scope and untouched: style values/active pointer (D-73), `StyleTokens` wiring or any `:style` change, built-in assets (TODO 4a), converter (TODO 3), editor/UI, dynamic colour/dark mode, Room/schema, and every existing store's key names, write semantics and clamping. Schema v1 and the D-68/D-69/D-70/D-71/D-72/D-73 contracts are unchanged.
