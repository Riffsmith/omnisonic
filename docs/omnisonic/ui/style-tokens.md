# UI style tokens — `ui/style-tokens.md`

Purpose: define the single source of every color, size, typography and layout value in the Omnisonic UI, the hard-coded Phase 5 default, and the seam Phase 9 replaces with JSON style profiles.

How derived: own design for Phase 5 (`.agents/plans/phase-plans.md` §Phase 5: "all colors, sizes and layout read from a `StyleTokens` object with one hard-coded default"). Navigation defaults follow ordinary mobile/tablet convention because the studied behavior notes do not decode the reference enum; that choice is ours and configurable. Revised 2026-11-07 (task 1b) to absorb the Phase 9 alignment deltas: full 15-role type ramp, corner-scale-as-a-value, and a per-orientation nav label-mode token (roadmap Phase 5 "task-1 delta"). Extended 2026-11-07 (task 1c) with the effects/motion/font groups and the tab-swipe/tab-bar-style layout tokens; API floors and degradation semantics follow `reference/docs/GESTURES.md` §3 (capability matrix), preset/axis modeling follows THEMING.md §9.1 (studied overlay labels re-modeled — never re-named; mapping in GESTURES.md §5.1).

Confidence: `[VERIFIED]` against `style/src/main/java/app/omnisonic/style/StyleTokens.kt`, `OmnisonicTheme.kt` and `style/src/test/java/app/omnisonic/style/StyleTokensTest.kt` (15 tests). Dynamic color, dark-mode switching, JSON profile loading and the actual effect/font renderers are Phase 9 / screen-task work and not implemented here `[UNKNOWN]` until then.

Date: 2026-11-07 (initial 2026-11-04)

Links: `decisions.md`; `docs/omnisonic/README.md`; roadmap Phase 5 (UI shell + search) and Phase 9 (JSON style system); `.agents/plans/phase-5-tasks.md` task 1.

---

## Model

`StyleTokens` (`app.omnisonic.style`) is an immutable data holder with nine groups:

| Group | Type | Contents |
|---|---|---|
| Colors | `ColorTokens` | 36 Material color roles (including surface containers and inverse roles) plus UI roles: `coverPlaceholder`, `onCoverPlaceholder`, `progressTrack`, `progressActive`, `nowPlayingBackground`, `selectedIndicator` |
| Typography | `TypographyTokens` | the full 15-role M3 ramp — display/headline/title/body/label × large/medium/small, each a `TypeToken` (size/line-height/weight/letter-spacing) — plus `caption` as an app-specific extra role read directly from tokens (not part of `MaterialTheme` typography) |
| Spacing | `SpacingTokens` | `xxs` 2dp through `xxl` 32dp |
| Shapes | `ShapeTokens` | `cornerScale` (four stops, see below) with the small/medium/large/extra-large radii **derived** from it, plus explicit `cover` and `pill` radii |
| Layout | `LayoutTokens` | navigation layout per orientation, label mode per orientation, tab-swipe mode per orientation, tab-bar style per orientation, bottom-bar/rail sizes, grid columns per orientation, grid spacing, page padding, row heights, detail/player art sizes, content width, section spacing |
| Imagery | `ImageryTokens` | list thumbnail size, grid aspect ratio, detail/now-playing art sizes, placeholder tint alpha |
| Effects | `EffectTokens` | backdrop preset + gradient anchor + strength/overlay-alpha axes, legibility shield, blur radius/scope, progressive-blur mode, vignette strength, nav/tab bar alphas (see "Effects and degradation") |
| Motion | `MotionTokens` | `durationScale`, `easingProfile` (+ concrete curve mapping), per-interaction specs `tabTransition`, `nowPlayingExpand` (spring), `queueReorder`, `artCrossfade` |
| Fonts | `FontTokens` | `fontFamilies` per role (title/body/lyrics/nav; `null` = platform default) + `customFontUris` (SAF-imported face type; loading is later-phase) |

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

