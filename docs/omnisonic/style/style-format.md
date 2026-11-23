# Style file format and schema v1 contract — `style-format.md`

Purpose: pin down which style-file formats Omnisonic reads, what the user-supplied third-party samples actually contain, and the document contract that the Omnisonic-native `schemaVersion 1` style file must satisfy. It is the input to every Phase 9 implementation child.

How derived: parsed the three user-supplied samples in `reference/extracted/style-samples/` with a JSON walker (container keys, section sizes, leaf types, nested-encoded strings, value ranges) and cross-read the reference format notes for the built-in asset shape and decoded value tables (`reference/docs/THEMING.md` §1–§4, `reference/extracted/theme/theme_keys.json`). The native document contract is own design for Phase 9 (`.agents/plans/phase-plans.md` §Phase 9, `.agents/plans/phase-9-tasks.md` TODO 1), constrained by the existing `StyleTokens` seam (`docs/omnisonic/ui/style-tokens.md`) and by the confirmed user decisions D-63/D-64/D-65.

Confidence: [VERIFIED] for the sample inventory, container shape, section sizes, leaf-type counts, encoded-string count and the presence of device paths and an embedded raster image — all read directly from the files. [INFERRED] for what `type`, `version` and `imported` mean, and for the enum-value families the reference notes do not decode. [UNKNOWN] for the meaning of most ordinal values, for the exporter's guarantees, and for how the studied app treats these files at runtime.

Date: 2026-11-22

Links: `decisions.md` D-63/D-64/D-65; `.agents/plans/phase-9-tasks.md`; `docs/omnisonic/ui/style-tokens.md`; `docs/omnisonic/style/style-schema.md` (TODO 1a–1d), `style-loader.md` (TODO 2), `style-converter.md` (TODO 3), `style-profiles.md` (TODO 4/6), `style-editor.md` (TODO 5), `style-layout.md` (TODO 7), `style-nowplaying.md` (TODO 8).

---

## 1. Scope (R-7 resolved)

- [VERIFIED] R-7 is resolved **yes**: Omnisonic supports loading third-party style files. The confirmation of usable samples on 2026-11-22 removes the last gate on converter work.
- [VERIFIED — D-63] Third-party files are **converter input only**. They are opened at import time, converted into schema v1, and then discarded; nothing in the app reads a third-party schema at runtime, and no third-party key, container field or value spelling appears in the v1 document, the store, the tests or the shipped assets.
- [VERIFIED — D-63] Omnisonic never writes a third-party format. Export and backup always emit the normalized v1 document.
- [INFERRED] The converter is therefore a one-way, import-time translation whose failure mode is "reject with a readable message and keep the current style", never "partially apply".

## 2. Confirmed sample evidence

### 2.1 Inventory

| File | Bytes | `type` | Inner sections | Leaf entries |
|---|---|---|---|---|
| `community-theme-1.json` | 463,406 | `application` | `i` 66, `a` 13, `d` 170, `ci` | 250 (50 JSON-encoded strings) |
| `community-theme-2.json` | 21,101 | `nowplaying` | `i` 1 | 1 (one JSON-encoded string) |
| `community-themes-3.json` | 463,440 | `application` | `i` 66, `a` 13, `d` 170, `ci` | 250 (50 JSON-encoded strings) |

[VERIFIED — parsed 2026-11-22]

- [VERIFIED] The two `application` samples are **near-duplicates**: sections `a`, `d` and `ci` are byte-identical and only five `i` values differ (a custom icon path, two per-row configs, the shortcut list and one shortcut slot). They carry different ids and names. Identity is therefore the container `id`, not the content, and an import flow cannot deduplicate by comparing documents.
- [INFERRED] `type` distinguishes an application-wide style from a now-playing-only style: the now-playing sample contains exactly one configuration object and nothing else, which only makes sense as a partial style for one screen.
- [UNKNOWN] whether the exporter ever emits other `type` values. Only these two were observed, and no reference note enumerates the set.

### 2.2 Container shape

[VERIFIED] Every sample is a single JSON object with exactly six top-level keys:

| Key | Type | Observed value |
|---|---|---|
| `name` | string | user-visible style name |
| `type` | string | `application` or `nowplaying` |
| `id` | string | UUID |
| `imported` | boolean | `true` in all three |
| `version` | integer | `1` in all three |
| `style` | **string** | a JSON document, itself a string |

