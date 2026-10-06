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

    /** Design 12 (QA #6): the call log goes to the phone book, set next to the Play link blocker on
     * every apply() - before anything in apply() that can throw - and never lifted. */
    @Test
    fun `apply pins the call log to the phone book next to the link blocker`() {
        val enforcer = sources.walkTopDown().first { it.name == "AppEnforcer.kt" }.readText()
        val apply = enforcer.substringAfter("fun apply(context: Context").substringBefore("CallPolicyStore.ensureLoaded(context)")
        assertTrue("enforcePlayLinkBlocker(dpm, admin, context)" in apply)
        assertTrue("enforceCallLogPin(dpm, admin, context)" in apply)
        val pin = enforcer.substringAfter("private fun enforceCallLogPin(").substringBefore("\n    }\n")
        assertTrue("addDataType(CALL_LOG_TYPE)" in pin)
        assertTrue("Intent.CATEGORY_DEFAULT" in pin)
        assertTrue("PhoneBookActivity::class.java" in pin)
    }
}
