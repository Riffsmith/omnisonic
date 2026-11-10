package app.omnisonic.feature.library.detail

/**
 * Which detail screen a [DetailViewModel] instance serves. Each detail
 * destination owns its own view-model instance (back-stack-entry scoped), so
 * one instance loads exactly one (kind, id) pair (UI_SPEC.md §3.5).
 */
enum class DetailKind { ALBUM, ARTIST, GENRE, PLAYLIST }