- [VERIFIED] The document is **double-encoded**: `style` is a string whose content parses as JSON. The reference asset shape has no such wrapper and no `ci` section, so the wrapper is the export container, not the stored asset format.
- [INFERRED] `imported` marks a style that arrived through the share/import path rather than one shipped by the app. All three samples are `true`, so an export by the app itself was not observed.
- [UNKNOWN] whether `version` is the style-schema version, a container version, or an app-version marker. It is `1` everywhere, and the reference format notes describe no version field — so a converter must not treat it as a schema version.

### 2.3 Inner document

[VERIFIED] The decoded `style` object has four sections:

- `i` — interface/home values (66 keys in the application samples, 1 in the now-playing sample).
- `a` — application/theme values (13 keys).
- `d` — display/page values (170 keys: 132 integer, 33 boolean, 5 string).
- `ci` — a single base64 payload beginning with the PNG signature, 351,759 characters in both application samples (≈ 258 KB decoded).

[VERIFIED] Key spelling is dotted-lowercase throughout (`albums.list.display.columns.landscape`, `list.track.swipe.left.action`, `now.playing.page.configuration`), matching the reference key index; there is **no** camelCase key in any sample. [VERIFIED] `theme_keys.json` holds 241 entries covering this surface.

[VERIFIED] 50 of the 250 leaf entries are **JSON encoded as strings** and must be parsed a second time. The observed families are:

- `i.now.playing.page.configuration` → `{compactConfiguration, portraitConfiguration, landscapeConfiguration}`. The compact object carries ~23 keys (size, floating, margins, corner radius, background/thumbnail/progress styles, title and subtitle text blocks, a `buttons` array of action names). The two expanded objects carry ~100 keys each and are near-identical, differing in layout positions; they cover top/bottom bars with seven button slots each, cover-art options with four button slots and four gesture actions, landscape slot positions, a `controlsOrder` array of 12 slot ids, slider/volume/rating bars, six text blocks and seven control-button slots. Nested inside each text block are `margins` objects and `icon1..3` action/colour objects.
- `i.overview.row.<token>` → per-row config objects (header group, data group, card group, `hideIfEmpty`) for each home row.
- `i.library.page.configuration` → `{gridColumns, gridLandscapeColumns, padding, buttonStyle}`.
- `a.custom.material.theme` → `{name, id, imported, schemes:{light, dark}}` where each scheme maps ~30 role names to `#AARRGGBB` strings.

[VERIFIED] Section `d` also contains five **string** values, four of which are page configurations (`album`, `artist`, `genre`, `playlist`) and one a button-style token list.

### 2.4 Value encodings

| Encoding | Evidence | Meaning status |
|---|---|---|
| boolean | 33 values in `d`, 44 bool leaves overall | [VERIFIED] direct |
| integer ordinal | 132 `d` values using only 16 distinct numbers, range 0–600 | [INFERRED] enum ordinals; the reference notes decode only navigation type, button action and button style, and partially list display |
| string enum | `dark.mode` = `auto`, `theme.color.contrast` = `high`, `theme.color.palette` = `Neutral`, `theme.color.spec` = `SPEC_2021`, `theme.color.source` = `custom`, `theme.color.now.playing.style` = `dominant`, `typography` = a font family name | [VERIFIED] literal values; [INFERRED] the accepted value sets |
| signed ARGB integer as string | `theme.color.seed` = `"-16711783"` | [VERIFIED] value; [INFERRED] that it is the colour int |
| colour strings | `#RRGGBB` and `#AARRGGBB` inside `custom.material.theme` | [VERIFIED] literal |
| delimited token list | `home.rows` / `home.rows.hidden` = `;`-separated tokens such as `q:recentalbums`, `pl:a-44§§Dive Right In`, `s:shortcut`; `library.shortcuts` = `;`-separated section ids | [VERIFIED] shape; [INFERRED] that `pl:`/`q:`/`s:` prefixes select a row kind and the `§§` suffix is a user-visible row title |
| `%placeholder%` templates | `%title%`, `%artist%`, `%album%`, `%player.remaining%`, `%next.cleantitle%`, `%queue.name%` inside `customText` | [VERIFIED] tokens; [UNKNOWN] the full placeholder set |
| base64 raster | `ci` PNG payload | [VERIFIED] |

