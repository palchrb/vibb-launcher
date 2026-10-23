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

    /** Design 59 F3 as amended by 59d step F (§8.3, Q59d-8): the manifest never makes the cover show over the keyguard;
     * at runtime it is showWhenLocked and asks for Android's PIN pad only inside the gated functions - the android
     * marker and the user still locked, read again each time - and it never dismisses or turns the screen on itself. */
    @Test
    fun `the cover shows over the keyguard only gated at runtime, and the locked boot's start reads device-protected storage only`() {
        for (attr in listOf("showWhenLocked", "turnScreenOn", "showForAllUsers", "dismissKeyguard")) assertEquals(attr, "", cover.getAttributeNS(ns, attr))
        val activity = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        for (banned in listOf("setTurnScreenOn", "FLAG_SHOW_WHEN_LOCKED", "FLAG_DISMISS_KEYGUARD", "FLAG_TURN_SCREEN_ON", "dismissKeyguard(")) {
            assertFalse(banned, activity.contains(banned))
        }
        fun body(name: String) = activity.substringAfter("private fun $name(").substringBefore("\n    }\n")
        // The gate: the marker (create's read and a fresh one) and the user still locked; unreadable = unlocked.
        val gate = body("padGate")
        for (needed in listOf("bootPadGate(", "androidMarker = waitForLauncher && BootCoverGuard.androidMode(this)", "unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked != false", "inert = inert")) {
            assertTrue(needed, gate.contains(needed))
        }
        // setShowWhenLocked once, set to the gate's value; requestDismissKeyguard once, behind the gate read first.
        assertEquals(1, Regex("setShowWhenLocked\\(").findAll(activity).count())
        val face = body("applyLockedFace")
        assertTrue(face.indexOf("val show = padGate()") in 0 until face.indexOf("setShowWhenLocked(show)"))
        assertEquals(1, Regex("requestDismissKeyguard\\(").findAll(activity).count())
        val ask = body("askPad")
        assertTrue(ask.indexOf("if (!padGate()) return") in 0 until ask.indexOf("requestDismissKeyguard("))
        // The ask comes only from the rule's ASK; the rule gets the gate, the screen and Telecom read now.
        assertEquals(2, Regex("askPad\\(").findAll(activity).count())
        val step = body("padStep")
        assertTrue(step.contains("val gate = padGate()"))
        assertTrue(step.contains("val inCall = gate && telecomInCall()"))
        assertTrue(step.contains("val step = bootPadStep(pad, event, gate, interactive, inCall)"))
        assertTrue(step.contains("PadAction.ASK -> askPad(event)"))
        // The flag follows the gate at create, every resume, the unlock broadcast and F3's poll once unlocked.
        assertTrue(activity.substringAfter("override fun onCreate(").substringBefore("\n    }\n").contains("waitForLauncher = BootCoverGuard.androidMode(this)\n        applyLockedFace()"))
        val resume = activity.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        assertTrue(resume.contains("applyLockedFace()"))
        // qa-59d-m-code M-4: the first resume after a configuration-change relaunch is RELAUNCHED (no new counted ask).
        assertTrue(resume.contains("val event = if (relaunched) PadEvent.RELAUNCHED else PadEvent.RESUMED\n        relaunched = false\n        handler.post { if (resumed) padStep(event) }"))
        val stop = activity.substringAfter("override fun onStop()").substringBefore("\n    }\n")
        assertTrue(stop.indexOf("if (!isChangingConfigurations) {") in 0 until stop.indexOf("padStep(PadEvent.STOPPED)"))
        assertTrue(stop.indexOf("if (!isChangingConfigurations) {") < stop.indexOf("waitStep(visible = false)"))
        assertTrue(activity.substringAfter("override fun onDestroy()").substringBefore("\n    }\n").contains("if (isChangingConfigurations) carriedPad = pad"))
        val create = activity.substringAfter("override fun onCreate(").substringBefore("\n    }\n")
        assertTrue(create.contains("carriedPad?.let {\n            pad = it\n            relaunched = true\n        }\n        carriedPad = null"))
        // M-1: the tripped android-mode cover stays (inert): no disable, no finish, no mark; own mode's goes as before.
        assertTrue(create.contains("inert = coverInert(BootCoverGuard.androidMode(this), tripped)"))
        assertTrue(create.indexOf("if (tripped && !inert) {") in 0 until create.indexOf("BootCoverGuard.disable(this, \"crash guard\")"))
        assertTrue(create.contains("if (!inert) setImageResource(R.drawable.splash_vibb_breathe)"))
        val install = code("java/com/kidslauncher/mdm/lock/BootCoverGuard.kt").substringAfter("fun install(").substringBefore("\n    }\n")
        assertTrue(install.contains("bootCoverEnabled(CoverEvent.CRASH_GUARD, wanted = true, guardTripped = true, androidMode = androidMode(app))"))
        assertTrue(install.contains("if (coverGuardTripped(next) && keep == false) disable("))
        assertTrue(activity.substringAfter("override fun onReceive(").substringBefore("\n            }\n").contains("applyLockedFace()"))
        assertTrue(activity.substringAfter("private fun waitStep(").substringBefore("\n    }\n").contains("if (unlocked) applyLockedFace()"))
        assertTrue(activity.substringAfter("override fun onStop()").substringBefore("\n    }\n").contains("padStep(PadEvent.STOPPED)"))
        // An earlier ask's answer is dropped; the count is the process's.
        assertTrue(body("padAnswer").contains("if (id != askId || isDestroyed) return"))
        assertTrue(activity.contains("private var pad = BootPad(autoAsks = autoAsksThisProcess)"))
        assertTrue(step.contains("autoAsksThisProcess = pad.autoAsks"))
        // qa-59d-f-code: the line only with the gate (F59d-4, M12), a due retry posted again at the resume (M14), the
        // face views following the gate in applyLockedFace (F59d-3/6), Back held exactly while the flag is on (F59d-1).
        assertTrue(step.contains("tapLine?.visibility = if (gate && pad.line) View.VISIBLE else View.GONE"))
        assertTrue(resume.contains("if (pad.retryDue) handler.postDelayed(padRetry, BOOT_PAD_RETRY_MS)"))
        assertTrue(face.indexOf("faceViews(show)") in 0 until face.indexOf("if (show == showsWhenLocked) return"))
        assertTrue(face.indexOf("setShowWhenLocked(show)") in 0 until face.indexOf("holdBack(show)"))
        val back = body("holdBack")
        assertTrue(back.contains("if (hold) {\n                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backOnFace)"))
        assertTrue(back.contains("onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backOnFace)"))
        assertEquals(1, Regex("holdBack\\(show\\)").findAll(activity).count())
        assertFalse("no default Back override", activity.contains("onBackPressed"))
        val views = body("faceViews")
        for (needed in listOf("if (!face) tapLine?.visibility = View.GONE", "isClickable = face", "contentDescription = if (face) getString(R.string.boot_cover_tap_to_unlock) else null")) {
            assertTrue(needed, views.contains(needed))
        }
        // It leaves on the take-over only when stopped or the grace is over (the rule is coverWaitStep's), and its
        // own hand-over disables itself before starting Home.
        val wait = activity.substringAfter("private fun waitStep(").substringBefore("\n    }\n")
        assertTrue(wait.contains("coverWaitStep(unlocked, takenOver, visible"))
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
        assertTrue(cover.substringAfter("private fun coverFilter()").substringBefore("\n    }\n").contains("addCategory(BOOT_COVER_CATEGORY)"))
        // F3C-3: the guard's clear on an update or the switch's edge.
        // R2R-6: and the versionName (an RC over an RC of one version keeps the code).
        assertTrue(apply.contains("coverGuardClearDue(BootCoverGuard.read(app), com.kidslauncher.mdm.BuildConfig.VERSION_CODE.toLong(), switchWas, switchOn, com.kidslauncher.mdm.BuildConfig.VERSION_NAME)"))
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
        assertTrue(wait.substringAfter("CoverStep.START_HOME ->").substringBefore("CoverStep.HAND_OVER").contains("startOwnHome("))
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
     * Design 59d step M (six-inch F1, F6) - it replaced f3-run B2's "exactly one HOME PPA of ours once unlocked": in
     * android mode the cover stays enabled, so a boot the shutdown didn't arm still has a direct-boot-aware HOME (else,
     * on a phone whose stock launcher isn't one, the boot never completes), and our component state never changes
     * before the unlock (a `PACKAGE_CHANGED` then makes the policy engine drop every PPA of ours that isn't
     * direct-boot aware). The unlocked HOME stays HomeActivity through `HomePin`: checked at the take-over (before the
     * boot's Home start) and after every apply, the PPAs re-seated with HomeActivity's first. Own mode keeps 16b's rule:
     * enabled only from the shutdown to the unlock.
     */
    @Test
    fun `android mode keeps the cover enabled and the unlocked HOME on HomeActivity (59d step M)`() {
        val cover = code("java/com/kidslauncher/mdm/lock/BootCover.kt")
        fun body(name: String) = cover.substringAfter("fun $name(").substringBefore("\n    }\n")
        // Every enable: an apply's rule, the take-over's rule, the shutdown's arm and the locked boot's start.
        val writers = Regex("setEnabled\\(app, ").findAll(cover).map { m ->
            cover.substring(0, m.range.first).substringAfterLast("fun ").substringBefore("(")
        }.toList()
        assertEquals(listOf("afterHomeResumed", "applyPolicy", "atLockedBoot", "coverAtShutdown", "takeOver", "takeOver"), writers.sorted())
        val apply = body("applyPolicy")
        assertTrue(apply.contains("bootCoverEnabled(CoverEvent.POLICY, wanted, tripped, androidMode = bootCoverAndroidMarker(wanted, androidGate))"))
        assertTrue(apply.indexOf("setEnabled(app, bootCoverEnabled(CoverEvent.POLICY") in 0 until apply.indexOf("HomePin.check(app, \"policy\")"))
        // qa-59d-m-code M-5: enabled first, then its PPA (a purge for an earlier disable can't drop the PPA just added).
        assertTrue(apply.indexOf("setEnabled(app, ") in 0 until apply.indexOf("HomePin.locked { addCoverPin(app, dpm, admin) }"))
        assertTrue(body("coverAtShutdown").contains("androidMode = BootCoverGuard.androidMode(app)"))
        // The take-over in android mode: never a disable, the PPA added again, HomePin before the stamp, then return.
        val takeOver = body("takeOver")
        val android = takeOver.substringAfter("if (BootCoverGuard.androidMode(app)) {").substringBefore("\n                return\n")
        assertTrue(android.contains("bootCoverEnabled(CoverEvent.HANDED_OVER, wanted(app), coverGuardTripped(BootCoverGuard.read(app)), androidMode = true)"))
        assertTrue(android.indexOf("setEnabled(app, enabled") in 0 until android.indexOf("addCoverPin(app, dpm, admin)"))
        assertTrue(android.indexOf("addCoverPin(app, dpm, admin)") in 0 until android.indexOf("HomePin.check(app, \"take-over\", log = true)"))
        // M-6: Home's resume enables a cover that disabled itself, the same way, off the main thread.
        val again = body("afterHomeResumed")
        assertTrue(again.contains("if (!BootCoverGuard.androidMode(app) || isEnabled(app)) return"))
        assertTrue(again.indexOf("setEnabled(app, enabled") in 0 until again.indexOf("addCoverPin(app, dpm, admin)"))
        assertTrue(again.indexOf("addCoverPin(app, dpm, admin)") < again.indexOf("HomePin.check("))
        assertTrue(code("java/com/kidslauncher/mdm/ui/HomeActivity.kt").substringAfter("override fun onResume()").substringBefore("\n    }\n")
            .contains("CoroutineScope(Dispatchers.IO).launch { com.kidslauncher.mdm.lock.BootCover.afterHomeResumed(this@HomeActivity) }"))
        assertTrue(android.indexOf("HomePin.check(") in 0 until android.indexOf("BootCoverGuard.markTakenOver(app)"))
        assertEquals(true, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted = true, androidMode = true))
        assertEquals(true, bootCoverEnabled(CoverEvent.POLICY, wanted = true, androidMode = true))
        // Own mode unchanged: the take-over disables, an apply leaves it.
        assertTrue(takeOver.substringAfter("\n                return\n").contains("bootCoverEnabled(CoverEvent.HANDED_OVER, wanted(app))"))
        assertEquals(false, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted = true))
        assertEquals(null, bootCoverEnabled(CoverEvent.POLICY, wanted = true))
        assertEquals(LockedBootCover.NONE, coverAtLockedBoot(true, unlocked = true, guardTripped = false, tookOver = false, callLive = false))
        // The cover's process: the setter is only the guard's disable; it reads the stamp as the take-over.
        val activity = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        assertFalse(activity.contains("COMPONENT_ENABLED_STATE_ENABLED"))
        val destroy = activity.substringAfter("override fun onDestroy()").substringBefore("\n    }\n")
        assertTrue(destroy.contains("if (unlocked && !isChangingConfigurations)"))
        assertTrue(destroy.contains("if (coverDisablesOnDestroy(waitForLauncher, unlocked) && !BootCoverGuard.isDisabled(this)) {"))
        // F3R-1: in front at the system's finish, it starts Home in a call too (no call screen was over it).
        assertTrue(destroy.contains("coverDestroyStartsHome(waitForLauncher, unlocked, ownFinish, finishedInFront, homeStarted)) {"))
        assertTrue(destroy.contains("startOwnHome(\"on its way out\")"))
        assertFalse(destroy.contains("telecomInCall"))
        // Its own Home starts disable it first only when the typed HOME would be itself; the hand-over always does.
        val own = activity.substringAfter("private fun startOwnHome(").substringBefore("\n    }\n")
        assertTrue(own.indexOf("coverHomeStartDisablesFirst(typedHomeIsHomeActivity())") in 0 until own.indexOf("BootCoverGuard.disable("))
        assertTrue(own.indexOf("BootCoverGuard.disable(") < own.indexOf("startHome()"))
        val typed = activity.substringAfter("private fun typedHomeIsHomeActivity()").substringBefore("\n    }\n")
        assertTrue(typed.contains(".setPackage(packageName)"))
        assertTrue(typed.contains("?.activityInfo?.name == HOME_ACTIVITY_CLASS"))
        assertFalse("the cover's process never loads Home's class", activity.contains("HomeActivity::class"))
        assertTrue(activity.substringAfter("override fun onCreate(").substringBefore("\n    }\n").contains("BootCoverGuard.takenOverThisBoot(this)"))
        // F3R-7 (M12): the system's finish while in front is marked at the pause, before anything else of it.
        val pause = activity.substringAfter("override fun onPause()").substringBefore("\n    }\n")
        assertTrue(pause.contains("\n        if (isFinishing && !ownFinish) finishedInFront = true\n"))
        // Every own finish is marked, so a system finish is told apart (N2's log line, the Home start on the way out).
        assertEquals(Regex("\\bfinish\\(\\)").findAll(activity).count(), Regex("ownFinish = true\\s+finish\\(\\)").findAll(activity).count())
        // f3-run B5: the step reads Telecom (its hand-over waits for a call's end), and the stamp or its own disable.
        val wait = activity.substringAfter("private fun waitStep(").substringBefore("\n    }\n")
        assertTrue(wait.contains("val callLive = unlocked && telecomInCall()"))
        assertTrue(wait.contains("val takenOver = unlocked && (BootCoverGuard.takenOverThisBoot(this) || BootCoverGuard.isDisabled(this))"))
        assertTrue(wait.contains("coverWaitStep(unlocked, takenOver, visible, unlockedFor, takenOverFor, homeStarted, callLive, lateStart)"))
        // F3R-7 (M11): Telecom's answer is the managed-call read, a call only when it says so.
        val telecom = activity.substringAfter("private fun telecomInCall(): Boolean = try {").substringBefore("} catch (e: SecurityException)")
        assertEquals("getSystemService(android.telecom.TelecomManager::class.java)?.isInManagedCall == true", telecom.trim())
        // HomePin: unlocked only, the system's resolution, the re-seat under its lock with the cover's PPA last.
        val pin = code("java/com/kidslauncher/mdm/lock/HomePin.kt")
        val check = pin.substringAfter("fun check(").substringBefore("\n    }\n")
        assertTrue(check.indexOf("if (!unlocked) return") in 0 until check.indexOf("resolvedHome(app)"))
        assertTrue(check.contains("if (!homePinReseatDue(unlocked, isHome, reseats)) return"))
        assertTrue(pin.substringAfter("fun resolvedHome(").substringBefore("\n    }\n").contains("Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)"))
        assertFalse("the system's view, not our package's", pin.substringAfter("fun resolvedHome(").substringBefore("\n    }\n").contains("setPackage"))
        // The stamp: device-protected, the boot count.
        val guard = code("java/com/kidslauncher/mdm/lock/BootCoverGuard.kt")
        assertTrue(guard.substringAfter("private fun takenStamp(").substringBefore("\n").contains("createDeviceProtectedStorageContext()"))
        assertTrue(guard.substringAfter("fun takenOverThisBoot(").substringBefore("\n").contains("coverTakenOver(readTakenStamp(context), bootCount(context))"))
        // The emulator run: AtomicFile's read deletes the other process's in-flight `.new` - never read through it.
        assertFalse(guard.contains("readFully("))
        assertFalse("M-2: openRead deletes the writer's .new too", guard.contains("openRead("))
        assertFalse(guard.substringAfter("fun markTakenOver(").substringBefore("\n    }\n").contains("AtomicFile("))
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
