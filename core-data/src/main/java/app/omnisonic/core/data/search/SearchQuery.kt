package app.omnisonic.core.data.search

import app.omnisonic.core.model.text.TextNormalizer

/** One user token with the two LIKE patterns it is matched by. */
data class SearchTerm(
    val text: String,
    val likePattern: String,
    val normalizedLikePattern: String,
)

/**
 * Query-at-will tokenizer for local-library search (K7: LIKE only, no FTS5).
 * Tokens split on whitespace and are ANDed by the DAO; every token is matched
 * against the raw columns with [likePattern] and against the pre-computed
 * roman/sort columns with [normalizedLikePattern] (the same spelling
 * `SubsonicLibrarySyncer` writes). Patterns escape the SQL wildcards so user
 * input cannot widen a query.
 */
object SearchQuery {

    const val MAX_TERMS = 6

    private val whitespace = Regex("\\s+")

    fun terms(query: String): List<SearchTerm> =
        query.trim()
            .split(whitespace)
            .filter { it.isNotEmpty() }
            .take(MAX_TERMS)
            .map { token ->
                SearchTerm(
                    text = token,
                    likePattern = likePattern(token),
                    normalizedLikePattern = likePattern(normalized(token)),
                )
            }

    fun likePattern(value: String): String = "%${escape(value)}%"

    private fun normalized(token: String): String =
        TextNormalizer.sortKey(token).ifEmpty { token }

    internal fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\', '%', '_' -> {
                    append('\\')
                    append(character)
                }

                else -> append(character)
            }
        }
    }
}

/** Pads a token list to the DAO's fixed bind slots; missing slots pass the AND. */
internal fun List<SearchTerm>.padToSlots(): List<SearchTerm?> =
    List(SearchQuery.MAX_TERMS) { getOrNull(it) }
