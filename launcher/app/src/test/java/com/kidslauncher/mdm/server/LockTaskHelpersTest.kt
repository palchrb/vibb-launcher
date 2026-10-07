package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.play.PLAY_CORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 9 B4 with qa-09-design.md #2/#3/#4: the kiosk app block and the system helpers pinned with it. */
class LockTaskHelpersTest {
    private val settings = "com.android.settings"
    private val camera = "com.android.camera2"
    private val dialer = "com.android.dialer"
    private val forbidden = setOf(settings, camera)

    private val resolved = mapOf(
        HelperKind.EMERGENCY_DIALER to ResolvedHelper("com.android.phone", system = true),
        HelperKind.TELECOM to ResolvedHelper("com.android.server.telecom", system = true),
        HelperKind.PERMISSION_CONTROLLER to ResolvedHelper("com.google.android.permissioncontroller", system = true),
        HelperKind.CHOOSER to ResolvedHelper("com.android.intentresolver", system = true),
        HelperKind.DOCUMENTS to ResolvedHelper("com.google.android.documentsui", system = true),
        HelperKind.PHOTO_PICKER to ResolvedHelper("com.google.android.providers.media.module", system = true),
        HelperKind.CELL_BROADCAST to ResolvedHelper("com.google.android.cellbroadcastreceiver", system = true),
        HelperKind.RESOLVER to ResolvedHelper("android", system = true),
    )

    @Test
    fun `system helpers resolved on this phone are pinned`() {
        assertEquals(
            setOf(
                "com.android.phone", "com.android.server.telecom", "com.google.android.permissioncontroller",
                "com.android.intentresolver", "com.google.android.documentsui",
                "com.google.android.providers.media.module", "com.google.android.cellbroadcastreceiver", "android",
            ),
            lockTaskHelpers(resolved, forbidden),
        )
    }

    @Test
    fun `never Settings, Play, GMS, GSF or the camera - and never a non-system app`() {
        val hostile = mapOf(
            HelperKind.TELECOM to ResolvedHelper(settings, system = true),
            HelperKind.CHOOSER to ResolvedHelper(camera, system = true),
            HelperKind.DOCUMENTS to ResolvedHelper("com.android.vending", system = true),
            HelperKind.PHOTO_PICKER to ResolvedHelper("com.google.android.gms", system = true),
            HelperKind.CELL_BROADCAST to ResolvedHelper("com.google.android.gsf", system = true),
            HelperKind.PERMISSION_CONTROLLER to ResolvedHelper("com.evil.picker", system = false),
        )
        assertEquals(emptySet<String>(), lockTaskHelpers(hostile, forbidden))
        assertEquals(emptySet<String>(), lockTaskHelpers(mapOf(HelperKind.TELECOM to null), forbidden))
    }

    @Test
    fun `a forbidden or non-system first match doesn't hide the real helper (qa-09-code 6)`() {
        val telecom = ResolvedHelper("com.android.server.telecom", system = true)
        assertEquals(
            telecom,
            firstHelper(listOf(ResolvedHelper(camera, true), ResolvedHelper("com.evil", false), ResolvedHelper("com.android.vending", true), telecom), forbidden),
        )
        assertEquals(null, firstHelper(listOf(ResolvedHelper(settings, true)), forbidden))
    }

    private val recents = "com.google.android.apps.nexuslauncher"

    @Test
    fun `the provider is trusted only as a system app with a HOME or the recents activity - never SystemUI (qa-16d-code 5)`() {
        assertEquals(recents, trustedRecentsProvider(recents, system = true, hasHome = true, hasRecentsActivity = true))
        assertEquals(recents, trustedRecentsProvider(recents, system = true, hasHome = true, hasRecentsActivity = false))
        assertEquals(recents, trustedRecentsProvider(recents, system = true, hasHome = false, hasRecentsActivity = true))
        assertEquals(null, trustedRecentsProvider(recents, system = true, hasHome = false, hasRecentsActivity = false))
        assertEquals(null, trustedRecentsProvider(recents, system = false, hasHome = true, hasRecentsActivity = true))
        assertEquals(null, trustedRecentsProvider(null, system = true, hasHome = true, hasRecentsActivity = true))
        // Legacy recents in SystemUI: pinning it would open every exported SystemUI activity.
        for (never in listOf("com.android.systemui", "android", "com.android.vending", "com.google.android.gms")) {
            assertEquals(never, null, trustedRecentsProvider(never, system = true, hasHome = true, hasRecentsActivity = true))
        }
    }

