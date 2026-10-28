package app.omnisonic.core.data.json

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object OmnisonicJson {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
}

object JsonText {

    private val stringListAdapter =
        OmnisonicJson.moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java),
        )

    fun encodeStringList(values: List<String>): String = stringListAdapter.toJson(values)

    fun decodeStringList(encoded: String?): List<String> =
        encoded?.let { runCatching { stringListAdapter.fromJson(it) }.getOrNull() } ?: emptyList()
}
