# Search data layer — `search/data-layer.md`

Purpose: Pin the local-library search data layer — tokenisation, matching columns, ranking, paging, provider exclusion and the preference stores behind the search screen.

How derived: `reference/ROADMAP.md` §Phase 5 (search deliverable, K7 keep-list item) and the clean-room phase plan (`.agents/plans/phase-5-tasks.md` task 3), implemented against the schema-v1 column vocabulary. Search strategy is Omnisonic-original; only the capability shape (local LIKE, roman/sort columns, token AND, recency ranking) follows the roadmap.

Confidence:
- [VERIFIED] "beyonce" finds "Beyoncé" and a Latin query finds a Cyrillic title through the roman/sort columns (`SearchRepositoryTest`, Robolectric).
- [VERIFIED] Three-token queries narrow; ranking is `lastPlayed DESC` with unplayed last; paging windows and `hasMore` are exact (`SearchRepositoryTest`).
- [VERIFIED] Hidden and unavailable providers are excluded through the `ProfileScope` seam; the CSV `instr()` exclusion matches whole ids only (`SearchRepositoryTest`).
- [VERIFIED] Every catalog DAO read is wrapped by a `ProfileScope`-taking method (`ProfileScopeQueryTest`, structural).
- [VERIFIED] A worst-case (matches-everything) first page over a 100k-song library answers inside the Phase 2 query budget (3 s); measured 0.02–0.8 s under Robolectric (`SearchPerformanceTest`).
- [UNKNOWN] Real-device cold-cache latency and scroll smoothness — needs hardware.

Date: 2026-11-08 (task 7 screen + layout config added 2026-11-11)

Links: `../storage/sqlite-api29-floor.md` (999-variable bound → CSV exclusion), `../storage/schema-v2.md` (column vocabulary), `.agents/plans/phase-5-tasks.md` task 3, `reference/ROADMAP.md` §Phase 5.

---

## Query pipeline

`SearchRepository.search(query, offset, limit, categories?, scope)`:

