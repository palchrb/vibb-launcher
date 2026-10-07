package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_HOME
import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_KEYGUARD
import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_NOTIFICATIONS
import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_OVERVIEW
import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_SYSTEM_INFO
import com.kidslauncher.mdm.server.LockTaskSetting
import com.kidslauncher.mdm.server.featuresWhileLocked
import com.kidslauncher.mdm.server.lockTaskWhileLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 16d decision 1: the status-bar heal after a boot (experiment 2 §2). */
class StatusBarHealTest {

    // ---- AOSP's LockTaskController.getStatusBarDisableFlags(LOCK_TASK_MODE_LOCKED), mirrored ----

    private val disableExpand = 0x00010000
    private val disableNotificationIcons = 0x00020000
    private val disableNotificationAlerts = 0x00040000
    private val disableNotificationTicker = 0x00080000
    private val disableSystemInfo = 0x00100000
    private val disableHome = 0x00200000
    private val disableBack = 0x00400000
    private val disableClock = 0x00800000
    private val disableRecent = 0x01000000
    private val disableSearch = 0x02000000
    private val disableMask = 0x07FF0000
    private val disable2QuickSettings = 1
    private val disable2SystemIcons = 2
    private val disable2NotificationShade = 4
    private val disable2GlobalActions = 8
    private val disable2Mask = 0x1F

    /** (disable1, disable2) lock task asks of StatusBarManagerService for [features]. */
    private fun lockedDisableFlags(features: Int): Pair<Int, Int> {
        val map = mapOf(
            LOCK_TASK_FEATURE_SYSTEM_INFO to (disableClock to disable2SystemIcons),
            LOCK_TASK_FEATURE_NOTIFICATIONS to ((disableNotificationIcons or disableNotificationAlerts) to disable2NotificationShade),
            LOCK_TASK_FEATURE_HOME to (disableHome to 0),
            LOCK_TASK_FEATURE_OVERVIEW to (disableRecent to 0),
            LOCK_TASK_FEATURE_GLOBAL_ACTIONS to (0 to disable2GlobalActions),
        )
        var flags1 = disableMask
        var flags2 = disable2Mask
        for ((feature, flags) in map) {
            if (features and feature != 0) {
                flags1 = flags1 and flags.first.inv()
                flags2 = flags2 and flags.second.inv()
            }
        }
        val maskLocked = disableMask and (disableExpand or disableNotificationTicker or disableSystemInfo or disableBack).inv()
        return (flags1 and maskLocked) to flags2
    }

    /** Every feature set setLockTaskFeatures accepts (OVERVIEW and NOTIFICATIONS need HOME), always with KEYGUARD. */
    private val validFeatures = (0 until 128).map { it or LOCK_TASK_FEATURE_KEYGUARD }.distinct().filter {
        it and LOCK_TASK_FEATURE_HOME != 0 || it and (LOCK_TASK_FEATURE_OVERVIEW or LOCK_TASK_FEATURE_NOTIFICATIONS) == 0
    }

    private fun pinned(features: Int) = LockTaskSetting(setOf("me.vibb.launcher"), features, statusBarDisabled = true, createWindowsBlocked = true)

    @Test
    fun `the mirror gives experiment 2's dump for the LOCKED kiosk (113)`() {
        assertEquals(0x7260000 to 0x15, lockedDisableFlags(113))
    }

    @Test
    fun `a heal always changes the flags SystemUI is sent - and never what the shade, Home, Recents or the power menu get`() {
        val guarded1 = disableExpand or disableNotificationIcons or disableNotificationAlerts or disableHome or disableRecent or disableSearch
        val guarded2 = disable2QuickSettings or disable2NotificationShade or disable2GlobalActions
        assertTrue(validFeatures.size > 20)
        for (features in validFeatures) {
            val flipped = statusBarHealFeatures(pinned(features))!!
            val before = lockedDisableFlags(features)
            val during = lockedDisableFlags(flipped)
            assertNotEquals("$features: the net flags must change, or SystemUI is sent nothing", before, during)
            assertEquals("$features", before.first and guarded1, during.first and guarded1)
            assertEquals("$features", before.second and guarded2, during.second and guarded2)
            // Only SYSTEM_INFO moves: KEYGUARD (the emergency exemption) and the app block stay.
            assertEquals(LOCK_TASK_FEATURE_SYSTEM_INFO, features xor flipped)
            assertTrue(flipped and LOCK_TASK_FEATURE_KEYGUARD != 0)
            assertEquals(features and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK, flipped and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK)
            // Still valid for setLockTaskFeatures.
            assertTrue(flipped in validFeatures)
        }
    }

    @Test
    fun `the LOCKED kiosk and the kiosk-off lock are healed, the shade blocked throughout`() {
        val kiosk = setOf("me.vibb.launcher", "org.example.game")
        val server = 127
        for (setting in listOf(
            lockTaskWhileLocked(kiosk, server, false, locked = true, ownPackage = "me.vibb.launcher", lockHelpers = emptySet()),
            lockTaskWhileLocked(null, server, false, locked = true, ownPackage = "me.vibb.launcher", lockHelpers = setOf("com.android.phone")),
        )) {
            val flipped = statusBarHealFeatures(setting)!!
            for (features in listOf(setting.features, flipped)) {
                val (flags1, flags2) = lockedDisableFlags(features)
                assertTrue(flags2 and disable2NotificationShade != 0)
                assertTrue(flags2 and disable2QuickSettings != 0)
                assertTrue(flags1 and disableHome != 0)
                assertTrue(flags1 and disableRecent != 0)
            }
        }
        assertEquals(featuresWhileLocked(server, true), lockTaskWhileLocked(kiosk, server, false, true, "me.vibb.launcher", emptySet()).features)
    }

