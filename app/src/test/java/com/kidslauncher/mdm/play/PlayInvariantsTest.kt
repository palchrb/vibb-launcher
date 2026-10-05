package com.kidslauncher.mdm.play

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QA #2 (handy step 7): nothing may ever clear our persistent preferred activities - that would
 * drop the HOME pin together with the Play link blocker, and install mode, its end or a crash in
 * between must leave both in place. Both are set on every apply() and never removed.
 */
class PlayInvariantsTest {
    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    @Test
    fun `persistent preferred activities are never cleared`() {
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                "clearPackagePersistentPreferredActivities" in text || "clearPersistentPreferredActivity" in text
            }
            .map { it.name }
            .toList()
        assertTrue("Found in $offenders", offenders.isEmpty())
    }

    @Test
    fun `apply sets both the HOME pin and the link blocker`() {
        val enforcer = sources.walkTopDown().first { it.name == "AppEnforcer.kt" }.readText()
        assertTrue("enforceDefaultHome(dpm, admin, context)" in enforcer)
        assertTrue("enforcePlayLinkBlocker(dpm, admin, context)" in enforcer)
    }
}