1. **Tokenise** (`SearchQuery.terms`): trim, split on whitespace runs, take the first `MAX_TERMS = 6` tokens. Each token carries two LIKE patterns: the raw text and `TextNormalizer.sortKey(token)` (ICU transliteration on Android, NFD+mark-strip fallback — the same normaliser the library sync writes columns with). SQL wildcards (`%`, `_`, `\`) are escaped and the patterns are wrapped in `%…%`, so user input cannot widen a query.
2. **Match**: per-token AND of per-column OR groups:
   - songs: `title`, `latinTitle`, `sortTitle`, `displayArtist`
   - albums: those four **plus** `discNamesJson`
   - artists: `title`, `latinTitle`, `sortTitle`
   - genres: `title`, `sortTitle`
   - playlists: `title`
   
   Each column is matched with the raw pattern *and* the normalized pattern; the roman/sort columns are where diacritics and other scripts are bridged. [VERIFIED]
3. **Rank**: songs `ORDER BY (lastPlayed IS NULL), lastPlayed DESC, COALESCE(sortTitle, title) COLLATE NOCASE, id` — newest played first, unplayed last, stable tail. Albums/artists/genres order by `COALESCE(sortTitle, title)`; playlists by title. No relevance scoring (K7 keeps the crude-but-decent ranking; a hook exists implicitly at the repository). [VERIFIED]
4. **Page**: hand-rolled `LIMIT :limit+1 OFFSET :offset`; the extra row drives `SearchPage.hasMore`. Page size defaults to 30 (`SearchPreferencesStore.DEFAULT_PAGE_SIZE`, clamped 1–200). No Paging 3. [VERIFIED]

An empty (blank) query short-circuits to an empty `SearchResults` without touching the DAO. Categories the caller disabled return empty pages; `SearchResults` always exposes all five sections.

## Provider exclusion (ProfileScope seam)

`ProfileScope` (`db/ProfileScope.kt`) is the single visibility filter threaded through every catalog query (ROADMAP §Phase 5 / R-13). Phase 5 semantics: `excludedProviderIds` only. The repository merges:

- **Hidden providers** from `ProviderVisibilityStore` (DataStore `providers.hidden_ids`),
- **Unavailable providers** from `ProviderStatusTracker` — any status other than `OK` (`OFFLINE`, `NEEDS_WIFI`, `AUTH_FAILED`) excludes. The repository calls `tracker.refresh()` before reading so a fresh process without a prior status pass still excludes correctly; it never inspects connectivity itself.
- **Caller-supplied exclusions** on the passed-in scope.

`SearchRepository.search` is a plain suspend call with no UI dependency, so a future voice/Android Auto "play from search" callback can reuse it unchanged (play-from-search itself is NOT built). [VERIFIED]

## The 999-variable bound and the CSV exclusion

DAO SQL cannot carry a parameterized `NOT IN (:ids)` for the exclusion: API 29's SQLite 3.22 caps statements at 999 bound variables and `DaoSqlBoundedListTest` rejects every non-chunked parameterized IN/NOT IN list (`docs/omnisonic/storage/sqlite-api29-floor.md`). Instead the scope's provider ids are bound as **one CSV string** and matched with

```sql
instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
```

`instr()` exists since SQLite 3.7.15, safely inside the 3.22 floor; the leading/trailing commas make the match whole-id exact (12 never matches 1). Empty CSV excludes nothing. [VERIFIED by `csvExclusionMatchesWholeProviderIdsOnly`]

## Catalog-query wrapper convention

Room cannot bind an object parameter, so the convention is: a `*Raw` `@Query` method (Room-verified SQL, takes the CSV + fixed token slots + limit/offset) and a public wrapper named without the suffix that takes `(scope: ProfileScope, terms: List<SearchTerm>, limit, offset)` and pads the token list to the six slots. `ProfileScopeQueryTest` scans the five catalog DAO sources plus `SearchDao` and fails for any SELECT query that is not either (a) paired with a ProfileScope wrapper or (b) listed in the explicit pre-convention exemption table (pre-existing sync/plumbing reads). The exemption table test also fails when an exempted method no longer exists. Tasks 4/5 (browse, detail) must follow the same convention. [VERIFIED]

## Preference stores

- `SearchPreferencesStore` (DataStore `omnisonic_search`): `search.categories` (string set of `SearchCategory` names; absent key = all five enabled), `search.recent_searches` (JSON string list via `JsonText`, newest first, case-insensitive dedupe, cap 10), `search.page_size`. Setters: `setCategories`/`setCategoryEnabled`, `recordRecentSearch`/`removeRecentSearch`/`clearRecentSearches`, `setPageSize`. [VERIFIED]
- `ProviderVisibilityStore` (DataStore `omnisonic_provider_visibility`): `providers.hidden_ids` string set of provider ids. Hidden-provider management UI is Phase 9/10; Phase 5 ships the seam only. [VERIFIED]
- The search **page layout** config lives in `:style` (`SearchPageConfig`, task 7), deliberately not in this store: `chipLayout` (`SINGLE_LINE`/`WRAPPED`), `autofocusOnEntry`, `showRecentSearches`. `SearchPageConfig.DEFAULT_JSON` is the shipped default; `fromJson` falls back to `SearchPageConfig.DEFAULT` for malformed JSON, unknown enum values and type mismatches, and missing keys keep their defaults. [VERIFIED `SearchPageConfigTest`]

## Search screen (task 7)

`feature-search` renders `SearchRoute` from `SearchViewModel` (`search/`), which talks only to the `SearchSource` seam (`RepositorySearchSource` wraps `SearchRepository` + `SearchPreferencesStore`). One submit runs one repository call at offset 0; the state machine is `IDLE → SEARCHING → RESULTS | NO_RESULTS | ERROR`. Category chips are the persisted toggles; toggling re-runs the active query. Recent searches are recorded only after a successful submit, newest first (store-side dedupe/cap). The load-more footer requests the next offset across all enabled categories, appends each category page and ORs their `hasMore`; a failed append keeps the loaded rows and offers an inline retry. Results render in one `LazyColumn` in the fixed order albums → artists → songs → genres → playlists; a song row opens its album detail. Covers are requested at a bucketed thumb size (≤1024 px, the D-17 rule). Result taps are wired by `:app` to the shell routes.

## Deferred

- Device-gated: first-page latency on a cold cache, scroll smoothness of results on a large library. [UNKNOWN]
- FTS5 stays unbuilt (K7): LIKE measured well inside budget at 100k; revisit only if libraries get much larger.
- Extra tokens beyond `MAX_TERMS = 6` are dropped; no UI indication (Phase 5 acceptance only requires 3-token narrowing).