    @Test
    fun `the recents provider is a helper - resolved, system only, never forbidden or Play (design 16d)`() {
        val withRecents = resolved + (HelperKind.RECENTS to ResolvedHelper(recents, system = true))
        assertTrue(recents in lockTaskHelpers(withRecents, forbidden))
        // Never a non-system package, whatever the framework resource names; unknown = nothing.
        assertFalse(recents in lockTaskHelpers(resolved + (HelperKind.RECENTS to ResolvedHelper(recents, system = false)), forbidden))
        assertEquals(lockTaskHelpers(resolved, forbidden), lockTaskHelpers(resolved + (HelperKind.RECENTS to null), forbidden))
        for (bad in listOf(settings, camera, "com.android.vending", "com.google.android.gms")) {
            assertFalse(bad, bad in lockTaskHelpers(mapOf(HelperKind.RECENTS to ResolvedHelper(bad, system = true)), forbidden))
        }
    }

    @Test
    fun `kiosk with the block pins the recents provider and keeps OVERVIEW - design 16's drop reverted (16d)`() {
        val server = (LOCK_TASK_FEATURE_SYSTEM_INFO or LOCK_TASK_FEATURE_NOTIFICATIONS or LOCK_TASK_FEATURE_HOME or
            LOCK_TASK_FEATURE_OVERVIEW or LOCK_TASK_FEATURE_GLOBAL_ACTIONS).toLong()
        val withRecents = lockTaskHelpers(resolved + (HelperKind.RECENTS to ResolvedHelper(recents, system = true)), forbidden)
        fun plan(block: Boolean, locked: Boolean = false, helpers: Set<String> = withRecents, kiosk: Boolean = true) = computeEnforcementPlan(
            listOf("org.example.game"), kiosk, server, false, controllable, "me.vibb.launcher", dialer,
            scheduleLocked = locked, blockActivityStart = block, lockTaskHelpers = if (block) helpers else emptySet(),
        )
        for (locked in listOf(false, true)) {
            val on = plan(block = true, locked = locked)
            assertTrue(recents in on.kioskPackages!!)
            assertEquals(server.toInt() or LOCK_TASK_FEATURE_KEYGUARD or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK, on.lockTaskFeatures)
        }
        // Not resolvable as a system app: not pinned, and OVERVIEW still stays (the user's gesture rule).
        val unpinned = plan(block = true, helpers = lockTaskHelpers(resolved, forbidden))
        assertFalse(recents in unpinned.kioskPackages!!)
        assertTrue(unpinned.lockTaskFeatures and LOCK_TASK_FEATURE_OVERVIEW != 0)
        // Without the block nothing is pinned for it (no BlockedAppActivity possible); kiosk off: no list.
        assertFalse(recents in plan(block = false).kioskPackages!!)
        assertTrue(plan(block = false).lockTaskFeatures and LOCK_TASK_FEATURE_OVERVIEW != 0)
        assertEquals(null, plan(block = true, kiosk = false).kioskPackages)
    }

    @Test
    fun `LOCKED takes the recents provider out of the kiosk list, unlocked puts it back (qa-16d-code 1)`() {
        val kiosk = setOf("me.vibb.launcher", "org.example.game", recents)
        val unlocked = 127
        val locked = lockTaskWhileLocked(kiosk, unlocked, false, locked = true, ownPackage = "me.vibb.launcher", lockHelpers = emptySet(), recentsPin = recents)
        // Every other package stays (their locked tasks keep their state); Home, Recents and the shade go (113).
        assertEquals(kiosk - recents, locked.packages)
        assertEquals(113, locked.features)
        assertEquals(kiosk, lockTaskWhileLocked(kiosk, unlocked, false, locked = false, ownPackage = "me.vibb.launcher", lockHelpers = emptySet(), recentsPin = recents).packages)
        // No pin known: the list as it is.
        assertEquals(kiosk, lockTaskWhileLocked(kiosk, unlocked, false, true, "me.vibb.launcher", emptySet()).packages)
        // The plan names its pin only when the provider is in the kiosk list.
        val withRecents = lockTaskHelpers(resolved + (HelperKind.RECENTS to ResolvedHelper(recents, system = true)), forbidden)
        fun plan(block: Boolean, kioskOn: Boolean = true) = computeEnforcementPlan(
            listOf("org.example.game"), kioskOn, 127, false, controllable, "me.vibb.launcher", dialer,
            blockActivityStart = block, lockTaskHelpers = if (block) withRecents else emptySet(), recentsPackage = recents,
        )
        assertEquals(recents, plan(block = true).recentsPin)
        assertEquals(null, plan(block = false).recentsPin)
        assertEquals(null, plan(block = true, kioskOn = false).recentsPin)
        // Without a plan: the platform's list after a LOCKED pass lacks it - unlocked it goes back in
        // whenever the app block is on.
        val blocked = 63 or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
        assertEquals(kiosk, fallbackKioskPackages(kiosk - recents, recents, blocked))
        assertEquals(kiosk - recents, fallbackKioskPackages(kiosk - recents, recents, 63))
        assertEquals(kiosk - recents, fallbackKioskPackages(kiosk - recents, null, blocked))
        assertEquals(kiosk - recents, lockTaskWhileLocked(fallbackKioskPackages(kiosk - recents, recents, blocked), blocked, false, true, "me.vibb.launcher", emptySet(), recentsPin = recents).packages)
        // Kiosk off: the lock pins only our package and the PIN-lock helpers - never the recents
        // provider (no block bit there, so no dialog; pinning it would open its stock home to a call app).
        val kioskOff = lockTaskWhileLocked(null, unlocked, false, true, "me.vibb.launcher", setOf("com.android.phone"))
        assertFalse(recents in kioskOff.packages!!)
    }

