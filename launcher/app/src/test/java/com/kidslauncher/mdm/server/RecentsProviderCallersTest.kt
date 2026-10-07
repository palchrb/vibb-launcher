package com.kidslauncher.mdm.server

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * qa-16d-code #4: every `recentsPackage`/`recentsPin` parameter defaults to `null`, so a call site
 * that drops it still compiles - and then the fence skips the pinned provider as protected (the
 * stock launcher unfenced), or the allowlist hides quickstep. Each real call passes it.
 */
class RecentsProviderCallersTest {
    private fun code(path: String) = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/$path") }
        .first { it.exists() }.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    /** The argument lists of every call of [name] in [text] (not its declaration). */
    private fun calls(text: String, name: String): List<String> =
        Regex("(?<!\\w)(fun\\s+)?$name\\(").findAll(text).filter { it.groupValues[1].isEmpty() }.map { match ->
            var depth = 0
            val start = match.range.last
            var end = start
            while (end < text.length) {
                when (text[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            text.substring(start, end + 1)
        }.toList()

    private fun assertPassed(path: String, name: String, argument: String, expected: Int) {
        val found = calls(code(path), name)
        assertTrue("$path: $expected call(s) of $name, found ${found.size}", found.size == expected)
        for (call in found) assertTrue("$path: $name$call doesn't pass $argument", call.contains(argument))
    }

    @Test
    fun `enforcement passes the provider - plan, new packages, camera lock`() {
        assertPassed("server/AppEnforcer.kt", "computeEnforcementPlan", "recentsPackage = systemRecentsPackage(context)", 1)
        assertPassed("server/AppEnforcer.kt", "shouldSuspendNewPackage", "recentsPackage = systemRecentsPackage(context)", 2)
        assertPassed("lock/CameraLock.kt", "cameraLockTargets", "AppEnforcer.systemRecentsPackage(app)", 1)
    }

    @Test
    fun `the fence passes the provider - plan, release and orphan sweep`() {
        for (name in listOf("fencePlan", "runRelease", "orphanFenceTargets")) {
            assertPassed("server/UpdateFence.kt", name, "recentsPackage = AppEnforcer.systemRecentsPackage(app)", 1)
        }
    }

    @Test
    fun `the chrome gets the kiosk's recents pin from the plan`() {
        assertPassed("server/AppEnforcer.kt", "applyPlan", "recentsPin = plan.recentsPin", 1)
        val chrome = code("lock/LockTaskChrome.kt")
        assertTrue(chrome.contains("recentsPin = current.recentsPin,"))
        assertTrue(chrome.contains("recentsPin = pin,"))
    }
}
