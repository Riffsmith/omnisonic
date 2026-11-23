# Third-party style converter — `style-converter.md`

Purpose: pin the one-way, import-time conversion of a confirmed third-party style export into an Omnisonic `schemaVersion 1` document — what input it accepts, what it rejects, how every value that cannot be carried over is reported, and why a successful conversion is always a valid v1 document.

How derived: own design for Phase 9 TODO 3 (`.agents/plans/phase-9-tasks.md`), built from the three confirmed user samples and their container/value inventory in `style-format.md` §2, the v1 document contract in `style-schema.md`, and the evidenced value decodes recorded in the reference mapping table (`reference/docs/GESTURES.md` §5.1). The converter introduces no new schema field and no second parser. Recognizing the source format necessarily embeds studied input-format key and token spellings in the converter and its fixtures; that conflict with the project's clean-room string-key rule is recorded as an open blocker in §1.1, not claimed as compliant.

Confidence: `[VERIFIED]` against `StyleConverter` source and `StyleConverterTest` for the accept/reject matrix, the evidenced mappings, the supported placeholder vocabulary, the home-row absent/empty/dedupe semantics, the portable font-family rule, numeric button-slot ordering, the warning contract, the dropped image/paths, determinism and the normalization round-trip. `[INFERRED]` for the future import UI wiring (TODO 5/7c) and for exporter variants not present in the confirmed samples. `[UNKNOWN]` for any ordinal or enum value the reference decode does not evidence, for on-device results, and for the clean-room policy resolution.

Date: 2026-11-23

Links: `style-format.md` (§1 scope, §2 samples, §4 boundaries), `style-schema.md` (§1 Model, §2 Defaults, §6 Normalized form), `style-loader.md` (where a converted document is stored), `decisions.md` D-63/D-64/D-75; `.agents/plans/phase-9-tasks.md` TODO 3.

---

## 1. Boundary

- [VERIFIED — D-63] Third-party files are **converter input only**. `StyleConverter.convert` reads one export container, produces a v1 `StyleDocument`, and returns it. Nothing downstream reads a third-party format, and no third-party key, container field, token spelling or enum ordinal is written into the document.
- [VERIFIED — source] The converter lives in `:core-data` (`app.omnisonic.core.data.style.StyleConverter`). It has no Android dependency, no store, no logger and no UI; the caller owns persistence and presentation.
- [VERIFIED — source] The output is produced by the canonical `StyleWriter` and then decoded again by `StyleDecoder` before it is returned. A document the writer cannot represent, or the decoder would reject, is returned as a rejection, never as a partial conversion.
- [INFERRED] Because the converter is pure, the future import flow can run it off the main thread, show the warning list as an import summary, and hand a `Converted.document` to `StyleRepository.write` unchanged. That wiring is TODO 5/7c, not this task.

### 1.1 Clean-room status — open blocker

[UNKNOWN — policy blocker, 2026-11-23] `.agents/rules/project-rules.md` rule 1 forbids studied "string keys in Omnisonic code, comments, strings, assets, tests, or docs", with no import-boundary exception. A converter that recognizes the studied input format must compare those input keys and token spellings, and its fixtures must carry realistic input documents to test that recognition. The current implementation therefore contains studied input-format literals in `StyleConverter` and the three converter fixtures, and the configured banned-name scan does not look for them (it scans app/package-name patterns only). TODO 3 is **not clean-room compliant by the current rule text**; the options are (a) a user-approved, narrowly scoped import-boundary exception recorded in the project rules, with a scanner that enforces its exact scope, or (b) a different import design the roadmap does not currently describe. Neither option was taken by this audit, because amending the clean-room policy is a user decision (project rule 11). The behavior contract below is verified independently of that policy question.

## 2. API

```kotlin
sealed interface StyleConversionResult {
    data class Converted(val document: StyleDocument, val warnings: List<StyleIssue>) : StyleConversionResult
    data class Rejected(val errors: List<StyleIssue>) : StyleConversionResult {
        val firstMessage: String
    }
}

object StyleConverter {
    fun convert(text: String): StyleConversionResult
}
```

- [VERIFIED — source] `convert` is deterministic and stateless: the same text yields the same document and the same warning list on every call.
- [VERIFIED — source] `StyleIssue.path` is a dotted path rooted at the container (`type`, `id`, `style.a.…`, `style.d.…`, `style.i.…`, `style.ci`, including `[n]` for array entries); `message` is `"<path>: <detail>"`, the same shape the decoder uses.
- [VERIFIED — source] `Rejected.firstMessage` is the first path-qualified error, suitable for a one-line import error. A rejection always carries at least one error.

## 3. Accept / reject matrix