    @Test
    fun `the recents provider is resolved for the kiosk block only - never a PIN-lock or VoIP helper`() {
        val enforcer = listOf("src/main", "app/src/main").map { java.io.File(it, "java/com/kidslauncher/mdm/server/AppEnforcer.kt") }
            .first { it.exists() }.readText()
        assertEquals(1, Regex("HelperKind\\.RECENTS to systemRecentsPackage\\(context\\)").findAll(enforcer).count())
        for (fn in listOf("resolvePinLockHelpers", "resolveVoipHelpers")) {
            val body = enforcer.substringAfter("fun $fn(").substringBefore("\n    }")
            assertFalse(fn, body.contains("RECENTS"))
        }
        assertTrue(enforcer.contains("Resources.getSystem()"))
        assertTrue(enforcer.contains("\"config_recentsComponentName\", \"string\", \"android\""))
    }

    @Test
    fun `the block bit follows the server switch and never comes from lock_task_features`() {
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD or 1, lockTaskFeatures(1 or 64, blockActivityStart = false))
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD or 1 or 64, lockTaskFeatures(1, blockActivityStart = true))
    }

    private val callsOn = CallPolicyState.Managed(CallRules(callsEnabled = true))
    private val controllable = listOf("me.vibb.launcher", dialer, "org.example.game")
    private val helpers = lockTaskHelpers(resolved, forbidden)

    private fun plan(block: Boolean, locked: Boolean = false, calls: CallPolicyState = callsOn, allow: List<String> = listOf("org.example.game", dialer)) =
        computeEnforcementPlan(
            allow, true, 0, false, controllable, "me.vibb.launcher", dialer,
            callState = calls, ourDialerActive = true, scheduleLocked = locked,
            blockActivityStart = block, lockTaskHelpers = helpers + dialer + "com.android.vending",
        )

    @Test
    fun `helpers are pinned only with the block, also during a time-rule lock`() {
        val on = plan(block = true)
        assertTrue(on.kioskPackages!!.containsAll(helpers))
        assertTrue(on.lockTaskFeatures and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK != 0)
        val locked = plan(block = true, locked = true)
        assertTrue(locked.kioskPackages!!.containsAll(helpers))
        assertTrue("com.android.phone" in locked.kioskPackages!!)
        val off = plan(block = false)
        assertFalse(off.kioskPackages!!.any { it in helpers })
        assertEquals(0, off.lockTaskFeatures and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK)
    }

    @Test
    fun `with the block the system dialer is always pinned - in-call UI and emergency (qa-09-code 1)`() {
        val noCallsLock = computeEnforcementPlan(
            listOf("org.example.game"), true, 0, false, controllable, "me.vibb.launcher", dialer,
            scheduleLocked = true, ruleBlocksCalls = true, blockActivityStart = true, lockTaskHelpers = helpers,
        )
        for (kiosk in listOf(
            plan(true)!!.kioskPackages!!, // calls managed
            plan(true, calls = CallPolicyState.Unmanaged, allow = listOf("org.example.game"))!!.kioskPackages!!,
            plan(true, locked = true)!!.kioskPackages!!,
            noCallsLock.kioskPackages!!,
        )) {
            assertTrue(dialer in kiosk)
            assertFalse(kiosk.any { it in PLAY_CORE })
        }
        // Pinning changes no call rule: its keypad still goes through our redirection/in-call
        // services (managed) or DISALLOW_OUTGOING_CALLS.
        assertTrue(noCallsLock.restrictOutgoingCalls)
        assertTrue(plan(true, calls = CallPolicyState.Unmanaged, allow = listOf("org.example.game")).restrictOutgoingCalls)
        assertEquals(plan(false).restrictOutgoingCalls, plan(true).restrictOutgoingCalls)
        // Without the block, the old rule: never pinned while calls are managed.
        assertFalse(dialer in plan(false).kioskPackages!!)
    }
}