### Tab swipe and tab-bar style

`TabSwipeMode { ENABLED, DISABLED }` and `TabBarStyle { VISIBLE, HIDDEN }` (task 1c) resolve per orientation via `tabSwipeModeFor(isLandscape)` / `tabBarStyleFor(isLandscape)`, defaults `ENABLED` / `VISIBLE`. Together with the nav + label tokens they compose the gesture-only shell without a new nav family: `BOTTOM_BAR + HIDDEN + ENABLED + NONE` = invisible tabs, horizontal swipe walks the main destinations, mini-player gestures handle playback (GESTURES.md §4.3). Tab+pager pairing itself is Omnisonic-original — the studied app has no tab pager (GESTURES.md §1); the shell (task 2) owns one shared `PagerState`.

## Rules for screens

1. No literal color, dimension, text size, grid count or nav choice in a composable. Read `OmnisonicTheme.tokens` (or the `MaterialTheme` values derived from it).
2. New UI values are added to the appropriate token group with a hard-coded default here, not at the call site.
3. A screen must not know where tokens come from; Phase 9 changes only the source.
4. Tests assert token mapping/defaults, not pixel screenshots. Device frame-time and visual soak checks stay `[UNKNOWN]` until hardware exists.

## Test coverage

`StyleTokensTest` (15 tests, JUnit 5): every color alpha = 1, spacing scale ordered and non-negative, grid/nav sizes and all radii non-negative, the 15-role ramp maps 1:1 onto `material3.Typography` (full `TextStyle` equality per role, plus per-family size ordering and the caption extra), color roles map to `ColorScheme`, corner tokens map to `Shapes`, the corner scale derives all four radii per stop (default stop = the original 4/8/16/24 values; cover/pill unaffected; `fromLevel` clamps), orientation selects the nav layout, label mode, grid columns, tab-swipe mode and tab-bar style (defaults + per-orientation overrides), the source seam accepts a replacement, a partial override (colors, corner scale, effects, motion) leaves unrelated groups unchanged, effect defaults are stable with API-gated fields in their safe state (`progressiveBlur = AUTO`, vignette off) with the alpha/radius ranges and API-floor logic asserted (`ProgressiveBlurMode.active`, `vignetteRequested`), motion defaults are stable and valid (durations > 0, spring damping/stiffness positive, easing curves stable and distinct per profile), and font tokens default to platform families with no custom imports (role/isolation overrides). Device frame-time, effect-render and gesture-feel checks stay `[UNKNOWN]` until hardware exists.

## Effects and degradation

`EffectTokens` (task 1c) models the now-playing backdrop as five orthogonal presets plus continuous axes — the studied overlay label family (THEMING.md §9.1) re-modeled, never re-named (counterparts in GESTURES.md §5.1):

| Token | Default | Meaning |
|---|---|---|
| `backdropPreset` | `SOLID` | `SOLID` (plain color), `GRADIENT`, `ART_BLURRED`, `CARD`, `FULL_BAR` |
| `backdropGradientAnchor` | `TOP` | gradient direction when the preset is `GRADIENT`: `TOP`, `BOTTOM`, `TOP_AND_BOTTOM`, `START`, `END`, `START_AND_END` |
| `backdropStrength` | `0.5` | per-preset intensity (0-1) — the collapsed axis for the studied "darker/more pronounced" duplicate enum values |
| `backdropOverlayAlpha` | `0.4` | uniform scrim alpha (0-1) over the backdrop |
| `backdropLegibilityShield` | `true` | scrim behind text where legibility needs it (studied `backgroundProtection` behavior) |
| `backdropBlurRadiusDp` | `24.dp` | blur radius for `ART_BLURRED` (and scope: `NOW_PLAYING`, `NAV_BAR`, `FULL_SCREEN`) |
| `progressiveBlur` | `AUTO` | `AUTO` enables only where the platform supports it, `ON` forces the attempt (renderer no-ops below the floor), `OFF` never |
| `vignetteStrength` | `0` (off) | AGSL-shader vignette, 0-1 |
| `navBarAlpha` / `tabBarAlpha` | `1` | nav/tab bar translucency, 0-1 |

