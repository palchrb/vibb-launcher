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
        // qa-16d-code #2: the unseen screen-off flip waits longer for a delayed update; the visible ones stay short.
        assertEquals(1_000L, HealTrigger.SCREEN_OFF.flipMs)
        for (trigger in HealTrigger.entries - HealTrigger.SCREEN_OFF) assertEquals(trigger.name, STATUS_BAR_HEAL_FLIP_MS, trigger.flipMs)
    }

    @Test
    fun `the lock's resume heals at most once a minute (qa-16d-code 2)`() {
        assertTrue(lockResumedHealDue(null, 5_000L))
        assertFalse(lockResumedHealDue(5_000L, 5_000L))
        assertFalse(lockResumedHealDue(5_000L, 64_999L))
        assertTrue(lockResumedHealDue(5_000L, 65_000L))
        // Bug 25b-C: the first resume after a screen-off heal (the lock-up after power-on) heals
        // nothing visible - the screen-off heal re-sent the flags while the screen was dark.
        assertFalse(lockResumedHealDue(null, 5_000L, healedSinceScreenOff = true))
        assertFalse(lockResumedHealDue(5_000L, 500_000L, healedSinceScreenOff = true))
        assertTrue(lockResumedHealDue(5_000L, 500_000L, healedSinceScreenOff = false))
    }

    /** Design 59d §8.9 (a): the system's Home while LOCKED is the desync's proof (the edge-swipe capture). */
    @Test
    fun `the system's Home while locked heals at once, at most every 5 s, never for our own Home or unlocked`() {
        assertTrue(systemHomeHealDue(locked = true, interactive = true, broughtByUs = false, lastHealElapsedMs = null, nowElapsedMs = 5_000L))
        assertFalse("our own HomeFront start", systemHomeHealDue(locked = true, interactive = true, broughtByUs = true, lastHealElapsedMs = null, nowElapsedMs = 5_000L))
        assertFalse("the kid's Home press, unlocked", systemHomeHealDue(locked = false, interactive = true, broughtByUs = false, lastHealElapsedMs = null, nowElapsedMs = 5_000L))
        assertFalse("dark (a lock-up)", systemHomeHealDue(locked = true, interactive = false, broughtByUs = false, lastHealElapsedMs = null, nowElapsedMs = 5_000L))
        assertFalse(systemHomeHealDue(locked = true, interactive = true, broughtByUs = false, lastHealElapsedMs = 5_000L, nowElapsedMs = 9_999L))
        assertTrue(systemHomeHealDue(locked = true, interactive = true, broughtByUs = false, lastHealElapsedMs = 5_000L, nowElapsedMs = 10_000L))
        assertTrue("a clock that went back", systemHomeHealDue(locked = true, interactive = true, broughtByUs = false, lastHealElapsedMs = 9_000L, nowElapsedMs = 5_000L))
        assertEquals("a visible blink like the lock's resume heal", STATUS_BAR_HEAL_FLIP_MS, HealTrigger.SYSTEM_HOME.flipMs)
    }

    /**
     * Design 59d §8.16 (§8.9's option (b)): where SystemUI never tells Quickstep that Home is disabled (the user's AVD: no
     * navigation bar of SystemUI's), every edge swipe gets through and no heal helps - so the lock the system's Home brings
     * back asks for the unlock at once, once per gesture.
     */
    @Test
    fun `a home gesture that got through makes the returning lock ask, once, never for a notice, a call or a screen-on`() {
        fun due(
            locked: Boolean = true, interactive: Boolean = true, ours: Boolean = false, lockWasUp: Boolean = true,
            call: Boolean = false, screenOn: Long? = 1_000L, notice: Long? = null, ask: Long? = null, now: Long = 10_000L,
        ) = gestureAskDue(locked, interactive, ours, lockWasUp, call, screenOn, notice, ask, now)
        assertTrue(due())
        assertFalse("our own HomeFront start", due(ours = true))
        // qa-edge-swipe-fix-code Q-1: the system's own Home start at a process start (a boot, an update's restart, a crash)
        // comes before any lock exists - no gesture.
        assertFalse("no lock in this process yet: a boot's or an update's Home start", due(lockWasUp = false, screenOn = null))
        assertFalse("unlocked: the kid's own Home", due(locked = false))
        assertFalse("dark: a lock-up", due(interactive = false))
        assertFalse("a call is live", due(call = true))
        assertFalse("a screen-on 999 ms ago", due(screenOn = 9_001L))
        assertTrue("a screen-on 1 s ago", due(screenOn = 9_000L))
        assertFalse("a notice 500 ms ago", due(notice = 9_500L))
        assertTrue("a notice 1 s ago", due(notice = 9_000L))
        assertFalse("an ask 1.9 s ago (a loop can't hammer the pad)", due(ask = 8_100L))
        assertTrue("an ask 2 s ago", due(ask = 8_000L))
        assertTrue("swipes 2.5 s apart all ask (the heal's 5 s gap doesn't hold them)", due(ask = 7_500L))
        assertTrue("a clock that went back", due(ask = 12_000L, screenOn = 12_000L, notice = 12_000L))
        // Taken once, within 3 s of the mark, in the same screen-on, with the screen on and no call (Q-2).
        fun taken(mark: Long? = 10_000L, markOffs: Int = 4, offs: Int = 4, interactive: Boolean = true, call: Boolean = false, now: Long = 10_000L) =
            gestureAskTaken(mark, markOffs, offs, interactive, call, now)
        assertFalse(taken(mark = null))
        assertTrue(taken())
        assertTrue(taken(now = 12_999L))
        assertFalse("the lock never came back in time", taken(now = 13_000L))
        assertFalse(taken(now = 9_000L))
        assertFalse("a screen-off since the mark", taken(offs = 5))
        assertFalse("dark at the take", taken(interactive = false))
        assertFalse("a call began: never the pad over its call screen", taken(call = true))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), listOf(GESTURE_ASK_QUIET_MS, GESTURE_ASK_MIN_GAP_MS, GESTURE_ASK_WINDOW_MS))
    }

    /** §8.16: the keyguard putting itself back under the lock cancels the gesture's first ask (jelly36: 0.25 s after it). */
    @Test
    fun `a gesture's ask cancelled while the keyguard settles asks once more, never twice, never a swipe's own`() {
        assertTrue(gestureAskRetryDue(gestureAsk = true, retried = false, sameScreenOn = true, askedAtElapsedMs = 10_000L, nowElapsedMs = 10_250L))
        assertFalse("the one re-ask went", gestureAskRetryDue(gestureAsk = true, retried = true, sameScreenOn = true, askedAtElapsedMs = 10_000L, nowElapsedMs = 10_250L))
        assertFalse("the handle's own swipe: its cancel is the kid's", gestureAskRetryDue(gestureAsk = false, retried = false, sameScreenOn = true, askedAtElapsedMs = 10_000L, nowElapsedMs = 10_250L))
        assertFalse("a screen-off since", gestureAskRetryDue(gestureAsk = true, retried = false, sameScreenOn = false, askedAtElapsedMs = 10_000L, nowElapsedMs = 10_250L))
        assertFalse("a cancel 1.5 s on is the kid's Back", gestureAskRetryDue(gestureAsk = true, retried = false, sameScreenOn = true, askedAtElapsedMs = 10_000L, nowElapsedMs = 11_500L))
        assertTrue(gestureAskRetryDue(gestureAsk = true, retried = false, sameScreenOn = true, askedAtElapsedMs = 10_000L, nowElapsedMs = 11_499L))
        assertFalse(gestureAskRetryDue(gestureAsk = true, retried = false, sameScreenOn = true, askedAtElapsedMs = null, nowElapsedMs = 10_250L))
        assertEquals(listOf(1_500L, 700L), listOf(GESTURE_ASK_SETTLE_MS, GESTURE_ASK_RETRY_DELAY_MS))
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
        val sleep = heal.indexOf("Thread.sleep(trigger.flipMs)")
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
        // Bug 25b-B: the screen-off heal runs in the deferred screen-off work, while the screen is still off.
        val screenOff = runtime.substringAfter("private val screenOffWork = Runnable {").substringBefore("\n    }\n")
        assertTrue(screenOff.contains("if (!screenOffWorkDue(interactive(app))) return@Runnable"))
        assertTrue(screenOff.contains("healStatusBar(app, HealTrigger.SCREEN_OFF)"))
        val resumed = body(runtime, "onLockResumed")
        assertTrue(resumed.contains("if (lockResumedHealDue(lockResumedHealAt, now, afterScreenOffHeal))"))
        assertTrue(resumed.contains("screenOffHealed = false"))
        assertTrue(screenOff.contains("screenOffHealed = true"))
        assertTrue(resumed.contains("healStatusBar(context, HealTrigger.LOCK_RESUMED)"))
        // Design 59d §8.9 (a): Home's every new intent is offered; the runtime decides, stamps both gaps, heals.
        val home = body(runtime, "homeIntent")
        assertTrue(home.contains("if (!systemHomeHealDue(mode == LockMode.LOCKED, interactive(context), broughtByUs, systemHomeHealAt, now)) return"))
        assertTrue(home.contains("lockResumedHealAt = now"))
        assertTrue(home.contains("healStatusBar(context, HealTrigger.SYSTEM_HOME)"))
        // Design 59d §8.16: the gesture ask is decided before the heal's gap can return, marked once, taken by the lock's resume.
        // Q-3: the whole call (every argument is its own guard), Q-9's cheap reads first, the stamps.
        val mark = home.substringAfter("if (mode == LockMode.LOCKED && !broughtByUs && gestureAskDue(").substringBefore(") {").lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "locked = mode == LockMode.LOCKED,", "interactive = interactive(context),", "broughtByUs = broughtByUs,",
                "lockWasUp = PinLockActivity.instances > 0,", "callLive = callLiveNow(context),",
                "lastScreenOnElapsedMs = lastScreenOnElapsed,", "lastNoticeElapsedMs = lastNoticeElapsed,",
                "lastAskElapsedMs = gestureAskAt,", "nowElapsedMs = now,",
            ),
            mark.dropLast(1),
        )
        assertTrue(home, home.indexOf("gestureAskDue(") in 0 until home.indexOf("if (!systemHomeHealDue("))
        val stamps = home.substringAfter("nowElapsedMs = now,").substringBefore("Log.i(")
        for (line in listOf("gestureAskAt = now", "gestureAskOffs = screenOffs", "gestureAskPending = true")) assertTrue(stamps, stamps.contains(line))
        val take = body(runtime, "takeGestureAsk")
        assertTrue(take, take.contains("gestureAskPending = false"))
        assertTrue(take, take.contains("return gestureAskTaken(gestureAskAt, gestureAskOffs, screenOffs, interactive(context), callLiveNow(context), SystemClock.elapsedRealtime())"))
        // Q-2: the mark dies at a screen-off and at any lock-state change.
        val screenOffBranch = runtime.substringAfter("screenOffs++").substringBefore("androidRefronts = 0")
        assertTrue(screenOffBranch, screenOffBranch.contains("gestureAskPending = false"))
        val modeEdge = body(runtime, "dispatch").substringAfter("if (before != result.mode) {").substringBefore("if ((before == LockMode.LOCKED)")
        assertTrue(modeEdge, modeEdge.contains("gestureAskPending = false"))
        assertTrue(body(runtime, "noticeCame").contains("lastNoticeElapsed = SystemClock.elapsedRealtime()"))
        val lockResume = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").substringAfter("override fun onResume() {").substringBefore("override fun dispatchTouchEvent(")
        assertTrue(lockResume, lockResume.contains("if (PinLockRuntime.takeGestureAsk(this) && askedAt == null && voipRing.root.visibility != View.VISIBLE) {"))
        assertTrue(lockResume, lockResume.contains("handler.post { askAfterGesture(retry = false) }"))
        // The keyguard's re-occlusion cancels the first ask: one re-ask, by the pure rule, after its delay.
        val activity = code("java/com/kidslauncher/mdm/lock/PinLockActivity.kt")
        val answered = activity.substringAfter("private fun androidAnswered(").substringBefore("private val askCheck")
        assertTrue(answered, answered.contains("gestureAskRetryDue(askFromGesture, gestureAskRetried, offs == PinLockRuntime.screenOffs, askedAt, SystemClock.elapsedRealtime())"))
        assertTrue(answered, answered.indexOf("gestureAskRetryDue(") < answered.indexOf("closeAsk(cancelled"))
        assertTrue(answered, answered.contains("handler.postDelayed({ askAfterGesture(retry = true) }, GESTURE_ASK_RETRY_DELAY_MS)"))
        val gestureAsk = activity.substringAfter("private fun askAfterGesture(retry: Boolean) {").substringBefore("private fun askAndroid()")
        assertTrue(gestureAsk, gestureAsk.contains("if (!resumed || isFinishing || finishingAfterUnlock || PinLockRuntime.mode != LockMode.LOCKED) return"))
        assertTrue(gestureAsk, gestureAsk.contains("gestureAskRetried = retry"))
        assertTrue(gestureAsk, gestureAsk.contains("if (!interactiveNow() || PinLockRuntime.callLiveNow(this)) return"))
        // Q-3: the gesture flag never outlives its ask - reset when no ask went out, and by the ask's answer.
        assertTrue(gestureAsk, gestureAsk.contains("askFromGesture = true\n        glance?.askAfterGesture()\n        if (askedAt == null) askFromGesture = false"))
        assertTrue(answered, answered.contains("if (current) askFromGesture = false"))
        assertTrue(answered, answered.indexOf("gestureAskRetryDue(") < answered.indexOf("if (current) askFromGesture = false"))
        assertTrue(lockResume, lockResume.indexOf("if (resumeAsFace()) return") < lockResume.indexOf("PinLockRuntime.takeGestureAsk(this)"))
        val ask = code("java/com/kidslauncher/mdm/lock/GlancePanel.kt").substringAfter("fun askAfterGesture() = guarded(").substringBefore("private fun step(")
        assertTrue(ask, ask.contains("if (leaving || !glancePageShown(state.stage)) return@guarded"))
        assertTrue(ask, ask.contains("step(GlanceStageEvent.Unlock)"))
        val newIntent = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt").substringAfter("override fun onNewIntent(").substringBefore("override fun onStop(")
        assertTrue(newIntent.contains("val broughtByUs = intent.getBooleanExtra(HomeFront.EXTRA_BROUGHT, false)"))
        assertTrue(newIntent.indexOf("PinLockRuntime.homeIntent(this, broughtByUs)") in 0 until newIntent.indexOf("if (!toTop) return"))
        // JL-7: a destroyed Home created by the system's start is offered too - not a recreation.
        val create = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt").substringAfter("override fun onCreate(").substringBefore("gate()")
        assertTrue(create, create.contains("if (savedInstanceState == null && intent?.hasCategory(Intent.CATEGORY_HOME) == true) {"))
        assertTrue(create, create.contains("PinLockRuntime.homeIntent(this, intent.getBooleanExtra(HomeFront.EXTRA_BROUGHT, false))"))
        // The LOCKED edge's pass stays on the main thread before the lock resumes (qa-16c-code #1):
        // the heal never replaces it.
        // (16e, qa-16e-code #3: before show while the boot's mark may hold the lock, after it otherwise.)
        val dispatch = body(runtime, "dispatch")
        assertTrue(dispatch.contains("val chromeFirst = lockedEdge && bootMark != BootMarkState.Over"))
        assertTrue(dispatch.contains("if (chromeFirst) refreshChromeNow(context)"))
        assertTrue(dispatch.contains("if (lockedEdge && !chromeFirst) refreshChromeNow(context)"))
    }
}
