# UI style tokens — `ui/style-tokens.md`

Purpose: define the single source of every color, size, typography and layout value in the Omnisonic UI, the hard-coded Phase 5 default, and the seam Phase 9 replaces with JSON style profiles.

How derived: own design for Phase 5 (`.agents/plans/phase-plans.md` §Phase 5: "all colors, sizes and layout read from a `StyleTokens` object with one hard-coded default"). Navigation defaults follow ordinary mobile/tablet convention because the studied behavior notes do not decode the reference enum; that choice is ours and configurable. Revised 2026-11-07 (task 1b) to absorb the Phase 9 alignment deltas: full 15-role type ramp, corner-scale-as-a-value, and a per-orientation nav label-mode token (roadmap Phase 5 "task-1 delta"). Extended 2026-11-07 (task 1c) with the effects/motion/font groups and the tab-swipe/tab-bar-style layout tokens; API floors and degradation semantics follow `reference/docs/GESTURES.md` §3 (capability matrix), preset/axis modeling follows THEMING.md §9.1 (studied overlay labels re-modeled — never re-named; mapping in GESTURES.md §5.1). Revised 2026-11-08 (task 1d, decision D-13): backdrops became an ordered **layer stack**, per-surface styles landed, the device quality tier and the auto legibility shield arrived as pure token logic, and the remaining D-13 flags (audio-reactive, art-color-following, accent overrides, edge exclusion) joined the surface.

Confidence: `[VERIFIED]` against `style/src/main/java/app/omnisonic/style/StyleTokens.kt`, `BackdropStack.kt`, `OmnisonicTheme.kt` and `style/src/test/java/app/omnisonic/style/StyleTokensTest.kt` (23 tests). Dynamic color, dark-mode switching, JSON profile loading and the actual effect/font renderers are Phase 9 / screen-task work and not implemented here `[UNKNOWN]` until then.

Date: 2026-11-08 (initial 2026-11-04)

Links: `decisions.md` (D-13); `docs/omnisonic/README.md`; roadmap Phase 5 (UI shell + search) and Phase 9 (JSON style system); `.agents/plans/phase-5-tasks.md` task 1d; `reference/docs/UI_SPEC.md` §4-5.

---

## Model

`StyleTokens` (`app.omnisonic.style`) is an immutable data holder with ten groups:

| Group | Type | Contents |
|---|---|---|
| Colors | `ColorTokens` | 36 Material color roles (including surface containers and inverse roles) plus UI roles: `coverPlaceholder`, `onCoverPlaceholder`, `progressTrack`, `progressActive`, `nowPlayingBackground`, `selectedIndicator`; plus `accentOverrides` (see "Accent overrides") |
| Typography | `TypographyTokens` | the full 15-role M3 ramp — display/headline/title/body/label × large/medium/small, each a `TypeToken` (size/line-height/weight/letter-spacing) — plus `caption` as an app-specific extra role read directly from tokens (not part of `MaterialTheme` typography) |
| Spacing | `SpacingTokens` | `xxs` 2dp through `xxl` 32dp |
| Shapes | `ShapeTokens` | `cornerScale` (four stops, see below) with the small/medium/large/extra-large radii **derived** from it, plus explicit `cover` and `pill` radii |
| Layout | `LayoutTokens` | navigation layout per orientation, label mode per orientation, tab-swipe mode per orientation, tab-bar style per orientation, bottom-bar/rail sizes, grid columns per orientation, grid spacing, page padding, row heights, detail/player art sizes, content width, section spacing, `edgeExclusionDp` (24dp) |
| Imagery | `ImageryTokens` | list thumbnail size, grid aspect ratio, detail/now-playing art sizes, placeholder tint alpha |
| Surfaces | `SurfaceStyleTokens` | per-surface alpha / blur / tint / border for nav bar, tab bar, mini-player, cards, sheets (see "Per-surface styles") |
| Effects | `EffectTokens` | the backdrop `BackdropStack`, overlay/scrim alpha, legibility shield, progressive-blur mode, `effectsQuality`, `audioReactive`, `artColorFollowing`, tilt-parallax flags (see "Backdrop layer stack" and "Quality tier and legibility shield") |
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

### Edge exclusion

`layout.edgeExclusionDp` (default **24dp**, UI_SPEC §1.2): drags starting within this distance of the screen edge are never claimed by pager/swipe surfaces — predictive back owns the strip. Task 2a's SwipeZone contract consumes the token; the value is a token so Phase 9 styles can widen it.

## Backdrop layer stack

Decision D-13 (2026-11-08, task 1d): the 1c preset enum is gone; a backdrop is a **`BackdropStack`** — an ordered list of typed `BackdropLayer`s drawn bottom-up, each with its own params, `alpha` (0-1) and `blend` (`NORMAL | MULTIPLY | SCREEN | OVERLAY`). Layer types (`BackdropLayerType`): `solid` (a `BackdropColorRole`), `gradient` (anchor from the 1c six-anchor enum, start/end color roles, `artTint` 0-1), `albumArt` (cover image, `scale`), `blur` (`radiusDp`, `scope` — the 1c `NOW_PLAYING | NAV_BAR | FULL_SCREEN` enum), `grain` (`density`), `vignette` (`strength`), `gradientMesh` (`intensity`). Params are construction-validated (ranges, non-empty stacks). Layers resolve colors through `BackdropColorRole.resolve(colors)` — never raw ARGB — so theme changes flow through.

