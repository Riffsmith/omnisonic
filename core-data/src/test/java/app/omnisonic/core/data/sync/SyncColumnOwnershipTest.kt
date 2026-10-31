package app.omnisonic.core.data.sync

import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import com.google.common.truth.Truth.assertThat
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the server/local ownership model the syncer relies on: every entity
 * column must be classified, and a new column fails this test until its owner
 * is declared. Locally-owned columns must never appear in the syncer's
 * server-field UPDATE statements (merge tests enforce that behaviorally).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncColumnOwnershipTest {

    private enum class Owner { KEY, SERVER, LOCAL }

    private val keys = setOf("id", "providerId", "externalId")

    private val songOwnership = mapOf(
        // Server-owned: written by SubsonicLibrarySyncer.updateServerFields.
        "externalDataJson" to Owner.SERVER,
        "albumId" to Owner.SERVER,
        "title" to Owner.SERVER,
        "titleRoman" to Owner.SERVER,
        "sortTitle" to Owner.SERVER,
        "displayArtist" to Owner.SERVER,
        "filePath" to Owner.SERVER,
        "durationMs" to Owner.SERVER,
        "trackNo" to Owner.SERVER,
        "discNo" to Owner.SERVER,
        "year" to Owner.SERVER,
        "genresJson" to Owner.SERVER,
        "imagePath" to Owner.SERVER,
        "codec" to Owner.SERVER,
        "bitrateKbps" to Owner.SERVER,
        "sampleRate" to Owner.SERVER,
        "bitDepth" to Owner.SERVER,
        "channelCount" to Owner.SERVER,
        "serverRating" to Owner.SERVER,
        "updatedAt" to Owner.SERVER,
        // Locally-owned: playback state, editorial notes, downloads.
        "playCount" to Owner.LOCAL,
        "lastPlayed" to Owner.LOCAL,
        "skipped" to Owner.LOCAL,
        "lastSkipped" to Owner.LOCAL,
        "userRating" to Owner.LOCAL,
        "isFavorite" to Owner.LOCAL,
        "favoriteAt" to Owner.LOCAL,
        "resumePointMs" to Owner.LOCAL,
        "resumeUpdatedAt" to Owner.LOCAL,
        "missingOnServer" to Owner.LOCAL,
        "replayGainTrack" to Owner.LOCAL,
        "replayGainAlbum" to Owner.LOCAL,
        "replayGainType" to Owner.LOCAL,
        "clipStartMs" to Owner.LOCAL,
        "clipEndMs" to Owner.LOCAL,
        "isAudiobook" to Owner.LOCAL,
    )

    private val albumOwnership = mapOf(
        "externalDataJson" to Owner.SERVER,
        "title" to Owner.SERVER,
        "titleRoman" to Owner.SERVER,
        "sortTitle" to Owner.SERVER,
        "displayArtist" to Owner.SERVER,
        "year" to Owner.SERVER,
        "imagePath" to Owner.SERVER,
        "durationMs" to Owner.SERVER,
        "songCount" to Owner.SERVER,
        "genresJson" to Owner.SERVER,
        "serverRating" to Owner.SERVER,
        "updatedAt" to Owner.SERVER,
        "playCount" to Owner.LOCAL,
        "lastPlayed" to Owner.LOCAL,
        "userRating" to Owner.LOCAL,
        "isFavorite" to Owner.LOCAL,
        "favoriteAt" to Owner.LOCAL,
        "resumeFilePath" to Owner.LOCAL,
        "isAudiobook" to Owner.LOCAL,
        "description" to Owner.LOCAL,
        "discTitlesJson" to Owner.LOCAL,
        "isCompilation" to Owner.LOCAL,
    )

    private val artistOwnership = mapOf(
        "externalDataJson" to Owner.SERVER,
        "title" to Owner.SERVER,
        "titleRoman" to Owner.SERVER,
        "sortTitle" to Owner.SERVER,
        "imagePath" to Owner.SERVER,
        "songCount" to Owner.SERVER,
        "albumCount" to Owner.SERVER,
        "serverRating" to Owner.SERVER,
        "updatedAt" to Owner.SERVER,
        "description" to Owner.LOCAL,
        "playCount" to Owner.LOCAL,
        "lastPlayed" to Owner.LOCAL,
        "userRating" to Owner.LOCAL,
        "isFavorite" to Owner.LOCAL,
        "favoriteAt" to Owner.LOCAL,
    )

    @Test
    fun `every song column is classified`() = assertFullyClassified(SongEntity::class, songOwnership)

    @Test
    fun `every album column is classified`() = assertFullyClassified(AlbumEntity::class, albumOwnership)

    @Test
    fun `every artist column is classified`() = assertFullyClassified(ArtistEntity::class, artistOwnership)

    private fun assertFullyClassified(entity: KClass<*>, ownership: Map<String, Owner>) {
        val columns = entity.memberProperties.map { it.name }.toSet()
        val classified = ownership.keys + keys
        val unclassified = columns - classified
        val unknown = classified - columns

        assertThat(unclassified).isEmpty()
        assertThat(unknown).isEmpty()
    }
}
