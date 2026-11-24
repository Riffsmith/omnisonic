# Style profiles — `style-profiles.md`

Purpose: pin the built-in style profiles Omnisonic ships (delivery form, identity, naming, validation) and the pure profile→token resolution, plus, in later sections, the colour source and dark mode. This revision covers the Phase 9 TODO 4a built-ins, the TODO 4b resolver, the TODO 4c profile-backed source with its Hilt wiring, the TODO 4d live repaint, and the TODO 4R1 production baseline + now-playing fidelity correction (D-105).

How derived: own design for Phase 9 TODO 4a/4b (`.agents/plans/phase-9-tasks.md` TODO 4a/4b), constrained by the delivery-form decision D-77 (raw asset JSON), the schema v1 contract in `style-schema.md` (§1 Model, §4 Decoding, §6 Normalized form), the loader's asset seam in `style-loader.md` (`StyleAssetSource`: built-ins are matched by document id, and the asset file name equals `<id>.json`) and the existing Phase 5 token surface in `style/src/main/java/app/omnisonic/style/StyleTokens.kt`. The six documents were authored as typed `StyleDocument`s and emitted through `StyleWriter`, so each asset is already in the canonical normalized form. The resolver is a total `when`-driven mapping over the typed document, because a validated document must always yield tokens.

