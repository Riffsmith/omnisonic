package app.omnisonic.core.model.style

private val columnRange = 1..12
private val spacingRange = 0f..64f
private val unitRange = 0f..1f
private val maxLinesRange = 1..4
private val progressHeightRange = 0f..64f
private val tiltOffsetRange = 0f..64f
private val albumArtScaleRange = 0.1f..4f
private val blurRadiusRange = 0f..128f

private val appKeys = setOf(
    "colorSource",
    "seedColor",
    "darkMode",
    "colorPalette",
    "colorContrast",
    "cornerScale",
    "fontFamilies",
    "effects",
)
private val fontFamilyKeys = setOf("title", "body", "lyrics", "nav")
private val effectsKeys = setOf(
    "quality",
    "progressiveBlur",
    "overlayAlpha",
    "legibilityShield",
    "artColorFollowing",
    "audioReactive",
    "tiltParallax",
    "tiltParallaxMaxOffsetDp",
)
private val homeKeys = setOf("sections")
private val homeSectionEntryKeys = setOf("section", "visible")
private val pagesKeys = setOf("albums", "artists", "genres", "songs", "playlists")
private val listLayoutKeys = setOf("columns", "spacingDp")
private val orientationPairKeys = setOf("portrait", "landscape")
private val nowPlayingKeys = setOf("compact", "expanded", "background")
private val playerKeys = setOf("blocks", "buttons", "text", "progress")
private val blockKeys = setOf("kind", "visible")
private val buttonKeys = setOf("action", "style", "size")
private val textBlocksKeys = setOf("title", "subtitle")
private val textBlockKeys = setOf("template", "alignment", "maxLines")
private val progressKeys = setOf("appearance", "heightDp", "showRemainingTime")
private val backdropKeys = setOf("extent", "layers")
private val backdropLayerKeys = setOf("kind", "alpha", "blend")

private val seedColorPattern = Regex("^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

internal fun decodeStyleApp(path: String, node: Map<String, Any?>, issues: StyleIssues): StyleApp {
    val defaults = StyleApp()
    issues.unknownKeys(path, node, appKeys)
    return StyleApp(
        colorSource = issues.optionalField(path, node, "colorSource", defaults.colorSource) { valuePath, value ->
            issues.jsonEnum(valuePath, value, ColorSource.entries)
        },
        seedColor = issues.optionalField(path, node, "seedColor", defaults.seedColor) { valuePath, value ->
            issues.seedColor(valuePath, value)
        },
        darkMode = issues.optionalField(path, node, "darkMode", defaults.darkMode) { valuePath, value ->
            issues.jsonEnum(valuePath, value, DarkMode.entries)
        },
        colorPalette = issues.optionalField(path, node, "colorPalette", defaults.colorPalette) { valuePath, value ->
            issues.jsonEnum(valuePath, value, ColorPalette.entries)
        },
        colorContrast = issues.optionalField(path, node, "colorContrast", defaults.colorContrast) { valuePath, value ->
            issues.jsonEnum(valuePath, value, ColorContrast.entries)
        },
        cornerScale = issues.optionalField(path, node, "cornerScale", defaults.cornerScale) { valuePath, value ->
            issues.jsonEnum(valuePath, value, CornerRoundness.entries)
        },
        fontFamilies = issues.optionalField(path, node, "fontFamilies", defaults.fontFamilies) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { decodeStyleFontFamilies(valuePath, it, issues) }
        },
        effects = issues.optionalField(path, node, "effects", defaults.effects) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { decodeStyleEffects(valuePath, it, issues) }
        },
    )
}

private fun StyleIssues.seedColor(path: String, value: Any?): String? {
    val text = jsonString(path, value) ?: return null
    if (!seedColorPattern.matches(text)) {
        error(path, "expected a #RRGGBB or #AARRGGBB colour, got '$text'")
        return null
    }
    return text
}

