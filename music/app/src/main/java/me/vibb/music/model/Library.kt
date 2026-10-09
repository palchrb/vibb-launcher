package me.vibb.music.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * The phone's library (design 21 §1.3, 21b §4.1): `GET /api/devices/music/library`, pinned by
 * server/testdata/music_library.json (byte-identical in src/test/resources, LibraryParseTest).
 * Parsing is lenient per element: a bad category, entry or file is dropped, never the library
 * (21 §4.6); only a document that isn't `v: 1` is refused (the old library stays).
 */

val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

private val KEY = Regex("[A-Za-z0-9_-]{1,64}")
private val HASH = Regex("[0-9a-f]{64}")
private val VERSION = Regex("[0-9a-f]{16}")
val ORDERS = setOf("auto", "newest_first", "oldest_first")

enum class Source { NRK, RSS, OWN }

data class Category(val id: Long, val name: String, val icon: String, val color: String, val sort: Int)

data class Entry(
    val id: Long,
    /** vibb's `state_key`: positions and cache keys hang off it. */
    val key: String,
    val name: String,
    val category: Long?,
    val source: Source,
    val target: String?,
    val order: String,
    /** -1 all, 0 none, 1-100 the newest N (vibb). */
    val cache: Int,
    val resume: Boolean,
    val cover: String?,
    /** The listing's version, null for own files and for an NRK/RSS entry never listed (21b §4.1). */
    val items: String?,
    val sort: Int,
    /** An RSS feed on the home LAN or tailnet (21b §2.7/§4.1): its audio may come from private addresses. */
    val lan: Boolean = false,
) {
    /** An NRK serie (`radio.nrk.no/serie/...`): a story read from its start (vibb `_natural_order`). */
    val nrkSerie: Boolean get() = source == Source.NRK && target.orEmpty().contains("radio.nrk.no/serie/", ignoreCase = true)
}

data class OwnFile(
    val id: Long,
    val entry: Long,
    val title: String,
    val artist: String?,
    val track: Int?,
    val durationMs: Long?,
    val size: Long,
    val sha256: String,
    val art: String?,
    val sort: Int,
    /** The server lost its copy (QA #3): a copy on the phone stays and plays. */
    val missing: Boolean,
) {
    /** The item key of an own file (its positions, Nytt and queue key). */
    val key: String get() = "f$id"
}

data class Library(
    val version: String,
    val categories: List<Category>,
    val entries: List<Entry>,
    val files: List<OwnFile>,
    val covers: Set<String>,
) {
    fun entry(id: Long): Entry? = entries.firstOrNull { it.id == id }
    fun filesOf(entryId: Long): List<OwnFile> = files.filter { it.entry == entryId }.sortedWith(compareBy({ it.sort }, { it.id }))

    companion object {
        val EMPTY = Library("", emptyList(), emptyList(), emptyList(), emptySet())
    }
}

@Serializable
private data class CategoryDto(val id: Long, val name: String, val icon: String = "", val color: String = "", val sort: Int = 0)

@Serializable
private data class EntryDto(
    val id: Long,
    val key: String,
    val name: String,
    val category: Long? = null,
    val source: String,
    val target: String? = null,
    val order: String = "auto",
    val cache: Int = 5,
    val resume: Boolean = true,
    val cover: String? = null,
    val items: String? = null,
    val sort: Int = 0,
    val lan: Boolean = false,
)

@Serializable
private data class FileDto(
    val id: Long,
    val entry: Long,
    val title: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val size: Long,
    val sha256: String,
    val art: String? = null,
    val sort: Int = 0,
    val missing: Boolean = false,
)

object LibraryParser {
    /** The library, or null when [text] isn't a `v: 1` library object. */
    fun parse(text: String): Library? {
        val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if (root["v"]?.jsonPrimitive?.intOrNull != 1) return null
        val version = (root["version"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val categories = elements(root["categories"]).mapNotNull { decode<CategoryDto>(it) }
            .filter { it.id > 0 && it.name.isNotBlank() }
            .distinctBy { it.id }
            .map { Category(it.id, it.name.trim(), it.icon, it.color, it.sort) }
        val entries = elements(root["entries"]).mapNotNull { decode<EntryDto>(it)?.let(::entry) }.distinctBy { it.id }
        val entryIds = entries.map { it.id }.toSet()
        val files = elements(root["files"]).mapNotNull { decode<FileDto>(it)?.let(::file) }
            .filter { it.entry in entryIds }
            .distinctBy { it.id }
        val covers = elements(root["covers"]).mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .filter { HASH.matches(it) }.toSet()
        return Library(version, categories, entries, files, covers)
    }

    private fun elements(e: JsonElement?): List<JsonElement> = (e as? JsonArray).orEmpty()

    private inline fun <reified T> decode(e: JsonElement): T? = runCatching { JSON.decodeFromJsonElement<T>(e) }.getOrNull()

    private fun entry(d: EntryDto): Entry? {
        val source = when (d.source) {
            "nrk" -> Source.NRK
            "rss" -> Source.RSS
            "own" -> Source.OWN
            else -> return null // storytel and spotify come with their phases
        }
        if (d.id <= 0 || !KEY.matches(d.key) || d.name.isBlank()) return null
        if (d.order !in ORDERS) return null
        if (!(d.cache == -1 || d.cache in 0..100)) return null
        if (d.cover != null && !HASH.matches(d.cover)) return null
        if (d.items != null && !VERSION.matches(d.items)) return null
        if (source != Source.OWN && d.target.isNullOrBlank()) return null
        return Entry(
            d.id, d.key, d.name.trim(), d.category, source, d.target, d.order, d.cache, d.resume, d.cover,
            if (source == Source.OWN) null else d.items, d.sort, source == Source.RSS && d.lan,
        )
    }

    private fun file(d: FileDto): OwnFile? {
        if (d.id <= 0 || d.size < 0 || !HASH.matches(d.sha256)) return null
        if (d.art != null && !HASH.matches(d.art)) return null
        val title = d.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "#${d.track ?: d.id}"
        return OwnFile(d.id, d.entry, title, d.artist, d.track, d.durationMs?.takeIf { it > 0 }, d.size, d.sha256, d.art, d.sort, d.missing)
    }
}