Confidence: `[VERIFIED]` against `BuiltinStyleAssetsTest` (9 cases), `StyleTokenResolverTest` (13 cases), `ProfileStyleTokenSourceTest` (5 cases) and `StyleWiringTest` (4 cases) run over the actual shipped files in both test variants, plus `StyleRepaintTest` (7 cases, debug variant) and the production-source Roborazzi goldens, and against the naming diff of every asset string/id/name and every new identifier against all 241 strings of the reference key index plus the THEMING §4 kept names. `[INFERRED]` for the choice of the seven distinguishing documents (they encode the plan's default / compact-list / large-cover / tablet / three now-playing concepts against v1 defaults; no studied preset was copied) and for the v1 single-grid-spacing representative (portrait). `[UNKNOWN]` for every on-device result (asset loading through `AssetManager`, APK packaging, token rendering) — no device was attached.

Date: 2026-11-23

Links: `style-schema.md` (§1, §4, §6), `style-format.md` (§3), `style-loader.md` (§Storage), `style-converter.md` (import path shares the validator); `decisions.md` D-64/D-71/D-77/D-78/D-79/D-80/D-105; `.agents/plans/phase-9-tasks.md` TODO 4a–4d, 4R1.

---

## 1. Built-ins

### 1.1 Delivery form

- [VERIFIED — D-77] Each built-in ships as one **raw JSON asset** at `style/src/main/assets/styles/<id>.json`; `:app` packages the `:style` module's assets unchanged, so the shipped bytes are the validated bytes. File name = document `id` + `.json`, so repository identity (file name) and document identity agree by construction (`style-loader.md` §Storage).
- [VERIFIED — source] The asset text is the canonical normalized form of its document (`style-schema.md` §6): fixed key order, two-space indent, one trailing newline, shortest round-trip floats. `StyleNormalizer.normalize(assetText)` returns the asset text byte-identically, and export→import→export is byte-equal.

### 1.2 The seven built-ins

| Asset file | `id` | `name` | Distinguishing values |
|---|---|---|---|
| `builtin-default.json` | `builtin-default` | Default Style | the production baseline: every field holds the v1 default, so its resolved `StyleTokens` equal `DefaultStyleTokens` (3/5 columns, 8 dp, base palette/effects) |
| `builtin-compact-list.json` | `builtin-compact-list` | Compact List | dense library grids: albums/artists/genres/songs columns 4 portrait / 6 landscape, playlists 3/5, spacing 4/6 dp |
| `builtin-large-cover.json` | `builtin-large-cover` | Large Cover | big tiles: albums columns 2/3 spacing 12/16 dp, artists/genres 3/4, playlists 2/3, songs default-ish 3/5 at 8 dp |
| `builtin-tablet-wide.json` | `builtin-tablet-wide` | Tablet Wide | wide grids: columns 5/8 (playlists 4/6), spacing 12 dp, `app.cornerScale` `LARGE` |
| `builtin-player-artwork.json` | `builtin-player-artwork` | Artwork Player | now-playing: `%artist% · %album%` landscape subtitle, full-screen backdrop stack album-art 1.2 → blur 48 dp @0.6 → top-and-bottom scrim gradient |
| `builtin-player-minimal.json` | `builtin-player-minimal` | Minimal Player | compact blocks reduced to artwork/title/controls with a single play/pause; expanded drops queue/lyrics/progress-block and uses a 2 dp hairline |
| `builtin-player-queue.json` | `builtin-player-queue` | Queue Player | expanded queue block moved to index 3, `OPEN_QUEUE` button appended, subtitle `%artist% · %album%` |

- [VERIFIED — test] All seven decode through `StyleDecoder.decode` as `Decoded` with **zero warnings** and `schemaVersion` 1; ids are distinct, match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` and equal their file names; the seven documents are pairwise distinct.
- [VERIFIED — test, TODO 4R1/D-105] `BuiltinStyleAssetsTest.theDefaultBuiltinResolvesExactlyToTheBaseStyleTokens` pins the baseline asset's resolution to `DefaultStyleTokens`; `everyBuiltinNowPlayingDefaultsMatchTheShippedScreens` pins every asset's compact play/pause style/size and each expanded orientation's title/subtitle alignment and max lines plus the remaining-time flag.
- [VERIFIED — test] A deliberately broken fixture (`style/src/test/resources/style-samples/broken-builtin.json`, `pages.albums.columns.portrait` 0) is `Rejected` by the same decoder with `firstMessage` `pages.albums.columns.portrait: value 0 is out of range 1..12`, and `StyleNormalizer.normalize` returns `Failed` with the same message — proving the loading test can fail.

### 1.3 Naming

- [VERIFIED — scan 2026-11-23] Every string, key, id and display name in the seven assets and the test diffs clean (exact and case-insensitive) against all 241 strings of the reference key index and the THEMING §4 kept model names; no dotted-lowercase (studied key shape) string appears in any asset. The display names are Omnisonic coinages; the studied counterparts (built-in asset file names and human names) are recorded only in the reference mapping table (GESTURES §5.1).
- [VERIFIED — scan 2026-11-23] The banned-name scan over every touched file, including the six assets and the broken fixture, exits 0 (name class plus the D-76 key class).

### 1.4 Boundaries

- [VERIFIED] TODO 4a adds no UI, no editor, no token resolution (TODO 4b), no wiring (TODO 4c/6), no loader/resolver/store/converter contract change (D-71…D-76), no schema v1 widening, no Room change, no import/export flow (TODO 5e). The only production artifacts are the six asset files; the only code artifact is the validation test.
- [UNKNOWN] On-device asset loading through `AssetManager` and APK packaging — JVM tests read the asset directory directly; no device was attached.

[VERIFIED — test] Evidence: `BuiltinStyleAssetsTest` 9/9 in both debug and release (2026-11-23 gate: `/tmp/opencode/phase-9-task-4a-gate.log`, XML `/tmp/opencode/phase-9-task-4a-xml-totals.txt`; `:style` 62/0/0/0 both variants, `:app:assembleDebug` green).

### 1.5 The production baseline and now-playing fidelity (TODO 4R1, D-105)

- [VERIFIED — source] `builtin-default` (display name **Default Style**) is the seventh canonical built-in and `StyleWiringModule.DEFAULT_STYLE_ID`. A null active id (fresh install, cleared pointer) and every invalid-profile fallback resolve it through the same repository/resolver path as any other built-in; there is no id-keyed special case anywhere in `:style` or `:core-data`.
- [VERIFIED — test] Because the asset is the canonical form of a minimal v1 document, `StyleTokenResolver.resolve` yields exactly `DefaultStyleTokens` (3/5 columns, 8 dp, base effects/fonts/colours). A fresh production graph therefore resolves the no-source tokens from shipped bytes; the no-source path stays only for `DefaultStyleTokenSource`/`OmnisonicTheme { }` readers.
- [VERIFIED — test] `StyleNowPlaying` constructor defaults now describe the shipped screens (F-02): the compact play/pause is `DEFAULT`/`MEDIUM` (plain `IconButton`s in `MiniPlayerBar`), each expanded orientation centres a two-line `%title%` and a one-line `%artist%` (`NowPlayingScreen`), and expanded progress shows remaining time while the compact hairline does not. The expanded play/pause stays `FILLED`/`LARGE` (the shipped `FilledIconButton` is larger than the default). Every built-in asset carries the same corrected values; deliberate per-style values (subtitle templates, blocks, backdrops, grids) are unchanged.
- [VERIFIED JVM] `StyleWiringTest.aFreshInstallResolvesTheApprovedBaselineTokens` proves the production Hilt graph: `activeId` is null, `activeProfile` resolves `builtin-default`, and the source emits `DefaultStyleTokens`. `:core-model` 112/0/0/0 and `:style` 82/0/0/0 in both variants; `:app` 182/0/0/0 debug / 45/0/0/0 release.
- [UNKNOWN device] Cold-start frame ordering/flicker is a device question (F-01): the first frame still seeds `source.tokens()` before the flow emits, but the fallback and the resolved baseline are now token-identical, so no value changes between them. Whether any visible artifact remains is `[UNKNOWN device]`.
- [VERIFIED — test] The five Roborazzi goldens now render `OmnisonicTheme(source = <production source>)` (Hilt, fresh install) instead of the no-source theme; all five are byte-identical to their previous hashes, so no golden was re-recorded (R-25).

## 2. Resolution

[VERIFIED — source] `StyleTokenResolver.resolve(document: StyleDocument): StyleTokens` (`style/src/main/java/app/omnisonic/style/StyleTokenResolver.kt`) is a pure, total function: the same document always yields structurally equal tokens. Its only input is a document the loader already merged over its built-in default (D-72) and the decoder already validated (D-68), so the resolver has no `Context`, no suspension, no I/O and no fallback branch. Out-of-schema input cannot reach it: `StyleDecodeResult.Rejected` carries no document, so only a `Decoded` document exists at this seam.

| Token group | Source in the v1 document |
|---|---|
| `colors` | base — the colour enums (`colorSource`, `seedColor`, `darkMode`, `colorPalette`, `colorContrast`) do not alter tokens until scheme derivation (TODO 6a/6b) |
| `typography` | base (no v1 field yet) |
| `spacing` | base (no v1 field yet) |
| `shapes` | `app.cornerScale` → `CornerScale` (name-for-name); `cover`/`pill` keep the base values |
| `layout` | `pages.albums.columns` → `gridColumnsPortrait`/`gridColumnsLandscape`; `pages.albums.spacingDp.portrait` → `gridSpacing`; `nowPlaying.compact.progress.heightDp` → `miniPlayerProgressHeight`; every other layout value stays base |
| `imagery` | base (no v1 field yet) |
| `surfaces` | base (no v1 field yet) |
| `effects` | `app.effects.quality`/`progressiveBlur`/`overlayAlpha`/`legibilityShield`/`artColorFollowing`/`audioReactive`/`tiltParallax`/`tiltParallaxMaxOffsetDp`; `nowPlaying.background` → `BackdropStack` (a `null` background keeps `DefaultBackdropStacks.SUBTLE`) |
| `motion` | base (no v1 field yet) |
| `fonts` | `app.fontFamilies` per role (`null` = platform default); `customFontUris` stays empty (v1 has no custom faces) |

[VERIFIED — source/test] Every mapped v1 enum is covered by an exhaustive `when`, so a new enum constant cannot compile without a token decision: `CornerRoundness` → `CornerScale`, `QualityTier` → `EffectsQuality`, `ProgressiveBlur` → `ProgressiveBlurMode`, `BackdropExtent` → `BackdropScope`, `BackdropBlendMode` → `BackdropBlend`, `BackdropColor` → `BackdropColorRole`, `BackdropAnchor` → `BackdropGradientAnchor`, `BackdropBlurExtent` → `BackdropBlurScope`, and every `BackdropLayerKind` payload type → its `BackdropLayerType` (layer params carry through: art scale, blur radius/scope, gradient anchor/colours/art tint, grain density, vignette strength, mesh intensity, plus alpha and blend on every layer).

[INFERRED] `LayoutTokens` carries one grid spacing for both orientations, so `pages.albums.spacingDp.portrait` is the v1 representative value; per-orientation spacing and the per-list pairs (`artists`, `genres`, `songs`, `playlists`) need token fields that arrive with TODO 7b. The `nowPlaying` block/button/text/progress composition, `home.sections` and the colour/dark-mode enums have no token group in v1 either, so those groups stay at the base rather than inventing a mapping — the now-playing-only built-ins (`Minimal Player`, `Queue Player`) therefore resolve exactly to `DefaultStyleTokens`, and their distinguishing values remain document-only until TODO 8.

[VERIFIED — test] `StyleTokenResolverTest` (12 cases, both variants) pins: the default profile resolving exactly to `DefaultStyleTokens`; each built-in's expected tokens (Compact List 4/6 columns at 4 dp; Large Cover 2/3 at 12 dp; Tablet Wide 5/8 at 12 dp with `LARGE` corners and the 42 dp extra-large radius; Artwork Player's full-screen album-art → blur (48 dp, full-screen, 0.6) → gradient (`TOP_AND_BOTTOM`, scrim→background) stack); Minimal/Queue keeping base tokens while Artwork differs; determinism across repeated calls and fresh decodes; every corner roundness, quality tier, progressive blur, backdrop colour/blend/anchor/blur-extent/layer-kind and backdrop extent; the albums/compact-progress layout effects with the other lists untokenised; font families and effect flags field by field; every colour enum leaving the palette unchanged; and the broken built-in fixture rejected before any document exists.

[UNKNOWN] On-device rendering of the resolved tokens — the resolver is JVM-verified only; live repaint belongs to TODO 4d.

[VERIFIED — gate 2026-11-23] `StyleTokenResolverTest` 12/12 in both debug and release; `:style` 74/0/0/0 in both variants; serial `:style:compileDebugKotlin :style:compileReleaseKotlin :style:testDebugUnitTest :style:testReleaseUnitTest :app:assembleDebug` exit 0 (`/tmp/opencode/phase-9-task-4b-gate.log`; XML `/tmp/opencode/phase-9-task-4b-xml-totals.txt`).

### 2.1 Boundaries

- [VERIFIED] TODO 4b adds no UI, no Hilt/DI seam change (TODO 4c), no repaint test (TODO 4d), no loader/resolver/store/converter contract change, no schema v1 widening, no asset change and no Room change. The only production artifact is `StyleTokenResolver.kt`; the only test artifact is `StyleTokenResolverTest.kt`.

## 3. Wiring

[VERIFIED — source] `:style` adds `ObservableStyleTokenSource` — an **additive** `StyleTokenSource` with `val tokenFlow: Flow<StyleTokens>` — and its profile-backed implementation `ProfileStyleTokenSource(activeDocument: Flow<StyleDocument?>, fallback: StyleTokens = DefaultStyleTokens)` (`style/src/main/java/app/omnisonic/style/ProfileStyleTokenSource.kt`, D-79). `tokenFlow` maps each usable document through `StyleTokenResolver.resolve` (D-78), drops `null`, and applies `distinctUntilChanged`: a switch to a different profile emits exactly once, an equal re-resolution emits nothing, and a `null` (a profile the loader could not turn into a document) is skipped so the **last good tokens stay** — the source defines no second fallback rule. The synchronous `tokens()` returns `fallback` for existing readers and for the window before the first emission; `StyleTokenSource` and `OmnisonicTheme`'s default are unchanged, and the flow is consumed by the root theme only (§4).

[VERIFIED — source] `:app` `StyleWiringModule` (SingletonComponent) binds the production chain:

| Seam | Binding |
|---|---|
| `StyleAssetSource` | `AssetManager` over `styles/` — the six raw built-in assets shipped by `:style` (D-77) |
| `StyleRepository` | `File(context.filesDir, StyleRepository.STYLES_DIRECTORY)` + assets + logger (D-71) |
| `StyleProfileResolver` | repository + `DEFAULT_STYLE_ID = "builtin-default"` + logger (D-72/D-105) |
| `StyleProfileStore` | `StyleProfileStore.create(context, …)` over `omnisonic_style` (D-73) |
| `ProfileStyleTokenSource` | `store.activeProfile` mapped: `Resolved` → its document, `Unavailable` → `null` |
| `StyleTokenSource` | the same singleton `ProfileStyleTokenSource` |

- [VERIFIED — source] The `StyleProfileState` → document adapter lives in `:app` because that type belongs to `:core-data`; `:style` therefore keeps its one-way dependency on `:core-model` (plus `kotlinx-coroutines-core` for `Flow`) and gains no persistence or Android-context access.
- [VERIFIED — test] `ProfileStyleTokenSourceTest` (5 cases, both variants) pins: the synchronous fallback leaves `DefaultStyleTokens` for a no-store context; `tokenFlow` emits exactly `StyleTokenResolver.resolve(compact)` for the compact active document; a switch compact → large-cover adds exactly one emission; compact → `null` → compact keeps a single emission and then large-cover adds the second; a `null` start stays silent until the first usable document.
- [VERIFIED — test] `StyleWiringTest` (3 cases, both variants, Robolectric + Hilt) pins the production graph: `StyleTokenSource` resolves to the same singleton `ProfileStyleTokenSource`; a real `switch("builtin-tablet-wide")` makes the flow emit 5/8 columns and `LARGE` corners from the shipped asset; and compact → large-cover yields exactly one new emission with no extra buffered value after a settle window.
- [VERIFIED — D-105] `builtin-default` is the first-run/default profile id (TODO 4R1). It remains a wiring policy value, not schema data; it changes only the starting profile a fresh install resolves, never a document or a token mapping.
- [UNKNOWN] On-device asset loading and APK packaging through `AssetManager` are not exercised here (JVM only); live repaint evidence is in §4.

### 3.1 Boundaries

- [VERIFIED] TODO 4c changes no resolver, loader, store, converter, schema v1, asset or Room contract, and adds no UI. The seam is additive: `DefaultStyleTokenSource` remains for default/no-store readers and no screen was rewritten.
- [VERIFIED — gate 2026-11-23] `ProfileStyleTokenSourceTest` 5/5 and `StyleWiringTest` 3/3 in both variants; `:style` 79/0/0/0, `:app` 174/0/0/0 debug and 44/0/0/0 release; serial gate exit 0 (`/tmp/opencode/phase-9-task-4c-gate.log`; XML `/tmp/opencode/phase-9-task-4c-xml-totals.txt`).

## 4. Repaint

### 4.1 The observing theme

[VERIFIED — source] `:style` gains an additive observing overload `OmnisonicTheme(source: ObservableStyleTokenSource, content)` (`style/src/main/java/app/omnisonic/style/OmnisonicTheme.kt`, D-80). It collects `source.tokenFlow` with `collectAsState(initial = source.tokens())` and then delegates to the existing `OmnisonicTheme(tokens = …)`, so `LocalStyleTokens`, the Material colour-scheme/typography/shapes derivation and every screen read are unchanged. Seeding with the synchronous `tokens()` keeps exactly one fallback rule (D-79) and gives a defined first frame: a start with no usable profile renders `DefaultStyleTokens` until the first document arrives.

[VERIFIED — source] `DefaultStyleTokenSource` now implements `ObservableStyleTokenSource` with `tokenFlow = flowOf(DefaultStyleTokens)`; `StyleTokenSource`, the `OmnisonicTheme { }` default and the `DefaultStyleTokens` value are untouched, so no-store contexts and every existing test stay on the default style. `:app` closes the chain: `StyleWiringModule.observableStyleTokenSource` exposes the same singleton `ProfileStyleTokenSource` as `ObservableStyleTokenSource`, `MainActivity` injects it, and `OmnisonicApp(tokenSource = …)` renders the observing theme.

[VERIFIED — source] A profile switch therefore republishes tokens through D-79's flow and recomposes the existing tree: no `recreate()`, no process restart, no screen reinterprets a style value, and a `null`/rejected profile emits nothing so the last good tokens stay visible.

### 4.2 Repaint evidence

[VERIFIED — test] `StyleRepaintTest` (7 cases, debug variant — compose-rule tests live in `app.omnisonic.shell`, which the release variant excludes) composes the observing theme over a real `ProfileStyleTokenSource` whose documents are decoded from the shipped assets and asserts a visible token-driven value (`Text` carrying `layout.gridColumnsPortrait`/`Landscape`):

| Case | Behaviour pinned |
|---|---|
| `aProfileSwitchRepaintsTheTokenDrivenValue` | compact 4/6 → large-cover 2/3 without touching the composition root |
| `aProfileSwitchRepaintsWithoutRecreatingTheComposition` | the host `Activity` instance and a `remember`ed marker are unchanged across the switch (both collected during composition) |
| `aRejectedProfileLeavesTheVisibleValueUnchanged` | `null` after compact keeps 4/6 |
| `anUnavailableStartShowsTheSourceFallbackUntilTheFirstDocument` | a `null` start shows `DefaultStyleTokens` (3/5), then tablet-wide repaints to 5/8 |
| `switchingBackRepaintsTheEarlierValueAgain` | compact → large → compact repaints 4/6 → 2/3 → 4/6 |
| `everyBuiltInProfileResolvesToItsOwnRepaint` | compact → tablet → large repaints 4/6 → 5/8 → 2/3 |
| `theDefaultThemeStillServesTheDefaultStyle` | the no-source `OmnisonicTheme { }` overload still reads `DefaultStyleTokens` |

[VERIFIED — gate 2026-11-23] `:app:verifyRoborazziDebug --rerun-tasks` exit 0. All five goldens are byte-identical before and after the change — `shell_home_portrait.png` (`806cfaf1…65e6`), `mini_player_bar_visible.png` (`c3d413e2…c786`), `mini_player_bar_hidden.png` (`022fd525…f2da`), `now_playing_portrait.png` (`81f5bc79…6676`), `queue_portrait.png` (`8e7809b9…d2c`); the before/after sha256 lists are `/tmp/opencode/phase-9-task-4d-goldens-before.txt` and `-after.txt`. **Correction (TODO 4R1): this was a no-source claim only.** Every one of those goldens rendered `OmnisonicTheme { }`/`DefaultStyleTokens`, which at the time was *not* the production default (F-01), so the evidence proved the no-source path rather than what a fresh install resolves. TODO 4R1 changes the claim, not the images: the same five tests now render `OmnisonicTheme(source = …)` with the Hilt-resolved production source, and the hashes are still byte-identical (`/tmp/opencode/phase-9-task-4r1-goldens-{before,after}.txt`).

[UNKNOWN] On-device repaint feel, transition smoothness and cold-start ordering of the first emission — JVM compose recomposition is not a device result.

### 4.3 Boundaries

- [VERIFIED] TODO 4d adds no UI, no editor, no dynamic colour or dark-mode derivation (TODO 6), no gesture work (TODO 9), and changes no resolver, loader, store, converter, schema v1, asset or Room contract. The theme is the only consumer seam: `OmnisonicApp` observes the source, screens are untouched, and no screen reinterprets a style value.
- [VERIFIED — gate 2026-11-23] `StyleRepaintTest` 7/7 (debug); `:style` 79/0/0/0 in both variants; `:app` 181/0/0/0 debug and 44/0/0/0 release; serial gate including `:app:verifyRoborazziDebug --rerun-tasks` and `:app:assembleDebug` exit 0 (`/tmp/opencode/phase-9-task-4d-gate.log`; XML `/tmp/opencode/phase-9-task-4d-xml-totals.txt`).
