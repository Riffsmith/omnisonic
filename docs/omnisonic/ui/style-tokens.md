# UI style tokens — `ui/style-tokens.md`

Purpose: define the single source of every color, size, typography and layout value in the Omnisonic UI, the hard-coded Phase 5 default, and the seam Phase 9 replaces with JSON style profiles.

How derived: own design for Phase 5 (`.agents/plans/phase-plans.md` §Phase 5: "all colors, sizes and layout read from a `StyleTokens` object with one hard-coded default"). Navigation defaults follow ordinary mobile/tablet convention because the studied behavior notes do not decode the reference enum; that choice is ours and configurable. Revised 2026-11-07 (task 1b) to absorb the Phase 9 alignment deltas: full 15-role type ramp, corner-scale-as-a-value, and a per-orientation nav label-mode token (roadmap Phase 5 "task-1 delta").

Confidence: `[VERIFIED]` against `style/src/main/java/app/omnisonic/style/StyleTokens.kt`, `OmnisonicTheme.kt` and `style/src/test/java/app/omnisonic/style/StyleTokensTest.kt` (9 tests). Dynamic color, dark-mode switching and JSON profile loading are Phase 9 work and not implemented here `[UNKNOWN]` until then.

Date: 2026-11-07 (initial 2026-11-04)

Links: `decisions.md`; `docs/omnisonic/README.md`; roadmap Phase 5 (UI shell + search) and Phase 9 (JSON style system); `.agents/plans/phase-5-tasks.md` task 1.

---

## Model

`StyleTokens` (`app.omnisonic.style`) is an immutable data holder with six groups:

| Group | Type | Contents |
|---|---|---|
| Colors | `ColorTokens` | 36 Material color roles (including surface containers and inverse roles) plus UI roles: `coverPlaceholder`, `onCoverPlaceholder`, `progressTrack`, `progressActive`, `nowPlayingBackground`, `selectedIndicator` |
| Typography | `TypographyTokens` | the full 15-role M3 ramp — display/headline/title/body/label × large/medium/small, each a `TypeToken` (size/line-height/weight/letter-spacing) — plus `caption` as an app-specific extra role read directly from tokens (not part of `MaterialTheme` typography) |
| Spacing | `SpacingTokens` | `xxs` 2dp through `xxl` 32dp |
| Shapes | `ShapeTokens` | `cornerScale` (four stops, see below) with the small/medium/large/extra-large radii **derived** from it, plus explicit `cover` and `pill` radii |
| Layout | `LayoutTokens` | navigation layout per orientation, label mode per orientation, bottom-bar/rail sizes, grid columns per orientation, grid spacing, page padding, row heights, detail/player art sizes, content width, section spacing |
| Imagery | `ImageryTokens` | list thumbnail size, grid aspect ratio, detail/now-playing art sizes, placeholder tint alpha |

All values are Compose `Color`/`Dp`/`TextUnit` types so screens can consume them without conversion helpers. `LayoutTokens.navigationFor(isLandscape)`, `labelModeFor(isLandscape)` and `gridColumnsFor(isLandscape)` resolve orientation-dependent choices in one place.

### Type ramp

The ramp is the complete Material 3 role set (`displayLarge`…`labelSmall`); `toTypography()` maps every role 1:1 into `material3.Typography`. The 2026-11-04 shape collapsed the ramp onto six scales; task 1b expanded it so Phase 9 style JSON can restyle each M3 role independently without a schema migration. Default ramp (own values; the 2026-11-04 anchors — display 28sp, headline 22sp, title 16sp, body 14sp, label 12sp, caption 11sp — remain the medium/primary stops of their families):

| Family | Large | Medium | Small |
|---|---|---|---|
| Display | 32/40 Bold | 28/36 Bold | 24/32 Bold |
| Headline | 24/32 SemiBold | 22/28 SemiBold | 20/26 SemiBold |
| Title | 18/24 Medium | 16/22 Medium | 14/20 Medium |
| Body | 16/24 Normal | 14/20 Normal | 12/16 Normal |
| Label | 14/20 Medium | 12/16 Medium | 11/16 Medium |

`caption` (11/14 Normal) stays outside the ramp as an app token for dense meta text.

### Corner scale

`CornerScale` is a four-stop value (`DEFAULT`=0, `MEDIUM`=1, `LARGE`=2, `EXTRA_LARGE`=3; the int is the Phase 9 JSON encoding, `cornerScale: 0-3`, with out-of-range values falling back to `DEFAULT`). The four corner radii are derived properties of the stop — restyling roundness is a one-token change, not four. The `DEFAULT` stop is exactly the 2026-11-04 radii (4/8/16/24 dp); higher stops round progressively up to 14/24/38/56 dp. `cover` (8 dp) and `pill` (50 dp) stay explicit and are unaffected by the scale. Stop names and radii are our own values, equivalent in shape (four stops, increasing roundness) to the studied corner-size int [INFERRED].

## Default and theme wiring