| Input | Result | Evidence |
|---|---|---|
| JSON object with `type` = `application` or `nowplaying`, usable `id`, non-blank `name`, `style` JSON string, and at least one of `i` / `a` / `d` as an object | converted; unsupported values become warnings | [VERIFIED — source, test] |
| Malformed container JSON | rejected: `$: malformed JSON: …` | [VERIFIED — test] |
| Container without `type`, `id`, `name` or `style`; or with a non-string / blank value | rejected: required field is missing or has the wrong type | [VERIFIED — test] |
| `type` other than `application` / `nowplaying` | rejected: `type: unsupported style type '…'` | [VERIFIED — test] |
| `id` that cannot be a file-name segment | rejected: `id: not a usable style id` | [VERIFIED — source] |
| `style` not a JSON object, or with no `i` / `a` / `d` section | rejected: `style: no style sections found …` | [VERIFIED — test] |
| A present section whose value is not an object | rejected: `style.<section>: expected object, got …` | [VERIFIED — source] |
| A malformed or non-object nested page configuration | converted; one warning at that path; the section keeps its default | [VERIFIED — test] |
| Any key or value with no evidenced v1 meaning | converted; one warning at that path; the value is dropped | [VERIFIED — source, test] |

- [VERIFIED — source] Rejection is all-or-nothing: the first structural failure stops conversion and no document is returned.
- [VERIFIED — source] Unknown **container** keys (`imported`, `version`, extra metadata) are ignored without a warning because they are export metadata, not style content. Unknown **style sections** (anything other than `i`, `a`, `d`, `ci`) warn and are dropped.

## 4. What is carried over

The complete studied-name → Omnisonic-name mapping is the reference mapping table (`reference/docs/GESTURES.md` §5.1); it is intentionally not duplicated here. In behavior terms the converter carries five groups:

| Group | Source section | v1 destination | Notes |
|---|---|---|---|
| App appearance | `a` | `app` | colour source, seed, dark mode, palette, contrast, corner scale, one portable font family for all four roles | [VERIFIED — test] |
| Home composition | `i` | `home.sections` | four evidenced query-row families in first-seen order, hidden rows as `visible = false`, explicit-empty preserved, per-token dedupe warnings | [VERIFIED — test] |
| Library presentation | `d` | `pages` | per-list portrait/landscape column and spacing pairs for albums, artists, genres, songs, playlists | [VERIFIED — test] |
| Compact player | `i` | `nowPlaying.compact` | title/subtitle template with `maxLines`, only when every placeholder is in the supported vocabulary; otherwise the fallback template | [VERIFIED — test] |
| Expanded player | `i` | `nowPlaying.expanded` | the evidenced control-button action ordinals in numeric slot order, per orientation | [VERIFIED — test] |

- [VERIFIED — source] A source section that is absent leaves its destination at the v1 defaults; the converter does not invent values to fill a section.
- [VERIFIED — source] Column counts outside `1..12` and spacing outside `0..64` warn and keep the v1 default rather than writing a document the decoder would reject.
- [VERIFIED — source] A font family applies to `title`, `body`, `lyrics` and `nav` together because the confirmed input carries one family name; no per-role family is invented. A blank value, a POSIX absolute path, a Windows absolute path, a UNC path or a `scheme://` URI warns and keeps all four roles null (portable identifiers only).
- [VERIFIED — source/test] Compact text templates carry only the evidenced placeholders `%title%`, `%artist%` and `%album%`. A template with any other placeholder, an unterminated `%`, or conditional/escape syntax warns at its own path and keeps the contextual fallback template (`%title%` or `%artist%`); TODO 8b owns the general template grammar and validator. A supported template is copied verbatim.
- [VERIFIED — source/test] Home rows distinguish three states: both lists absent keeps the four-section v1 default; a present list is processed even when empty, so an explicitly empty visible list yields an empty composition; a hidden-only input yields only the hidden sections. Tokens that collapse to the same native family are added once and warn once per dropped token (position-qualified), so nothing is silently lost or invented.
- [VERIFIED — source/test] Expanded control-button slots are recognized as `controlButton1..7` and sorted by slot number before the native ordered list is built; source object order cannot reorder the controls.
- [INFERRED] The four home-row families are the only row kinds with an evidenced v1 counterpart; query rows outside them, shortcut slots and per-row configuration are deliberately dropped instead of being approximated.

## 5. Warning contract

- [VERIFIED — source] Every key, token or value that is not carried over produces one warning at its own path; identical path+detail pairs are deduplicated, so the list is stable across runs.
- [VERIFIED — source] Warning order is deterministic, not source-object order across sections: fixed conversion stages emit first — interface (`i`, including the deferred home-row warnings), then app (`a`), then display (`d`) — and finally warnings for other style sections. Within one stage, keys follow the source object's order, and repeated home tokens carry their position in the detail so each dropped value gets a distinct warning.
- [VERIFIED — source] An ordinal or enum token is **never guessed**. Values whose meaning the reference decode does not evidence (display modes, navigation types, button sizes and styles beyond the default, action ids outside the decoded set, unknown palette/contrast/source/dark-mode strings) warn and are dropped; the destination keeps its default.
- [VERIFIED — test] The warning list is non-empty for each confirmed sample, and every warning path is rooted at `style.` (or at a container field for a rejection).
- [VERIFIED — source] Warnings do not affect the conversion result: a warning-only input still returns a `Converted` document that passes the v1 decoder.
- [INFERRED] The warning detail strings are user-facing import-summary copy, so they are written fresh and state the consequence ("not carried over", "kept the default") instead of echoing third-party key names as instructions.