private fun decodeStyleFontFamilies(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleFontFamilies {
    issues.unknownKeys(path, node, fontFamilyKeys)
    return StyleFontFamilies(
        title = issues.optionalNullableString(path, node, "title"),
        body = issues.optionalNullableString(path, node, "body"),
        lyrics = issues.optionalNullableString(path, node, "lyrics"),
        nav = issues.optionalNullableString(path, node, "nav"),
    )
}

private fun decodeStyleEffects(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleEffects {
    val defaults = StyleEffects()
    issues.unknownKeys(path, node, effectsKeys)
    return StyleEffects(
        quality = issues.optionalField(path, node, "quality", defaults.quality) { valuePath, value ->
            issues.jsonEnum(valuePath, value, QualityTier.entries)
        },
        progressiveBlur = issues.optionalField(path, node, "progressiveBlur", defaults.progressiveBlur) { valuePath, value ->
            issues.jsonEnum(valuePath, value, ProgressiveBlur.entries)
        },
        overlayAlpha = issues.optionalField(path, node, "overlayAlpha", defaults.overlayAlpha) { valuePath, value ->
            issues.jsonFloat(valuePath, value, unitRange)
        },
        legibilityShield = issues.optionalField(path, node, "legibilityShield", defaults.legibilityShield) { valuePath, value ->
            issues.jsonBoolean(valuePath, value)
        },
        artColorFollowing = issues.optionalField(path, node, "artColorFollowing", defaults.artColorFollowing) { valuePath, value ->
            issues.jsonBoolean(valuePath, value)
        },
        audioReactive = issues.optionalField(path, node, "audioReactive", defaults.audioReactive) { valuePath, value ->
            issues.jsonBoolean(valuePath, value)
        },
        tiltParallax = issues.optionalField(path, node, "tiltParallax", defaults.tiltParallax) { valuePath, value ->
            issues.jsonBoolean(valuePath, value)
        },
        tiltParallaxMaxOffsetDp = issues.optionalField(
            path,
            node,
            "tiltParallaxMaxOffsetDp",
            defaults.tiltParallaxMaxOffsetDp,
        ) { valuePath, value ->
            issues.jsonFloat(valuePath, value, tiltOffsetRange)
        },
    )
}

internal fun decodeStyleHome(path: String, node: Map<String, Any?>, issues: StyleIssues): StyleHome {
    val defaults = StyleHome()
    issues.unknownKeys(path, node, homeKeys)
    val sections = issues.optionalField(path, node, "sections", defaults.sections) { valuePath, value ->
        issues.jsonArray(valuePath, value)?.mapIndexed { index, entry ->
            val entryPath = styleIndexPath(valuePath, index)
            issues.jsonObject(entryPath, entry)?.let { decodeHomeSectionEntry(entryPath, it, issues) }
                ?: HomeSectionEntry()
        }
    }
    return StyleHome(sections = sections)
}

private fun decodeHomeSectionEntry(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): HomeSectionEntry {
    val defaults = HomeSectionEntry()
    issues.unknownKeys(path, node, homeSectionEntryKeys)
    return HomeSectionEntry(
        section = issues.optionalField(path, node, "section", defaults.section) { valuePath, value ->
            issues.jsonEnum(valuePath, value, HomeSection.entries)
        },
        visible = issues.optionalField(path, node, "visible", defaults.visible) { valuePath, value ->
            issues.jsonBoolean(valuePath, value)
        },
    )
}

internal fun decodeStylePages(path: String, node: Map<String, Any?>, issues: StyleIssues): StylePages {
    val defaults = StylePages()
    issues.unknownKeys(path, node, pagesKeys)
    return StylePages(
        albums = decodeListLayoutField(path, node, "albums", defaults.albums, issues),
        artists = decodeListLayoutField(path, node, "artists", defaults.artists, issues),
        genres = decodeListLayoutField(path, node, "genres", defaults.genres, issues),
        songs = decodeListLayoutField(path, node, "songs", defaults.songs, issues),
        playlists = decodeListLayoutField(path, node, "playlists", defaults.playlists, issues),
    )
}

private fun decodeListLayoutField(
    path: String,
    node: Map<String, Any?>,
    key: String,
    fallback: StyleListLayout,
    issues: StyleIssues,
): StyleListLayout = issues.optionalField(path, node, key, fallback) { valuePath, value ->
    issues.jsonObject(valuePath, value)?.let { decodeListLayout(valuePath, it, issues) }
}

private fun decodeListLayout(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleListLayout {
    val defaults = StyleListLayout()
    issues.unknownKeys(path, node, listLayoutKeys)
    return StyleListLayout(
        columns = issues.optionalField(path, node, "columns", defaults.columns) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { pairNode ->
                decodeOrientationPair(valuePath, pairNode, issues, defaults.columns) { itemPath, item ->
                    issues.jsonInt(itemPath, item, columnRange)
                }
            }
        },
        spacingDp = issues.optionalField(path, node, "spacingDp", defaults.spacingDp) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { pairNode ->
                decodeOrientationPair(valuePath, pairNode, issues, defaults.spacingDp) { itemPath, item ->
                    issues.jsonFloat(itemPath, item, spacingRange)
                }
            }
        },
    )
}

private fun <T> decodeOrientationPair(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
    defaults: OrientationPair<T>,
    decodeValue: (String, Any?) -> T?,
): OrientationPair<T> {
    issues.unknownKeys(path, node, orientationPairKeys)
    return OrientationPair(
        portrait = issues.requiredValue(path, node, "portrait", defaults.portrait, decodeValue),
        landscape = issues.requiredValue(path, node, "landscape", defaults.landscape, decodeValue),
    )
}

internal fun decodeStyleNowPlaying(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleNowPlaying {
    val defaults = StyleNowPlaying()
    issues.unknownKeys(path, node, nowPlayingKeys)
    val backgroundPath = stylePath(path, "background")
    val background = when {
        !node.containsKey("background") -> null
        node["background"] == null -> null
        else -> issues.jsonObject(backgroundPath, node["background"])?.let {
            decodeStyleBackdrop(backgroundPath, it, issues)
        }
    }
    return StyleNowPlaying(
        compact = issues.optionalField(path, node, "compact", defaults.compact) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { decodeCompactPlayer(valuePath, it, issues) }
        },
        expanded = issues.optionalField(path, node, "expanded", defaults.expanded) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { pairNode ->
                decodeOrientationPair(valuePath, pairNode, issues, defaults.expanded) { itemPath, item ->
                    issues.jsonObject(itemPath, item)?.let { decodeExpandedPlayer(itemPath, it, issues) }
                }
            }
        },
        background = background,
    )
}

