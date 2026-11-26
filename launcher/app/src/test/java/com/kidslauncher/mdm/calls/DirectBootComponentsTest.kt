package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** KidAppComponentFactory's list of direct-boot-aware components must match the manifest: a
 * component missing from it would run the unlocked setup before unlock (QA direct-boot #1). */
class DirectBootComponentsTest {

    private val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        .map(::File).first { it.exists() }

    private fun manifestComponents(): Pair<Set<String>, Set<String>> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val aware = mutableSetOf<String>()
        val all = mutableSetOf<String>()
        for (tag in listOf("activity", "service", "receiver", "provider")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val name = element.getAttributeNS(ns, "name").let { if (it.startsWith(".")) "com.kidslauncher.mdm$it" else it }
                all += name
                if (element.getAttributeNS(ns, "directBootAware") == "true") aware += name
            }
        }
        return aware to all
    }

    @Test
    fun `the list matches the manifest's directBootAware components`() {
        val (aware, _) = manifestComponents()
        assertEquals(aware, DirectBootComponents.CLASS_NAMES)
    }

    @Test
    fun `Home, the lock screen and the background services need the unlocked setup`() {
        val (_, all) = manifestComponents()
        for (name in listOf(
            "com.kidslauncher.mdm.ui.HomeActivity", "com.kidslauncher.mdm.ui.LockActivity",
            "com.kidslauncher.mdm.server.KidVpnService", "com.kidslauncher.mdm.server.CommandListenerService",
            "com.kidslauncher.mdm.server.PackageReplacedReceiver",
            // Writes the learned Element rooms to CE prefs (design 15, qa-15-code #3).
            "com.kidslauncher.mdm.badges.BadgeListenerService",
        )) {
            assertTrue(name, name in all)
            assertTrue(name, DirectBootComponents.needsUnlockedSetup(name))
        }
        assertFalse(DirectBootComponents.needsUnlockedSetup("com.kidslauncher.mdm.calls.KidCallScreeningService"))
    }

    /** The rc.5 gate (§6bv finding 2): only pages and receivers wait for the setup's second part at their create. */
    @Test
    fun `an activity or a receiver gets the whole unlocked setup first, a service or a provider the first part`() {
        assertTrue(DirectBootComponents.unlockedRulesBefore(DirectBootComponents.Kind.ACTIVITY))
        assertTrue(DirectBootComponents.unlockedRulesBefore(DirectBootComponents.Kind.RECEIVER))
        assertFalse(DirectBootComponents.unlockedRulesBefore(DirectBootComponents.Kind.SERVICE))
        assertFalse(DirectBootComponents.unlockedRulesBefore(DirectBootComponents.Kind.PROVIDER))
        val src = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val factory = File(src, "com/kidslauncher/mdm/KidAppComponentFactory.kt").readText()
        for ((call, kind) in listOf("instantiateActivity" to "ACTIVITY", "instantiateService" to "SERVICE", "instantiateReceiver" to "RECEIVER", "instantiateProvider" to "PROVIDER")) {
            assertTrue(call, factory.substringAfter("override fun $call(").substringBefore("\n    }\n").contains("beforeCreating(className, DirectBootComponents.Kind.$kind)"))
        }
        assertTrue(factory.contains("ensureUnlockedInit(rulesNow = DirectBootComponents.unlockedRulesBefore(kind))"))
        // The application's wiring of UnlockedSetupSteps (its order is UnlockedSetupStepsTest's): onCreate unlocked runs
        // both parts at once, the factory and the unlock broadcast go through ensureUnlockedInit, which schedules the
        // second part at an idle moment.
        val app = File(src, "com/kidslauncher/mdm/Application.kt").readText()
        assertTrue(app.contains("setupSteps.ensure(rulesNow = true) { initUnlocked() }"))
        assertTrue(app.contains("fun ensureUnlockedInit(rulesNow: Boolean = false) = setupSteps.ensure(rulesNow) {\n        com.kidslauncher.mdm.calls.CallPolicyStore.refresh(this, ceReadable = true)\n        initUnlocked()"))
        assertTrue(app.contains("rules = { initRules() },\n        schedule = { step -> scheduleRules(step) },"))
        val schedule = app.substringAfter("private fun scheduleRules(step: () -> Unit) {").substringBefore("\n    }\n")
        assertTrue(schedule.contains("android.os.Looper.getMainLooper().queue.addIdleHandler {\n            step()\n            false"))
        assertTrue(schedule.contains("mainHandler.postDelayed({ step() }, RULES_STEP_LATEST_MS)"))
        // The unlock broadcast (a process with no component of ours created at the unlock) schedules it too.
        val deferred = app.substringAfter("private fun deferUntilUnlocked() {").substringBefore("\n    }\n")
        assertTrue(deferred.contains("override fun onReceive(context: Context?, intent: Intent?) = ensureUnlockedInit()"))
        assertTrue(deferred.contains("if (com.kidslauncher.mdm.calls.CallPolicyStore.userUnlocked(this)) ensureUnlockedInit()"))
        assertTrue(app.substringAfter("private fun initRules() {").substringBefore("\n    }\n").contains("rulesAndLock()"))
        val rest = app.substringAfter("private fun initRest() {").substringBefore("\n    }\n")
        val second = app.substringAfter("private fun rulesAndLock() {").substringBefore("\n    }\n")
        assertTrue(rest.contains("ZenRuleRuntime.init(this)"))
        for (piece in listOf("TimeRulesRuntime.recheck(", "UpdateFence.init(this)", "PinLockRuntime.init(this)")) {
            assertFalse(piece, rest.contains(piece))
            assertTrue(piece, second.contains(piece))
        }
        assertTrue(second.indexOf("TimeRulesRuntime.recheck(") < second.indexOf("UpdateFence.init(this)"))
        assertTrue(second.indexOf("UpdateFence.init(this)") < second.indexOf("PinLockRuntime.init(this)"))
    }
}