The 1c presets survive as **`DefaultBackdropStacks` named stacks** (`SUBTLE`, `SOLID`, `GRADIENT`, `ART_BLURRED`, `CARD`, `FULL_BAR`); presets no longer cap customization — they name complete examples Phase 9 can store copies of. `FULL_BAR` sets `scope = FULL_SCREEN` (edge-to-edge, under system bars); the rest are `NOW_PLAYING`.

**Default look = "Subtle"** (`EffectTokens.backdropStack` default): a solid `background` base plus one soft gradient (`alpha` 0.35, `artTint` 0.35) — clean dark, art-tinted player backdrop only. `artTint` expresses how strongly the renderer *may* tint the gradient toward the current track's art palette when art colors are available; with none extracted the gradient falls back to its color roles. This is backdrop-level tinting only — the app-wide scheme/accent derivation is `artColorFollowing` and stays **opt-in, default OFF** (D-13). The AMOLED pure-black dark variant stays a Phase 9 `darkMode` schema concern; the Phase 5 default palette is already near-black.

## Quality tier and legibility shield

- `effectsQuality: auto | low | high` (`EffectsQuality`). `auto` classifies the device at first run (RAM/GPU heuristics — Phase 9 work); until a classification exists it resolves conservatively to `low` (`resolveTier()`). `low` caps blur radius at 16dp (`capBlurRadius`, never raises smaller radii) and drops the API-33-only layers; `high` draws everything the platform allows.
- `BackdropStack.effectiveLayers(quality, apiLevel)` is the one place renderers get their layer list: layers **drop below their API floor** (blur < 31, grain/vignette/gradient-mesh and progressive blur < 33 — `supportedOn(apiLevel)`), the AGSL-only layers drop at the `low` tier, and blur radius comes back capped at `low`. Base layers (solid/gradient/albumArt) always survive, so a degraded backdrop is still a valid backdrop.
- **Legibility shield** (`LegibilityShield.scrimAlpha`): a pure contrast function — given backdrop and text luminances (sRGB-relative 0-1), the manual scrim floor (`backdropOverlayAlpha`, default 0.4) and the master switch (`backdropLegibilityShield`, default on), it raises the scrim until text contrast reaches 4.5:1 (composited backdrop ≈ `luminance × (1 − alpha)`; dark text on dark backdrops cannot be rescued by darkening and passes the manual floor through; unreachable targets clamp to a full scrim). Renderers compose: `max(manual floor, auto raise)`.
- **Audio-reactive** (`audioReactive`, default **OFF**): the future FFT tap rides the Phase 4 sink-processor seam (our own audio path — no `RECORD_AUDIO` permission, the Android `Visualizer` API is NOT used). Phase 5 ships only the flag; data plumbing and the visual styles (pulse/breathe shaders) are Phase 9 content.
- **Tilt parallax** (`tiltParallaxEnabled`, default OFF; `tiltParallaxMaxOffsetDp` 12dp): gyroscope-driven offset for art/backdrop, an API-33+ AGSL-class effect in D-13's chosen set. Film grain and gradient mesh exist as layer types above. Chromatic aberration and touch ripple are **not built** (declined, D-13).

**Degradation rules (binding for every renderer):** per the capability matrix (GESTURES.md §3), blur drops below API 31 and the AGSL layers (grain, vignette, gradient mesh, progressive blur) below API 33 — `effectiveLayers` already removed them, so the renderer draws what remains. Effects must never be load-bearing for legibility: the surviving base layers plus `backdropOverlayAlpha` + `backdropLegibilityShield` keep text readable in every fallback. The API and quality decisions are pure token logic (`supportedOn`, `effectiveLayers`, `ProgressiveBlurMode.active`, `resolveTier`, `capBlurRadius`, `scrimAlpha`) so fallback behavior is unit-tested without a device. Renderers themselves land with the screens that use them; on-device effect behavior is `[UNKNOWN]` until hardware.

## Per-surface styles

`SurfaceStyleTokens` (task 1d) carries one `SurfaceStyle` per surface role — `navBar`, `tabBar`, `miniPlayer`, `cards`, `sheets` — each with `alpha`, `blurRadiusDp`, `tintAlpha`, `borderAlpha`. Tint draws over the `surfaceTint` color token, border over `outlineVariant` (both already tokens); surface blur rides the same quality caps as the backdrop. Defaults are "Subtle": plain bars/cards/sheets (`alpha` 1, no blur/tint/border), mini-player slightly translucent with a hairline border (`alpha` 0.92, `borderAlpha` 0.15). The 1c `navBarAlpha`/`tabBarAlpha` fields were absorbed here (`navBar.alpha`/`tabBar.alpha`). Consumers: shell (task 2) for the bars, task 6a for the mini-player, task 4/5 for cards, Phase 9 for sheets.

