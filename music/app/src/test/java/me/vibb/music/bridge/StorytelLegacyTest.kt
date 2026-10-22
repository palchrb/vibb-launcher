package me.vibb.music.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** The old Storytel login goes (design 58 §5.5, QA 58-15): the files and the Keystore key, once per process. */
class StorytelLegacyTest {
    @Test
    fun `the old login's files and its key go, once per process`() {
        val dir = Files.createTempDirectory("no_backup").toFile()
        try {
            for (name in StorytelLegacy.FILES) File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
            val other = File(dir, "renderer.json").apply { writeText("{}") }
            var keys = 0
            val flag = AtomicBoolean(false)
            assertTrue(StorytelLegacy.purgeOnce(flag, dir) { keys++; true })
            assertEquals(listOf("storytel.bin", "storytel.bin.tmp", "storytel_refused.json", "storytel_refused.json.tmp"), StorytelLegacy.FILES)
            for (name in StorytelLegacy.FILES) assertFalse(name, File(dir, name).exists())
            assertTrue("nothing else is touched", other.isFile)
            assertEquals(1, keys)

            // once per process: a second sync deletes nothing, not even a file that came back
            File(dir, "storytel.bin").writeBytes(byteArrayOf(1))
            assertFalse(StorytelLegacy.purgeOnce(flag, dir) { keys++; true })
            assertTrue(File(dir, "storytel.bin").isFile)
            assertEquals(1, keys)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nothing there is nothing to say, and a failing key store never throws`() {
        val dir = Files.createTempDirectory("no_backup").toFile()
        try {
            assertFalse(StorytelLegacy.purgeOnce(AtomicBoolean(false), dir) { false })
            assertFalse(StorytelLegacy.purgeOnce(AtomicBoolean(false), dir) { throw java.security.KeyStoreException("no keystore") })
            File(dir, "storytel_refused.json").writeText("{}")
            assertTrue("the files go even when the key can't", StorytelLegacy.purgeOnce(AtomicBoolean(false), dir) { throw IllegalStateException() })
            assertFalse(File(dir, "storytel_refused.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