## 6. Output guarantee

- [VERIFIED — test] Every confirmed sample converts to a document with `schemaVersion = 1`, a non-empty `id` and `name`, and the written text decodes as a valid v1 document.
- [VERIFIED — test] Writing the converted document and normalizing the result are idempotent: the normalized text equals the writer output byte for byte, and a second normalization changes nothing.
- [VERIFIED — test] Converting the same input twice yields an equal document and an equal warning list.
- [VERIFIED — source] The writer owns key order, number formatting, indentation and the trailing newline; the converter never emits JSON itself.

## 7. Privacy and portability

- [VERIFIED — test] The embedded raster image (`style.ci`) is dropped with one warning. It is never decoded, copied or written to v1.
- [VERIFIED — test] Absolute device paths (the custom home icon in the confirmed samples) are dropped with a warning that names the reason (`absolute path is not portable`). No path string from the input reaches the document.
- [VERIFIED — source] Nothing from the input is passed through as free-form content except the container `name` and `id`, which the schema already requires; all other strings are either evidenced enum tokens, validated portable font-family names, templates made only of the supported placeholders, or dropped.
- [INFERRED] Dropping the image and the icon keeps an imported style portable across devices and keeps the v1 document small; a future asset-import feature would need its own explicit design.

## 8. Test coverage

[VERIFIED — `StyleConverterTest`, 32 executions / 0 failures / 0 skipped, debug and release variants, 2026-11-23 audit gate]

| Case | Asserts |
|---|---|
| All three samples convert | valid v1 document, non-empty identity, writer → decoder round-trip |
| All three samples warn | non-empty, `style.`-rooted warning paths |
| Application sample values | seed, source, dark mode, palette, contrast, corner scale, four font roles |
| Per-list pairs | albums/artists/genres/songs/playlists columns and spacing, both orientations |
| Home order | four families in first-seen order |
| Hidden rows | hidden-only family becomes `visible = false`; hidden-only input converts without a visible list |
| Explicit empty home | a present empty visible list yields an empty composition, not the default |
| Home dedupe | repeated native family kept once, one position-qualified warning per dropped token |
| Compact player | title/subtitle templates, `favorite` + `play` buttons |
| Unsupported template | `%artist% (%player.remaining%)` keeps `%artist%` and warns at the exact subtitle path |
| Supported template | `%album% - %artist%` is copied verbatim with no template warning |
| Portable font family | an ordinary family name maps to every role; POSIX/Windows/UNC/URI values warn and keep null roles |
| Expanded player | `PREVIOUS`, `PLAY_PAUSE`, `NEXT` from the observed ordinals, sorted by slot number despite reversed object order |
| Unknown ordinals | display mode, navigation type and progress style warn; columns keep the mapped values |
| Unknown enum tokens | palette/contrast/dark mode/source/corner scale warn and keep defaults |
| Non-integral seed | warned, default seed kept |
| Unknown action ordinal | warned, dropped, surrounding buttons kept |
| All-empty button family | empty buttons for that orientation, defaults for an absent orientation, still valid v1 |
| Image and paths | `style.ci` and `style.i.home.custom.icon` warn; written text contains neither |
| Malformed container | readable `$: malformed JSON` rejection |
| Foreign JSON | `type: required field is missing` rejection |
| No sections | rejection naming the expected sections |
| Unsupported type | rejection naming the value |
| Malformed nested config | warning, section defaults, no rejection |
| Determinism | two runs equal in document and warnings |
| Normalization | byte-equal after one and two normalizations |

## 9. Limits

- [UNKNOWN] The exporter's full value set. Only two container `type` values, one palette, one contrast value, one dark-mode value and a subset of action ordinals appear in the confirmed samples; everything else is treated as un-evidenced and dropped.
- [UNKNOWN] The meaning of the list display-mode ordinals and the navigation-type ordinal set; they are warned, never mapped.
- [VERIFIED — D-63] There is no third-party writer. Export always emits the normalized v1 document.
- [INFERRED] The converter is not the import UI. File picking, duplicate-name handling, the warning summary and profile storage are TODO 5/7c; this task ends at a `StyleConversionResult`.
- [UNKNOWN — blocker] Clean-room compliance is unresolved (§1.1): the input vocabulary the converter must recognize is the studied format's own keys and tokens, while project rule 1 forbids studied string keys in code/tests/fixtures. TODO 3 must not be reported as clean-room compliant until the user decides the exception or a different design.