- [VERIFIED] Keys can carry **instance identity inside the key name** (`overview.row.q:lastplayedalbums`, `overview.row.pl:a-11§§Rediscover albums`). These are per-row instances, not a schema: their count depends on the user's configuration and they embed user text directly in the key.
- [VERIFIED] `home.rows` references those instance keys, so the home composition is a delimited string whose members only resolve against the `i` map.

### 2.5 Conversion risks

1. **Ordinal values without a legend.** [VERIFIED, high risk] Most visual choices are integers with no accompanying names. A converter cannot map them to v1's named values without the studied enum tables, which the reference notes only partly decode. Guessing would produce a style that looks plausible and is wrong.
2. **Unknown key surface.** [VERIFIED] v1 will not have a counterpart for every observed key (170 display keys, 22 of them lyric-display, four page-config subtrees, `ci`). A converter must report these as warnings, not silently drop them without telling the user.
3. **Double encoding.** [VERIFIED] `style` is a string and 50 of its leaves are strings holding JSON. Every layer needs its own parse error path, or a malformed nested value becomes a missing setting instead of a readable failure.
4. **Non-document payloads.** [VERIFIED] `ci` is a raster image, and `home.custom.icon` values are absolute app-private storage paths that embed the studied package name (≈ 90 characters). Neither is portable; both must be dropped — and the path is also a privacy leak if it is ever written into a committed fixture.
5. **Per-user instance keys and delimited lists.** [VERIFIED] Row instances live in key names and are referenced by a `;` string. There is no schema-stable identity to preserve, so home rows can only be converted by kind, losing per-row titles.
6. **Scale and divergence.** [VERIFIED] One sample is a 21 KB now-playing override, another an 8× larger application style with 250 values; the reference notes show the same divergence in shipped styles. A converter that only handles the smaller case will fail silently on the larger one; the test matrix needs both.
7. **No checksum or schema marker beyond `version`.** [VERIFIED] A truncated or foreign file is only detectable by parse failure. The converter must reject a JSON document that lacks `style` (or whose `style` lacks all of `i`/`a`/`d`) rather than converting an empty result.

## 3. Omnisonic schema v1 contract

### 3.1 Envelope

[VERIFIED — D-64] A v1 document is:

```json
{
  "schemaVersion": 1,
  "id": "<uuid>",
  "name": "...",
  "app": { },
  "home": { },
  "pages": { },
  "nowPlaying": { }
}
```

- [VERIFIED — D-64] Keys are camelCase and the seven names above are fixed for v1. `schemaVersion` is a document field and the only version marker.
- [VERIFIED — D-64] **No JSON-in-strings.** Every studied nested config string becomes a real object, and every delimited list becomes an array. This is the single largest divergence from the third-party format and the reason a converter (not a reader) is required.
- [VERIFIED — D-64] No third-party spelling, dotted-lowercase key, ordinal enum, container field (`type`, `imported`, `version`) or embedded raster is representable in v1.
- [INFERRED] `id` is the identity for the file name, the active-profile pointer and the Phase 10 style link; `name` is presentation only and may repeat.
- [UNKNOWN] the exact field inventory inside the four sections — that is TODO 1a's deliverable (`style-schema.md`), deliberately not fixed here so the model can start from what `StyleTokens` already expresses.

### 3.2 Section responsibilities

| Section | Owns | Evidence |
|---|---|---|
| `app` | colour source and seed, dark mode, palette/contrast, corner scale, typography and font families, effects quality and backdrop defaults | [INFERRED] from the token groups that already exist and the reference `a` section's role |
| `home` | ordered, hideable home sections and their per-section presentation | [INFERRED] the reference `i` row composition maps onto an ordered list, not a delimited string |
| `pages` | per-list/library presentation, including per-orientation column pairs | [INFERRED] from the reference `d` list-display family, reduced to the pairs Omnisonic renders |
| `nowPlaying` | ordered blocks per orientation/compact, text templates, control slots, progress styling, background layer reference | [INFERRED] from the reference expanded/compact configuration and D-13's `BackdropStack` |

- [VERIFIED — D-64] Per-orientation values are **structural pairs** (`{ "portrait": …, "landscape": … }`), never duplicated sibling keys as the reference format does (`…columns` + `…columns.landscape`).
- [VERIFIED — D-64] v1 is deliberately narrower than the reference surface: no lyric-styling section, no page-header builder, no per-row card geometry, no BPM/waveform styling. Additions arrive as optional keys under a schema bump through the migrator.

