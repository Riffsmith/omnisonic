package app.omnisonic.core.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * R-13 / `reference/ROADMAP.md` §Phase 5: every catalog DAO read (search,
 * browse, detail) is reachable only through a method that takes a
 * [ProfileScope]. Room cannot bind a scope object into a query, so the
 * convention here is a `*Raw` `@Query` method whose SQL projects the scope's
 * `excludedProviderIds`, wrapped by a public method taking the scope. Reads
 * that predate the convention (sync plumbing, identity lookups) are exempted
 * by name; any NEW catalog query without the pairing fails this test.
 *
 * Scanned set: the five catalog entity DAOs plus `SearchDao`.
 */
class ProfileScopeQueryTest {

    @Test
    fun `catalog queries are wrapped by profile-scoped methods`() {
        val violations = daoFiles().flatMap { (file, exempt) -> scan(file, exempt) }

        if (violations.isNotEmpty()) {
            fail(
                "catalog DAO reads without a ProfileScope wrapper " +
                    "(add a *Raw @Query carrying excludedProviderIds plus a ProfileScope-taking wrapper, " +
                    "or extend the pre-convention exemption table deliberately):\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `exemption table stays accurate`() {
        val stale = daoFiles().flatMap { (file, exempt) ->
            val names = functionNames(file)
            exempt.filterNot { it in names }.map { "${file.name}.$it" }
        }

        assertThat(stale).isEmpty()
    }

    private fun scan(file: File, exempt: Set<String>): List<String> {
        val text = file.readText()
        val functions = functionRegex.findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        return queryMethodRegex.findAll(text).mapNotNull { match ->
            val sql = (match.groupValues[1] + match.groupValues[2]).trimStart()
            if (!sql.uppercase().startsWith("SELECT")) return@mapNotNull null
            val name = match.groupValues[3]
            val params = match.groupValues[4]
            if (name in exempt) return@mapNotNull null
            val key = "${file.name}.$name"
            if (!name.endsWith(RAW_SUFFIX)) {
                return@mapNotNull "$key: catalog read is not scope-wrapped"
            }
            val wrapperName = name.removeSuffix(RAW_SUFFIX)
            val wrapper = functions.firstOrNull { it.first == wrapperName }
            when {
                wrapper == null ->
                    "$key: missing scope wrapper $wrapperName"

                !scopeParameter.containsMatchIn(wrapper.second) ->
                    "$key: wrapper $wrapperName does not take a ProfileScope parameter"

                !params.contains(EXCLUDED_IDS_PARAM) ->
                    "$key: raw query does not project excludedProviderIds"

                else -> null
            }
        }.toList()
    }

    private fun functionNames(file: File): Set<String> =
        functionRegex.findAll(file.readText()).mapTo(mutableSetOf()) { it.groupValues[1] }

    private fun daoFiles(): List<Pair<File, Set<String>>> {
        val directory = repositoryRoot().resolve(DAO_DIRECTORY)
        return listOf(
            "SongDao.kt" to setOf(
                "selectIdByProviderAndExternal",
                "selectAll",
                "selectByAlbum",
                "selectByProvider",
                "selectById",
                "selectByProviderAndExternal",
                "selectByProviderAndFilePath",
                "selectMaxLastSeenSyncId",
                "selectCountByProvider",
                "selectUnseenDeletableIds",
            ),
            "AlbumDao.kt" to setOf(
                "selectIdByProviderAndExternal",
                "selectAll",
                "selectByProvider",
                "selectById",
                "selectByProviderAndExternal",
                "selectMostPlayed",
                "selectMaxLastSeenSyncId",
                "selectUnseenWithoutSongsIds",
            ),
            "ArtistDao.kt" to setOf(
                "selectIdByProviderAndExternal",
                "selectAll",
                "selectByProvider",
                "selectById",
                "selectByProviderAndExternal",
                "selectMaxLastSeenSyncId",
            ),
            "GenreDao.kt" to setOf(
                "selectIdByProviderAndExternal",
                "selectAll",
                "selectByProvider",
                "selectById",
                "selectByProviderAndExternal",
            ),
            "PlaylistDaos.kt" to setOf(
                "selectAll",
                "selectByProvider",
                "selectByProviderAndExternal",
                "selectMaxLastSeenSyncId",
                "selectById",
                "selectByPlaylist",
                "countByPlaylist",
                "selectMaxPosition",
            ),
            "SearchDao.kt" to emptySet(),
        ).map { (name, exempt) -> directory.resolve(name) to exempt }
    }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("repository root not found from ${File("").absolutePath}")
    }

    private companion object {
        const val DAO_DIRECTORY = "core-data/src/main/java/app/omnisonic/core/data/db/dao"
        const val RAW_SUFFIX = "Raw"
        const val EXCLUDED_IDS_PARAM = "excludedProviderIds"
        val scopeParameter = Regex("scope\\s*:\\s*ProfileScope")

        val queryMethodRegex = Regex(
            "(?:^|\\n)\\s*(?:@\\w[\\w.]*\\s+)*@Query\\(\\s*" +
                "(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")\\s*\\)" +
                "\\s*(?:@\\w[\\w.]*\\s+)*(?:suspend\\s+)?fun\\s+(\\w+)\\s*\\(([\\s\\S]*?)\\)",
        )

        val functionRegex = Regex(
            "(?:^|\\n)\\s*(?:@\\w[\\w.]*\\s+)*(?:suspend\\s+)?fun\\s+(\\w+)\\s*\\(([\\s\\S]*?)\\)",
        )
    }
}
