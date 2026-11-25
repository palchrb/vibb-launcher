package com.kidslauncher.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The build chain's guards (security review L9, L13), checked as text: Gradle and CI run them, a
 * JVM test can only see that they are wired up. The release-build failure itself was run by hand
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

    private fun repo(path: String) = listOf(File("../../$path"), File("../$path")).first { it.exists() }

    /** L9: both wrappers pin the distribution's SHA-256 (the wrapper refuses another download). */
    @Test
    fun `the wrappers pin the Gradle distribution`() {
        val sums = listOf("launcher", "music").map { project ->
            val properties = repo("$project/gradle/wrapper/gradle-wrapper.properties").readText()
            val url = Regex("""(?m)^distributionUrl=(.+)$""").find(properties)!!.groupValues[1]
            val sum = Regex("""(?m)^distributionSha256Sum=([0-9a-f]{64})$""").find(properties)
            assertTrue("$project pins its distribution", sum != null)
            url to sum!!.groupValues[1]
        }
        assertTrue("one Gradle, one checksum", sums.distinct().size == 1)
    }

    /** L9: CI validates every gradle-wrapper.jar before Gradle runs, with the action pinned by commit. */
    @Test
    fun `CI validates the wrapper jars before running Gradle`() {
        val validation = Regex("""uses: gradle/actions/wrapper-validation@[0-9a-f]{40} # v""")
        for ((workflow, jobs) in listOf(
            "launcher.yml" to listOf("launcher-build", "launcher-release-build"),
            "music.yml" to listOf("music-build", "music-release-build"),
        )) {
            val text = repo(".github/workflows/$workflow").readText()
            for (job in jobs) {
                val body = text.substringAfter("\n  $job:\n").substringBefore("\n\n  ")
                val at = validation.find(body)
                assertTrue("$job validates the wrappers", at != null)
                assertTrue("$job validates before Gradle", at!!.range.first < body.indexOf("./gradlew"))
                assertTrue("min-wrapper-count: 2" in body)
            }
        }
        // QA Low 6: this test reads music's wrapper, metadata and workflow, so a change to them
        // alone must run the launcher's build too.
        val filter = repo(".github/workflows/launcher.yml").readText()
            .lineSequence().first { "git diff --name-only" in it && "grep -qE" in it }
        for (path in listOf("music/gradle/wrapper/gradle-wrapper.properties", "music/gradle/verification-metadata.xml", ".github/workflows/music.yml")) {
            val pattern = Regex(filter.substringAfter("grep -qE '").substringBefore("'; then"))
            assertTrue("launcher.yml's filter covers $path", pattern.containsMatchIn(path))
        }
    }

    /** The files under [dir], relative to it ('/'-separated); none when it doesn't exist. */
    private fun filesUnder(dir: File): List<String> =
        if (!dir.isDirectory) emptyList()
        else dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()

    private fun workflowFilter(workflow: String): Regex {
        val line = repo(".github/workflows/$workflow").readText()
            .lineSequence().first { "git diff --name-only" in it && "grep -qE" in it }
        return Regex(line.substringAfter("grep -qE '").substringBefore("'; then"))
    }

    /** Design 133 §6.2: a change to any contract runs the launcher's build, and one to a file the music app reads
     * (contracts/music/, contracts/shipped/bridge-aidl/) runs the music app's. */
    @Test
    fun `the filters match every file under contracts`() {
        val launcher = workflowFilter("launcher.yml")
        val music = workflowFilter("music.yml")
        val all = filesUnder(repo("contracts")).map { "contracts/$it" }
        assertTrue("contracts/ has its README", "contracts/README.md" in all)
        for (path in all) {
            assertTrue("launcher.yml's filter covers $path", launcher.containsMatchIn(path))
            val musicReads = path.startsWith("contracts/music/") || path.startsWith("contracts/shipped/bridge-aidl/")
            if (musicReads) assertTrue("music.yml's filter covers $path", music.containsMatchIn(path))
        }
        assertTrue("music.yml's filter covers a new music contract", music.containsMatchIn("contracts/music/x.json"))
        assertTrue(music.containsMatchIn("contracts/shipped/bridge-aidl/me/vibb/bridge/X.aidl"))
    }

    /** Design 133 §6.2: no file of a contract root has a copy in the module's own source sets, where it would shadow
     * the contract (a leftover or re-added copy). The music app's ContractFilesTest checks its own module. */
    @Test
    fun `no contract has a copy in the launcher or music source sets`() {
        val contracts = repo("contracts")
        val src = module("src")
        val musicSrc = File(repo("music"), "app/src")
        for (root in listOf("core", "integrations", "music")) {
            for (name in filesUnder(File(contracts, root))) {
                assertFalse("$root/$name has a copy in test resources", File(src, "test/resources/$name").exists())
            }
        }
        for (name in filesUnder(File(contracts, "shipped/launcher-assets"))) {
            assertFalse("shipped/launcher-assets/$name has a copy in assets", File(src, "main/assets/$name").exists())
        }
        for (name in filesUnder(File(contracts, "shipped/bridge-aidl"))) {
            assertFalse("$name has a copy in the launcher's aidl", File(src, "main/aidl/$name").exists())
            assertFalse("$name has a copy in the music app's aidl", File(musicSrc, "main/aidl/$name").exists())
        }
    }

    /** L9: dependency verification (gradle/verification-metadata.xml): every artifact's SHA-256 is
     * checked, including the x86_64 CI's aapt2 that a local build never resolves. */
    @Test
    fun `dependencies are verified by checksum`() {
        for (project in listOf("launcher", "music")) {
            val metadata = repo("$project/gradle/verification-metadata.xml").readText()
            assertTrue("$project verifies", "<verify-metadata>true</verify-metadata>" in metadata)
            assertTrue("$project lists aapt2 for linux", "aapt2-8.13.0-13719691-linux.jar" in metadata)
            assertFalse("no blanket trust", Regex("""<trust group="[^"]*" regex="true"/>""").containsMatchIn(metadata))
            assertTrue(Regex("""<sha256 value="[0-9a-f]{64}"""").containsMatchIn(metadata))
        }
    }

    /** compat-fix-1: lint fails the build on a new error; the baseline holds that day's errors, never a NewApi (Android
     * 14-17: an API above minSdk is gated, root CLAUDE.md "Supported Android"). */
    @Test
    fun `lint fails on new errors and never baselines NewApi`() {
        val lint = buildScript.substringAfter("    lint {").substringBefore("    }")
        assertTrue("abortOnError = true" in lint)
        assertTrue("baseline = file(\"lint-baseline.xml\")" in lint)
        val baseline = module("lint-baseline.xml").readText()
        assertTrue("<issues" in baseline)
        assertFalse("NewApi is never baselined", "id=\"NewApi\"" in baseline)
    }
}