**Degradation rules (binding for every renderer):** per the capability matrix (GESTURES.md §3), fixed-radius blur is a no-op below API 31, progressive blur and any AGSL distortion (vignette) below API 33. Effects must never be load-bearing for legibility: when an effect silently no-ops, the preset still renders its non-effect layers (solid color / gradient scrim / card surface) and `backdropOverlayAlpha` + `backdropLegibilityShield` keep text readable. The API decisions are pure logic on the tokens — `ProgressiveBlurMode.active(apiLevel)` and `EffectTokens.vignetteRequested(apiLevel)` — so the fallback behavior is unit-tested without a device. Renderers themselves land with the screens that use them; on-device effect behavior is `[UNKNOWN]` until hardware.

## Motion

`MotionTokens` (task 1c) is Omnisonic-original — the studied key surface has no motion section (ROADMAP G4). `durationScale` (0-1) multiplies every duration (0 disables); `easingProfile` (`STANDARD`, `EMPHASIZED`, `EXPRESSIVE`) maps to concrete `CubicBezierEasing` curves via `toEasing()` (own values). Per-interaction specs: `tabTransition` (250 ms tween), `nowPlayingExpand` (spring, damping 0.85 / stiffness 380 — the mini-player drag-expand feel, GESTURES.md §4.4), `queueReorder` (300 ms), `artCrossfade` (200 ms). Values are our own starting points; tuning happens at the consuming screens (task 2/6) and stays device-gated `[UNKNOWN]`.

## Fonts

`FontTokens` (task 1c): `fontFamilies` carries one family ref per role (`title`, `body`, `lyrics`, `nav`); `null` renders the platform default, a non-null ref names a downloadable-catalog entry (the studied app's Google-Fonts mechanism, THEMING.md §9.3 — behavior borrowed, catalog resolver not built yet) or a `CustomFontFace.id`. `customFontUris` is the Omnisonic-original SAF-import type (`id` + `content://` uri, TTF/OTF); copying faces into app storage and building `FontFamily`s is later-phase work, as are variable-font axes (Compose `FontVariation`, API 26+). Nothing renders a custom face in Phase 5 `[UNKNOWN]` until the loader exists.

## Consumers

The shell (task 2) reads `TabSwipeMode`/`tabBarStyle` and hosts the SwipeZone contract; the now-playing screens (task 6) read `backdropPreset`/`MotionTokens.nowPlayingExpand`; Phase 9 swaps the `StyleTokenSource` with no screen changes. The studied-name mapping table lives in GESTURES.md §5.1; never port a studied name verbatim into these tokens.

## Planned revision — task 1d (backdrop layer stack, D-13)

Decision D-13 (2026-11-07, user-ratified): backdrops become an **ordered layer stack** (`solid | gradient | albumArt | blur | grain | vignette | gradientMesh`, per-layer params + alpha + blend) — the task-1c `BackdropPreset` enum survives as **named default stacks**, with the default look "Subtle" (solid base + soft art-tinted gradient). Task 1d also adds `SurfaceStyleTokens` (per-surface alpha/blur/tint/border for nav bar, mini-player, cards, sheets), `effectsQuality: auto|low|high` + the auto legibility-shield function, `audioReactive` flag (default OFF — sink-seam FFT hook, no `RECORD_AUDIO`), `accentOverrides` type, and `edgeExclusionDp` (default 24dp). Optional layer types: film grain, tilt parallax (gyroscope), gradient mesh — chromatic aberration and touch ripple are **not built** (declined). Full spec: `reference/docs/UI_SPEC.md` §4-5; schema: ROADMAP §Phase 9. This section is a plan until 1d lands; the shipped groups above remain the build surface for tasks 2-7.
