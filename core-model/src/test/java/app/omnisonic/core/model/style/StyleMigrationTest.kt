package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import org.junit.jupiter.api.Test

class StyleMigrationTest {

    private val treeAdapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    private val v1Text: String = when (val written = StyleWriter.write(fullStyleDocument())) {
        is StyleWriteResult.Written -> written.text
        is StyleWriteResult.Failed -> throw AssertionError(written.errors.map { it.message })
    }

    @Suppress("UNCHECKED_CAST")
    private fun tree(text: String): Map<String, Any?> = treeAdapter.fromJson(text) as Map<String, Any?>

    private fun migrated(result: StyleMigrationResult): String =
        when (result) {
            is StyleMigrationResult.Migrated -> result.text
            is StyleMigrationResult.Failed -> throw AssertionError(result.errors.map { it.message })
        }

    private fun failed(result: StyleMigrationResult): StyleMigrationResult.Failed =
        when (result) {
            is StyleMigrationResult.Failed -> result
            is StyleMigrationResult.Migrated -> throw AssertionError("expected a failure, got:\n${result.text}")
        }

    private fun appValue(text: String, key: String): Any? = (tree(text)["app"] as? Map<*, *>)?.get(key)

    @Suppress("UNCHECKED_CAST")
    private fun appStep(from: Int, to: Int, key: String, value: Any?): StyleMigration =
        StyleMigration(from, to) { node ->
            val app = (node["app"] as? Map<String, Any?>).orEmpty().toMutableMap()
            app[key] = value
            StyleMigrationOutcome.Migrated(node + ("app" to app))
        }

    @Test
    fun `a document at the current version passes through untouched`() {
        val result = StyleMigrator().migrate(v1Text)

        assertThat(migrated(result)).isEqualTo(v1Text)
        val decoded = StyleDecoder.decode(migrated(result)) as StyleDecodeResult.Decoded
        assertThat(decoded.document).isEqualTo(fullStyleDocument())
        assertThat(decoded.warnings).isEmpty()
    }

    @Test
    fun `a v1 fixture migrates to the synthetic v2`() {
        val migrator = StyleMigrator(
            migrations = listOf(appStep(from = 1, to = 2, key = "seedColor", value = "#445566")),
            targetVersion = 2,
        )

        val text = migrated(migrator.migrate(v1Text))

        assertThat(tree(text)["schemaVersion"]).isEqualTo(2.0)
        assertThat(appValue(text, "seedColor")).isEqualTo("#445566")
        assertThat(text).isNotEqualTo(v1Text)
    }

    @Test
    fun `steps run in version order regardless of declaration order`() {
        val migrator = StyleMigrator(
            migrations = listOf(
                appStep(from = 2, to = 3, key = "darkMode", value = "LIGHT"),
                appStep(from = 1, to = 2, key = "seedColor", value = "#111111"),
            ),
            targetVersion = 3,
        )

        val text = migrated(migrator.migrate(v1Text))

        assertThat(tree(text)["schemaVersion"]).isEqualTo(3.0)
        assertThat(appValue(text, "seedColor")).isEqualTo("#111111")
        assertThat(appValue(text, "darkMode")).isEqualTo("LIGHT")
    }

    @Test
    fun `a failing step surfaces its error and returns no text`() {
        val migrator = StyleMigrator(
            migrations = listOf(
                StyleMigration(from = 1, to = 2) {
                    StyleMigrationOutcome.Failed("seed colour cannot be carried forward")
                },
            ),
            targetVersion = 2,
        )

        val failure = failed(migrator.migrate(v1Text))

        assertThat(failure.firstMessage)
            .isEqualTo("$: migration 1 -> 2 failed: seed colour cannot be carried forward")
    }

    @Test
    fun `an unknown future version is rejected with the version in the message`() {
        val future = v1Text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 3")

        val failure = failed(StyleMigrator().migrate(future))

        assertThat(failure.firstMessage)
            .isEqualTo("schemaVersion: unsupported schema version 3; this build reads version 1")
        assertThat(failure.errors).hasSize(1)
    }

    @Test
    fun `a missing step between versions is a gap`() {
        val migrator = StyleMigrator(
            migrations = listOf(appStep(from = 1, to = 2, key = "seedColor", value = "#111111")),
            targetVersion = 3,
        )

        assertThat(failed(migrator.migrate(v1Text)).firstMessage)
            .isEqualTo("$: no migration step from version 2 to version 3")
    }

    @Test
    fun `a step that skips a version is rejected`() {
        val migrator = StyleMigrator(
            migrations = listOf(appStep(from = 1, to = 3, key = "seedColor", value = "#111111")),
            targetVersion = 3,
        )

        assertThat(failed(migrator.migrate(v1Text)).firstMessage)
            .isEqualTo("$: migration 1 -> 3 targets version 3; the next step must target version 2")
    }

    @Test
    fun `two steps from the same version are rejected`() {
        val migrator = StyleMigrator(
            migrations = listOf(
                appStep(from = 1, to = 2, key = "seedColor", value = "#111111"),
                appStep(from = 1, to = 2, key = "darkMode", value = "LIGHT"),
            ),
            targetVersion = 2,
        )

        assertThat(failed(migrator.migrate(v1Text)).firstMessage)
            .isEqualTo("$: more than one migration step from version 1")
    }

    @Test
    fun `a migrated document still passes the decoder and writer`() {
        val original = v1Text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0")
        val migrator = StyleMigrator(
            migrations = listOf(StyleMigration(0, 1) { StyleMigrationOutcome.Migrated(it) }),
        )

        val text = migrated(migrator.migrate(original))

        val decoded = StyleDecoder.decode(text) as StyleDecodeResult.Decoded
        val written = StyleWriter.write(decoded.document) as StyleWriteResult.Written
        val normalized = StyleNormalizer.normalize(text) as StyleWriteResult.Written

        assertThat(decoded.document).isEqualTo(fullStyleDocument())
        assertThat(written.text).isEqualTo(v1Text)
        assertThat(normalized.text).isEqualTo(v1Text)
    }

    @Test
    fun `invalid migrated output is rejected by the build-version decoder`() {
        val original = v1Text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0")
        val migrator = StyleMigrator(
            migrations = listOf(
                appStep(from = 0, to = 1, key = "darkMode", value = 7),
            ),
        )

        val failure = failed(migrator.migrate(original))

        assertThat(failure.firstMessage).isEqualTo("app.darkMode: expected string, got number")
    }
}
