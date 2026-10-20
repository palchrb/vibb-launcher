package me.vibb.music.data

import me.vibb.music.Fixtures
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room 6 (designs 49 §1.2 and 50 §1.5, one round): `repeat` and `favourites`, new tables by an auto migration. */
class Room6Test {
    @Test
    fun `Room 6 - repeat and favourites are new tables by an auto migration`() {
        val v6 = Fixtures.moduleFile("schemas/me.vibb.music.data.MusicDb/6.json").readText()
        assertTrue(v6.contains("\"version\": 6"))
        assertTrue(v6.contains("\"tableName\": \"repeat\""))
        assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`entryKey` TEXT NOT NULL, `mode` TEXT NOT NULL, PRIMARY KEY(`entryKey`))"))
        assertTrue(v6.contains("\"tableName\": \"favourites\""))
        assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`entryKey` TEXT NOT NULL, `itemKey` TEXT NOT NULL, PRIMARY KEY(`entryKey`, `itemKey`))"))
        val db = Fixtures.moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("AutoMigration(from = 5, to = 6),"))
        assertTrue(db.contains("version = 6,"))
    }
}