    @Test
    fun `nothing pinned - no lock task, nothing to heal`() {
        assertNull(statusBarHealFeatures(lockTaskWhileLocked(null, 127, false, locked = false, ownPackage = "me.vibb.launcher", lockHelpers = emptySet())))
        assertNull(statusBarHealFeatures(LockTaskSetting(null, 127, statusBarDisabled = true, createWindowsBlocked = false)))
    }

    @Test
    fun `a pass without a plan reads a heal's flip as its original - the flip is never kept`() {
        val flip = HealFlip(original = 113, flipped = 112)
        assertEquals(113, healBase(112, flip))
        // Any other value was written since: it is the base.
        assertEquals(119, healBase(119, flip))
        assertEquals(112, healBase(112, null))
    }

    @Test
    fun `heals run after the start past quickstep's start-up, briefly, and at the listed triggers`() {
        assertEquals(STATUS_BAR_HEALS_AFTER_START_MS.sorted(), STATUS_BAR_HEALS_AFTER_START_MS)
        assertTrue(STATUS_BAR_HEALS_AFTER_START_MS.first() <= 1_000L)
        // Experiment 2: TIS init 2.4-5.2 s after the unlock - heals before, during and well after.
        assertTrue(STATUS_BAR_HEALS_AFTER_START_MS.any { it in 2_000L..3_000L })
        assertTrue(STATUS_BAR_HEALS_AFTER_START_MS.any { it in 5_500L..8_000L })
        assertTrue(STATUS_BAR_HEALS_AFTER_START_MS.last() >= 30_000L)
        assertTrue(STATUS_BAR_HEAL_FLIP_MS in 100L..500L)
    }

    // ---- the glue --------------------------------------------------------------------------------

    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun code(path: String) = strip(file(path).readText())
    private fun strip(text: String) = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val rest = text.substring(start)
        val end = Regex("\\n    (override |private |internal |fun |val |var |companion)").find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `LockTaskChrome stays the only writer of lock-task packages, features and the status bar`() {
        val roots = listOf("src", "app/src").map(::File).first { File(it, "main").exists() }
        val writers = listOf("main", "debug", "release").map { File(roots, it) }.filter { it.exists() }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f -> Regex("\\.(setLockTaskFeatures|setLockTaskPackages|setStatusBarDisabled)\\(").containsMatchIn(strip(f.readText())) }
            .map { it.name }
        assertEquals(listOf("LockTaskChrome.kt"), writers)
    }

    @Test
    fun `the flip writes only the features, through the same pass, and the next pass ends it`() {
        val chrome = code("java/com/kidslauncher/mdm/lock/LockTaskChrome.kt")
        val flip = body(chrome, "writeHealFlip")
        assertTrue(flip.contains("statusBarHealFeatures(setting) ?: return false"))
        assertTrue(flip.contains("dpm.setLockTaskFeatures(admin, flipped)"))
        for (other in listOf("setLockTaskPackages", "setStatusBarDisabled", "UserRestriction")) assertFalse(other, flip.contains(other))
        val apply = body(chrome, "apply")
        assertTrue(apply.indexOf("LockTaskDebug.adjust(context, computed)") in 0 until apply.indexOf("if (heal != null) return writeHealFlip("))
        assertTrue(apply.contains("if (writeLockTask(dpm, admin, setting, kioskOn)) healFlip = null"))
        assertTrue(body(chrome, "applyFallback").contains("healBase(dpm.getLockTaskFeatures(admin), healFlip)"))
        assertTrue(body(chrome, "healStatusBar").contains("synchronized(this)"))
        assertTrue(body(chrome, "endStatusBarHeal").contains("if (healFlip != null) refresh(context)"))
    }

    @Test
    fun `the runtime heals after the start, at boot completed, the first screen-on and every screen-off - waiting outside the monitor`() {
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val heal = body(runtime, "healStatusBar")
        val flip = heal.indexOf("LockTaskChrome.healStatusBar(app, trigger)")
        val sleep = heal.indexOf("Thread.sleep(STATUS_BAR_HEAL_FLIP_MS)")
        val end = heal.indexOf("LockTaskChrome.endStatusBarHeal(app)")
        assertTrue(heal.contains("chromeExecutor.execute"))
        assertTrue(flip in 0 until sleep)
        assertTrue(sleep < end)
        assertTrue(body(runtime, "init").contains("for (delay in STATUS_BAR_HEALS_AFTER_START_MS)"))
        assertTrue(body(runtime, "init").contains("IntentFilter(Intent.ACTION_BOOT_COMPLETED)"))
        for (trigger in HealTrigger.entries) {
            assertTrue(trigger.name, runtime.contains("healStatusBar(app, HealTrigger.${trigger.name})") ||
                runtime.contains("healStatusBar(context, HealTrigger.${trigger.name})"))
        }
        val screenOff = runtime.substringAfter("if (intent.action == Intent.ACTION_SCREEN_OFF) {").substringBefore("} else {")
        assertTrue(screenOff.contains("healStatusBar(app, HealTrigger.SCREEN_OFF)"))
        // The LOCKED edge's pass stays on the main thread before the lock resumes (qa-16c-code #1):
        // the heal never replaces it.
        assertTrue(body(runtime, "dispatch").contains("if (lockedEdge) refreshChromeNow(context)"))
    }
}