private fun decodeCompactPlayer(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleCompactPlayer {
    val defaults = StyleCompactPlayer()
    issues.unknownKeys(path, node, playerKeys)
    return StyleCompactPlayer(
        blocks = decodeBlocks(path, node, defaults.blocks, issues),
        buttons = decodeButtons(path, node, defaults.buttons, issues),
        text = decodeTextBlocks(path, node, defaults.text, issues),
        progress = decodeProgress(path, node, defaults.progress, issues),
    )
}

private fun decodeExpandedPlayer(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleExpandedPlayer {
    val defaults = StyleExpandedPlayer()
    issues.unknownKeys(path, node, playerKeys)
    return StyleExpandedPlayer(
        blocks = decodeBlocks(path, node, defaults.blocks, issues),
        buttons = decodeButtons(path, node, defaults.buttons, issues),
        text = decodeTextBlocks(path, node, defaults.text, issues),
        progress = decodeProgress(path, node, defaults.progress, issues),
    )
}

private fun decodeBlocks(
    path: String,
    node: Map<String, Any?>,
    fallback: List<NowPlayingBlock>,
    issues: StyleIssues,
): List<NowPlayingBlock> = issues.optionalField(path, node, "blocks", fallback) { valuePath, value ->
    issues.jsonArray(valuePath, value)?.mapIndexed { index, entry ->
        val entryPath = styleIndexPath(valuePath, index)
        issues.jsonObject(entryPath, entry)?.let { decodeBlock(entryPath, it, issues) } ?: NowPlayingBlock()
    }
}

private fun decodeBlock(path: String, node: Map<String, Any?>, issues: StyleIssues): NowPlayingBlock {
    val defaults = NowPlayingBlock()
    issues.unknownKeys(path, node, blockKeys)
    return NowPlayingBlock(
        kind = issues.optionalField(path, node, "kind", defaults.kind) { valuePath, value ->
            issues.jsonEnum(valuePath, value, NowPlayingBlockKind.entries)
        },
        visible = issues.optionalField(path, node, "visible", defaults.visible) { valuePath, value ->
            issues.jsonBoolean(valuePath, value)
        },
    )
}

private fun decodeButtons(
    path: String,
    node: Map<String, Any?>,
    fallback: List<StyleControlButton>,
    issues: StyleIssues,
): List<StyleControlButton> = issues.optionalField(path, node, "buttons", fallback) { valuePath, value ->
    issues.jsonArray(valuePath, value)?.mapIndexed { index, entry ->
        val entryPath = styleIndexPath(valuePath, index)
        issues.jsonObject(entryPath, entry)?.let { decodeButton(entryPath, it, issues) }
            ?: StyleControlButton()
    }
}

private fun decodeButton(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleControlButton {
    val defaults = StyleControlButton()
    issues.unknownKeys(path, node, buttonKeys)
    return StyleControlButton(
        action = issues.optionalField(path, node, "action", defaults.action) { valuePath, value ->
            issues.jsonEnum(valuePath, value, PlayerAction.entries)
        },
        style = issues.optionalField(path, node, "style", defaults.style) { valuePath, value ->
            issues.jsonEnum(valuePath, value, ControlButtonStyle.entries)
        },
        size = issues.optionalField(path, node, "size", defaults.size) { valuePath, value ->
            issues.jsonEnum(valuePath, value, ControlButtonSize.entries)
        },
    )
}

private fun decodeTextBlocks(
    path: String,
    node: Map<String, Any?>,
    fallback: StyleTextBlocks,
    issues: StyleIssues,
): StyleTextBlocks {
    val textPath = stylePath(path, "text")
    if (!node.containsKey("text")) return fallback
    val textNode = issues.jsonObject(textPath, node["text"]) ?: return fallback
    issues.unknownKeys(textPath, textNode, textBlocksKeys)
    return StyleTextBlocks(
        title = issues.optionalField(textPath, textNode, "title", fallback.title) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { decodeTextBlock(valuePath, it, issues, fallback.title) }
        },
        subtitle = issues.optionalField(textPath, textNode, "subtitle", fallback.subtitle) { valuePath, value ->
            issues.jsonObject(valuePath, value)?.let { decodeTextBlock(valuePath, it, issues, fallback.subtitle) }
        },
    )
}

private fun decodeTextBlock(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
    defaults: StyleTextBlock,
): StyleTextBlock {
    issues.unknownKeys(path, node, textBlockKeys)
    return StyleTextBlock(
        template = issues.optionalField(path, node, "template", defaults.template) { valuePath, value ->
            issues.jsonString(valuePath, value)
        },
        alignment = issues.optionalField(path, node, "alignment", defaults.alignment) { valuePath, value ->
            issues.jsonEnum(valuePath, value, TextAlignment.entries)
        },
        maxLines = issues.optionalField(path, node, "maxLines", defaults.maxLines) { valuePath, value ->
            issues.jsonInt(valuePath, value, maxLinesRange)
        },
    )
}

private fun decodeProgress(
    path: String,
    node: Map<String, Any?>,
    fallback: StyleProgress,
    issues: StyleIssues,
): StyleProgress {
    val progressPath = stylePath(path, "progress")
    if (!node.containsKey("progress")) return fallback
    val progressNode = issues.jsonObject(progressPath, node["progress"]) ?: return fallback
    issues.unknownKeys(progressPath, progressNode, progressKeys)
    return StyleProgress(
        appearance = issues.optionalField(progressPath, progressNode, "appearance", fallback.appearance) { valuePath, value ->
            issues.jsonEnum(valuePath, value, ProgressAppearance.entries)
        },
        heightDp = issues.optionalField(progressPath, progressNode, "heightDp", fallback.heightDp) { valuePath, value ->
            issues.jsonFloat(valuePath, value, progressHeightRange)
        },
        showRemainingTime = issues.optionalField(
            progressPath,
            progressNode,
            "showRemainingTime",
            fallback.showRemainingTime,
        ) { valuePath, value ->
            issues.jsonBoolean(valuePath, value)
        },
    )
}

private fun decodeStyleBackdrop(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleBackdrop {
    val defaults = StyleBackdrop(layers = emptyList())
    issues.unknownKeys(path, node, backdropKeys)
    val extent = issues.optionalField(path, node, "extent", defaults.extent) { valuePath, value ->
        issues.jsonEnum(valuePath, value, BackdropExtent.entries)
    }
    val layersPath = stylePath(path, "layers")
    if (!node.containsKey("layers")) {
        issues.error(layersPath, "required field is missing")
        return StyleBackdrop(extent = extent, layers = emptyList())
    }
    val rawLayers = issues.jsonArray(layersPath, node["layers"])
        ?: return StyleBackdrop(extent = extent, layers = emptyList())
    if (rawLayers.isEmpty()) {
        issues.error(layersPath, "at least one layer is required")
        return StyleBackdrop(extent = extent, layers = emptyList())
    }
    val layers = rawLayers.mapIndexedNotNull { index, entry ->
        val layerPath = styleIndexPath(layersPath, index)
        issues.jsonObject(layerPath, entry)?.let { decodeBackdropLayer(layerPath, it, issues) }
    }
    return StyleBackdrop(extent = extent, layers = layers)
}

private fun decodeBackdropLayer(
    path: String,
    node: Map<String, Any?>,
    issues: StyleIssues,
): StyleBackdropLayer? {
    if (!node.containsKey("kind")) {
        issues.error(stylePath(path, "kind"), "required field is missing")
        return null
    }
    val kind = issues.jsonEnum(stylePath(path, "kind"), node["kind"], BackdropLayerKind.entries)
        ?: return null
    return when (kind) {
        BackdropLayerKind.SOLID -> {
            val defaults = StyleSolidBackdropLayer()
            issues.unknownKeys(path, node, backdropLayerKeys + "color")
            StyleSolidBackdropLayer(
                color = issues.optionalField(path, node, "color", defaults.color) { valuePath, value ->
                    issues.jsonEnum(valuePath, value, BackdropColor.entries)
                },
                alpha = issues.layerAlpha(path, node, defaults.alpha),
                blend = issues.layerBlend(path, node, defaults.blend),
            )
        }

        BackdropLayerKind.GRADIENT -> {
            val defaults = StyleGradientBackdropLayer()
            issues.unknownKeys(path, node, backdropLayerKeys + setOf("anchor", "startColor", "endColor", "artTint"))
            StyleGradientBackdropLayer(
                anchor = issues.optionalField(path, node, "anchor", defaults.anchor) { valuePath, value ->
                    issues.jsonEnum(valuePath, value, BackdropAnchor.entries)
                },
                startColor = issues.optionalField(path, node, "startColor", defaults.startColor) { valuePath, value ->
                    issues.jsonEnum(valuePath, value, BackdropColor.entries)
                },
                endColor = issues.optionalField(path, node, "endColor", defaults.endColor) { valuePath, value ->
                    issues.jsonEnum(valuePath, value, BackdropColor.entries)
                },
                artTint = issues.optionalField(path, node, "artTint", defaults.artTint) { valuePath, value ->
                    issues.jsonFloat(valuePath, value, unitRange)
                },
                alpha = issues.layerAlpha(path, node, defaults.alpha),
                blend = issues.layerBlend(path, node, defaults.blend),
            )
        }

        BackdropLayerKind.ALBUM_ART -> {
            val defaults = StyleAlbumArtBackdropLayer()
            issues.unknownKeys(path, node, backdropLayerKeys + "scale")
            StyleAlbumArtBackdropLayer(
                scale = issues.optionalField(path, node, "scale", defaults.scale) { valuePath, value ->
                    issues.jsonFloat(valuePath, value, albumArtScaleRange)
                },
                alpha = issues.layerAlpha(path, node, defaults.alpha),
                blend = issues.layerBlend(path, node, defaults.blend),
            )
        }

        BackdropLayerKind.BLUR -> {
            val defaults = StyleBlurBackdropLayer()
            issues.unknownKeys(path, node, backdropLayerKeys + setOf("radiusDp", "extent"))
            StyleBlurBackdropLayer(
                radiusDp = issues.optionalField(path, node, "radiusDp", defaults.radiusDp) { valuePath, value ->
                    issues.jsonFloat(valuePath, value, blurRadiusRange)
                },
                extent = issues.optionalField(path, node, "extent", defaults.extent) { valuePath, value ->
                    issues.jsonEnum(valuePath, value, BackdropBlurExtent.entries)
                },
                alpha = issues.layerAlpha(path, node, defaults.alpha),
                blend = issues.layerBlend(path, node, defaults.blend),
            )
        }

        BackdropLayerKind.GRAIN -> {
            val defaults = StyleGrainBackdropLayer()
            issues.unknownKeys(path, node, backdropLayerKeys + "density")
            StyleGrainBackdropLayer(
                density = issues.optionalField(path, node, "density", defaults.density) { valuePath, value ->
                    issues.jsonFloat(valuePath, value, unitRange)
                },
                alpha = issues.layerAlpha(path, node, defaults.alpha),
                blend = issues.layerBlend(path, node, defaults.blend),
            )
        }

        BackdropLayerKind.VIGNETTE -> {
            val defaults = StyleVignetteBackdropLayer()
            issues.unknownKeys(path, node, backdropLayerKeys + "strength")
            StyleVignetteBackdropLayer(
                strength = issues.optionalField(path, node, "strength", defaults.strength) { valuePath, value ->
                    issues.jsonFloat(valuePath, value, unitRange)
                },
                alpha = issues.layerAlpha(path, node, defaults.alpha),
                blend = issues.layerBlend(path, node, defaults.blend),
            )
        }

        BackdropLayerKind.GRADIENT_MESH -> {
            val defaults = StyleGradientMeshBackdropLayer()
            issues.unknownKeys(path, node, backdropLayerKeys + "intensity")
            StyleGradientMeshBackdropLayer(
                intensity = issues.optionalField(path, node, "intensity", defaults.intensity) { valuePath, value ->
                    issues.jsonFloat(valuePath, value, unitRange)
                },
                alpha = issues.layerAlpha(path, node, defaults.alpha),
                blend = issues.layerBlend(path, node, defaults.blend),
            )
        }
    }
}

private fun StyleIssues.layerAlpha(path: String, node: Map<String, Any?>, fallback: Float): Float =
    optionalField(path, node, "alpha", fallback) { valuePath, value ->
        jsonFloat(valuePath, value, unitRange)
    }

private fun StyleIssues.layerBlend(
    path: String,
    node: Map<String, Any?>,
    fallback: BackdropBlendMode,
): BackdropBlendMode = optionalField(path, node, "blend", fallback) { valuePath, value ->
    jsonEnum(valuePath, value, BackdropBlendMode.entries)
}