### 3.3 Value vocabulary

- [VERIFIED — D-64] Enum-valued fields use **readable string tokens**, matching the project rule that enum-valued persisted state is stored by name. Ordinals are not accepted from third-party input and are not written by Omnisonic.
- [INFERRED] This makes each value self-describing in a diff, a warning message and a hand-edited import, at the cost of a slightly larger file — an accepted trade for a format whose main risk is silent mis-mapping.
- [UNKNOWN] the token names themselves (TODO 1a), except that they must be Omnisonic coinages and pass the naming diff against `theme_keys.json` and `THEMING.md` §4.

### 3.4 Validation, normalization and versioning

- [VERIFIED — plan/D-64] **Unknown key** → collected warning, dropped on normalization, import still succeeds. **Wrong type, out-of-range value, unknown enum name, missing required field** → whole document rejected with the first path-qualified message; all messages retained for the editor.
- [VERIFIED — plan/D-64] **Normalization** is a single canonical writer: fixed key order per section, stable number formatting, two-space indent, trailing newline, unknown keys stripped, fully resolved sections. `normalize(parse(normalize(x))) == normalize(x)` is the invariant, and export→import→export is byte-equal.
- [VERIFIED — plan/D-64] **Migration** is an ordered `StyleMigration(from, to)` chain; an unknown future `schemaVersion` fails loudly with the version in the message and never falls back destructively. TODO 1d proves the chain with the v1 baseline plus a synthetic v2 step before a real v2 exists.
- [INFERRED] A style that fails validation never becomes active: the previous document stays, so a malformed file can only ever fail to add a style, never remove the working look.

## 4. Processing boundaries

| Stage | Reads | Writes | Never |
|---|---|---|---|
| Converter (TODO 3) | third-party container | v1 document + warnings | reads a third-party file at runtime; guesses an ordinal |
| Validator/decoder (TODO 1b) | v1 text or tree | document or path-qualified errors | repairs a value silently; warns and rejects the same field |
| Writer (TODO 1c) | document | normalized v1 text | emits unknown keys, ordinals or non-deterministic ordering |
| Migrator (TODO 1d) | document at version N | document at version N+1 | loses unknown future data destructively; accepts a gap |
| Repository (TODO 2a–2c) | v1 files + assets + active id | `styles/<id>.json`, active id | surfaces an unreadable file as a crash; shares a partial write |
| Token source (TODO 4c) | active merged document | `Flow<StyleTokens>` | contains layout or colour logic outside the resolver |
| Editor (TODO 5) | merged document + tokens | validated field writes, active id | renders screens; re-implements a token rule; free-form key edits |

[VERIFIED — D-65] The editor's boundary is the important one: it edits validated data, previews the result on the real screens, applies live and undoes within the session. Screen rendering stays with the screens, and the meaning of a style value stays with the token resolver.

## 5. Editor location

- [VERIFIED — D-65, user-confirmed 2026-11-22] The editor lives in **Settings** on its own route (`styleEditor`), opened from a single Settings row.
- [VERIFIED — D-65] It is a **hub**: the style list plus one row per area, each opening its own sub-screen — Styles, Appearance, Home, Library, Now-playing, Import/export, Advanced. Home/Library controls are delivered by TODO 7c, now-playing controls by TODO 8e/8f, and the remaining areas by TODO 5; each child adds rows to this hub rather than starting a second editor.
- [INFERRED] The hub shape keeps each sub-screen small enough for one view model section and one test class, and keeps the number of settings routes proportional to the areas a user actually thinks in.

## 6. Open items

- [UNKNOWN] Whether a phone-side export exists that differs from these three files (no app-exported file was observed; `imported` is `true` everywhere).
- [UNKNOWN] The accepted value set behind each string enum, beyond the literals observed once.
- [UNKNOWN] The meaning of the `d`-section ordinals not decoded by the reference notes; the converter treats them as "not carried over" until evidence exists.
- [UNKNOWN] Device-side feel of live apply and undo (no device attached during this task).
- [INFERRED, not yet designed] Whether third-party conversion should also be offered for backup-restored styles — the backup entry is Omnisonic-native by construction, so conversion is not expected there.
