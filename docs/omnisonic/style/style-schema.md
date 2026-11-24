# Style schema v1 — `style-schema.md`

Purpose: pin the typed, immutable `schemaVersion 1` style document that Omnisonic stores, validates, writes and edits. This revision covers the Phase 9 TODO 1a model (field inventory for `app`, `home`, `pages` and `nowPlaying`, value-token vocabulary, every default, the `schemaVersion`/`id`/`name` envelope) plus the TODO 1b tree-first decoder with its path-qualified validation contract, the TODO 1c canonical writer with its normalization entry point, and the TODO 1d ordered migration chain.

How derived: own design for Phase 9 (`.agents/plans/phase-9-tasks.md` TODO 1a/1b), constrained by the document contract in `style-format.md` §3/§4 (D-64), the Phase 5 token surface in `style/src/main/java/app/omnisonic/style/StyleTokens.kt`, the current mini-player/now-playing layout in `:feature-library`, and the roadmap's narrowed v1 scope. The model and decoder are pure Kotlin in `:core-model` (`app.omnisonic.core.model.style`) with no Android or Compose types, because the document must be decodable, comparable and testable on the JVM before any UI exists. The decoder uses a tree-only Moshi adapter and hand-written field readers (D-68).

Confidence: `[VERIFIED]` against the model/decoder source and the two test variants' XML for the envelope, field inventory, defaults, closed token vocabularies, structural orientation pairs, immutability, decode entry point, required/optional field policy, path format, warning and error inventory, version gate and range table. `[INFERRED]` for the default home-section vocabulary (Home content is still the Phase 5 placeholder), for the TODO 7/8 field subset chosen to cover their planned controls, and for the decoder ranges (they reject non-renderable values; the model does not require them). `[VERIFIED]` for the canonical writer, its normalized byte form and the normalization entry point, and for the migration chain (TODO 1d). `[UNKNOWN]` for the shape of a real v2 (none exists) and for every on-device result — later children.

Date: 2026-11-22

Links: `style-format.md` (file scope and v1 contract), `style-loader.md` (TODO 2), `style-converter.md` (TODO 3), `style-profiles.md` (TODO 4/6), `style-editor.md` (TODO 5), `style-layout.md` (TODO 7), `style-nowplaying.md` (TODO 8); `decisions.md` D-13/D-63/D-64/D-67/D-68/D-69/D-70; `.agents/plans/phase-9-tasks.md` TODO 1a/1b/1c/1d.

---

## 1. Model

### 1.1 Envelope

[VERIFIED — source] `StyleDocument` is the root type. It is a Kotlin data class: equality is structural, every property is a `val`, and every style-value field has a default.

| Field | Type | Default | Notes |
|---|---|---|---|
| `schemaVersion` | `Int` | `1` (`StyleDocument.CURRENT_SCHEMA_VERSION`) | The only version marker (D-64). |
| `id` | `String` | none | Required identity for the file name, active pointer and Phase 10 link. A decoder must reject its absence (TODO 1b). |
| `name` | `String` | none | Presentation only; duplicates are allowed because identity is `id`. |
| `app` | `StyleApp` | `StyleApp()` | Application-wide appearance. |
| `home` | `StyleHome` | `StyleHome()` | Ordered, hideable Home composition. |
| `pages` | `StylePages` | `StylePages()` | Per-library list presentation. |
| `nowPlaying` | `StyleNowPlaying` | `StyleNowPlaying()` | Compact and expanded player composition. |

- [VERIFIED — D-64] The keys are camelCase and the seven envelope names are fixed for v1; no JSON-in-strings, delimited list, ordinal enum or third-party container field is representable.
- [INFERRED] `id`/`name` deliberately have no model-level defaults so a decoder cannot silently construct a nameless document; an empty-string value is a decoder rejection, not a model default. Invalid-field policy belongs to TODO 1b.
- [VERIFIED — source] The model performs no cross-field validation. Type/range/enum rejection, unknown-key warnings and future-version handling are TODO 1b's decoder and TODO 1d's migrator, so the model cannot disagree with them.

### 1.2 `app`

| Field | Type | Default |
|---|---|---|
| `colorSource` | `ColorSource` | `SYSTEM` |
| `seedColor` | `String` hex | `#7CC4FF` |
| `darkMode` | `DarkMode` | `SYSTEM` |
| `colorPalette` | `ColorPalette` | `TONAL_SPOT` |
| `colorContrast` | `ColorContrast` | `STANDARD` |
| `cornerScale` | `CornerRoundness` | `DEFAULT` |
| `fontFamilies` | `StyleFontFamilies` | all four roles `null` |
| `effects` | `StyleEffects` | see below |

