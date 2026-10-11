package com.kidslauncher.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The build chain's guards (security review L9, L13), checked as text: Gradle runs them, a JVM test
 * can only see that they are wired up. The release-build failure itself was run by hand
 * (`assembleRelease` without `libs/tsnet.aar` fails in `checkReleaseHasTsnet`).
 */
class BuildChainTest {
    private fun module(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    private val buildScript by lazy { module("build.gradle.kts").readText() }

    /** L13: the stub is the debug variant's only, and a release without the aar fails whatever the flags. */
    @Test
    fun `a release build never gets the tsnet stub`() {
        assertTrue(
            "the stub belongs to the debug source set",
            "sourceSets.getByName(\"debug\").java.srcDir(\"src/tsnetStub/java\")" in buildScript,
        )
        assertFalse(
            "the stub must not be in the main source set",
            Regex("""getByName\("(main|release)"\)\.java\.srcDir\("src/tsnetStub""").containsMatchIn(buildScript),
        )
        val guard = buildScript.substringAfter("abstract class CheckHasTsnet").substringBefore("androidComponents {")
        assertTrue("the guard throws without the aar", "if (!aarPresent.get())" in guard && "throw GradleException" in guard)
        val wiring = buildScript.substringAfter("androidComponents {")
        assertTrue(
            "every release variant's pre-build depends on the guard",
            "register<CheckHasTsnet>(\"check\${cap}HasTsnet\")" in wiring &&
                "it.name == \"pre\${cap}Build\" }.configureEach { dependsOn(tsnetGuard) }" in wiring,
        )
        assertTrue("the guard sees the aar itself, not a flag", "aarPresent.set(hasTsnet)" in wiring)
        assertTrue("hasTsnet is the file's existence", "val hasTsnet = file(\"libs/tsnet.aar\").exists()" in buildScript)
    }
}
