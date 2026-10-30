package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AggregateDao {

    @Query(
        """
        UPDATE albums SET
          playCount = (SELECT COALESCE(SUM(songs.playCount), 0) FROM songs WHERE songs.albumId = albums.id),
          lastPlayed = (SELECT MAX(songs.lastPlayed) FROM songs WHERE songs.albumId = albums.id),
          resumeFilePath = (
            SELECT songs.filePath FROM songs
            WHERE songs.albumId = albums.id AND songs.resumePointMs > 0
            ORDER BY songs.lastPlayed DESC LIMIT 1
          )
        WHERE albums.id IN (:albumIds)
        """,
    )
    suspend fun recomputeAlbumsSumById(albumIds: List<Long>)

    @Query(
        """
        UPDATE albums SET
          playCount = (SELECT COALESCE(ROUND(AVG(songs.playCount)), 0) FROM songs WHERE songs.albumId = albums.id),
          lastPlayed = (SELECT MAX(songs.lastPlayed) FROM songs WHERE songs.albumId = albums.id),
          resumeFilePath = (
            SELECT songs.filePath FROM songs
            WHERE songs.albumId = albums.id AND songs.resumePointMs > 0
            ORDER BY songs.lastPlayed DESC LIMIT 1
          )
        WHERE albums.id IN (:albumIds)
        """,
    )
    suspend fun recomputeAlbumsAverageById(albumIds: List<Long>)

    @Query(
        """
        UPDATE albums SET
          playCount = (SELECT COALESCE(MIN(songs.playCount), 0) FROM songs WHERE songs.albumId = albums.id),
          lastPlayed = (SELECT MAX(songs.lastPlayed) FROM songs WHERE songs.albumId = albums.id),
          resumeFilePath = (
            SELECT songs.filePath FROM songs
            WHERE songs.albumId = albums.id AND songs.resumePointMs > 0
            ORDER BY songs.lastPlayed DESC LIMIT 1
          )
        WHERE albums.id IN (:albumIds)
        """,
    )
    suspend fun recomputeAlbumsMinimumById(albumIds: List<Long>)

    @Query(
        """
        UPDATE albums SET
          playCount = (SELECT COALESCE(SUM(songs.playCount), 0) FROM songs WHERE songs.albumId = albums.id),
          lastPlayed = (SELECT MAX(songs.lastPlayed) FROM songs WHERE songs.albumId = albums.id),
          resumeFilePath = (
            SELECT songs.filePath FROM songs
            WHERE songs.albumId = albums.id AND songs.resumePointMs > 0
            ORDER BY songs.lastPlayed DESC LIMIT 1
          )
        WHERE albums.providerId = :providerId
        """,
    )
    suspend fun recomputeAlbumsSumByProvider(providerId: Long)

    @Query(
        """
        UPDATE albums SET
          playCount = (SELECT COALESCE(ROUND(AVG(songs.playCount)), 0) FROM songs WHERE songs.albumId = albums.id),
          lastPlayed = (SELECT MAX(songs.lastPlayed) FROM songs WHERE songs.albumId = albums.id),
          resumeFilePath = (
            SELECT songs.filePath FROM songs
            WHERE songs.albumId = albums.id AND songs.resumePointMs > 0
            ORDER BY songs.lastPlayed DESC LIMIT 1
          )
        WHERE albums.providerId = :providerId
        """,
    )
    suspend fun recomputeAlbumsAverageByProvider(providerId: Long)

    @Query(
        """
        UPDATE albums SET
          playCount = (SELECT COALESCE(MIN(songs.playCount), 0) FROM songs WHERE songs.albumId = albums.id),
          lastPlayed = (SELECT MAX(songs.lastPlayed) FROM songs WHERE songs.albumId = albums.id),
          resumeFilePath = (
            SELECT songs.filePath FROM songs
            WHERE songs.albumId = albums.id AND songs.resumePointMs > 0
            ORDER BY songs.lastPlayed DESC LIMIT 1
          )
        WHERE albums.providerId = :providerId
        """,
    )
    suspend fun recomputeAlbumsMinimumByProvider(providerId: Long)

    @Query(
        """
        UPDATE artists SET
          playCount = COALESCE((
            SELECT SUM(songs.playCount) FROM song_artists
            JOIN songs ON songs.id = song_artists.songId
            WHERE song_artists.artistId = artists.id AND (:includeSupporting = 1 OR song_artists.role = '')
          ), 0),
          lastPlayed = (
            SELECT MAX(songs.lastPlayed) FROM song_artists
            JOIN songs ON songs.id = song_artists.songId
            WHERE song_artists.artistId = artists.id AND (:includeSupporting = 1 OR song_artists.role = '')
          )
        WHERE artists.id IN (:artistIds)
        """,
    )
    suspend fun recomputeArtistsById(artistIds: List<Long>, includeSupporting: Boolean)

    @Query(
        """
        UPDATE artists SET
          playCount = COALESCE((
            SELECT SUM(songs.playCount) FROM song_artists
            JOIN songs ON songs.id = song_artists.songId
            WHERE song_artists.artistId = artists.id AND (:includeSupporting = 1 OR song_artists.role = '')
          ), 0),
          lastPlayed = (
            SELECT MAX(songs.lastPlayed) FROM song_artists
            JOIN songs ON songs.id = song_artists.songId
            WHERE song_artists.artistId = artists.id AND (:includeSupporting = 1 OR song_artists.role = '')
          )
        WHERE artists.providerId = :providerId
        """,
    )
    suspend fun recomputeArtistsByProvider(providerId: Long, includeSupporting: Boolean)
}
