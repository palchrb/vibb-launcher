package me.vibb.music.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.vibb.music.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 6 (designs 49 §1.2 and 50 §1.5, one round): `repeat` and `favourites`, new tables by an auto migration; Room 7
 * (design r8 §1.3): `now_playing.favourites`, a "Favoritter" queue's scope.
 */
class Room6Test {
    private fun entities(version: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(Fixtures.moduleFile("schemas/me.vibb.music.data.MusicDb/$version.json").readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }

    @Test
    fun `Room 6 - repeat and favourites are new tables by an auto migration`() {
        val v6 = Fixtures.moduleFile("schemas/me.vibb.music.data.MusicDb/6.json").readText()
        assertTrue(v6.contains("\"version\": 6"))
        assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`entryKey` TEXT NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`entryKey`))"))
        assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`entryKey` TEXT NOT NULL, `itemKey` TEXT NOT NULL, PRIMARY KEY(`entryKey`, `itemKey`))"))
        val db = Fixtures.moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("AutoMigration(from = 5, to = 6),"))
    }

    @Test
    fun `qa-music-a-code 5 - 5 to 6 only adds repeat and favourites, every v5 table unchanged`() {
        val v5 = entities(5)
        val v6 = entities(6)
        assertEquals(setOf("repeat", "favourites"), v6.keys - v5.keys)
        assertEquals("nothing dropped", v5.keys, v6.keys.intersect(v5.keys))
        for ((name, table) in v5) assertEquals("$name unchanged", table, v6[name])
    }

    @Test
    fun `design r8 - 6 to 7 only adds the nullable now_playing favourites, every other table unchanged`() {
        val v6 = entities(6)
        val v7 = entities(7)
        assertEquals(v6.keys, v7.keys)
        for ((name, table) in v6) if (name != "now_playing") assertEquals("$name unchanged", table, v7[name])
        val added = v7["now_playing"]!!["fields"]!!.jsonArray - v6["now_playing"]!!["fields"]!!.jsonArray.toSet()
        assertEquals("no notNull: old rows read null, the whole entry", listOf("""{"fieldPath":"favourites","columnName":"favourites","affinity":"TEXT"}"""), added.map { it.toString() })
        assertTrue(v7["now_playing"]!!["createSql"]!!.jsonPrimitive.content.endsWith("`updatedAt` INTEGER NOT NULL, `favourites` TEXT, PRIMARY KEY(`id`))"))
        val db = Fixtures.moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("AutoMigration(from = 6, to = 7),"))
        assertTrue(db.contains("version = 7,"))
    }
}