`StyleFontFamilies`: `title`, `body`, `lyrics`, `nav` — each `String?`, default `null`. [VERIFIED — source] `null` means the platform default, matching `FontTokens.fontFamilies` in `:style`; custom font URIs and variable axes stay out of v1 (D-64's narrowed baseline).

`StyleEffects`:

| Field | Type | Default |
|---|---|---|
| `quality` | `QualityTier` | `AUTO` |
| `progressiveBlur` | `ProgressiveBlur` | `AUTO` |
| `overlayAlpha` | `Float` | `0.4` |
| `legibilityShield` | `Boolean` | `true` |
| `artColorFollowing` | `Boolean` | `false` |
| `audioReactive` | `Boolean` | `false` |
| `tiltParallax` | `Boolean` | `false` |
| `tiltParallaxMaxOffsetDp` | `Float` | `12` |

[VERIFIED — source] These defaults are copied from the Phase 5 `EffectTokens` default (`DefaultStyleTokens.effects`), including the D-13 opt-outs. [INFERRED] `seedColor` is a portable hex string rather than an ARGB integer so a hand-edited file, a diff and a warning message stay self-describing and never inherit the reference format's signed-integer colour encoding.

### 1.3 `home`

| Field | Type | Default |
|---|---|---|
| `sections` | `List<HomeSectionEntry>` | recently played, recently added, most played, favourites — all visible |

`HomeSectionEntry`:

| Field | Type | Default |
|---|---|---|
| `section` | `HomeSection` | `RECENTLY_PLAYED` |
| `visible` | `Boolean` | `true` |

- [VERIFIED — source] Order is list order; hiding is `visible = false` in the same list, so the editor's "hidden rows" view needs no second ordering field and TODO 7a's reorder/hide operations are pure list edits.
- [INFERRED] Home content is still the Phase 5 placeholder (`ShellPlaceholders.kt`), so the four-section default is the v1 vocabulary TODO 7a will render, not a copy of an implemented screen. Per-row header/data/card geometry from the reference format stays out of v1 (D-64's narrowed baseline).

### 1.4 `pages`

[VERIFIED — source] `StylePages` carries one `StyleListLayout` each for `albums`, `artists`, `genres`, `songs` and `playlists` — the five current `LibrarySection` ids.

`StyleListLayout`:

| Field | Type | Default |
|---|---|---|
| `columns` | `OrientationPair<Int>` | portrait `3`, landscape `5` |
| `spacingDp` | `OrientationPair<Float>` | portrait `8`, landscape `8` |

- [VERIFIED — source] The 3/5 defaults equal `LayoutTokens.gridColumnsPortrait`/`gridColumnsLandscape` and `gridSpacing`; TODO 7b reads these pairs instead of the single per-orientation token.
- [INFERRED] Every list type carries the pair, including the song list, because TODO 7b requires "every list type has a pair" and a later display-mode change can consume the same shape without a schema bump.

### 1.5 `nowPlaying`

| Field | Type | Default |
|---|---|---|
| `compact` | `StyleCompactPlayer` | see below |
| `expanded` | `OrientationPair<StyleExpandedPlayer>` | both orientations `StyleExpandedPlayer()` |
| `background` | `StyleBackdrop?` | `null` (resolver's default stack) |

`StyleCompactPlayer`:

| Field | Type | Default |
|---|---|---|
| `blocks` | `List<NowPlayingBlock>` | artwork, title, subtitle, controls, progress |
| `buttons` | `List<StyleControlButton>` | previous, play/pause (default, medium), next |
| `text` | `StyleTextBlocks` | title `%title%`, subtitle `%artist%` |
| `progress` | `StyleProgress` | hairline, `2f` dp, no remaining time |

`StyleExpandedPlayer`:

| Field | Type | Default |
|---|---|---|
| `blocks` | `List<NowPlayingBlock>` | artwork, title, subtitle, progress, controls, queue, lyrics |
| `buttons` | `List<StyleControlButton>` | shuffle, previous, play/pause (filled, large), next, repeat |
| `text` | `StyleTextBlocks` | title `%title%` centred, 2 lines; subtitle `%artist%` centred, 1 line |
| `progress` | `StyleProgress` | slider, `4f` dp, remaining time shown |

`NowPlayingBlock`: `kind` (`NowPlayingBlockKind`, default `ARTWORK`), `visible` (`Boolean`, default `true`).

`StyleControlButton`: `action` (`PlayerAction`, default `NONE`), `style` (`ControlButtonStyle`, default `DEFAULT`), `size` (`ControlButtonSize`, default `MEDIUM`).

`StyleTextBlock`: `template` (`String`, default `""`), `alignment` (`TextAlignment`, default `START`), `maxLines` (`Int`, default `1`).

`StyleProgress`: `appearance` (`ProgressAppearance`, default `SLIDER`), `heightDp` (`Float`, default `4f`), `showRemainingTime` (`Boolean`, default `false`).

- [VERIFIED — source] The default block and button orders match the implemented screens: `NowPlayingScreen` draws art, title/artist, seek, shuffle/previous/play/next/repeat, then queue/lyrics; `MiniPlayerBar` draws art, title/artist, previous/play/next and a bottom progress hairline. TODO 8a/8c consume these lists so the default style renders today's layout without a conditional.
- [VERIFIED — source/test, TODO 4R1/D-105] The now-playing *fidelity* defaults were corrected to the shipped screens (F-02): the compact play/pause is `DEFAULT`/`MEDIUM` because the mini bar uses plain `IconButton`s, which is also why compact text stays start-aligned at one line and compact progress keeps `showRemainingTime = false`; the expanded composition centres a two-line title and one-line subtitle and always shows remaining time, while expanded play/pause stays `FILLED`/`LARGE` (the shipped `FilledIconButton` is enlarged). `StyleOrientationTest.now-playing defaults match the shipped mini-player and player screens` pins these values; built-in assets use the same corrected values.
- [INFERRED] Blocks intentionally describe major areas only; per-block params (template, button slots, progress styling, background) live in dedicated fields so a block entry stays a stable ordering unit. Duplicate/unknown block ids and placeholder validation are TODO 8a/8b.

### 1.6 Backdrop layers

[VERIFIED — source] `StyleBackdrop` mirrors D-13's ordered layer stack in document form: `extent` (`BackdropExtent`, default `NOW_PLAYING`) plus a required `layers` list. It exists because `:core-model` cannot depend on the Compose-bearing `:style` module; TODO 8e maps each `Style*BackdropLayer` onto `BackdropStack`.

| Layer type | Fields and defaults |
|---|---|
| `StyleSolidBackdropLayer` | `color` `BACKGROUND`, `alpha` `1`, `blend` `NORMAL` |
| `StyleGradientBackdropLayer` | `anchor` `TOP`, `startColor` `SURFACE_CONTAINER`, `endColor` `BACKGROUND`, `artTint` `0`, `alpha` `0.5`, `blend` `NORMAL` |
| `StyleAlbumArtBackdropLayer` | `scale` `1`, `alpha` `1`, `blend` `NORMAL` |
| `StyleBlurBackdropLayer` | `radiusDp` `24`, `extent` `NOW_PLAYING`, `alpha` `1`, `blend` `NORMAL` |
| `StyleGrainBackdropLayer` | `density` `0.5`, `alpha` `1`, `blend` `OVERLAY` |
| `StyleVignetteBackdropLayer` | `strength` `0.5`, `alpha` `1`, `blend` `MULTIPLY` |
| `StyleGradientMeshBackdropLayer` | `intensity` `0.5`, `alpha` `1`, `blend` `NORMAL` |

- [VERIFIED — source] Layer defaults equal `BackdropStack.kt` in `:style`.
- [VERIFIED — source] A `null` `background` means "use the default stack", so the document does not duplicate `DefaultBackdropStacks.SUBTLE`; the single source for the default look stays in `:style`.
- [INFERRED] A present backdrop must contain at least one layer; emptiness and alpha/scale ranges are TODO 1b validation, not model constructors, for the same reason as §1.1.

## 2. Defaults

[VERIFIED — source/tests] A minimal document is `StyleDocument(id = "...", name = "...")`; every other field is filled from the tables above. `StyleDocumentTest.a minimal document equals the documented defaults` pins that equality, `app defaults mirror the Phase 5 style tokens` pins every `app`/`effects` default, `StyleOrientationTest.home defaults...`, `pages carry...`, `expanded...` and `compact...` pin the section defaults, and `StyleBackdropTest.backdrop layer defaults...` pins the layer defaults.

- [VERIFIED — source] Defaults are constructor defaults on the model types, not a separate default-document constant, so the decoder can omit absent keys and still produce a fully resolved document in TODO 1b, and the merger's "absent key falls through" rule (TODO 2b) has one authoritative value per field.
- [VERIFIED — source] Every default is taken from an existing Phase 5 token, the implemented screen it describes, or D-13; nothing is a new hard-coded look. The single `[INFERRED]` default family is the four Home sections (§1.3), noted there.
- [VERIFIED — source/test, TODO 4R1/D-105] The `nowPlaying` defaults were re-derived from the shipped `MiniPlayerBar`/`NowPlayingScreen` (§1.5) rather than from the previous constructor values; this is a default-value correction, not a schema-shape or `schemaVersion` change.
- [VERIFIED — source] `schemaVersion` defaults to `1` but is not enforced by the constructor: an unknown future version must reach the migrator's loud failure (TODO 1d), not fail during object construction.
- [INFERRED] Keeping defaults on the types means a per-field reset in TODO 5 can restore the default by omitting the field during merge, with no editor-side default table to drift.

## 3. Naming

### 3.1 Keys

- [VERIFIED — source/test] Every model property is lower camelCase and matches `^[a-z][A-Za-z0-9]*$`; no key contains a dot, so no reference dotted-lowercase spelling (`albums.list.display.columns.landscape`) is representable. `StyleNamingTest.model keys are camelCase and never dotted` walks every model class.
- [VERIFIED — source/test] No model key equals a bare reference section marker (`a`, `d`, `i`, `ci`); `model keys avoid reference section markers` pins this.
- [VERIFIED — D-64] Orientation pairs are structural (`OrientationPair.portrait`/`.landscape`). There is no `columnsPortrait`/`columnsLandscape` sibling pair anywhere; `StyleOrientationTest.orientation pairs are structural fields...` asserts the field shape.
- [VERIFIED — source] The envelope keys are exactly `schemaVersion`, `id`, `name`, `app`, `home`, `pages`, `nowPlaying`.

### 3.2 Value tokens

[VERIFIED — source/test] Every enum-valued field is a closed Kotlin enum; an unknown token is not representable and is a decoder rejection (the type-level half of D-64's validation contract). `StyleNamingTest.value vocabularies are closed and exactly documented` pins every enum's constant list:

| Enum | Tokens |
|---|---|
| `ColorSource` | `SYSTEM`, `SEED` |
| `DarkMode` | `SYSTEM`, `LIGHT`, `DARK`, `BLACK` |
| `ColorPalette` | `TONAL_SPOT`, `NEUTRAL`, `VIBRANT`, `EXPRESSIVE`, `RAINBOW`, `FRUIT_SALAD`, `MONOCHROME`, `FIDELITY`, `CONTENT` |
| `ColorContrast` | `STANDARD`, `MEDIUM`, `HIGH` |
| `CornerRoundness` | `DEFAULT`, `MEDIUM`, `LARGE`, `EXTRA_LARGE` |
| `QualityTier` | `AUTO`, `LOW`, `HIGH` |
| `ProgressiveBlur` | `AUTO`, `ON`, `OFF` |
| `HomeSection` | `RECENTLY_PLAYED`, `RECENTLY_ADDED`, `MOST_PLAYED`, `FAVORITES` |
| `NowPlayingBlockKind` | `ARTWORK`, `TITLE`, `SUBTITLE`, `PROGRESS`, `CONTROLS`, `QUEUE`, `LYRICS` |
| `PlayerAction` | `NONE`, `PLAY_PAUSE`, `PREVIOUS`, `NEXT`, `SHUFFLE`, `REPEAT`, `STOP`, `SEEK_BACK_10`, `SEEK_FORWARD_10`, `OPEN_QUEUE`, `OPEN_LYRICS`, `FAVORITE`, `SLEEP_TIMER` |
| `ControlButtonStyle` | `DEFAULT`, `FILLED`, `OUTLINED`, `TEXT` |
| `ControlButtonSize` | `SMALL`, `MEDIUM`, `LARGE` |
| `TextAlignment` | `START`, `CENTER`, `END` |
| `ProgressAppearance` | `SLIDER`, `HAIRLINE` |
| `BackdropLayerKind` | `SOLID`, `GRADIENT`, `ALBUM_ART`, `BLUR`, `GRAIN`, `VIGNETTE`, `GRADIENT_MESH` |
| `BackdropBlendMode` | `NORMAL`, `MULTIPLY`, `SCREEN`, `OVERLAY` |
| `BackdropColor` | `BACKGROUND`, `SURFACE`, `SURFACE_DIM`, `SURFACE_CONTAINER_LOW`, `SURFACE_CONTAINER`, `SURFACE_CONTAINER_HIGH`, `SCRIM`, `PRIMARY` |
| `BackdropExtent` | `NOW_PLAYING`, `FULL_SCREEN` |
| `BackdropAnchor` | `TOP`, `BOTTOM`, `TOP_AND_BOTTOM`, `START`, `END`, `START_AND_END` |
| `BackdropBlurExtent` | `NOW_PLAYING`, `NAV_BAR`, `FULL_SCREEN` |

- [VERIFIED — source] The enum constant name is the v1 token; the decoder in TODO 1b maps a JSON string by name. No field accepts an integer ordinal, and no reference ordinal is representable.
- [VERIFIED — source] `ColorPalette`'s nine tokens are the open-source materialkolor `PaletteStyle` set already used by the reference format notes; they are not a studied-app coinage. Per-enum type names avoid duplicate simple names with `:style` (`QualityTier` vs `EffectsQuality`, `ProgressiveBlur` vs `ProgressiveBlurMode`, `BackdropBlendMode` vs `BackdropBlend`, `Style*BackdropLayer` vs `*BackdropLayer`) so TODO 4/8 mappings need no import aliases.

### 3.3 Collision pass

- [VERIFIED — test] No model type simple name equals a kept reference config-model name from `THEMING.md` §4 (`NowPlayingPageConfiguration`, `NowPlayingCompactConfiguration`, `NowPlayingExpandedConfiguration`, `HomeRowConfiguration`, `PageHeaderConfiguration`, `LibraryPageConfiguration`, `SearchPageConfiguration`, `CustomMaterialTheme`, `MaterialSchemes`, `MaterialScheme`, `NowPlayingButton`, `HeaderConfiguration`), case-insensitively. `StyleNamingTest.model type names avoid kept reference model names` pins the check.
- [VERIFIED — scan] A separate public-name/key diff compares every declared type, property and enum constant in the new files against all 241 strings in `reference/extracted/theme/theme_keys.json` plus the §4 kept names; there is no exact or case-insensitive collision, and the only shared vocabulary is generic property words (`name`, `id`, `color`, `alpha`, `size`, `style`, `action`, `progress`, `background`, `sections`, `columns`, `spacing`, `visible`, `blocks`, `buttons`, `layers`, `scope`) that are not distinctive reference coinages, and no dotted key or kept model name was adopted.
- [VERIFIED — scan] The banned-name scan over every touched file is clean (the literal pattern is read from project configuration and never written here).
- [VERIFIED — source] The model does not accept, store or emit any third-party key spelling; the converter (TODO 3) maps into these names and the writer (TODO 1c) emits only them.

## 4. Decoding

[VERIFIED — source/test] `StyleDecoder.decode(text)` is the only entry point. It parses the text with a tree-only Moshi adapter (`Moshi.Builder().build().adapter(Any::class.java)`) into `Map`/`List`/`String`/`Double`/`Boolean`/`null`, then walks that tree with hand-written decoders. No reflection adapter is registered and no codegen DTO exists for the document. `:core-model` cannot see `:core-data`'s `OmnisonicJson` without inverting the module direction, so the tree instance is module-local and never exposed for typed adapters (D-68).

| Input | Result |
|---|---|
| Malformed/trailing JSON or a non-object root | `Rejected` with `$: malformed JSON: …` / `$: expected object, got <type>` |
| Integer `schemaVersion` other than `1` | `Rejected` immediately, `schemaVersion: unsupported schema version N; this build reads version 1` |
| Wrong type, unknown enum token, out-of-range value, missing required key | `Rejected` after the walk, every error retained; `firstMessage` is the first in document order |
| Unknown keys only | `Decoded` with one `unknown key dropped` warning per key |
| Valid v1 | `Decoded` with an empty warning list |

- [VERIFIED — source/test] **Required envelope:** all seven keys are required. `id`/`name` must be non-blank strings; `app`/`home`/`pages`/`nowPlaying` must be objects; `schemaVersion` must be the integer `1`. A missing key reports `required field is missing`. [INFERRED] Identity and presentation have no model default, and a document without a version cannot be migrated safely (TODO 1d); requiring the four section objects keeps an empty style from arriving as a silent success.
- [VERIFIED — source] **Field defaults inside a present object:** an absent nested key falls back to the model's constructor default, the same value TODO 2b's merge falls through to. An explicit `null` is accepted only for `fontFamilies.title|body|lyrics|nav` and `nowPlaying.background`; elsewhere it is a wrong-type error. [INFERRED] A present orientation pair must carry both `portrait` and `landscape`; a missing orientation is `required field is missing` rather than a per-orientation silent fallback, because the pair is the structural unit D-64 chose.
- [VERIFIED — source/test] **Numbers:** the tree adapter yields `Double` for every JSON number. `Int` fields accept only integral values (`2` and `2.0` both decode; `2.5` is `expected an integer`).
- [VERIFIED — source/test] **Backdrop layers** dispatch on the required `kind` token; each kind validates its own key set plus `kind`, `alpha`, `blend`. A present `background` requires a non-empty `layers` array.
- [VERIFIED — source/test] **Enum values decode by constant name only** (`ColorSource.entries`, `PlayerAction.entries`, …). No ordinal, lowercase alias or third-party spelling is accepted.
- [VERIFIED — source/test] Unknown keys are read past and dropped: the decoder constructs the document from known keys only, so `app.mystery` disappears while its warning stays.
- [VERIFIED — source] Decoding is pure, deterministic and I/O-free: the same text yields the same document, warnings and errors in the same order; no Android type is referenced.
- [VERIFIED — source] Document order of the decoded result follows the envelope (`id`, `name`, `app`, `home`, `pages`, `nowPlaying`), so `firstMessage` is stable across runs.
- [VERIFIED — source] The version gate above is unchanged by TODO 1d: `StyleMigrator` reads the version and runs steps on the tree before this decoder, so a document only reaches `decode` once it is at the build's version (§7).

## 5. Errors

[VERIFIED — source/test] Messages are `path: detail`. Paths join keys with `.` and array indexes with `[n]` (`nowPlaying.compact.buttons[2].action`); the document root is `$` and only root-level failures use it. `StyleValidationTest` pins the format.

| Detail | Example |
|---|---|
| `required field is missing` | `id: required field is missing` |
| `expected <type>, got <type>` | `nowPlaying.compact.buttons[2].action: expected string, got number` |
| `expected an integer, got <value>` | `nowPlaying.compact.text.title.maxLines: expected an integer, got 2.5` |
| `unknown value '<token>'; expected one of <tokens>` | `app.darkMode: unknown value 'TWILIGHT'; expected one of SYSTEM, LIGHT, DARK, BLACK` |
| `value <n> is out of range <min>..<max>` | `pages.albums.columns.portrait: value 0 is out of range 1..12` |
| `must not be blank` | `name: must not be blank` |
| `expected a #RRGGBB or #AARRGGBB colour, got '<value>'` | `app.seedColor: …` |
| `at least one layer is required` | `nowPlaying.background.layers: …` |
| `malformed JSON: <parser message>` | `$: malformed JSON: …` |
| `unknown key dropped` (warning) | `app.effects.mystery: unknown key dropped` |

[VERIFIED — source/test] Closed ranges enforced by the decoder:

| Field | Range |
|---|---|
| `pages.*.columns.portrait/landscape` | 1..12 |
| `pages.*.spacingDp.portrait/landscape` | 0..64 |
| `app.effects.overlayAlpha` | 0..1 |
| `app.effects.tiltParallaxMaxOffsetDp` | 0..64 |
| `…text.title/subtitle.maxLines` | 1..4 |
| `…progress.heightDp` | 0..64 |
| layer `alpha`, gradient `artTint`, grain `density`, vignette `strength`, mesh `intensity` | 0..1 |
| album-art `scale` | 0.1..4 |
| blur `radiusDp` | 0..128 |

- [INFERRED] Ranges are decoder policy rather than model invariants: they reject values no screen can render while keeping the model constructible for code that already holds a valid value. They are deliberately conservative and may widen in a later child without a schema bump, because a wider range still accepts every existing document.
- [VERIFIED — source/test] A rejection exposes no partially decoded document; `StyleDecodeResult.Rejected` carries errors only. An unsupported version stops the walk, so a future document's fields cannot produce misleading warnings.
- [VERIFIED — source/test] Warnings never fail a parse; a document with unknown keys still decodes and retains every warning in document order.
- [VERIFIED — test] Evidence: `StyleDecoderTest` (12 cases) and `StyleValidationTest` (28 cases). `:core-model` is 111/0/0/0 in both debug and release (2026-11-23 audit gate: `/tmp/opencode/phase-9-task-1-3-audit-gate.log`, XML `/tmp/opencode/phase-9-task-1-3-audit-xml-totals.txt`). Path cases cover all four section objects, nested array indexes, every depth of unknown key, enum tokens, number-vs-string, integer boundaries, ranges, missing required keys and the version gate.

## 6. Normalized form

[VERIFIED — source/test] `StyleWriter.write(document)` returns `StyleWriteResult.Written(text)` or `.Failed(errors)`, and `StyleNormalizer.normalize(text)` is the text entry point (decode → document → writer) returning the same result type. Serialization uses Moshi's `JsonWriter` over a `Buffer` with `setIndent("  ")` and `setSerializeNulls(true)`, so indent, escaping and the closing brace do not depend on the platform or on map iteration order (`style-format.md` §3.4, D-64/D-69).

- [VERIFIED — source] **Envelope order** is `schemaVersion`, `id`, `name`, `app`, `home`, `pages`, `nowPlaying`.
- [VERIFIED — source] **Per-section order** is fixed by the writer's call order: `app` = `colorSource`, `seedColor`, `darkMode`, `colorPalette`, `colorContrast`, `cornerScale`, `fontFamilies` (`title`, `body`, `lyrics`, `nav`), `effects` (`quality`, `progressiveBlur`, `overlayAlpha`, `legibilityShield`, `artColorFollowing`, `audioReactive`, `tiltParallax`, `tiltParallaxMaxOffsetDp`); `home.sections[]` = `section`, `visible`; `pages` = `albums`, `artists`, `genres`, `songs`, `playlists`, each with `columns`, `spacingDp`, each pair `portrait`, `landscape`; `nowPlaying` = `compact`, `expanded`, `background`, the expanded pair `portrait`, `landscape`, a player `blocks`, `buttons`, `text`, `progress`, a block `kind`, `visible`, a button `action`, `style`, `size`, a text block `template`, `alignment`, `maxLines`, `progress` `appearance`, `heightDp`, `showRemainingTime`, a backdrop `extent`, `layers`, and a layer `kind`, its kind-specific fields, `alpha`, `blend`.
- [VERIFIED — source] **Fully resolved:** every field of every section is written, including defaults and every list element's complete object. An empty list is written as `[]`; a `null` `fontFamilies.*` or `background` is written as `null` rather than omitted, because a null font family means the platform default and a null background means the resolver's default stack.
- [VERIFIED — source/test] **Numbers:** `Int` fields are written as integers. A `Float` field is written as the shortest decimal that reads back as the same float (`Float.toString()`, trailing `.0` removed) and emitted through a decimal number value so `JsonWriter` writes those digits instead of re-formatting a `Double`. Fractions stay fractions (`0.05`, `7.5`, `1.25`) and integral floats lose the suffix (`20`, `12`, `2`).
- [VERIFIED — source/test] **Indent and newline:** two spaces per depth and exactly one trailing `\n` after the closing brace.
- [VERIFIED — source] **Unknown keys cannot be written:** the writer reads only model fields, so it has no pass-through. The 1b decoder is what warns and drops unknown keys on the way in; normalization never adds one back.
- [VERIFIED — source/test] **Explicit failure, never a partial write:** the encoded text is decoded again before it is returned. If that decode rejects, the writer returns `Failed` with the decoder's path-qualified errors and no text (for example `pages.albums.columns.portrait: value 0 is out of range 1..12`, `name: must not be blank`). A non-finite float is not representable in JSON and returns `Failed` at `$` (`unrepresentable document: non-finite number …`).
- [VERIFIED — source/test] **Symmetry:** a full document round-trips byte-equal and decodes back to the same document; `normalize(normalize(x)) == normalize(x)`; export → import → export is byte-equal.
- [VERIFIED — source] Normalization is pure, deterministic and I/O-free; it references no Android type.

[VERIFIED — test] Evidence: `StyleWriterTest` (9 cases: full-document byte round-trip, minimal document fully resolved, envelope/app key order, indent and newline shape, fractional/integral number formatting, empty-list shape, template escaping, explicit failure, canonical plain-decimal floats incl. scientific-notation boundary) and `StyleNormalizationTest` (5 cases: key-order stability under reordering, unknown-key stripping, idempotence, export→import→export equality, rejection without output). `:core-model` is 111/0/0/0 in both debug and release (2026-11-23 audit gate).

## 7. Versioning

[VERIFIED — source/test] `schemaVersion` is the only version marker (D-64) and `StyleDocument.CURRENT_SCHEMA_VERSION` is the version this build reads. `StyleMigrator(migrations, targetVersion = CURRENT_SCHEMA_VERSION).migrate(text)` brings text forward to `targetVersion` and returns `StyleMigrationResult.Migrated(text)` or `.Failed(errors)` using the §5 `path: detail` message shape.

### 7.1 Chain shape

- [VERIFIED — source] `StyleMigration(from, to)` is one forward step over the JSON tree, mirroring the database's `Migration(from, to)` steps (`:core-data` `Migrations`). A step returns `StyleMigrationOutcome.Migrated(tree)` or `.Failed(detail)`; the migrator stamps `schemaVersion = to` after each step, so a step never owns the envelope version.
- [VERIFIED — source/test] Steps are ordered by version, not by list order: the migrator looks up the step whose `from` equals the current version, so a chain may be declared in any order.
- [VERIFIED — source/test] Chain integrity is checked at migrate time: exactly one step per version (`$: more than one migration step from version N`), no missing version (`$: no migration step from version N to version N+1`) and no skipped version (`$: migration N -> M targets version M; the next step must target version N+1`).
- [VERIFIED — source/test] A failing step surfaces its own detail and returns no text: `$: migration 1 -> 2 failed: <detail>`.

### 7.2 Version rules

| Input version | Result |
|---|---|
| `> targetVersion` | `Failed` immediately: `schemaVersion: unsupported schema version N; this build reads version 1`; no fallback, no destructive rewrite |
| `== targetVersion` | `Migrated(text)` with the input text byte-identical — the current document is neither re-serialized nor normalized here |
| `< targetVersion` | steps run in ascending order; the migrated tree is serialized once and, when the target is this build's version, decoded by §4 before it is returned |

- [VERIFIED — source] Versioning happens on the tree before §4's decoder, so D-68's gate (only version 1 decodes) is unchanged. A document that migrates to the build version must still pass `StyleDecoder.decode`; an output the decoder rejects fails as a whole with the decoder's errors and no text.
- [VERIFIED — source/test] An unknown future version stops before any step and carries the version in the message; there is no best-effort or destructive downgrade (`style-format.md` §3.4, D-64).
- [INFERRED] When a real v2 ships, its step becomes a production migration and `targetVersion` stays `CURRENT_SCHEMA_VERSION`; the loader (TODO 2) calls the migrator before the decoder, so it needs no version-specific branch.
- [VERIFIED — test] The chain is exercised now by a test-only synthetic `StyleMigration(1, 2)` with `targetVersion = 2` (fixture: the full v1 document). The migrator skips the §4 decode when the target is above this build's version, because this build cannot read that document yet.

### 7.3 Boundaries

- [VERIFIED — source] TODO 1d adds no v2 field, no v1 widening and no cross-version normalization beyond the synthetic step; the §1 inventory and every §2 default are unchanged (D-67).
- [VERIFIED — source] The migrator is pure Kotlin with no Android type and no I/O beyond the text argument.
- [VERIFIED — source] The database stays v3 with exactly `MIGRATION_1_2`/`MIGRATION_2_3`; style versioning is document-level only.
- [UNKNOWN] The real v2 field set and any on-device load of a migrated profile — no real v2 exists and no device was attached.

[VERIFIED — test] Evidence: `StyleMigrationTest` (10 cases: current-version passthrough, synthetic v1→v2, a synthetic 0→1 migration path with valid and invalid output, declaration-order independence, failing step, future-version rejection, missing step, skipped step, duplicate step, migrated text through the decoder and writer). `:core-model` is 111/0/0/0 in both debug and release (2026-11-23 audit gate).

## 8. Boundaries

- [VERIFIED — source] TODO 1a ships the model, defaults and vocabulary; TODO 1b the tree-first decoder; TODO 1c the canonical writer and the normalization entry point; TODO 1d the ordered migration chain. There is no loader/merger/active-profile/token wiring in these changes.
- [VERIFIED — source] `:core-model` still has exactly one dependency, Moshi, used for tree parsing, the writer's escaping and indent, and the migrator's tree serialization; the package imports no Android/Compose type. `:style`, `StyleTokens`, playback/transcode and Room v3 are untouched.
- [VERIFIED — D-67] The TODO 1a field inventory and defaults are unchanged; the writer emits them rather than widening v1.
- [VERIFIED — D-68] The TODO 1b decode contract is unchanged; the writer only calls it and the migrator runs before it.
- [VERIFIED — D-69] The TODO 1c writer/normalization contract is unchanged.
- [UNKNOWN] Every on-device result — those belong to later children.
