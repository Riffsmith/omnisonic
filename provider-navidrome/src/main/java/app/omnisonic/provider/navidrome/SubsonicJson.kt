package app.omnisonic.provider.navidrome

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

object SubsonicJson {
    val moshi: Moshi = Moshi.Builder()
        .add(SubsonicEmbeddedListFactory())
        .add(KotlinJsonAdapterFactory())
        .build()
}

/**
 * Subsonic servers emit one element or an array for collection fields. Fields
 * of the whitelisted Subsonic DTO element types decode from either shape;
 * encoding always writes an array. Non-DTO lists decode as plain arrays.
 */
class SubsonicEmbeddedListFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (type !is ParameterizedType || Types.getRawType(type) != List::class.java) return null
        val elementType = type.actualTypeArguments[0]
        if (elementType !is Class<*> || elementType !in SUBSONIC_ELEMENT_TYPES) return null
        val elementAdapter = moshi.adapter<Any>(elementType)
        return SubsonicListJsonAdapter(elementAdapter)
    }
}

private class SubsonicListJsonAdapter(
    private val elementAdapter: JsonAdapter<Any>,
) : JsonAdapter<Any?>() {
    override fun fromJson(reader: JsonReader): Any? {
        if (reader.peek() == JsonReader.Token.NULL) return reader.nextNull<Any?>()
        val value = reader.readJsonValue()
        return when (value) {
            is List<*> -> value.mapNotNull { elementAdapter.fromJsonValue(it) }
            is Map<*, *> -> listOfNotNull(elementAdapter.fromJsonValue(value))
            else -> emptyList()
        }
    }

    override fun toJson(writer: JsonWriter, value: Any?) {
        writer.beginArray()
        (value as? Collection<*>)?.forEach { elementAdapter.toJson(writer, it) }
        writer.endArray()
    }
}

private val SUBSONIC_ELEMENT_TYPES: Set<Class<*>> = setOf<Class<*>>(
    SubsonicExtensionDto::class.java,
    SubsonicIndexDto::class.java,
    SubsonicArtistDto::class.java,
    SubsonicChildDto::class.java,
    SubsonicAlbumDto::class.java,
    SubsonicDirectoryDto::class.java,
    SubsonicGenreDto::class.java,
    SubsonicStationDto::class.java,
    SubsonicPlaylistSummaryDto::class.java,
    SubsonicPlaylistDto::class.java,
    SubsonicBookmarkDto::class.java,
)

data class SubsonicErrorDto(val code: Int? = null, val message: String? = null)

data class SubsonicExtensionDto(val name: String? = null, val versions: List<Int> = emptyList())

data class SubsonicArtistsRootDto(
    val index: List<SubsonicIndexDto> = emptyList(),
)

data class SubsonicIndexDto(
    val name: String? = null,
    val artist: List<SubsonicArtistDto> = emptyList(),
)

data class SubsonicArtistDto(
    val id: String? = null,
    val name: String? = null,
    val songCount: Int? = null,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val artistImageUrl: String? = null,
)

data class SubsonicChildDto(
    val id: String? = null,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val isDir: Boolean? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    val duration: Int? = null,
    val path: String? = null,
    val discNumber: Int? = null,
    val starred: String? = null,
)

data class SubsonicAlbumDto(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null,
    val song: List<SubsonicChildDto> = emptyList(),
)

data class SubsonicAlbumList2RootDto(
    val album: List<SubsonicAlbumDto> = emptyList(),
)

data class SubsonicDirectoryDto(
    val id: String? = null,
    val name: String? = null,
    val child: List<SubsonicChildDto> = emptyList(),
)

data class SubsonicGenresRootDto(
    val genre: List<SubsonicGenreDto> = emptyList(),
)

data class SubsonicGenreDto(
    val value: String? = null,
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

data class SubsonicRadiosRootDto(
    val station: List<SubsonicStationDto> = emptyList(),
)

data class SubsonicStationDto(
    val id: String? = null,
    val name: String? = null,
    val streamUrl: String? = null,
    val homePageUrl: String? = null,
)

data class SubsonicPlaylistsRootDto(
    val playlist: List<SubsonicPlaylistSummaryDto> = emptyList(),
)

data class SubsonicPlaylistSummaryDto(
    val id: String? = null,
    val name: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val owner: String? = null,
    val changed: String? = null,
    val created: String? = null,
)

data class SubsonicPlaylistDto(
    val id: String? = null,
    val name: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val owner: String? = null,
    val changed: String? = null,
    val created: String? = null,
    val entry: List<SubsonicChildDto> = emptyList(),
)

data class SubsonicBookmarkRootDto(
    val bookmark: List<SubsonicBookmarkDto> = emptyList(),
)

data class SubsonicBookmarkDto(
    val entry: List<SubsonicChildDto> = emptyList(),
    val position: Long? = null,
    val created: String? = null,
    val changed: String? = null,
)

data class SubsonicSimilarSongsRootDto(
    val song: List<SubsonicChildDto> = emptyList(),
)

data class SubsonicSearchDto(
    val artist: List<SubsonicArtistDto> = emptyList(),
    val album: List<SubsonicAlbumDto> = emptyList(),
    val song: List<SubsonicChildDto> = emptyList(),
)

data class SubsonicEnvelope(
    val status: String? = null,
    val version: String? = null,
    val error: SubsonicErrorDto? = null,
    val openSubsonicExtensions: List<SubsonicExtensionDto> = emptyList(),
    val artists: SubsonicArtistsRootDto? = null,
    val directory: List<SubsonicDirectoryDto> = emptyList(),
    val album: List<SubsonicAlbumDto> = emptyList(),
    val albumList2: SubsonicAlbumList2RootDto? = null,
    val song: List<SubsonicChildDto> = emptyList(),
    val searchResult3: SubsonicSearchDto? = null,
    val genres: SubsonicGenresRootDto? = null,
    val internetRadioStations: SubsonicRadiosRootDto? = null,
    val playlists: SubsonicPlaylistsRootDto? = null,
    val playlist: List<SubsonicPlaylistDto> = emptyList(),
    val bookmarks: SubsonicBookmarkRootDto? = null,
    val similarSongs: SubsonicSimilarSongsRootDto? = null,
)

data class SubsonicBody(
    @Json(name = "subsonic-response") val subsonicResponse: SubsonicEnvelope? = null,
)

