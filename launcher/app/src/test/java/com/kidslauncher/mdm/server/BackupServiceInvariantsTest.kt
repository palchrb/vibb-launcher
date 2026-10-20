package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's backup to Google stays off (docs/setup/google-account.md at the monorepo root): AOSP
 * leaves the backup service off on a device-owner phone and only the device owner can switch it
 * on, so nothing in the launcher may ever do that. Every mention of the setter in the production
 * sources (every source set but the tests) must be a plain call whose last argument is the
 * literal `false` - a variable, a named argument, a method reference or reflection fails here.
 */
class BackupServiceInvariantsTest {
    private val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }

    private val productionSources: List<File> = src.listFiles()!!
        .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
        .flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }

    @Test
    fun `nothing ever switches the backup service on`() {
        val setter = "setBackupServiceEnabled"
        val offenders = mutableListOf<String>()
        var calls = 0
        for (file in productionSources) {
            val text = file.readText()
            var at = text.indexOf(setter)
            while (at >= 0) {
                val open = at + setter.length
                val line = text.substring(0, at).count { it == '\n' } + 1
                if (text.getOrNull(open) != '(') {
                    offenders += "${file.path}:$line (not a plain call)"
                } else {
                    var depth = 0
                    var close = open
                    while (close < text.length) {
                        when (text[close]) {
                            '(' -> depth++
                            ')' -> if (--depth == 0) break
                        }
                        close++
                    }
                    val lastArgument = text.substring(open + 1, close.coerceAtMost(text.length)).substringAfterLast(',').trim()
                    if (lastArgument != "false") offenders += "${file.path}:$line (last argument `$lastArgument`)"
                    calls++
                }
                at = text.indexOf(setter, open)
            }
        }
        assertEquals("Only setBackupServiceEnabled(admin, false) is allowed: $offenders", emptyList<String>(), offenders)
        // The scan must have found BackupService's own call, or it isn't looking at the sources.
        assertTrue("No call found in $src", calls >= 1)
    }

    @Test
    fun `apply keeps it off`() {
        val enforcer = productionSources.first { it.name == "AppEnforcer.kt" }.readText()
        assertTrue("BackupService.enforce(dpm, admin, managedForHardening)" in enforcer)
    }

    /**
     * Launcher round 7 §2 (docs/design/r7-launcher-small-items.md; qa-l7-b-code finding 1): a package on
     * `setUserControlDisabledPackages` can't be suspended, hidden or uninstalled by the device owner either
     * (AOSP "protected package"), so time rules, the budget, the camera lock and the update fence would
     * fail open for it. Nothing in the production code may call it - in code, not in a comment.
     */
    @Test
    fun `nothing sets setUserControlDisabledPackages`() {
        fun code(file: File) = file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
        val callers = productionSources.filter { "setUserControlDisabledPackages" in code(it) }.map { it.name }
        assertEquals(emptyList<String>(), callers)
        assertTrue("The scan found no sources in $src", productionSources.isNotEmpty())
    }
}
