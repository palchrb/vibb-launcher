package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.HardeningPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HardeningTest {

    private val serverDefaults = HardeningPolicy(
        disallowFactoryReset = true,
        disallowAddUser = true,
        disallowModifyAccounts = true,
        disallowConfigVpn = true,
        disallowUsbFileTransfer = true,
        disallowDebuggingFeatures = true,
        disallowSafeBoot = false,
        lockLocation = true,
        disallowAirplaneMode = false,
        disallowConfigLocale = true,
        disallowScreenCapture = false,
        disallowConfigCredentials = true,
    )

    @Test
    fun `defaults are on except safe boot and airplane mode`() {
        val defaults = HardeningRestriction.entries.associateWith {
            it != HardeningRestriction.SAFE_BOOT && it != HardeningRestriction.AIRPLANE_MODE
        }
        assertEquals(defaults, hardeningPlan(null, managed = true).restrictions)
        assertEquals(defaults, hardeningPlan(HardeningPolicy(), managed = true).restrictions)
        assertEquals(defaults, hardeningPlan(serverDefaults, managed = true).restrictions)
        assertTrue(hardeningPlan(null, managed = true).forceLocationOn)
    }

    @Test
    fun `explicit server values win, missing fields take the default`() {
        val plan = hardeningPlan(
            HardeningPolicy(disallowDebuggingFeatures = false, disallowSafeBoot = true, lockLocation = false),
            managed = true,
        )
        assertFalse(plan.isSet(HardeningRestriction.DEBUGGING_FEATURES))
        assertTrue(plan.isSet(HardeningRestriction.SAFE_BOOT))
        assertFalse(plan.isSet(HardeningRestriction.CONFIG_LOCATION))
        assertFalse(plan.forceLocationOn)
        assertTrue(plan.isSet(HardeningRestriction.FACTORY_RESET))
        assertTrue(plan.isSet(HardeningRestriction.CONFIG_VPN))

        val allOff = HardeningPolicy(false, false, false, false, false, false, false, false, false, false, false, false)
        // Every server switch off; the launcher-only locks have no switch.
        assertEquals(
            launcherOnly,
            hardeningPlan(allOff, managed = true).restrictions.filterValues { it }.keys,
        )

        // Airplane mode is only blocked when the parent says so, and like the rest only while managed.
        assertTrue(hardeningPlan(HardeningPolicy(disallowAirplaneMode = true), managed = true).isSet(HardeningRestriction.AIRPLANE_MODE))
        assertFalse(hardeningPlan(HardeningPolicy(disallowAirplaneMode = true), managed = false).isSet(HardeningRestriction.AIRPLANE_MODE))

        // The system language lock (fix round 2026-10-06): on by default, off only from the server.
        assertTrue(hardeningPlan(HardeningPolicy(), managed = true).isSet(HardeningRestriction.CONFIG_LOCALE))
        assertFalse(hardeningPlan(HardeningPolicy(disallowConfigLocale = false), managed = true).isSet(HardeningRestriction.CONFIG_LOCALE))
        assertFalse(hardeningPlan(null, managed = false).isSet(HardeningRestriction.CONFIG_LOCALE))

        // User certificates: blocked by default while managed, allowed only by the parent's switch.
        assertTrue(hardeningPlan(null, managed = true).isSet(HardeningRestriction.CONFIG_CREDENTIALS))
        assertTrue(hardeningPlan(HardeningPolicy(), managed = true).isSet(HardeningRestriction.CONFIG_CREDENTIALS))
        assertFalse(hardeningPlan(HardeningPolicy(disallowConfigCredentials = false), managed = true).isSet(HardeningRestriction.CONFIG_CREDENTIALS))
        assertFalse(hardeningPlan(null, managed = false).isSet(HardeningRestriction.CONFIG_CREDENTIALS))
    }

    @Test
    fun `screen capture is off by default, on only from the server and only while managed`() {
        for (policy in listOf(null, HardeningPolicy(), serverDefaults)) {
            assertFalse(hardeningPlan(policy, managed = true).screenCaptureDisabled)
        }
        val on = HardeningPolicy(disallowScreenCapture = true)
        assertTrue(hardeningPlan(on, managed = true).screenCaptureDisabled)
        assertFalse(hardeningPlan(on, managed = false).screenCaptureDisabled)
        // Not a user restriction: the other switches are untouched by it.
        assertEquals(hardeningPlan(null, managed = true).restrictions, hardeningPlan(on, managed = true).restrictions)
    }

    @Test
    fun `screen capture is written only when it differs`() {
        assertEquals(true, screenCaptureWrite(current = false, wanted = true))
        assertEquals(false, screenCaptureWrite(current = true, wanted = false))
        assertEquals(null, screenCaptureWrite(current = true, wanted = true))
        assertEquals(null, screenCaptureWrite(current = false, wanted = false))
    }

    @Test
    fun `screen capture follows the hardening plan alone, never the override or the pause`() {
        // hardeningPlan has no override input; the one setter is fed only from the plan.
        val main = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val sources = File(main, "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val setters = sources.filter { it.readText().contains("setScreenCaptureDisabled(") }
        assertEquals(listOf("AppEnforcer.kt"), setters.map { it.name })
        val enforcer = setters.single().readText()
        // Exactly these two guards: any other term (a pause, the override) fails.
        val calls = enforcer.lines().map { it.trim() }.filter { "syncScreenCapture(dpm, admin," in it }
        assertEquals(
            listOf(
                "if (plan.screenCaptureDisabled) syncScreenCapture(dpm, admin, true)",
                "if (!plan.screenCaptureDisabled) syncScreenCapture(dpm, admin, false)",
            ),
            calls,
        )
        // The one write reads the policy first, so an unchanged value is never written again.
        val body = enforcer.substringAfter("private fun syncScreenCapture(").substringBefore("\n    }")
        val read = body.indexOf("screenCaptureWrite(dpm.getScreenCaptureDisabled(admin), disabled) ?: return")
        assertTrue(body, read >= 0 && read < body.indexOf("setScreenCaptureDisabled("))
        assertEquals(1, Regex("""setScreenCaptureDisabled\(""").findAll(enforcer).count())
    }

    @Test
    fun `the wallpaper lock is set while managed and cleared when unmanaged`() {
        for (policy in listOf(null, serverDefaults, HardeningPolicy(false, false, false, false, false, false, false, false, false))) {
            assertTrue(hardeningPlan(policy, managed = true).isSet(HardeningRestriction.SET_WALLPAPER))
            assertFalse(hardeningPlan(policy, managed = false).isSet(HardeningRestriction.SET_WALLPAPER))
        }
    }

    private val launcherOnly = setOf(
        HardeningRestriction.SET_WALLPAPER,
        HardeningRestriction.CONTENT_CAPTURE,
        HardeningRestriction.CONTENT_SUGGESTIONS,
        HardeningRestriction.ASSIST_CONTENT,
    )

    @Test
    fun `the screen-content locks are on whenever managed, whatever the server sends (design 126 A4)`() {
        val allOff = HardeningPolicy(false, false, false, false, false, false, false, false, false, false, false, false)
        val a4 = launcherOnly - HardeningRestriction.SET_WALLPAPER
        for (policy in listOf(null, HardeningPolicy(), serverDefaults, allOff)) {
            for (restriction in a4) {
                assertTrue(hardeningPlan(policy, managed = true).isSet(restriction))
                assertFalse(hardeningPlan(policy, managed = false).isSet(restriction))
            }
        }
    }

    @Test
    fun `assist content exists from API 35, every other key on 34 too`() {
        for (sdk in listOf(34, 35, 36, 37)) {
            for (restriction in HardeningRestriction.entries) {
                val expected = restriction != HardeningRestriction.ASSIST_CONTENT || sdk >= 35
                assertEquals("$restriction on $sdk", expected, hardeningKeyApplies(restriction, sdk))
            }
        }
    }

    @Test
    fun `the provisioning baseline holds until the first policy, factory reset left open`() {
        val baseline = setOf(
            HardeningRestriction.DEBUGGING_FEATURES,
            HardeningRestriction.ADD_USER,
            HardeningRestriction.SAFE_BOOT,
            HardeningRestriction.CONFIG_CREDENTIALS,
        )
        assertEquals(baseline, PROVISIONING_BASELINE)
        val plan = hardeningPlan(null, managed = false, baseline = true)
        assertEquals(baseline, plan.restrictions.filterValues { it }.keys)
        assertFalse(plan.isSet(HardeningRestriction.FACTORY_RESET))
        assertFalse(plan.forceLocationOn)
        assertFalse(plan.screenCaptureDisabled)
        // Once managed, the server's switches alone: the parent's off wins over the baseline.
        val managed = hardeningPlan(HardeningPolicy(disallowDebuggingFeatures = false), managed = true, baseline = true)
        assertFalse(managed.isSet(HardeningRestriction.DEBUGGING_FEATURES))
        assertTrue(managed.isSet(HardeningRestriction.FACTORY_RESET))
        // Armed at a QR provisioning, gone with the first policy (an unmanaged phone then clears everything).
        assertTrue(provisioningBaselineHolds(armed = true, everManaged = false))
        assertFalse(provisioningBaselineHolds(armed = true, everManaged = true))
        assertFalse(provisioningBaselineHolds(armed = false, everManaged = false))
    }

    @Test
    fun `the baseline is armed by the two provisioning entries only, never by adb's provisioning`() {
        val main = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val sources = File(main, "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val callers = sources.filter { it.readText().contains("ProvisioningBaseline.arm(") }.map { it.name }.sorted()
        assertEquals(listOf("MdmDeviceAdminReceiver.kt", "ProvisioningActivities.kt"), callers)
    }

    @Test
    fun `unmanaged clears every restriction`() {
        for (policy in listOf(null, serverDefaults, HardeningPolicy(disallowSafeBoot = true))) {
            val plan = hardeningPlan(policy, managed = false)
            assertEquals(HardeningRestriction.entries.toSet(), plan.restrictions.keys)
            assertTrue(plan.restrictions.values.none { it })
            assertFalse(plan.forceLocationOn)
        }
    }

    @Test
    fun `the backup service is only ever switched off, and only while managed`() {
        assertEquals(BackupServiceAction.TURN_OFF, backupServiceAction(managed = true, enabled = true))
        assertEquals(BackupServiceAction.NONE, backupServiceAction(managed = true, enabled = false))
        // Unreadable: nothing to do (the next apply reads it again).
        assertEquals(BackupServiceAction.NONE, backupServiceAction(managed = true, enabled = null))
        // Unmanaged: left as it is - never switched on, not even to "undo" anything.
        for (enabled in listOf(true, false, null)) {
            assertEquals(BackupServiceAction.NONE, backupServiceAction(managed = false, enabled = enabled))
        }
        // There is no action that turns it on.
        assertEquals(setOf(BackupServiceAction.NONE, BackupServiceAction.TURN_OFF), BackupServiceAction.entries.toSet())
    }

    @Test
    fun `managed means an enforced allowlist or managed calls`() {
        assertTrue(hardeningManaged(emptyList(), callsManaged = false))
        assertTrue(hardeningManaged(listOf("org.example.music"), callsManaged = false))
        assertTrue(hardeningManaged(null, callsManaged = true))
        assertFalse(hardeningManaged(null, callsManaged = false))
    }

    @Test
    fun `the fallback plan keeps the switches, and an old one means defaults`() {
        val policy = NOTHING_ALLOWED_FALLBACK.copy(hardening = HardeningPolicy(disallowDebuggingFeatures = false))
        val plan = LastEnforcedPlan.decode(LastEnforcedPlan.encode(LastEnforcedPlan.of(policy)))!!.toPolicy()
        assertEquals(false, plan.hardening?.disallowDebuggingFeatures)
        assertFalse(hardeningPlan(plan.hardening, hardeningManaged(plan.allowlist, false)).isSet(HardeningRestriction.DEBUGGING_FEATURES))

        // A plan stored by the previous launcher has no hardening: defaults, still managed.
        val old = LastEnforcedPlan.decode("""{"allowlist":["org.example.music"],"kiosk_desired":true}""")!!.toPolicy()
        assertEquals(null, old.hardening)
        assertTrue(hardeningPlan(old.hardening, hardeningManaged(old.allowlist, false)).isSet(HardeningRestriction.DEBUGGING_FEATURES))
    }

    @Test
    fun `the server's hardening object decodes`() {
        val json = """
            {"allowlist": [], "call_policy": {"calls_mode": "unmanaged", "sms_mode": "unmanaged"}, "hardening": {
              "disallow_factory_reset": true, "disallow_add_user": true, "disallow_modify_accounts": true,
              "disallow_config_vpn": true, "disallow_usb_file_transfer": true,
              "disallow_debugging_features": false, "disallow_safe_boot": false, "lock_location": true,
              "disallow_airplane_mode": false, "disallow_config_locale": true,
              "disallow_screen_capture": false, "disallow_config_credentials": true}}
        """.trimIndent()
        val policy = (decodeFresh(json) as FreshDecode.Ok).policy
        assertEquals(serverDefaults.copy(disallowDebuggingFeatures = false), policy.hardening)
        // Round trip through the cache.
        val cached = decodeCached(ServerJson.encodeToString(com.kidslauncher.mdm.server.dto.PolicyResponse.serializer(), policy))
        assertEquals(policy, (cached as CachedPolicy.Ok).policy)
    }

    /** Design 142 M3 (PLAN 1m(b)): the setup hold's apply raced the "enrolled" sync's - a stale `Apply(null)` with the
     * sync's managed calls set DISALLOW_DEBUGGING_FEATURES, and AOSP doesn't switch adb back on when it is lifted. */
    @Test
    fun `a stale Apply(null) never meets managed calls, wherever the sync's writes land`() {
        // The first policy, "Block USB debugging" off.
        val first = com.kidslauncher.mdm.server.dto.PolicyResponse(
            allowlist = emptyList(),
            hardening = HardeningPolicy(disallowDebuggingFeatures = false),
        )
        // The sync commits the policy (storeAcceptedPolicy), then refreshes the call state from it (CallPolicyStore).
        // Each write lands before the first read, between the two, or after both - every order of the four steps.
        fun pairs(callsFirst: Boolean): List<Pair<Boolean, PolicyToApply>> = buildList {
            for (storeAt in 0..2) for (refreshAt in storeAt..2) {
                var stored = false
                var managed = false
                var reads = 0
                fun writes() {
                    if (storeAt == reads) stored = true
                    if (refreshAt == reads) managed = stored
                }
                val calls = {
                    writes()
                    reads++
                    managed
                }
                val decision = {
                    writes()
                    reads++
                    PolicyToApply.Apply(if (stored) first else null)
                }
                add(
                    if (callsFirst) {
                        callsThenDecision(calls, decision)
                    } else {
                        val d = decision()
                        calls() to d
                    },
                )
            }
        }
        val ordered = pairs(callsFirst = true)
        assertEquals(6, ordered.size)
        for ((calls, decision) in ordered) {
            assertFalse("Apply(null) with managed calls", decision.policy == null && calls)
            val plan = hardeningPlan(decision.policy?.hardening, hardeningManaged(decision.policy?.allowlist, calls))
            assertEquals(false, plan.restrictions[HardeningRestriction.DEBUGGING_FEATURES])
        }
        // The decision first (the old order, outside or inside the lock): one order pairs them wrong.
        assertTrue(pairs(callsFirst = false).any { (calls, decision) -> decision.policy == null && calls })
        assertEquals(true, hardeningPlan(null, hardeningManaged(null, callsManaged = true)).restrictions[HardeningRestriction.DEBUGGING_FEATURES])
    }

    @Test
    fun `every offline apply reads its decision under the lock, and the pass reads the call state once`() {
        val main = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val sources = File(main, "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val code = sources.associate { it.name to it.readText().replace(Regex("//[^\\n]*"), "") }
        val stale = Regex("""\bapply\([^()]*,\s*(?:com\.kidslauncher\.mdm\.server\.)?currentPolicyDecision\(\)\)""")
        for ((name, text) in code) assertFalse(name, stale.containsMatchIn(text))
        val enforcer = code.getValue("AppEnforcer.kt")
        val current = enforcer.substringAfter("fun applyCurrent(context: Context) {").substringBefore("\n    }\n")
        assertTrue(current, "callsThenDecision(" in current)
        assertTrue(current.indexOf("CallPolicyStore.state") in 0 until current.indexOf("currentPolicyDecision()"))
        assertTrue("calls = calls" in current)
        assertTrue(enforcer.substringBefore("fun applyCurrent(").lines().last { it.isNotBlank() }.trim() == "@Synchronized")
        val pass = enforcer.substringAfter("private fun applyHeld(").substringBefore("\n    /**")
        assertEquals("one read of the store's state in the pass", 1, Regex("""CallPolicyStore\.(state|callsState)\b""").findAll(pass).count())
        assertTrue("val callState = calls ?: CallPolicyStore.state" in pass)
        assertTrue(pass.indexOf("val callState") < pass.indexOf("hardeningManaged(policy?.allowlist, callState.managed)"))
        // The sync keeps its own fresh decision; its pass reads the call state after its own refresh.
        assertTrue("AppEnforcer.apply(context, decision, fromSync = true)" in code.getValue("MdmSyncWorker.kt"))
    }
}
