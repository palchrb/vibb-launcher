package com.kidslauncher.mdm.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QA #2 (handy step 7): nothing may clear our persistent preferred activities and leave them cleared - that would
 * drop the HOME pin together with the Play link blocker, and install mode, its end or a crash in
 * between must leave both in place. Both are set on every apply(). The one clear is design 59d step M's re-seat
 * (`AppEnforcer.reseatPreferredActivities`, from `lock/HomePin` only), which adds every PPA of ours back in the same
 * call, HomeActivity's HOME first and the boot cover's last.
 */
class PlayInvariantsTest {
    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    @Test
    fun `persistent preferred activities are cleared only by the re-seat, which adds them all back`() {
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = code(file.name)
                "clearPackagePersistentPreferredActivities" in text || "clearPersistentPreferredActivity" in text
            }
            .map { it.name }
            .toList()
        assertEquals(listOf("AppEnforcer.kt"), offenders)
        val enforcer = code("AppEnforcer.kt")
        assertEquals(1, Regex("clearPackagePersistentPreferredActivities\\(").findAll(enforcer).count())
        assertFalse("clearPersistentPreferredActivity(" in enforcer)
        val reseat = enforcer.substringAfter("internal fun reseatPreferredActivities(").substringBefore("\n    }\n")
        val clear = reseat.indexOf("clearPackagePersistentPreferredActivities(")
        val home = reseat.indexOf("enforceDefaultHome(dpm, admin, context)")
        val play = reseat.indexOf("enforcePlayLinkBlocker(dpm, admin, context)")
        val calls = reseat.indexOf("enforceCallLogPin(dpm, admin, context)")
        assertTrue(reseat, clear >= 0 && clear < home && home < play && play < calls)
        // Only HomePin calls it, and adds the cover's PPA after it, all under its lock.
        val callers = sources.walkTopDown().filter { it.isFile && it.extension == "kt" && "reseatPreferredActivities(" in code(it.name) }
            .map { it.name }.toSet()
        assertEquals(setOf("AppEnforcer.kt", "HomePin.kt"), callers)
        val pin = code("HomePin.kt")
        val check = pin.substringAfter("fun check(").substringBefore("\n    }\n")
        assertTrue(check.contains("synchronized(lock) {"))
        assertTrue(check.indexOf("AppEnforcer.reseatPreferredActivities(app, dpm, admin)") in 0 until check.indexOf("BootCover.addCoverPin(app, dpm, admin)"))
    }

    @Test
    fun `apply sets both the HOME pin and the link blocker`() {
        val enforcer = sources.walkTopDown().first { it.name == "AppEnforcer.kt" }.readText()
        assertTrue("enforceDefaultHome(dpm, admin, context)" in enforcer)
        assertTrue("enforcePlayLinkBlocker(dpm, admin, context)" in enforcer)
    }

    /** Design 16b (QA #7/#9): the boot cover's PPA is added next to HomeActivity's with its own
     * category (a distinct filter - the DPM policy engine keys PPAs by filter, so it never
     * replaces the HOME pin), and HomeActivity's stays as it was. */
    @Test
    fun `the boot cover adds its own PPA and never touches HomeActivity's`() {
        val enforcer = sources.walkTopDown().first { it.name == "AppEnforcer.kt" }.readText()
        val home = enforcer.substringAfter("private fun enforceDefaultHome(").substringBefore("\n    }\n")
        assertTrue("HomeActivity::class.java" in home)
        assertTrue("BOOT_COVER" !in home)
        assertTrue("BootCover.applyPolicy(" in enforcer)
        val cover = sources.walkTopDown().first { it.name == "BootCover.kt" }.readText()
        val apply = cover.substringAfter("fun applyPolicy(").substringBefore("\n    }\n")
        assertTrue("if (wanted) HomePin.locked { addCoverPin(app, dpm, admin) }" in apply)
        val filter = cover.substringAfter("private fun coverFilter()").substringBefore("\n    }\n")
        for (needed in listOf("addCategory(BOOT_COVER_CATEGORY)", "Intent.CATEGORY_HOME")) assertTrue(needed, needed in filter)
        val add = cover.substringAfter("internal fun addCoverPin(").substringBefore("\n    }\n")
        assertTrue("addPersistentPreferredActivity(admin, coverFilter(), component(app))" in add)
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

    /** A source file's code without its comment lines, so a commented-out call doesn't pass a scan
     * (qa-28-s1-code L2). */
    private fun code(name: String): String = sources.walkTopDown().first { it.name == name }.readText()
        .lines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .joinToString("\n")

    /** Design 28: the drawer lists the Play Store while the override or the pause is open. */
    @Test
    fun `the filter asks the Play rule with the windows`() {
        val filter = code("AppFilter.kt")
        assertTrue("PlayRuntime.storeListable(context)" in filter)
        assertTrue("PlayRuntime.installModeActive(" !in filter)
        // The rule gets the windows - the same predicate as buildEnforcementPlan's overrideActive.
        val runtime = code("PlayRuntime.kt")
        assertTrue("fun storeListable(context: Context): Boolean = playStoreListable(installModeActive(context), overrideActive())" in runtime)
        assertTrue("private fun overrideActive() = OfflineOverride.isActive() || RestrictionsPause.isActive()" in runtime)
    }

    /** Design 28 (QA L8): in a window the store is in the drawer only, not on Home's grid. */
    @Test
    fun `Home drops the store outside install mode`() {
        val refresh = code("HomeActivity.kt").substringAfter("private fun refreshApps(").substringBefore("\n    }\n")
        assertTrue(".filter { playPackageOnHome((it.getRawInfo() as? AppInfo)?.packageName, installMode) }" in refresh)
        assertTrue("PlayRuntime.installModeActive(context)" in refresh)
        assertTrue("storeListable" !in refresh)
    }

    /** Design 28 (QA M1): the list refresh comes from the window's own edges, not from Play's
     * suspension, which on a GMS phone never happens. */
    @Test
    fun `a window's edges reload the app list`() {
        val initRest = code("Application.kt").substringAfter("private fun initRest(").substringBefore("\n    }\n")
        assertTrue("WindowRelock.addListener { loadApps() }" in initRest)
    }

    /** qa-28-s1-code L1: the drawer's filter can clear an expired window, whose preference listener
     * re-enters updateAppsList on main - the list must be filtered before it is replaced. */
    @Test
    fun `the drawer filters before it replaces its list`() {
        val update = code("AppsRecyclerAdapter.kt").substringAfter("fun updateAppsList(").substringBefore("\n    }\n")
        val filter = update.indexOf("appFilter(")
        val clear = update.indexOf("appsListDisplayed.clear()")
        assertTrue(filter >= 0 && clear > filter)
        assertTrue("appsListDisplayed.addAll(appFilter(" !in update)
    }
}