- `DefaultStyleTokens` is the one hard-coded default: a dark palette with a blue primary seed, standard spacing scale and 3/5-column grids for portrait/landscape.
- `StyleTokenSource` is a single-method seam; `DefaultStyleTokenSource` returns the default. Phase 9 supplies a JSON-profile-backed source without changing screens. `[VERIFIED]` by `defaultSourceReturnsTheHardCodedDefaultAndCanBeReplaced`.
- `OmnisonicTheme(tokens)` provides `MaterialTheme` with a color scheme, typography and shapes derived from the tokens (`toColorScheme`, `toTypography`, `toShapes`) and places the active tokens in `LocalStyleTokens`. Screens read them through `OmnisonicTheme.tokens`.
- Material component colors therefore also come from the tokens; no screen defines its own palette.

## Navigation defaults

| Orientation | Default nav | Token | Default label mode | Token |
|---|---|---|---|---|
| Portrait | bottom tab bar | `layout.portraitNavigation = BOTTOM_BAR` | all items labelled | `layout.portraitLabelMode = ALL` |
| Landscape | navigation rail | `layout.landscapeNavigation = RAIL` | all items labelled | `layout.landscapeLabelMode = ALL` |

`NavigationLabelMode` is `ALL | SELECTED_ONLY | NONE`, resolved per orientation by `labelModeFor(isLandscape)` — the label variants of the studied nav families need no shell or screen changes, only token values. Nav layout stays a two-value enum (`BOTTOM_BAR | RAIL`) for Phase 5; `NavigationLayout` and `NavigationLabelMode` are separate tokens so later nav-family additions extend the layout enum without touching the label contract. Both choices are ours, not decoded reference behavior `[INFERRED]`; the shell preferences store can override tab visibility/order and navigation type without touching the token object.

## Rules for screens

1. No literal color, dimension, text size, grid count or nav choice in a composable. Read `OmnisonicTheme.tokens` (or the `MaterialTheme` values derived from it).
2. New UI values are added to the appropriate token group with a hard-coded default here, not at the call site.
3. A screen must not know where tokens come from; Phase 9 changes only the source.
4. Tests assert token mapping/defaults, not pixel screenshots. Device frame-time and visual soak checks stay `[UNKNOWN]` until hardware exists.

## Test coverage

`StyleTokensTest` (9 tests, JUnit 5): every color alpha = 1, spacing scale ordered and non-negative, grid/nav sizes and all six radii non-negative, the 15-role ramp maps 1:1 onto `material3.Typography` (full `TextStyle` equality per role, plus per-family size ordering and the caption extra), color roles map to `ColorScheme`, corner tokens map to `Shapes`, the corner scale derives all four radii per stop (default stop = the original 4/8/16/24 values; cover/pill unaffected; `fromLevel` clamps), orientation selects the nav layout, label mode and grid columns, label-mode overrides resolve per orientation, the source seam accepts a replacement, and a partial override (colors or corner scale) leaves unrelated groups unchanged.

## Planned extension — task 1c (effects, motion, fonts, tab-swipe)

Spec source: `reference/ROADMAP.md` §Phase 5 (2026-11-07 revision), `reference/docs/GESTURES.md` §3-5 (capability matrix, design, **naming rules**), `reference/docs/THEMING.md` §9 (studied evidence). This section is a plan, not shipped code — the shipped model is the six groups above until task 1c lands.

| New group | Tokens (Omnisonic names — coined, diffed against the studied key surface per GESTURES.md §5) | Notes |
|---|---|---|
| `EffectTokens` | `backdropPreset` (SOLID / GRADIENT family with position+strength / ART_BLURRED / CARD / FULL_BAR), `backdropOverlayAlpha`, `backdropBlurRadiusDp`, `backdropBlurScope {NOW_PLAYING, NAV_BAR, FULL_SCREEN}`, `progressiveBlurEnabled`, `vignetteStrength`, `navBarAlpha`, `tabBarAlpha` | API-gated values (progressive blur, vignette: API 33+) must degrade gracefully; legibility never depends on an effect. Capability checklist = the studied overlay label family (THEMING.md §9.1), re-modeled with a strength axis instead of "darker" duplicates |
| `MotionTokens` | `durationScale`, `easingProfile`, `tabTransition`, `nowPlayingExpand` (spring), `queueReorder`, `artCrossfade` | Omnisonic-original (absent from the studied key surface); the mini-player expand spring is what makes gesture navigation "feel" right (GESTURES.md §4.4) |
| `FontTokens` | family refs per role (title/body/lyrics/nav) + `customFontUris` (SAF import type) | Downloadable-catalog + custom import; loading mechanics land with the screens that consume them |
| `LayoutTokens` additions | `TabSwipeMode {ENABLED, DISABLED}`, `tabBarStyle {VISIBLE, HIDDEN}` (per orientation) | With the existing nav/label tokens this composes into a Poweramp-style single-page gesture mode (GESTURES.md §4.3) — no new nav-family enum |

Consumers: the shell (task 2) reads `TabSwipeMode`/`tabBarStyle` and hosts the SwipeZone contract; the now-playing screens (task 6) read `backdropPreset`/`MotionTokens.nowPlayingExpand`. Phase 9 then swaps the `StyleTokenSource` — no screen changes. The studied-name mapping table lives in GESTURES.md §5.1; never port a studied name verbatim into these tokens.
