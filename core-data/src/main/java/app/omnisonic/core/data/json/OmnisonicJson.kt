package app.omnisonic.core.data.json

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

object OmnisonicJson {
    val moshi: Moshi = Moshi.Builder().build()
}

object JsonText {

    private val stringListAdapter =
        OmnisonicJson.moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java),
        )

    private val stringAdapter = OmnisonicJson.moshi.adapter<String>(String::class.java)

    fun encodeStringList(values: List<String>): String = stringListAdapter.toJson(values)

    /** The quoted, escaped JSON literal for one string — used to build LIKE patterns over JSON list columns. */
    fun encodeString(value: String): String = stringAdapter.toJson(value)

    fun decodeStringList(encoded: String?): List<String> =
        encoded?.let { runCatching { stringListAdapter.fromJson(it) }.getOrNull() } ?: emptyList()
}
