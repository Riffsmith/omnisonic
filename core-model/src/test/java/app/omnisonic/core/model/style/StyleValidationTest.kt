package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StyleValidationTest {

    private fun document(
        schemaVersion: String? = "1",
        id: String? = "\"style-id\"",
        name: String? = "\"Style name\"",
        app: String? = "{}",
        home: String? = "{}",
        pages: String? = "{}",
        nowPlaying: String? = "{}",
    ): String {
        val fields = buildList {
            schemaVersion?.let { add("\"schemaVersion\":$it") }
            id?.let { add("\"id\":$it") }
            name?.let { add("\"name\":$it") }
            app?.let { add("\"app\":$it") }
            home?.let { add("\"home\":$it") }
            pages?.let { add("\"pages\":$it") }
            nowPlaying?.let { add("\"nowPlaying\":$it") }
        }
        return fields.joinToString(separator = ",", prefix = "{", postfix = "}")
    }

    private fun rejected(json: String): StyleDecodeResult.Rejected =
        when (val result = StyleDecoder.decode(json)) {
            is StyleDecodeResult.Rejected -> result
            is StyleDecodeResult.Decoded -> throw AssertionError("expected a rejection, got ${result.document}")
        }

    @Test
    fun `app must be an object`() {
        val rejected = rejected(document(app = "\"nope\""))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("app: expected object, got string")
    }

    @Test
    fun `home must be an object`() {
        val rejected = rejected(document(home = "7"))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("home: expected object, got number")
    }

    @Test
    fun `pages must be an object`() {
        val rejected = rejected(document(pages = "[]"))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("pages: expected object, got array")
    }

    @Test
    fun `nowPlaying must be an object`() {
        val rejected = rejected(document(nowPlaying = "false"))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("nowPlaying: expected object, got boolean")
    }

    @Test
    fun `the editor message format is path-qualified with array indexes`() {
        val rejected = rejected(
            document(
                nowPlaying = """{"compact":{"buttons":[{"action":"PREVIOUS"},{"action":"NEXT"},{"action":7}]}}""",
            ),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("nowPlaying.compact.buttons[2].action: expected string, got number")
    }

    @Test
    fun `an unknown enum name is rejected at its nested array index`() {
        val rejected = rejected(
            document(
                nowPlaying = """{"compact":{"buttons":[{"action":"PREVIOUS"},{"action":"NEXT"},{"action":"WARP"}]}}""",
            ),
        )

        assertThat(rejected.firstMessage).startsWith(
            "nowPlaying.compact.buttons[2].action: unknown value 'WARP'; expected one of ",
        )
        assertThat(rejected.firstMessage).contains("PLAY_PAUSE")
    }

    @Test
    fun `array elements must be objects`() {
        val rejected = rejected(
            document(home = """{"sections":[{"section":"MOST_PLAYED"},7]}"""),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("home.sections[1]: expected object, got number")
    }

    @Test
    fun `an unknown enum name reports the closed token vocabulary`() {
        val rejected = rejected(document(app = """{"darkMode":"TWILIGHT"}"""))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo(
            "app.darkMode: unknown value 'TWILIGHT'; expected one of SYSTEM, LIGHT, DARK, BLACK",
        )
    }

    @Test
    fun `an unknown backdrop layer kind is rejected`() {
        val rejected = rejected(
            document(nowPlaying = """{"background":{"layers":[{"kind":"PLASMA"}]}}"""),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo(
            "nowPlaying.background.layers[0].kind: unknown value 'PLASMA'; expected one of " +
                "SOLID, GRADIENT, ALBUM_ART, BLUR, GRAIN, VIGNETTE, GRADIENT_MESH",
        )
    }

    @Test
    fun `a number encoded as a string is rejected`() {
        val rejected = rejected(
            document(nowPlaying = """{"compact":{"text":{"title":{"maxLines":"2"}}}}"""),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("nowPlaying.compact.text.title.maxLines: expected number, got string")
    }

    @Test
    fun `a string encoded as a number is rejected`() {
        val rejected = rejected(document(name = "7"))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("name: expected string, got number")
    }

    @Test
    fun `an explicit null for a required text field is rejected`() {
        val rejected = rejected(document(name = "null"))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("name: expected string, got null")
    }

    @Test
    fun `a future schema version is rejected with the version in the message`() {
        val rejected = rejected(document(schemaVersion = "2"))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("schemaVersion: unsupported schema version 2; this build reads version 1")
    }

    @Test
    fun `an older unsupported schema version is rejected with the version in the message`() {
        val rejected = rejected(document(schemaVersion = "0"))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("schemaVersion: unsupported schema version 0; this build reads version 1")
    }

    @Test
    fun `a schema version encoded as a string is rejected`() {
        val rejected = rejected(document(schemaVersion = "\"1\""))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("schemaVersion: expected number, got string")
    }

    @Test
    fun `a missing schema version is rejected`() {
        val rejected = rejected(document(schemaVersion = null))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("schemaVersion: required field is missing")
    }

    @Test
    fun `a missing id is rejected`() {
        val rejected = rejected(document(id = null))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("id: required field is missing")
    }

    @Test
    fun `a missing name is rejected`() {
        val rejected = rejected(document(name = null))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("name: required field is missing")
    }

    @Test
    fun `a missing section object is rejected`() {
        val rejected = rejected(document(pages = null))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage).isEqualTo("pages: required field is missing")
    }

    @Test
    fun `a blank identity is rejected`() {
        assertThat(rejected(document(id = "\"  \"")).firstMessage).isEqualTo("id: must not be blank")
        assertThat(rejected(document(name = "\"\"")).firstMessage).isEqualTo("name: must not be blank")
    }

    @Test
    fun `an out-of-range integer is rejected`() {
        val rejected = rejected(
            document(pages = """{"albums":{"columns":{"portrait":0,"landscape":5}}}"""),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("pages.albums.columns.portrait: value 0 is out of range 1..12")
    }

    @Test
    fun `an out-of-range float is rejected`() {
        val rejected = rejected(
            document(nowPlaying = """{"background":{"layers":[{"kind":"SOLID","alpha":1.5}]}}"""),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("nowPlaying.background.layers[0].alpha: value 1.5 is out of range 0.0..1.0")
    }

    @Test
    fun `a fractional value for an integer field is rejected`() {
        val rejected = rejected(
            document(nowPlaying = """{"compact":{"text":{"title":{"maxLines":2.5}}}}"""),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("nowPlaying.compact.text.title.maxLines: expected an integer, got 2.5")
    }

    @Test
    fun `a malformed seed colour is rejected`() {
        val rejected = rejected(document(app = """{"seedColor":"#12345"}"""))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("app.seedColor: expected a #RRGGBB or #AARRGGBB colour, got '#12345'")
    }

    @Test
    fun `an orientation pair requires both orientations`() {
        val rejected = rejected(
            document(pages = """{"albums":{"columns":{"portrait":2}}}"""),
        )

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("pages.albums.columns.landscape: required field is missing")
    }

    @Test
    fun `an empty backdrop layer list is rejected`() {
        val rejected = rejected(document(nowPlaying = """{"background":{"layers":[]}}"""))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("nowPlaying.background.layers: at least one layer is required")
    }

    @Test
    fun `a backdrop layer without a kind is rejected`() {
        val rejected = rejected(document(nowPlaying = """{"background":{"layers":[{"alpha":0.5}]}}"""))

        assertThat(rejected.errors).hasSize(1)
        assertThat(rejected.firstMessage)
            .isEqualTo("nowPlaying.background.layers[0].kind: required field is missing")
    }

    @Test
    fun `every error is retained and the first one stays primary`() {
        val rejected = rejected(
            document(
                app = """{"darkMode":"TWILIGHT"}""",
                home = """{"sections":[{"visible":"yes"}]}""",
            ),
        )

        assertThat(rejected.errors.map { it.path }).containsExactly(
            "app.darkMode",
            "home.sections[0].visible",
        ).inOrder()
        assertThat(rejected.firstMessage).isEqualTo(rejected.errors.first().message)
        assertThat(rejected.firstMessage)
            .isEqualTo("app.darkMode: unknown value 'TWILIGHT'; expected one of SYSTEM, LIGHT, DARK, BLACK")
    }
}