## Motion

`MotionTokens` (task 1c) is Omnisonic-original — the studied key surface has no motion section (ROADMAP G4). `durationScale` (0-1) multiplies every duration (0 disables); `easingProfile` (`STANDARD`, `EMPHASIZED`, `EXPRESSIVE`) maps to concrete `CubicBezierEasing` curves via `toEasing()` (own values). Per-interaction specs: `tabTransition` (250 ms tween), `nowPlayingExpand` (spring, damping 0.85 / stiffness 380 — the mini-player drag-expand feel, GESTURES.md §4.4), `queueReorder` (300 ms), `artCrossfade` (200 ms). Values are our own starting points; tuning happens at the consuming screens (task 2/6) and stays device-gated `[UNKNOWN]`.

## Fonts

`FontTokens` (task 1c): `fontFamilies` carries one family ref per role (`title`, `body`, `lyrics`, `nav`); `null` renders the platform default, a non-null ref names a downloadable-catalog entry (the studied app's Google-Fonts mechanism, THEMING.md §9.3 — behavior borrowed, catalog resolver not built yet) or a `CustomFontFace.id`. `customFontUris` is the Omnisonic-original SAF-import type (`id` + `content://` uri, TTF/OTF); copying faces into app storage and building `FontFamily`s is later-phase work, as are variable-font axes (Compose `FontVariation`, API 26+). Nothing renders a custom face in Phase 5 `[UNKNOWN]` until the loader exists.

## Accent overrides

`ColorTokens.accentOverrides` (task 1d) is the resolved view of per-album/artist accent pins (D-13): `AccentOverride(targetType: ALBUM | ARTIST, externalId, accent: Color)`, queried with `accentFor(targetType, externalId)` — the last pin per target wins, `null` falls back to `tokens.colors.primary`. Phase 9 owns the stored map (style JSON / server data); Phase 5 ships the type, the resolver and an empty default.

## Rules for screens

1. No literal color, dimension, text size, grid count or nav choice in a composable. Read `OmnisonicTheme.tokens` (or the `MaterialTheme` values derived from it).
2. New UI values are added to the appropriate token group with a hard-coded default here, not at the call site.
3. A screen must not know where tokens come from; Phase 9 changes only the source.
4. Tests assert token mapping/defaults, not pixel screenshots. Device frame-time and visual soak checks stay `[UNKNOWN]` until hardware exists.

## Test coverage

`StyleTokensTest` (23 tests, JUnit 5): every color alpha = 1, spacing scale ordered and non-negative, grid/nav sizes and all radii non-negative, edge exclusion = 24dp, accent overrides empty, all five surface styles in range; the 15-role ramp maps 1:1 onto `material3.Typography` (full `TextStyle` equality per role, plus per-family size ordering and the caption extra), color roles map to `ColorScheme`, corner tokens map to `Shapes`, the corner scale derives all four radii per stop (default stop = the original 4/8/16/24 values; cover/pill unaffected; `fromLevel` clamps), orientation selects the nav layout, label mode, grid columns, tab-swipe mode and tab-bar style (defaults + per-orientation overrides), the source seam accepts a replacement, a partial override (colors, corner scale, effects, surfaces, motion) leaves unrelated groups unchanged, effect defaults are stable and safe (default stack = "Subtle", overlay alpha 0.4, shield on, `progressiveBlur = AUTO`, `effectsQuality = AUTO`, `audioReactive`/`artColorFollowing`/tilt-parallax off) with range + construction validation asserted, the named stacks are valid with ordered bases (solid/gradient/albumArt first, blur never first, `FULL_BAR` scope = full screen) and "Subtle" is exactly solid + soft art-tinted gradient, the quality tier resolves AUTO→LOW and caps blur / drops AGSL layers at `low`, layer support follows the API floors (base layers always survive; blur < 31, AGSL < 33 drop), the legibility shield raises scrim exactly to the 4.5:1 threshold and clamps/passthroughs correctly, accent overrides resolve last-pin-per-target, and motion/font defaults are stable. Device frame-time, effect-render and gesture-feel checks stay `[UNKNOWN]` until hardware exists.

## Consumers

The shell (task 2) reads `TabSwipeMode`/`tabBarStyle`, the surface styles for its bars and `edgeExclusionDp` (via the task-2a SwipeZone contract); the now-playing screens (task 6) render `EffectTokens.backdropStack` through `effectiveLayers(...)` with `MotionTokens.nowPlayingExpand`; task 6a styles the mini-player from `SurfaceStyleTokens.miniPlayer`; Phase 9 swaps the `StyleTokenSource` with no screen changes. The studied-name mapping table lives in GESTURES.md §5.1; never port a studied name verbatim into these tokens.
