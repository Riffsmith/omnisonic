package app.omnisonic.core.model.style

/**
 * A document with no field left at its default, used by the writer and
 * normalization tests so a round-trip exercises every section and layer kind.
 */
internal fun fullStyleDocument(): StyleDocument = StyleDocument(
    id = "style-full",
    name = "Full style",
    app = StyleApp(
        colorSource = ColorSource.SEED,
        seedColor = "#112233",
        darkMode = DarkMode.BLACK,
        colorPalette = ColorPalette.VIBRANT,
        colorContrast = ColorContrast.HIGH,
        cornerScale = CornerRoundness.LARGE,
        fontFamilies = StyleFontFamilies(
            title = "Heading Face",
            body = null,
            lyrics = "Lyric Face",
            nav = "Nav Face",
        ),
        effects = StyleEffects(
            quality = QualityTier.HIGH,
            progressiveBlur = ProgressiveBlur.OFF,
            overlayAlpha = 0.25f,
            legibilityShield = false,
            artColorFollowing = true,
            audioReactive = true,
            tiltParallax = true,
            tiltParallaxMaxOffsetDp = 20f,
        ),
    ),
    home = StyleHome(
        sections = listOf(
            HomeSectionEntry(HomeSection.MOST_PLAYED),
            HomeSectionEntry(HomeSection.FAVORITES, visible = false),
        ),
    ),
    pages = StylePages(
        albums = StyleListLayout(
            columns = OrientationPair(portrait = 2, landscape = 4),
            spacingDp = OrientationPair(portrait = 4f, landscape = 12f),
        ),
    ),
    nowPlaying = StyleNowPlaying(
        compact = StyleCompactPlayer(
            blocks = listOf(
                NowPlayingBlock(NowPlayingBlockKind.ARTWORK),
                NowPlayingBlock(NowPlayingBlockKind.CONTROLS, visible = false),
            ),
            buttons = listOf(
                StyleControlButton(PlayerAction.PREVIOUS),
                StyleControlButton(
                    action = PlayerAction.PLAY_PAUSE,
                    style = ControlButtonStyle.FILLED,
                    size = ControlButtonSize.LARGE,
                ),
            ),
            text = StyleTextBlocks(
                title = StyleTextBlock(
                    template = "%title%",
                    alignment = TextAlignment.CENTER,
                    maxLines = 2,
                ),
                subtitle = StyleTextBlock(template = "%artist%", alignment = TextAlignment.END),
            ),
            progress = StyleProgress(
                appearance = ProgressAppearance.HAIRLINE,
                heightDp = 1f,
                showRemainingTime = true,
            ),
        ),
        expanded = OrientationPair(
            portrait = StyleExpandedPlayer(blocks = listOf(NowPlayingBlock(NowPlayingBlockKind.ARTWORK))),
            landscape = StyleExpandedPlayer(blocks = listOf(NowPlayingBlock(NowPlayingBlockKind.QUEUE))),
        ),
        background = StyleBackdrop(
            extent = BackdropExtent.FULL_SCREEN,
            layers = listOf(
                StyleSolidBackdropLayer(color = BackdropColor.SCRIM, alpha = 0.5f),
                StyleGradientBackdropLayer(
                    anchor = BackdropAnchor.TOP_AND_BOTTOM,
                    artTint = 0.25f,
                    blend = BackdropBlendMode.SCREEN,
                ),
                StyleAlbumArtBackdropLayer(scale = 1.5f),
                StyleBlurBackdropLayer(radiusDp = 32f, extent = BackdropBlurExtent.FULL_SCREEN),
                StyleGrainBackdropLayer(density = 0.25f),
                StyleVignetteBackdropLayer(strength = 0.75f),
                StyleGradientMeshBackdropLayer(intensity = 0.5f),
            ),
        ),
    ),
)
