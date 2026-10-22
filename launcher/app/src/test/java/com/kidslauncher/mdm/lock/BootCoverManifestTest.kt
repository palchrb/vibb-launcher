package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.calls.DirectBootComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 16b (QA #7-#11): the boot cover's manifest entry and theme, and the rules that keep a
 * direct-boot-aware HOME of ours from becoming a boot lockout (QA #10, the BFU rule).
 */
class BootCoverManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path))

    /** Code only: comments may name what the code must never do. */
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val cover: Element by lazy {
        val nodes = parse("AndroidManifest.xml").getElementsByTagName("activity")
        (0 until nodes.length).map { nodes.item(it) as Element }.first { it.getAttributeNS(ns, "name") == ".lock.BootCoverActivity" }
    }

    @Test
    fun `direct-boot-aware, own process, disabled until armed, a HOME at priority 0`() {
        assertEquals("true", cover.getAttributeNS(ns, "directBootAware"))
        assertEquals(":bootcover", cover.getAttributeNS(ns, "process"))
        assertTrue(cover.getAttributeNS(ns, "process").endsWith(BOOT_COVER_PROCESS_SUFFIX))
        assertEquals("false", cover.getAttributeNS(ns, "enabled"))
        assertEquals("true", cover.getAttributeNS(ns, "exported"))
        assertEquals("\${applicationId}.bootcover", cover.getAttributeNS(ns, "taskAffinity"))
        assertEquals("@style/BootCoverTheme", cover.getAttributeNS(ns, "theme"))
        val filters = cover.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        val filter = filters.item(0) as Element
        assertEquals("0", filter.getAttributeNS(ns, "priority"))
        val categories = filter.getElementsByTagName("category").let { c -> (0 until c.length).map { (c.item(it) as Element).getAttributeNS(ns, "name") } }
        assertEquals(setOf("android.intent.category.HOME", "android.intent.category.DEFAULT"), categories.toSet())
        assertTrue("com.kidslauncher.mdm.lock.BootCoverActivity" in DirectBootComponents.CLASS_NAMES)
    }

    @Test
    fun `a framework NoActionBar theme on Vibb night, nothing from AppCompat (QA 11)`() {
        val styles = parse("res/values/styles.xml").getElementsByTagName("style")
        val theme = (0 until styles.length).map { styles.item(it) as Element }.first { it.getAttribute("name") == "BootCoverTheme" }
        assertEquals("@android:style/Theme.Material.NoActionBar", theme.getAttribute("parent"))
        val items = theme.getElementsByTagName("item").let { i -> (0 until i.length).associate { (i.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } } }
        assertEquals("@color/kid_ground", items["android:windowBackground"])
        assertTrue(file("res/values/colors_kid.xml").readText().contains("<color name=\"kid_ground\">#0C0C14</color>"))
        assertTrue(file("res/drawable/splash_vibb_breathe.xml").readText().contains("<animated-vector"))
    }

    @Test
    fun `the cover reads no CE storage, uses no native code, starts no lock task or service`() {
        val activity = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        assertTrue("a plain framework Activity", activity.contains("class BootCoverActivity : Activity()"))
        for (banned in listOf(
            "androidx.", "getDefaultSharedPreferences", "LauncherPreferences", "PinLockStore", "CallPolicyStore",
            "PinLockRuntime", "startLockTask", "startService", "startForegroundService", "bindService",
            "System.loadLibrary", "Tsnet", "tsembed", "AppEnforcer", "SyncRunner",
        )) {
            assertFalse(banned, activity.contains(banned))
        }
        // The AVD starts in onResume and stops before the pause drops the callbacks (qa-16b-code #4).
        assertTrue(activity.substringAfter("override fun onResume()").substringBefore("\n    }").contains("logo?.start()"))
        val pause = activity.substringAfter("override fun onPause()").substringBefore("\n    }")
        assertTrue(pause.indexOf("logo?.stop()") in 0 until pause.indexOf("removeCallbacksAndMessages"))
        // The hand-over finishes once the disable reads back (qa-16b-code #1).
        val handOver = activity.substringAfter("private fun handOverNow()").substringBefore("\n    }")
        assertTrue(handOver.indexOf("BootCoverGuard.disable") in 0 until handOver.indexOf("finish()"))
        assertTrue(handOver.contains("BootCoverGuard.isDisabled(this)"))
        // Its own state is one device-protected file, no SharedPreferences at all.
        val guard = code("java/com/kidslauncher/mdm/lock/BootCoverGuard.kt")
        assertTrue(guard.contains("createDeviceProtectedStorageContext()"))
        for (source in listOf(activity, guard)) assertFalse(source.contains("getSharedPreferences"))
    }

    /** Design 59 F3: the cover sits behind Android's keyguard, never over it - Android's lock screen (the restart
     * line, the pad) stays the face before the PIN, and nothing of ours occludes or dismisses it. */
    @Test
    fun `the cover never shows over the keyguard, and the locked boot's start reads device-protected storage only`() {
        for (attr in listOf("showWhenLocked", "turnScreenOn", "showForAllUsers")) assertEquals(attr, "", cover.getAttributeNS(ns, attr))
        val activity = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        for (banned in listOf("setShowWhenLocked", "setTurnScreenOn", "requestDismissKeyguard", "FLAG_SHOW_WHEN_LOCKED", "FLAG_DISMISS_KEYGUARD")) {
            assertFalse(banned, activity.contains(banned))
        }
        // It leaves on the take-over only when stopped or the grace is over (the rule is coverWaitStep's), and its
        // own hand-over disables itself before starting Home.
        val wait = activity.substringAfter("private fun waitStep(").substringBefore("\n    }\n")
        assertTrue(wait.contains("coverWaitStep(unlocked, disabled, visible"))
        val handOver = wait.substringAfter("CoverStep.HAND_OVER ->")
        assertTrue(handOver.indexOf("BootCoverGuard.disable") in 0 until handOver.indexOf("startHome()"))
        assertTrue(activity.substringAfter("override fun onStop()").substringBefore("\n    }").contains("waitStep(visible = false)"))
        // BootCallReceiver: the call policy first, then the cover; atLockedBoot reads no CE preferences.
        val receiver = code("java/com/kidslauncher/mdm/calls/BootCallReceiver.kt")
        val lockedBoot = receiver.substringAfter("ACTION_LOCKED_BOOT_COMPLETED) return")
        assertTrue(lockedBoot.indexOf("CallPolicyStore.ensureLoaded") in 0 until lockedBoot.indexOf("BootCover.atLockedBoot(context)"))
        val cover = code("java/com/kidslauncher/mdm/lock/BootCover.kt")
        val atBoot = cover.substringAfter("fun atLockedBoot(").substringBefore("\n    }\n") +
            cover.substringAfter("private fun lockedBootStep(").substringBefore("\n    )\n") +
            cover.substringAfter("private fun retryLater(").substringBefore("\n    }\n")
        for (banned in listOf("getSharedPreferences", "prefs(", "wanted(", "LauncherPreferences", "startLockTask")) assertFalse(banned, atBoot.contains(banned))
        val callLive = code("java/com/kidslauncher/mdm/lock/BootHome.kt").substringAfter("fun callLive(").substringBefore("\n\n")
        for (banned in listOf("getSharedPreferences", "LauncherPreferences", "currentPolicyDecision")) assertFalse(banned, callLive.contains(banned))
    }

    /** F3C-5: the glue the pure rules hang on - each of the code QA's surviving mutations (M4b, M6, M7, M9, M10) and the
     * fix round's new wiring fails here. */
    @Test
    fun `the android-mode glue is wired (F3C-5)`() {
        val enforcer = code("java/com/kidslauncher/mdm/server/AppEnforcer.kt")
        // M4b: the gate comes from AndroidLock, never a constant; the parent's choice decides whether it may change.
        val call = enforcer.substringAfter("BootCover.applyPolicy(").substringBefore("\n            )")
        assertTrue(call, call.contains("androidGate = com.kidslauncher.mdm.lock.AndroidLock.gateNow(context)"))
        assertTrue(call, call.contains("parentChoice = parentChoice"))
        val cover = code("java/com/kidslauncher/mdm/lock/BootCover.kt")
        val apply = cover.substringAfter("fun applyPolicy(").substringBefore("\n    }\n")
        assertTrue("F3C-4: a fallback changes nothing", apply.contains("if (!parentChoice) return"))
        assertTrue(apply.contains("bootCoverWanted(switchOn, managed, deviceOwner = true, androidFace = androidGate)"))
        // M6: the marker is written from the rule.
        assertTrue(apply.contains("BootCoverGuard.setAndroidMode(app, bootCoverAndroidMarker(wanted, androidGate))"))
        // M7: the cover's PPA has its own category, never replacing HomeActivity's.
        assertTrue(apply.contains("addCategory(BOOT_COVER_CATEGORY)"))
        // F3C-3: the guard's clear on an update or the switch's edge.
        assertTrue(apply.contains("coverGuardClearDue(BootCoverGuard.read(app), com.kidslauncher.mdm.BuildConfig.VERSION_CODE.toLong(), switchWas, switchOn)"))
        // M9: the locked boot's start stays inside our package (before the unlock: the cover alone).
        val atBoot = cover.substringAfter("fun atLockedBoot(").substringBefore("\n    }\n")
        val start = atBoot.substringAfter("LockedBootCover.START ->")
        assertTrue(start.contains(".setPackage(app.packageName)"))
        assertTrue(start.indexOf("setEnabled(app, true") in 0 until start.indexOf("app.startActivity("))
        assertTrue(atBoot.contains("LockedBootCover.RETRY -> retryLater(app)"))
        val step = cover.substringAfter("private fun lockedBootStep(").substringBefore("\n    }\n")
        for (needed in listOf(
            "androidMarker = BootCoverGuard.androidMode(app)", "isUserUnlocked", "coverGuardTripped(BootCoverGuard.read(app))",
            "tookOver = tookOver", "callLive = BootHome.callLive(app)",
        )) assertTrue(needed, step.contains(needed))
        // F3R-2: what HOME resolves to is logged, never decided on - the start comes on an armed boot too.
        assertTrue(step.indexOf("logHomeResolution(app)") in 0 until step.indexOf("coverAtLockedBoot("))
        val decision = step.substringAfter("coverAtLockedBoot(")
        for (banned in listOf("resolveActivity", "armed", "isEnabled(")) assertFalse(banned, decision.contains(banned))
        val resolution = cover.substringAfter("private fun logHomeResolution(").substringBefore("\n    }\n")
        assertTrue(resolution.contains("Log.i(LOG_TAG, \"Locked boot: HOME resolves to \${home?.packageName}/\${home?.name}\")"))
        assertTrue("a log, no answer", cover.contains("private fun logHomeResolution(app: Context) {"))
        // f3-run B1: no runtime receiver waits for anything before the PIN (deferred while the process is frozen), and
        // the screen isn't read for the decision (F3R-4: logged after the start only); the retry is an alarm to the
        // manifest receiver.
        for (banned in listOf("registerReceiver(", "ACTION_SCREEN_OFF")) {
            assertFalse(banned, (atBoot + step + cover.substringAfter("private fun retryLater(").substringBefore("\n    }\n")).contains(banned))
        }
        assertFalse((atBoot.substringBefore("LockedBootCover.START ->") + step).contains("isInteractive"))
        assertTrue("F3R-4", start.indexOf("isInteractive") > start.indexOf("app.startActivity("))
        assertTrue(start.contains("\"Boot cover started at the locked boot (android mode, interactive \$interactive)\""))
        val retryIntent = cover.substringAfter("private fun retryIntent(").substringBefore("\n    )\n")
        assertTrue(retryIntent.contains("Intent(app, com.kidslauncher.mdm.calls.BootCallReceiver::class.java).setAction(ACTION_COVER_RETRY)"))
        // F3R-6: the cancel creates no PendingIntent; exact while it may, no dead SecurityException fallback.
        assertTrue(retryIntent.contains("if (create) android.app.PendingIntent.FLAG_UPDATE_CURRENT else android.app.PendingIntent.FLAG_NO_CREATE"))
        val retryLater = cover.substringAfter("private fun retryLater(").substringBefore("\n    }\n")
        assertTrue(retryLater.contains("retryIntent(app, create = true)"))
        assertTrue(retryLater.contains("if (alarms.canScheduleExactAlarms()) {"))
        assertTrue(retryLater.substringAfter("if (alarms.canScheduleExactAlarms()) {").substringBefore("} else {").contains("setExactAndAllowWhileIdle("))
        assertFalse(retryLater.contains("SecurityException"))
        val cancel = cover.substringAfter("private fun cancelRetry(").substringBefore("\n    }\n")
        assertTrue(cancel.contains("val intent = retryIntent(app, create = false) ?: return"))
        val receiverCode = code("java/com/kidslauncher/mdm/calls/BootCallReceiver.kt")
        assertTrue(receiverCode.substringAfter("BootCover.ACTION_COVER_RETRY)").substringBefore("return").contains("BootCover.atLockedBoot(context)"))
        // f3-run B2: init marks the take-over and takes over at every CE start, whatever a call does.
        val init = cover.substringAfter("fun init(context: Context)").substringBefore("\n    }\n")
        assertTrue(init.indexOf("tookOver = true") in 0 until init.indexOf("takeOver(app)"))
        assertFalse(init.contains("callLive"))
        assertTrue("unconditional, at the function's top level", init.contains("\n        takeOver(app)"))
        // M10: the cover reads the marker.
        val activity = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        assertTrue(activity.substringAfter("override fun onCreate(").substringBefore("\n    }\n").contains("waitForLauncher = BootCoverGuard.androidMode(this)"))
        assertTrue(activity.substringAfter("private fun scheduleHandOver()").substringBefore("\n    }\n").contains("if (waitForLauncher)"))
        val wait = activity.substringAfter("private fun waitStep(").substringBefore("\n    }\n")
        assertTrue(wait.substringAfter("CoverStep.START_HOME ->").substringBefore("CoverStep.HAND_OVER").contains("startHome()"))
        assertTrue("F3C-7", wait.substringAfter("CoverStep.HAND_OVER ->").contains("coverHandOverStartsHome(readBack, failedDisables)"))
        // F3C-2: the boot Home's retry - the take-over and the start in one pass, from every call end and screen-on.
        val home = code("java/com/kidslauncher/mdm/lock/BootHome.kt")
        assertTrue(home.contains("waitingForCall = if (bootHomeWaitsForCall(action, firstStart, inCall)) lockActive else null"))
        val retry = home.substringAfter("fun callMayHaveEnded(").substringBefore("\n    }\n")
        assertTrue(retry.indexOf("BootCover.takeOver(app)") in 0 until retry.indexOf("startIfDue(app, lockActive"))
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        assertTrue(runtime.contains("BootCover.init(app)"))
        assertTrue(runtime.substringAfter("private val callsListener").substringBefore("\n    }\n").contains("BootHome.callMayHaveEnded(ctx)"))
        assertTrue(runtime.substringAfter("fun onVoipPhase(").substringBefore("\n    }\n").contains("BootHome.callMayHaveEnded(app)"))
        assertTrue(runtime.substringAfter("private val screenReceiver").substringBefore("\n    }\n").contains("BootHome.callMayHaveEnded(app)"))
    }

    /**
     * f3-run B2 (the system's Home chooser after two graceful boots): exactly one HOME PPA of ours may resolve once
     * unlocked - HomeActivity's. The cover is enabled only at the shutdown (its arm) and at the locked boot's START
     * (before the unlock, [coverAtLockedBoot]); every unlocked start of our process disables it, and the cover disables
     * itself on every way out once unlocked. A disabled cover's PPA is inert (ComputerEngine matches a PPA only against
     * components in the query).
     */
    @Test
    fun `exactly one HOME PPA of ours resolves once unlocked (f3-run B2)`() {
        val cover = code("java/com/kidslauncher/mdm/lock/BootCover.kt")
        // Every enable of the cover: the shutdown's arm and the locked boot's start, nowhere else.
        val enables = Regex("setEnabled\\(app, (true|armed)").findAll(cover).map { m ->
            val before = cover.substring(0, m.range.first)
            before.substringAfterLast("fun ").substringBefore("(")
        }.toList()
        assertEquals(listOf("atLockedBoot", "coverAtShutdown"), enables.sorted())
        assertEquals(LockedBootCover.NONE, coverAtLockedBoot(true, unlocked = true, guardTripped = false, tookOver = false, callLive = false))
        assertEquals(false, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted = true))
        assertEquals(null, bootCoverEnabled(CoverEvent.POLICY, wanted = true))
        // The cover's process: the setter is only the guard's disable.
        val activity = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        assertFalse(activity.contains("COMPONENT_ENABLED_STATE_ENABLED"))
        val destroy = activity.substringAfter("override fun onDestroy()").substringBefore("\n    }\n")
        assertTrue(destroy.contains("if (unlocked && !isChangingConfigurations)"))
        assertTrue(destroy.contains("BootCoverGuard.disable(this, \"destroyed, unlocked\")"))
        // F3R-1: in front at the system's finish, it starts Home in a call too (no call screen was over it).
        assertTrue(destroy.contains("coverDestroyStartsHome(waitForLauncher, unlocked, ownFinish, finishedInFront, homeStarted)) {"))
        assertFalse(destroy.contains("telecomInCall"))
        // F3R-7 (M12): the system's finish while in front is marked at the pause, before anything else of it.
        val pause = activity.substringAfter("override fun onPause()").substringBefore("\n    }\n")
        assertTrue(pause.contains("\n        if (isFinishing && !ownFinish) finishedInFront = true\n"))
        // Every own finish is marked, so a system finish is told apart (N2's log line, the Home start on the way out).
        assertEquals(Regex("\\bfinish\\(\\)").findAll(activity).count(), Regex("ownFinish = true\\s+finish\\(\\)").findAll(activity).count())
        // f3-run B5: the step reads Telecom (its hand-over waits for a call's end).
        val wait = activity.substringAfter("private fun waitStep(").substringBefore("\n    }\n")
        assertTrue(wait.contains("val callLive = unlocked && telecomInCall()"))
        assertTrue(wait.contains("coverWaitStep(unlocked, disabled, visible, unlockedFor, disabledFor, homeStarted, callLive)"))
        // F3R-7 (M11): Telecom's answer is the managed-call read, a call only when it says so.
        val telecom = activity.substringAfter("private fun telecomInCall(): Boolean = try {").substringBefore("} catch (e: SecurityException)")
        assertEquals("getSystemService(android.telecom.TelecomManager::class.java)?.isInManagedCall == true", telecom.trim())
    }

    /** f3-run B3: initRest's tail after the PIN lock's init is its own posted message (the boot pass and what the system
     * queued run before it; one long message starved KidVpnService into a service ANR). */
    @Test
    fun `initRest posts its tail after the lock's init (f3-run B3)`() {
        val app = code("java/com/kidslauncher/mdm/Application.kt")
        val initRest = app.substringAfter("private fun initRest(").substringBefore("\n    }\n")
        val posted = initRest.substringAfter("PinLockRuntime.init(this)")
        val tail = posted.substringAfter("android.os.Handler(android.os.Looper.getMainLooper()).post {")
        assertTrue(posted.contains("android.os.Handler(android.os.Looper.getMainLooper()).post {"))
        for (piece in listOf("MusicMedia.init(this)", "Torch.init(this)", "GlanceStore.loadAtStart()", "CommandListenerService.start(this)", "KidVpnService.start(this)")) {
            assertTrue(piece, tail.contains(piece))
            assertFalse(piece, initRest.substringBefore("PinLockRuntime.init(this)").contains(piece))
        }
        assertTrue(tail.indexOf("MusicMedia.init(this)") < tail.indexOf("Torch.init(this)"))
        assertTrue(tail.contains("CrashReports.record(app, e)"))
        // F3R-5: the tail's own time, next to the head's "Unlocked setup took".
        assertTrue(tail.indexOf("val tailStart = android.os.SystemClock.elapsedRealtime()") in 0 until tail.indexOf("MusicMedia.init(this)"))
        assertTrue(tail.substringAfter("CrashReports.record(app, e)").contains("\"Unlocked setup tail took \${android.os.SystemClock.elapsedRealtime() - tailStart} ms\""))
    }

    @Test
    fun `Application onCreate does nothing in the cover's process (QA 10)`() {
        val app = code("java/com/kidslauncher/mdm/Application.kt")
        val onCreate = app.substringAfter("override fun onCreate()")
        val branch = onCreate.substringAfter("isBootCoverProcess(getProcessName())) {").substringBefore("}")
        assertEquals("only the crash counter, then return (qa-16b-code 2)", listOf("com.kidslauncher.mdm.lock.BootCoverGuard.install(this)", "return"),
            branch.lines().map { it.trim() }.filter { it.isNotEmpty() })
        val guard = onCreate.indexOf("isBootCoverProcess(getProcessName()))")
        assertTrue(guard > 0)
        for (later in listOf("instance = this", "CallPolicyStore.refresh", "setDefaultUncaughtExceptionHandler", "BootClock.init")) {
            assertTrue(later, onCreate.indexOf(later) > guard)
        }
    }
}