/** Handy step 10 (design §3, QA 10 #1/#10): the lock-task setting while the PIN lock is LOCKED. */
class PinLockTaskTest {
    private val own = "me.vibb.launcher"
    private val kiosk = setOf(own, "org.fossify.calendar", "com.android.phone")
    private val serverFeatures = LOCK_TASK_FEATURE_SYSTEM_INFO or LOCK_TASK_FEATURE_NOTIFICATIONS or
        LOCK_TASK_FEATURE_HOME or LOCK_TASK_FEATURE_OVERVIEW or LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
        LOCK_TASK_FEATURE_KEYGUARD or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
    private val helpers = setOf("com.android.phone", "com.android.server.telecom", "com.android.dialer", "com.google.android.deskclock")

    @Test
    fun `featuresWhileLocked drops the shade, Home and Recents and keeps the rest`() {
        val locked = featuresWhileLocked(serverFeatures, true)
        assertEquals(0, locked and LOCK_TASK_FEATURE_NOTIFICATIONS)
        assertEquals(0, locked and LOCK_TASK_FEATURE_HOME)
        assertEquals(0, locked and LOCK_TASK_FEATURE_OVERVIEW)
        assertTrue(locked and LOCK_TASK_FEATURE_KEYGUARD != 0)
        assertTrue(locked and LOCK_TASK_FEATURE_SYSTEM_INFO != 0)
        assertTrue(locked and LOCK_TASK_FEATURE_GLOBAL_ACTIONS != 0)
        assertTrue(locked and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK != 0)
        assertEquals(serverFeatures, featuresWhileLocked(serverFeatures, false))
        assertTrue("keyguard is forced", featuresWhileLocked(0, true) and LOCK_TASK_FEATURE_KEYGUARD != 0)
    }

    @Test
    fun `kiosk on - the kiosk list is never touched, only the features`() {
        val locked = lockTaskWhileLocked(kiosk, serverFeatures, restrictCreateWindows = false, locked = true, ownPackage = own, lockHelpers = helpers)
        assertEquals(kiosk, locked.packages)
        assertEquals(featuresWhileLocked(serverFeatures, true), locked.features)
        assertTrue(locked.statusBarDisabled)
        assertTrue("no overlays over the lock", locked.createWindowsBlocked)
        val open = lockTaskWhileLocked(kiosk, serverFeatures, restrictCreateWindows = false, locked = false, ownPackage = own, lockHelpers = helpers)
        assertEquals(LockTaskSetting(kiosk, serverFeatures, statusBarDisabled = false, createWindowsBlocked = false), open)
    }

    @Test
    fun `kiosk off - ours plus the helpers only, KEYGUARD, GLOBAL_ACTIONS and SYSTEM_INFO`() {
        val locked = lockTaskWhileLocked(null, serverFeatures, restrictCreateWindows = false, locked = true, ownPackage = own,
            lockHelpers = helpers + com.kidslauncher.mdm.play.PLAY_STORE)
        assertEquals(setOf(own) + helpers, locked.packages)
        assertEquals(LOCK_TASK_FEATURE_KEYGUARD or LOCK_TASK_FEATURE_GLOBAL_ACTIONS or LOCK_TASK_FEATURE_SYSTEM_INFO, locked.features)
        assertTrue(locked.statusBarDisabled)
        assertTrue(locked.createWindowsBlocked)
        val open = lockTaskWhileLocked(null, serverFeatures, restrictCreateWindows = true, locked = false, ownPackage = own, lockHelpers = helpers)
        assertEquals(null, open.packages)
        assertFalse(open.statusBarDisabled)
        assertTrue("the budget's own overlay block stays", open.createWindowsBlocked)
    }

