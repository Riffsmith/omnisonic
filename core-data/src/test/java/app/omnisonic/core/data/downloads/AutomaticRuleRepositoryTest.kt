package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.ProfileScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticRuleRepositoryTest : DatabaseTestBase() {

    private var now = 5_000L

    private fun repository() = AutomaticRuleRepository(db, nowMs = { now })

    @Test
    fun `all three kinds round-trip with named kinds and optional variants`() = runDbTest {
        newProvider(id = 1)
        val rules = repository()

        assertThat(rules.add(1, AutomaticRuleKind.FAVORITES, targetExternalId = "ignored"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)
        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "pl-1", transcodeBitrateKbps = 192, format = "mp3"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)
        assertThat(rules.add(1, AutomaticRuleKind.ALBUM, "alb-1"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)

        val stored = rules.list(ProfileScope.Unrestricted)
        assertThat(stored.map { it.kind })
            .containsExactly(AutomaticRuleKind.FAVORITES, AutomaticRuleKind.PLAYLIST, AutomaticRuleKind.ALBUM)
        val byKind = stored.associateBy { it.kind }
        assertThat(byKind[AutomaticRuleKind.FAVORITES]!!.targetExternalId).isEqualTo("")
        assertThat(byKind[AutomaticRuleKind.PLAYLIST]!!.targetExternalId).isEqualTo("pl-1")
        assertThat(byKind[AutomaticRuleKind.PLAYLIST]!!.transcodeBitrateKbps).isEqualTo(192)
        assertThat(byKind[AutomaticRuleKind.PLAYLIST]!!.format).isEqualTo("mp3")
        assertThat(byKind[AutomaticRuleKind.ALBUM]!!.targetExternalId).isEqualTo("alb-1")
        assertThat(byKind[AutomaticRuleKind.ALBUM]!!.transcodeBitrateKbps).isNull()
        assertThat(stored.map { it.createdAt }).containsExactly(now, now, now)
        assertThat(rules.find(1, AutomaticRuleKind.PLAYLIST, "pl-1", ProfileScope.Unrestricted)!!.id)
            .isEqualTo(byKind[AutomaticRuleKind.PLAYLIST]!!.id)
        assertThat(rules.findById(byKind[AutomaticRuleKind.ALBUM]!!.id, ProfileScope.Unrestricted)!!.kind)
            .isEqualTo(AutomaticRuleKind.ALBUM)
    }

    @Test
    fun `duplicate add is a strict no-op that keeps the original variant and timestamp`() = runDbTest {
        newProvider(id = 1)
        val rules = repository()
        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "pl-1", transcodeBitrateKbps = 192, format = "mp3"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)

        now = 9_000L
        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "pl-1", transcodeBitrateKbps = 320, format = "flac"))
            .isEqualTo(AutomaticRuleAddOutcome.DUPLICATE)

        val stored = rules.list(ProfileScope.Unrestricted).single()
        assertThat(stored.transcodeBitrateKbps).isEqualTo(192)
        assertThat(stored.format).isEqualTo("mp3")
        assertThat(stored.updatedAt).isEqualTo(5_000L)
    }

    @Test
    fun `the same external target on another provider or kind is a distinct rule`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        val rules = repository()

        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "shared"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)
        assertThat(rules.add(2, AutomaticRuleKind.PLAYLIST, "shared"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)
        assertThat(rules.add(1, AutomaticRuleKind.ALBUM, "shared"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)
        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "shared"))
            .isEqualTo(AutomaticRuleAddOutcome.DUPLICATE)

        assertThat(rules.list(ProfileScope.Unrestricted)).hasSize(3)
        assertThat(rules.listForProvider(2, ProfileScope.Unrestricted).single().targetExternalId).isEqualTo("shared")
    }

    @Test
    fun `blank playlist and album targets are rejected while favorites use the sentinel`() = runDbTest {
        newProvider(id = 1)
        val rules = repository()

        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "   ")).isEqualTo(AutomaticRuleAddOutcome.INVALID)
        assertThat(rules.add(1, AutomaticRuleKind.ALBUM, "")).isEqualTo(AutomaticRuleAddOutcome.INVALID)
        assertThat(rules.add(0, AutomaticRuleKind.PLAYLIST, "pl-1")).isEqualTo(AutomaticRuleAddOutcome.INVALID)
        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "pl-1", transcodeBitrateKbps = 0))
            .isEqualTo(AutomaticRuleAddOutcome.INVALID)
        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, "pl-1", format = " "))
            .isEqualTo(AutomaticRuleAddOutcome.INVALID)

        assertThat(rules.add(1, AutomaticRuleKind.FAVORITES, targetExternalId = "anything"))
            .isEqualTo(AutomaticRuleAddOutcome.ADDED)
        assertThat(rules.list(ProfileScope.Unrestricted).single().targetExternalId)
            .isEqualTo(AutomaticRuleRepository.FAVORITES_TARGET_EXTERNAL_ID)
        assertThat(rules.add(1, AutomaticRuleKind.FAVORITES, targetExternalId = "other"))
            .isEqualTo(AutomaticRuleAddOutcome.DUPLICATE)
    }

    @Test
    fun `an explicit variant change bumps updatedAt and respects the profile scope`() = runDbTest {
        newProvider(id = 1)
        val rules = repository()
        rules.add(1, AutomaticRuleKind.PLAYLIST, "pl-1")

        now = 7_000L
        assertThat(rules.setVariant(1, AutomaticRuleKind.PLAYLIST, "pl-1", 320, "flac", ProfileScope.Unrestricted))
            .isTrue()
        val updated = rules.find(1, AutomaticRuleKind.PLAYLIST, "pl-1", ProfileScope.Unrestricted)!!
        assertThat(updated.transcodeBitrateKbps).isEqualTo(320)
        assertThat(updated.format).isEqualTo("flac")
        assertThat(updated.updatedAt).isEqualTo(7_000L)

        assertThat(
            rules.setVariant(1, AutomaticRuleKind.PLAYLIST, "pl-1", 128, "mp3", ProfileScope().excluding(listOf(1L))),
        ).isFalse()
        assertThat(rules.find(1, AutomaticRuleKind.PLAYLIST, "pl-1", ProfileScope.Unrestricted)!!.transcodeBitrateKbps)
            .isEqualTo(320)
        assertThat(rules.setVariant(1, AutomaticRuleKind.PLAYLIST, "pl-1", -1, "mp3", ProfileScope.Unrestricted))
            .isFalse()
    }

    @Test
    fun `every read excludes a hidden provider under a real scope`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        val rules = repository()
        rules.add(1, AutomaticRuleKind.PLAYLIST, "pl-1")
        rules.add(2, AutomaticRuleKind.PLAYLIST, "pl-1")
        val hidden = ProfileScope().excluding(listOf(2L))
        val hiddenRule = rules.listForProvider(2, ProfileScope.Unrestricted).single()

        assertThat(rules.list(hidden).map { it.providerId }).containsExactly(1L)
        assertThat(rules.listForProvider(2, hidden)).isEmpty()
        assertThat(rules.listByKind(AutomaticRuleKind.PLAYLIST, hidden).map { it.providerId }).containsExactly(1L)
        assertThat(rules.find(2, AutomaticRuleKind.PLAYLIST, "pl-1", hidden)).isNull()
        assertThat(rules.findById(hiddenRule.id, hidden)).isNull()
        assertThat(rules.list(ProfileScope.Unrestricted)).hasSize(2)
        assertThat(rules.findById(hiddenRule.id, ProfileScope.Unrestricted)).isNotNull()
    }

    @Test
    fun `a rule survives catalog deletion and recreation because identity is the external target`() = runDbTest {
        newProvider(id = 1)
        val playlist = seedPlaylist(providerId = 1)
        val rules = repository()
        rules.add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
        val rule = rules.list(ProfileScope.Unrestricted).single()

        db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE id = ${playlist.id}")
        assertThat(rules.list(ProfileScope.Unrestricted).single().id).isEqualTo(rule.id)

        val recreated = seedPlaylist(providerId = 1)
        assertThat(recreated.externalId).isEqualTo("pl-1")
        assertThat(rules.add(1, AutomaticRuleKind.PLAYLIST, recreated.externalId!!))
            .isEqualTo(AutomaticRuleAddOutcome.DUPLICATE)
        assertThat(rules.list(ProfileScope.Unrestricted).single().id).isEqualTo(rule.id)
    }

    @Test
    fun `removing a rule and re-adding the same target mints a new row with the same identity`() = runDbTest {
        newProvider(id = 1)
        val rules = repository()
        rules.add(1, AutomaticRuleKind.ALBUM, "alb-1")
        val original = rules.list(ProfileScope.Unrestricted).single()

        assertThat(rules.remove(1, AutomaticRuleKind.ALBUM, "alb-1")).isTrue()
        assertThat(rules.remove(1, AutomaticRuleKind.ALBUM, "alb-1")).isFalse()
        assertThat(rules.find(1, AutomaticRuleKind.ALBUM, "alb-1", ProfileScope.Unrestricted)).isNull()

        assertThat(rules.add(1, AutomaticRuleKind.ALBUM, "alb-1")).isEqualTo(AutomaticRuleAddOutcome.ADDED)
        val readded = rules.list(ProfileScope.Unrestricted).single()
        assertThat(readded.id).isNotEqualTo(original.id)
        assertThat(readded.targetExternalId).isEqualTo("alb-1")
    }

    @Test
    fun `provider deletion cascades its rules together with queue and offline rows`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        val rules = repository()
        rules.add(1, AutomaticRuleKind.FAVORITES)
        rules.add(2, AutomaticRuleKind.FAVORITES)
        seedDownloadItem(providerId = 1)
        seedOfflineFile(providerId = 1)

        providerDao.deleteById(1)

        assertThat(rules.list(ProfileScope.Unrestricted).map { it.providerId }).containsExactly(2L)
        assertThat(downloadDao.selectByProvider(1)).isEmpty()
        assertThat(offlineFileDao.selectByProvider(1)).isEmpty()
        assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }
}
