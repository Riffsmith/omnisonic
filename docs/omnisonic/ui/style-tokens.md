# UI style tokens — `ui/style-tokens.md`

Purpose: define the single source of every color, size, typography and layout value in the Omnisonic UI, the hard-coded Phase 5 default, and the seam Phase 9 replaces with JSON style profiles.

How derived: own design for Phase 5 (`.agents/plans/phase-plans.md` §Phase 5: "all colors, sizes and layout read from a `StyleTokens` object with one hard-coded default"). Navigation defaults follow ordinary mobile/tablet convention because the studied behavior notes do not decode the reference enum; that choice is ours and configurable.

Confidence: `[VERIFIED]` against `style/src/main/java/app/omnisonic/style/StyleTokens.kt`, `OmnisonicTheme.kt` and `style/src/test/java/app/omnisonic/style/StyleTokensTest.kt` (7 tests). Dynamic color, dark-mode switching and JSON profile loading are Phase 9 work and not implemented here `[UNKNOWN]` until then.

Date: 2026-11-04

Links: `decisions.md`; `docs/omnisonic/README.md`; roadmap Phase 5 (UI shell + search) and Phase 9 (JSON style system); `.agents/plans/phase-5-tasks.md` task 1.

---

## Model

`StyleTokens` (`app.omnisonic.style`) is an immutable data holder with six groups:

| Group | Type | Contents |
|---|---|---|
| Colors | `ColorTokens` | 36 Material color roles (including surface containers and inverse roles) plus UI roles: `coverPlaceholder`, `onCoverPlaceholder`, `progressTrack`, `progressActive`, `nowPlayingBackground`, `selectedIndicator` |
| Typography | `TypographyTokens` | six `TypeToken` scales (`display`, `headline`, `title`, `body`, `label`, `caption`), each size/line-height/weight/letter-spacing |
| Spacing | `SpacingTokens` | `xxs` 2dp through `xxl` 32dp |
| Shapes | `ShapeTokens` | small/medium/large/extra-large corner radii plus `cover` and `pill` |
| Layout | `LayoutTokens` | navigation layout per orientation, bottom-bar/rail sizes, grid columns per orientation, grid spacing, page padding, row heights, detail/player art sizes, content width, section spacing |
| Imagery | `ImageryTokens` | list thumbnail size, grid aspect ratio, detail/now-playing art sizes, placeholder tint alpha |

All values are Compose `Color`/`Dp`/`TextUnit` types so screens can consume them without conversion helpers. `LayoutTokens.navigationFor(isLandscape)` and `gridColumnsFor(isLandscape)` resolve orientation-dependent choices in one place.

## Default and theme wiring

- `DefaultStyleTokens` is the one hard-coded default: a dark palette with a blue primary seed, standard spacing scale and 3/5-column grids for portrait/landscape.
- `StyleTokenSource` is a single-method seam; `DefaultStyleTokenSource` returns the default. Phase 9 supplies a JSON-profile-backed source without changing screens. `[VERIFIED]` by `defaultSourceReturnsTheHardCodedDefaultAndCanBeReplaced`.
- `OmnisonicTheme(tokens)` provides `MaterialTheme` with a color scheme, typography and shapes derived from the tokens (`toColorScheme`, `toTypography`, `toShapes`) and places the active tokens in `LocalStyleTokens`. Screens read them through `OmnisonicTheme.tokens`.
- Material component colors therefore also come from the tokens; no screen defines its own palette.

## Navigation defaults

| Orientation | Default | Token |
|---|---|---|
| Portrait | bottom tab bar | `layout.portraitNavigation = BOTTOM_BAR` |
| Landscape | navigation rail | `layout.landscapeNavigation = RAIL` |

Both are ordinary defaults, not decoded reference behavior `[INFERRED]`; the shell preferences store can override tab visibility/order and navigation type without touching the token object.

## Rules for screens

1. No literal color, dimension, text size, grid count or nav choice in a composable. Read `OmnisonicTheme.tokens` (or the `MaterialTheme` values derived from it).
2. New UI values are added to the appropriate token group with a hard-coded default here, not at the call site.
3. A screen must not know where tokens come from; Phase 9 changes only the source.
4. Tests assert token mapping/defaults, not pixel screenshots. Device frame-time and visual soak checks stay `[UNKNOWN]` until hardware exists.

## Test coverage

`StyleTokensTest` (7 tests, JUnit 5): every color alpha = 1, spacing scale ordered and non-negative, grid/nav sizes valid, typography roles map to Material roles, color roles map to `ColorScheme`, corner tokens map to `Shapes`, orientation selects the right nav layout and grid columns, source seam accepts a replacement, and a partial override leaves unrelated groups unchanged.