    @Test
    fun `the update fence disables the status bar - status bar = locked or fenced (qa-11 4)`() {
        for (kioskList in listOf(kiosk, null)) {
            for (locked in listOf(false, true)) {
                for (fenced in listOf(false, true)) {
                    val setting = lockTaskWhileLocked(kioskList, serverFeatures, restrictCreateWindows = false, locked = locked,
                        ownPackage = own, lockHelpers = helpers, fenced = fenced)
                    assertEquals("kiosk=${kioskList != null} locked=$locked fenced=$fenced", locked || fenced, setting.statusBarDisabled)
                }
            }
        }
        // Nothing else changes: the fence never touches lock-task packages or features.
        val fencedOpen = lockTaskWhileLocked(kiosk, serverFeatures, restrictCreateWindows = false, locked = false, ownPackage = own, lockHelpers = helpers, fenced = true)
        assertEquals(LockTaskSetting(kiosk, serverFeatures, statusBarDisabled = true, createWindowsBlocked = false), fencedOpen)
    }

    @Test
    fun `a release after a pre-kill failure re-enables the status bar despite the latch (qa-11 4)`() {
        val latch = StatusBarLatch()
        fun pass(locked: Boolean, fenced: Boolean): Boolean? {
            val wanted = lockTaskWhileLocked(null, serverFeatures, false, locked, own, helpers, fenced).statusBarDisabled
            return latch.toWrite(wanted)?.also { latch.written(it) }
        }
        assertEquals(false, pass(locked = false, fenced = false))
        assertEquals(null, pass(locked = false, fenced = false))
        // Fence (PIN lock off): the bar goes off ...
        latch.invalidate()
        assertEquals(true, pass(locked = false, fenced = true))
        // ... the install fails before the kill, the old process releases: the bar comes back.
        latch.invalidate()
        assertEquals(false, pass(locked = false, fenced = false))
        // Even if something else wrote the platform behind the latch's back, an invalidate re-writes.
        latch.invalidate()
        assertEquals(false, pass(locked = false, fenced = false))
        // LOCKED keeps it off after the release.
        latch.invalidate()
        assertEquals(true, pass(locked = true, fenced = false))
    }

    @Test
    fun `a VoIP call - kiosk off pins its app and the permission controller with the block bit (design 17)`() {
        val element = "io.element.android.x"
        val permissions = "com.google.android.permissioncontroller"
        val locked = lockTaskWhileLocked(null, serverFeatures, false, locked = true, ownPackage = own, lockHelpers = helpers,
            voipPackages = setOf(element, permissions, com.kidslauncher.mdm.play.PLAY_STORE))
        assertEquals(setOf(own) + helpers + element + permissions, locked.packages)
        assertEquals(PIN_LOCK_FEATURES_KIOSK_OFF or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK, locked.features)
        assertTrue(locked.statusBarDisabled)
        // Kiosk on: the list is never touched (the app is allowlisted); unlocked: as planned.
        assertEquals(kiosk, lockTaskWhileLocked(kiosk, serverFeatures, false, true, own, helpers, voipPackages = setOf(element)).packages)
        assertEquals(null, lockTaskWhileLocked(null, serverFeatures, false, false, own, helpers, voipPackages = setOf(element)).packages)
        // No call: no block bit with the kiosk off.
        assertEquals(PIN_LOCK_FEATURES_KIOSK_OFF, lockTaskWhileLocked(null, serverFeatures, false, true, own, helpers).features)
    }

    @Test
    fun `the lock's helpers are system packages only, never Settings, the camera or Play`() {
        val got = pinLockHelpers(
            emergencyDialer = ResolvedHelper("com.android.phone", system = true),
            telecom = ResolvedHelper("com.android.server.telecom", system = true),
            systemDialer = ResolvedHelper("com.android.dialer", system = true),
            alarmApp = ResolvedHelper("com.example.thirdpartyclock", system = false),
            forbidden = setOf("com.android.settings"),
        )
        assertEquals(setOf("com.android.phone", "com.android.server.telecom", "com.android.dialer"), got)
        val withRole = pinLockHelpers(
            null, null, ResolvedHelper("com.android.dialer", system = true), null, emptySet(), ourDialerHeld = true,
        )
        assertEquals("our dialer role held: the system dialer's full UI isn't pinned (qa-10-code 5)", emptySet<String>(), withRole)
        val clock = pinLockHelpers(null, null, null, ResolvedHelper("com.google.android.deskclock", system = true), emptySet())
        assertEquals(setOf("com.google.android.deskclock"), clock)
        assertEquals(emptySet<String>(), pinLockHelpers(ResolvedHelper("com.android.settings", true), null, null, null, setOf("com.android.settings")))
    }
}
