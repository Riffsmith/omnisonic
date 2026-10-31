package app.omnisonic.core.model.text

import java.text.Normalizer

/**
 * Sort/search helpers for server-supplied titles: CJK and other non-Latin
 * titles get a Latin approximation when ICU transliteration is available
 * (Android), otherwise diacritics are stripped on the JVM.
 */
object TextNormalizer {

    private val transliterate: ((String) -> String)? = loadTransliterator()

    /** Latin approximation of [title], original casing preserved where possible. */
    fun romanize(title: String): String =
        transliterate?.invoke(title) ?: stripMarks(title)

    /** Lowercased, mark-stripped sort key. */
    fun sortKey(title: String): String = romanize(title).lowercase().trim()

    private fun stripMarks(title: String): String = Normalizer
        .normalize(title, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

    private fun loadTransliterator(): ((String) -> String)? = runCatching {
        val icu = Class.forName("android.icu.text.Transliterator")
        val getInstance = icu.getMethod("getInstance", String::class.java)
        val transliterateMethod = icu.getMethod("transliterate", String::class.java)
        val instance = getInstance.invoke(null, "Any-Latin; Latin-ASCII")
        val function: (String) -> String = { input ->
            transliterateMethod.invoke(instance, input) as String
        }
        function
    }.getOrNull()
}
