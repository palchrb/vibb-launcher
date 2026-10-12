package me.vibb.music

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security review L9 (the Gradle side), checked as text: the wrapper pins the distribution's
 * SHA-256 and the build verifies every dependency's checksum (`gradle/verification-metadata.xml`).
 * The launcher's `BuildChainTest` also checks CI's wrapper validation for both apps.
 */
class BuildChainTest {
    private fun project(path: String) = listOf(File("../$path"), File(path)).first { it.exists() }

    @Test
    fun `the wrapper pins the Gradle distribution`() {
        val properties = project("gradle/wrapper/gradle-wrapper.properties").readText()
        assertTrue(Regex("""(?m)^distributionSha256Sum=[0-9a-f]{64}$""").containsMatchIn(properties))
    }

    @Test
    fun `dependencies are verified by checksum`() {
        val metadata = project("gradle/verification-metadata.xml").readText()
        assertTrue("<verify-metadata>true</verify-metadata>" in metadata)
        assertTrue("CI's aapt2 (x86_64 Linux) is listed", "aapt2-8.13.0-13719691-linux.jar" in metadata)
    }
}
