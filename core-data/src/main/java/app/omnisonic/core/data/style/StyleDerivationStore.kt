package app.omnisonic.core.data.style

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Derivation metadata for user styles: custom id → the built-in base id it was
 * copied or imported from (`docs/omnisonic/style/style-loader.md` §6, D-82).
 *
 * The entry lives outside the style document so schema v1 is unchanged and the
 * full resolved document is still the copy. A missing entry is not an error:
 * callers fall back explicitly to the default built-in and say so, never
 * silently to another base.
 */
interface StyleDerivationStore {

    fun baseOf(customId: String): String?

    fun entries(): Map<String, String>

    /** Records (or replaces) one entry; `false` means nothing was committed. */
    fun set(customId: String, baseId: String): Boolean

    /** Removes one entry; a missing entry counts as cleared. */
    fun clear(customId: String): Boolean
}

/**
 * One JSON object (`{ "<custom id>": "<base id>" }`) in a same-directory temp
 * file, flushed, synced and renamed over the target, so an interrupted write
 * leaves the previous metadata intact. The file is never placed under the
 * repository's `styles/` directory, so the style listing can never pick it up.
 */
class FileStyleDerivationStore(
    private val file: File,
    private val replace: (temp: File, target: File) -> Boolean = { temp, target ->
        temp.renameTo(target)
    },
) : StyleDerivationStore {

    private val adapter: JsonAdapter<Map<String, String>> =
        Moshi.Builder().build().adapter(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
        )

    override fun baseOf(customId: String): String? = entries()[customId]

    override fun entries(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: IOException) {
            return emptyMap()
        } catch (failure: SecurityException) {
            return emptyMap()
        }
        return try {
            adapter.fromJson(text) ?: emptyMap()
        } catch (failure: IOException) {
            emptyMap()
        } catch (failure: JsonDataException) {
            emptyMap()
        }
    }

    override fun set(customId: String, baseId: String): Boolean =
        commit(entries() + (customId to baseId))

    override fun clear(customId: String): Boolean {
        val current = entries()
        if (customId !in current) return true
        return commit(current - customId)
    }

    private fun commit(entries: Map<String, String>): Boolean {
        val directory = file.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            return false
        }
        val text = adapter.toJson(entries.toSortedMap()) + "\n"
        val temp = File(directory, "${file.name}$TEMP_SUFFIX")
        return try {
            temp.delete()
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!replace(temp, file)) {
                temp.delete()
                false
            } else {
                true
            }
        } catch (failure: IOException) {
            temp.delete()
            false
        } catch (failure: SecurityException) {
            temp.delete()
            false
        }
    }

    companion object {
        const val TEMP_SUFFIX: String = ".tmp"
    }
}
