package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import com.kidslauncher.mdm.server.LastEnforcedPlan
import com.kidslauncher.mdm.server.CredentialAction
import com.kidslauncher.mdm.server.PolicyToApply
import com.kidslauncher.mdm.server.credentialAction
import com.kidslauncher.mdm.server.dto.KidLock
import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 59 step 3 (docs/design/59-android-keyguard-design.md §3, §7): Android's lock as the gate, our glance its
 * face - the switch's derivation, the face's one rule, the process start, the rollback and its carrier, the token,
 * the keyguard write, and the pins that keep the way out Android's verdict.
 */
class AndroidLockPlanTest {
    private fun file(path: String) = listOf("src/$path", "app/src/$path").map(::File).first { it.exists() }
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
    private fun body(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name exists", start >= 0)
        val rest = code.substring(start)
        return rest.substring(0, Regex("""\n    }\n""").find(rest)?.range?.first ?: rest.length)
    }
    private val lock = "main/java/com/kidslauncher/mdm/lock"
    private val server = "main/java/com/kidslauncher/mdm/server"

    @Test
    fun `the features - FACE and IRIS off, fingerprint allowed, B2's camera bit checked`() {
        assertEquals(
            DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS or
                DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS or DevicePolicyManager.KEYGUARD_DISABLE_FACE or
                DevicePolicyManager.KEYGUARD_DISABLE_IRIS or DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL,
            ANDROID_KEYGUARD_FEATURES,
        )
        assertEquals(0, ANDROID_KEYGUARD_FEATURES and DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT)
        assertEquals(DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA, KEYGUARD_SECURE_CAMERA)
    }

    @Test
    fun `what the switch returns - only android is Android's lock (P59-5)`() {
        assertTrue(androidRequested(KidLock(keyguard = "android")))
        for (other in listOf("own", null, "", "Android", "android ", "hybrid")) assertFalse("$other", androidRequested(KidLock(keyguard = other)))
        assertFalse("no kid_lock", androidRequested(null))
        // The face and the migration from it.
        assertTrue(androidFace(requested = true, managed = true, hasKidPin = true, deviceSecure = true))
        for (i in 0 until 15) {
            val b = (0..3).map { ((i shr it) and 1) == 1 }
            assertFalse(b.toString(), androidFace(b[0], b[1], b[2], b[3]))
        }
        assertTrue(migrationDue(requested = true, managed = true, deviceSecure = false))
        assertFalse(migrationDue(true, true, deviceSecure = true))
        assertFalse(migrationDue(false, true, false))
        assertFalse(migrationDue(true, false, false))
        // configure derives both from the policy's kid_lock, and the keyguard and the lock's activation from them.
        val configure = body(code("$lock/PinLockRuntime.kt"), "configure")
        assertTrue(configure.contains("val requested = androidRequested(kidLock)"))
        assertTrue(configure.contains("val androidGate = AndroidLock.face(app, dpm, admin, requested, managed, newConfig != null, deviceSecure)"))
        assertTrue(configure.contains("androidGate = androidGate,"))
        assertTrue(body(code("$lock/AndroidLock.kt"), "face").contains("val face = androidFace(requested, managed, hasKidPin, deviceSecure)"))
        assertTrue(configure.indexOf("AndroidLock.configure(app, requested, parentChoice)") in 0 until configure.indexOf("val deviceSecure ="))
    }

    @Test
    fun `the face's rule - the glance leaves only on the pad's success or USER_PRESENT since the edge`() {
        for (trigger in FaceTrigger.entries) for (locked in listOf(true, false)) for (present in listOf(true, false)) {
            val step = faceStep(trigger, deviceSecure = true, androidLocked = locked, presentSinceLocked = present)
            if (step == FaceStep.LEAVE) assertTrue("$trigger $locked $present", trigger == FaceTrigger.PAD_SUCCEEDED || (present && !locked))
            assertEquals("no credential: our keypad", FaceStep.OWN_KEYPAD, faceStep(trigger, false, locked, present))
        }
        assertEquals(FaceStep.STAY, faceStep(FaceTrigger.SLEEP_CHECK, true, androidLocked = true, presentSinceLocked = false))
        assertEquals(FaceStep.RELOCK, faceStep(FaceTrigger.SLEEP_CHECK, true, false, false))
        assertEquals("dark: never a leave", FaceStep.RELOCK, faceStep(FaceTrigger.SLEEP_CHECK, true, false, true))
        assertEquals(FaceStep.STAY, faceStep(FaceTrigger.RESUME, true, true, false))
        assertEquals(FaceStep.LEAVE, faceStep(FaceTrigger.RESUME, true, false, true))
        assertEquals("a read alone waits (P59-6)", FaceStep.WAIT, faceStep(FaceTrigger.RESUME, true, false, false))
        assertEquals(FaceStep.ASK_ANDROID, faceStep(FaceTrigger.UNLOCK_TAP, true, true, false))
        assertEquals(FaceStep.LEAVE, faceStep(FaceTrigger.UNLOCK_TAP, true, false, true))
        assertEquals(FaceStep.WAIT, faceStep(FaceTrigger.UNLOCK_TAP, true, false, false))
        assertEquals("keeps the mark", FaceStep.STAY, faceStep(FaceTrigger.USER_PRESENT, true, true, true))
        assertEquals(FaceStep.LEAVE, faceStep(FaceTrigger.USER_PRESENT, true, false, true))
        assertEquals("then it locks", FaceStep.RELOCK, faceStep(FaceTrigger.AFTER_WAIT, true, false, false))
        assertEquals(FaceStep.STAY, faceStep(FaceTrigger.AFTER_WAIT, true, true, false))
        assertEquals(FaceStep.LEAVE, faceStep(FaceTrigger.PAD_SUCCEEDED, true, true, false))
    }

    @Test
    fun `an ask never leaves dead taps - a retry after a second, a recovery once nothing covers us (the run's bug 2)`() {
        assertTrue(askAgain(null))
        assertFalse("a double tap", askAgain(ANDROID_ASK_RETRY_MS - 1))
        assertTrue(askAgain(ANDROID_ASK_RETRY_MS))
        assertTrue(ANDROID_ASK_TIMEOUT_MS <= 2_000L)
        assertFalse(askRecoveryDue(null, focused = true))
        assertFalse("the pad may still be coming", askRecoveryDue(ANDROID_ASK_TIMEOUT_MS - 1, focused = true))
        assertFalse("the pad or a shade over us", askRecoveryDue(ANDROID_ASK_TIMEOUT_MS, focused = false))
        assertTrue(askRecoveryDue(ANDROID_ASK_TIMEOUT_MS, focused = true))
        val activity = code("$lock/PinLockActivity.kt")
        assertTrue(body(activity, "tapAsFace").contains("if (askAgain(askAge())) askAndroid()"))
        assertTrue(activity.contains("if (askRecoveryDue(askAge(), hasWindowFocus())) {"))
        assertTrue(body(activity, "onWindowFocusChanged").contains("handler.postDelayed(askCheck"))
    }

    @Test
    fun `the process start - the boot's first CE start UNLOCKED, every later one LOCKED`() {
        assertTrue(processStartUnlocked(face = true, bootCount = 7, storedBootCount = 6))
        assertTrue("never stored", processStartUnlocked(true, 7, null))
        assertFalse("a crash or an update later in the boot", processStartUnlocked(true, 7, 7))
        assertFalse("unknown boot count", processStartUnlocked(true, -1, 6))
        assertFalse("own mode", processStartUnlocked(false, 7, 6))
        assertTrue(firstCeStart(7, 6))
        assertFalse(firstCeStart(7, 7))
        assertEquals(LockMode.UNLOCKED, step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, androidBoot = true)).mode)
        assertFalse(step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, androidBoot = true)).showLock)
        assertEquals(LockMode.LOCKED, step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true)).mode)
        // L-4: the boot count is written at every CE start, before the face is read; the event carries the verdict.
        val decide = body(code("$lock/PinLockRuntime.kt"), "decide")
        assertTrue(decide.indexOf("PinLockStore.swapAndroidBoot(app, boot)") in 0 until decide.indexOf("processStartUnlocked(face, boot, stored)"))
        assertTrue(decide.contains("val face = startActive && requested && AndroidLock.deviceSecure(app)"))
        assertTrue(decide.contains("LockEvent.ProcessStart(startActive, interactive = false, androidBoot = startAndroidBoot)"))
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "init").contains("androidBoot = startAndroidBoot"))
        // The crash guard locks Android rather than leaving the phone open.
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "guardTripped").contains("AndroidLock.crashGuardTripped(app)"))
        val tripped = body(code("$lock/AndroidLock.kt"), "crashGuardTripped")
        assertTrue(tripped.contains("if (!faceOn) return") && tripped.contains("lockNow(context)"))
    }

    @Test
    fun `the rollback - only off a policy with the parent's choice, never a Fallback (L-1, M-1)`() {
        assertTrue(rollbackDue(owned = true, requested = false, parentChoice = true))
        assertFalse("a Fallback or Apply(null)", rollbackDue(true, false, parentChoice = false))
        assertFalse("still requested", rollbackDue(true, true, true))
        assertFalse("not ours (own mode's credential)", rollbackDue(false, false, true))
        assertEquals(AndroidRollback.CLEAR, androidRollback(deviceSecure = true, tokenActive = true))
        assertEquals(AndroidRollback.NO_TOKEN, androidRollback(true, false))
        assertEquals(AndroidRollback.NOTHING, androidRollback(false, true))
        // The carrier: only a real policy, never the last-enforced plan nor no policy.
        assertTrue(PolicyToApply.Apply(PolicyResponse()).carriesParentChoice)
        assertFalse(PolicyToApply.Apply(null).carriesParentChoice)
        assertFalse(PolicyToApply.Fallback(LastEnforcedPlan().toPolicy()).carriesParentChoice)
        // ... carried to configure, and every apply off the current decision passes the decision, not its policy.
        val enforcer = code("$server/AppEnforcer.kt")
        assertTrue(enforcer.contains("apply(context, decision.policy, fromSync, parentChoice = decision.carriesParentChoice)"))
        assertTrue(enforcer.contains("PinLockRuntime.configure(context, dpm, admin, policy, managed, parentChoice)"))
        val all = file("main/java").walk().filter { it.isFile && it.extension == "kt" }.map { code(it.path.substringAfter("src/")) }.joinToString("\n")
        assertFalse(Regex("""AppEnforcer\.apply\([^)]*currentPolicyDecision\(\)\.policy""").containsMatchIn(all))
        assertTrue(code("$server/MdmSyncWorker.kt").contains("AppEnforcer.apply(context, decision, fromSync = true)"))
        // M-1: the owned flag is set before the reset and cleared only once no credential is left.
        val android = code("$lock/AndroidLock.kt")
        val migrate = body(android, "migrateIfDue")
        assertTrue(migrate.indexOf("PinLockStore.saveAndroid(app, owned = true, rev = null)") in 0 until migrate.indexOf("reset(app, pin, settingRev = pinRev)"))
        assertTrue(migrate.indexOf("reset(app, pin, settingRev = pinRev)") < migrate.indexOf("if (set) commitRev(app, pinRev)"))
        val configure = body(android, "configure")
        assertTrue(configure.contains("if (rollbackDue(owned, requested, parentChoice)) {"))
        assertTrue(configure.contains("if (owned && !secure) {"))
        assertEquals(1, Regex("owned = false").findAll(android).count())
        // ... and step 4's command, its reset failed, gives back exactly what it wrote ahead (a credential not ours stays not ours).
        assertTrue(body(android, "setFromCommand").contains("PinLockStore.saveAndroid(app, owned = owned, rev = before)"))
        // The policy's request is committed with the hash (saveConfig), the owned flag apart (saveAndroid).
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "configure").contains("PinLockStore.saveConfig(app, newConfig, active, why, storedRequest)"))
    }

    @Test
    fun `a fallback apply keeps Android's keyguard and the stored request while we own the credential (S3-1)`() {
        assertTrue(androidKeyguardHeld(owned = true, deviceSecure = true, parentChoice = false))
        assertFalse("the parent's choice decides", androidKeyguardHeld(true, true, parentChoice = true))
        assertFalse("not ours", androidKeyguardHeld(false, true, false))
        assertFalse("no credential left", androidKeyguardHeld(true, false, false))
        // The last-enforced plan has no kid_lock: no PIN, not requested - still ANDROID while held, UNTOUCHED without.
        assertEquals(KeyguardMode.ANDROID, keyguardMode(true, hasKidPin = false, deviceSecure = true, coverWanted = false, voip = VoipPhase.NONE, guardTripped = false, androidRequested = false, androidHeld = true))
        assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(true, hasKidPin = false, deviceSecure = true, coverWanted = false, voip = VoipPhase.NONE, guardTripped = false, androidRequested = false, androidHeld = false))
        // The request is stored only off the parent's choice.
        assertNull(requestToStore(false, parentChoice = false))
        assertNull(requestToStore(true, parentChoice = false))
        assertEquals(false, requestToStore(false, parentChoice = true))
        assertEquals(true, requestToStore(true, parentChoice = true))
        val configure = body(code("$lock/PinLockRuntime.kt"), "configure")
        assertTrue(configure.contains("val owned = AndroidLock.configure(app, requested, parentChoice)"))
        assertTrue(configure.contains("val androidHeld = androidKeyguardHeld(owned, deviceSecure, parentChoice)"))
        assertTrue(configure.contains("guard.trippedAtMs != null, requested, androidHeld)"))
        assertTrue(configure.contains("val storedRequest = requestToStore(requested, parentChoice)"))
        assertTrue(configure.contains("PinLockStore.saveConfig(app, newConfig, active, why, storedRequest)"))
        assertTrue(body(code("$lock/AndroidLock.kt"), "configure").contains("if (parentChoice) this.requested = requested"))
        assertTrue(code("$lock/KeyguardCover.kt").contains("androidHeld = i.androidHeld,"))
        // 24b's step 1 is skipped while asked for or ours.
        assertTrue(code("$server/WindowRelock.kt").contains("PinLockStore.keyguardRequested(app) || com.kidslauncher.mdm.lock.PinLockStore.keyguardAndroid(app)"))
    }

    @Test
    fun `the glue of the leave rule - the mark, the waits, the resume, the boot count (S3-2, S3-3, S3-4)`() {
        val runtime = code("$lock/PinLockRuntime.kt")
        // M3: the mark goes at every LOCKED edge, screen-off and remote lock.
        assertTrue(body(runtime, "dispatch").contains(
            "if (result.mode == LockMode.LOCKED && (lockedEdge || event is LockEvent.ScreenOff || event is LockEvent.RemoteLock)) {\n            AndroidLock.lockedAgain()"))
        // S3-2: never marked while dark.
        val receiver = runtime.substringAfter("private val userPresentReceiver").substringBefore("\n    }\n")
        assertTrue(receiver.indexOf("if (!interactive(app)) return") in 0 until receiver.indexOf("AndroidLock.userPresent()"))
        // S3-3: the longer wait only after Android read locked since this screen-off; still no leave without the broadcast.
        assertEquals(ANDROID_ANSWER_WAIT_MS, faceWaitMs(false))
        assertEquals(ANDROID_UNLOCK_WAIT_MS, faceWaitMs(true))
        assertTrue(ANDROID_UNLOCK_WAIT_MS > ANDROID_ANSWER_WAIT_MS)
        assertTrue(body(runtime, "lockUpAtScreenOff").contains("if (kg && AndroidLock.faceOn) AndroidLock.sawLocked(screenOffs)"))
        assertTrue(runtime.substringAfter("private val androidSleepCheck").substringBefore("\n    }\n").contains("FaceStep.STAY -> AndroidLock.sawLocked(screenOffs)"))
        val android = code("$lock/AndroidLock.kt")
        assertTrue(android.contains("fun waitMs(offs: Int): Long = faceWaitMs(lockedSeenAtOffs == offs)"))
        val activity = code("$lock/PinLockActivity.kt")
        assertTrue(body(activity, "actAsFace").contains("handler.postDelayed(faceWait, AndroidLock.waitMs(PinLockRuntime.screenOffs))"))
        // M4: USER_PRESENT leaves only on faceStep's LEAVE.
        val present = activity.substringAfter("private val presentLeave: Runnable = Runnable {").substringBefore("\n    }\n")
        assertTrue(present.contains("val step = AndroidLock.step(this, FaceTrigger.USER_PRESENT)"))
        assertTrue(present.contains("if (step == FaceStep.LEAVE) {\n                actAsFace(step, \"USER_PRESENT\")"))
        // M5: the wait's end reads AFTER_WAIT (it relocks), never RESUME (which waits again).
        assertTrue(activity.substringAfter("private val faceWait = Runnable {").substringBefore("\n    }\n")
            .contains("actAsFace(AndroidLock.step(this, FaceTrigger.AFTER_WAIT), \"the glance's wait\")"))
        // M14: the resume acts only with the screen on and no ask open.
        assertTrue(body(activity, "resumeAsFace").contains("if (askedAt != null || !interactiveNow()) return false"))
        // M10: the boot count is committed at every CE start when new.
        assertEquals(7, bootSwap(stored = 6, boot = 7))
        assertEquals(7, bootSwap(stored = null, boot = 7))
        assertNull(bootSwap(stored = 7, boot = 7))
        assertNull("unknown", bootSwap(stored = 6, boot = -1))
        assertTrue(body(code("$lock/PinLockStore.kt"), "swapAndroidBoot").contains("bootSwap(stored, boot)?.let { p.edit().putInt(ANDROID_BOOT, it).commit() }"))
    }

    @Test
    fun `the keypad migration commits the rev of the hash it checked (Q59-5)`() {
        val runtime = code("$lock/PinLockRuntime.kt")
        assertTrue(body(runtime, "checkPin").contains("PinResult.Ok(cfg.pinRev)"))
        assertTrue(body(runtime, "configure").contains("KidLockConfig(it.pinHash, it.pinSalt, kidPinLength(it.pinLength), it.pinRev)"))
        val activity = code("$lock/PinLockActivity.kt")
        assertEquals(1, Regex("AndroidLock\\.migrateIfDue\\(").findAll(activity).count())
        assertTrue(activity.contains("if (it is PinLockRuntime.PinResult.Ok) AndroidLock.migrateIfDue(this@PinLockActivity, pin, it.pinRev)"))
        assertTrue(body(code("$lock/AndroidLock.kt"), "migrateIfDue").contains("if (!requested) return"))
    }

    @Test
    fun `the token rule in android mode, and the 24b relock's step 1 skipped while it is asked for`() {
        assertEquals(TokenStep.SET, androidTokenStep(stored = false, active = false, deviceSecure = false))
        assertEquals("none (or unreadable): escrow", TokenStep.SET, androidTokenStep(stored = false, active = false, deviceSecure = true))
        assertEquals(TokenStep.SET, androidTokenStep(stored = true, active = false, deviceSecure = false))
        assertEquals(TokenStep.WAIT, androidTokenStep(stored = true, active = false, deviceSecure = true))
        assertEquals(TokenStep.NOTHING, androidTokenStep(stored = true, active = true, deviceSecure = true))
        assertEquals(CredentialAction.REMOVE, credentialAction(false, secureNow = true, tokenUsable = true))
        assertEquals(CredentialAction.NONE, credentialAction(false, secureNow = true, tokenUsable = true, androidRequested = true))
        assertTrue(code("$server/WindowRelock.kt").contains("com.kidslauncher.mdm.lock.PinLockStore.keyguardRequested(app)"))
        assertTrue(body(code("$lock/AndroidLock.kt"), "configure").contains("if (requested || owned) WindowRelock.ensureAndroidResetToken(app)"))
    }

    @Test
    fun `onPasswordChanged - ours keeps the rev, another change makes it unknown`() {
        assertEquals(4L, androidPinRevAfterChange(expected = true, rev = 4L))
        assertNull(androidPinRevAfterChange(expected = false, rev = 4L))
        assertEquals("ours, its rev's commit lost to a death (S4-1)", 5L, androidPinRevAfterChange(expected = true, rev = 4L, settingRev = 5L))
        assertNull("the kid's change, whatever was being set", androidPinRevAfterChange(expected = false, rev = 4L, settingRev = 5L))
        assertTrue(code("$server/MdmDeviceAdminReceiver.kt").contains("AndroidLock.onPasswordChanged(app)"))
        val reset = body(code("$lock/AndroidLock.kt"), "reset")
        assertTrue(reset, reset.indexOf("ownChangeArmed(PinLockStore.ownChange(app), boot, SystemClock.elapsedRealtime(), settingRev)") in 0 until reset.indexOf("WindowRelock.resetCredential(app, pin)"))
        assertTrue(reset.contains("ownChangeConsumed(PinLockStore.ownChange(app), boot, SystemClock.elapsedRealtime(), failed = true)"))
    }

    /** S4-1: our changes are counted in CE, so a new process and two resets close together still know them as ours. */
    @Test
    fun `our own credential changes - counted, kept across a process, bounded by the boot and the window`() {
        val one = ownChangeArmed(null, boot = 7, elapsed = 1_000, settingRev = 5L)
        assertEquals(OwnChange(1, 7, 1_000 + OWN_CHANGE_MS, 5L), one)
        assertTrue(ownChangeLive(one, 7, 1_000 + OWN_CHANGE_MS - 1))
        assertFalse("the window", ownChangeLive(one, 7, 1_000 + OWN_CHANGE_MS))
        assertFalse("another boot", ownChangeLive(one, 8, 2_000))
        assertFalse("an unknown boot", ownChangeLive(one, -1, 2_000))
        // Two of ours close together: two broadcasts, both ours; the newest rev being set.
        val two = ownChangeArmed(one, 7, 2_000, settingRev = 6L)
        assertEquals(OwnChange(2, 7, 2_000 + OWN_CHANGE_MS, 6L), two)
        val after1 = ownChangeConsumed(two, 7, 3_000)
        assertEquals(OwnChange(1, 7, 2_000 + OWN_CHANGE_MS, 6L), after1)
        assertTrue(ownChangeLive(after1, 7, 3_000))
        assertNull(ownChangeConsumed(after1, 7, 3_500))
        // A failed reset takes its mark back and its rev is no one's; a stale mark restarts the count.
        assertEquals(OwnChange(1, 7, 2_000 + OWN_CHANGE_MS, null), ownChangeConsumed(two, 7, 3_000, failed = true))
        assertEquals(OwnChange(1, 9, 50 + OWN_CHANGE_MS, null), ownChangeArmed(two, 9, 50, settingRev = null))
        assertNull(ownChangeConsumed(one, 7, 1_000 + OWN_CHANGE_MS))
        // onPasswordChanged reads the stored mark, consumes one and adopts its rev only when it is ours.
        val changed = body(code("$lock/AndroidLock.kt"), "onPasswordChanged")
        assertTrue(changed.contains("val expected = ownChangeLive(change, boot, now)"))
        assertTrue(changed.contains("PinLockStore.saveOwnChange(app, ownChangeConsumed(change, boot, now))"))
        assertTrue(changed.contains("androidPinRevAfterChange(expected, rev, change?.settingRev.takeIf { expected })"))
        // Every own reset says what it sets: the command's and the migration's rev, a clear none.
        val android = code("$lock/AndroidLock.kt")
        assertTrue(android.contains("reset(app, null, settingRev = null)"))
        assertTrue(android.contains("reset(app, pin, settingRev = pinRev)"))
        assertTrue(android.contains("reset(app, pin, settingRev = rev)"))
        assertFalse("no in-memory one-shot flag any more", android.contains("expectOwnChangeUntil"))
    }

    @Test
    fun `re-front and camera - held only while Android's secure keyguard is locked, the camera on the bit read back`() {
        assertTrue(androidRefrontHeld(face = true, deviceSecure = true, androidLocked = true))
        assertFalse("a Swipe keyguard (P59-3)", androidRefrontHeld(true, deviceSecure = false, androidLocked = true))
        assertFalse(androidRefrontHeld(true, true, false))
        assertFalse(androidRefrontHeld(false, true, true))
        val base = RefrontInputs(locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false, emergencyFlow = false, alarmRinging = false)
        // Design 59b: Android locked under the face is the loss rule's (AndroidLockFaceTest), no longer a blanket stop.
        assertEquals(RefrontAction.Stop, refrontAction(base.copy(android = AndroidLoss.LOCKED, interactive = false), 0))
        assertTrue(cameraLockEngaged(locked = true, androidGuardsCamera = false))
        assertFalse(cameraLockEngaged(locked = true, androidGuardsCamera = true))
        assertFalse(cameraLockEngaged(locked = false, androidGuardsCamera = false))
        assertTrue(secureCameraHeld(managed = true, features = ANDROID_KEYGUARD_FEATURES or KEYGUARD_SECURE_CAMERA))
        assertFalse("not read back (P59-4)", secureCameraHeld(true, ANDROID_KEYGUARD_FEATURES))
        assertFalse("the write failed", secureCameraHeld(true, null))
        assertFalse(secureCameraHeld(false, KEYGUARD_SECURE_CAMERA))
        val android = code("$lock/AndroidLock.kt")
        assertTrue(body(android, "refrontHeld").contains("faceOn && androidRefrontHeld(true, deviceSecure(context), androidLocked(context))"))
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "runRefrontCheck").contains("android = if (chromeLocked && !lockResumed) AndroidLock.loss(context) else null,"))
        assertTrue(code("$lock/CameraLock.kt").contains("val locked = cameraLockEngaged(PinLockRuntime.chromeLocked, AndroidLock.cameraGuarded())"))
        assertTrue(android.contains("fun cameraGuarded(): Boolean = faceOn && KeyguardCover.androidCameraGuard"))
        assertTrue(code("$lock/KeyguardCover.kt").contains("androidCameraGuard = androidOn && secureCameraHeld(managed, written.features)"))
        // P59-8: the camera re-syncs on both edges of the face and of the bit.
        assertTrue(body(android, "face").contains("if (face != was) CameraLock.onLockChanged(app)"))
        assertTrue(body(android, "afterKeyguard").contains("if (guarded != cameraGuardWas) {"))
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "configure").let { it.indexOf("KeyguardCover.configure(") in 0 until it.indexOf("AndroidLock.afterKeyguard(app)") })
    }

    // ---- the keyguard (KeyguardCoverPlan) ------------------------------------------------------------------------

    @Test
    fun `keyguardMode - ANDROID only when asked for with a credential, never the cover then`() {
        assertEquals(KeyguardMode.ANDROID, keyguardMode(true, true, true, false, VoipPhase.NONE, false, androidRequested = true))
        assertEquals("a VoIP call, the crash guard: Android stays the gate", KeyguardMode.ANDROID, keyguardMode(true, true, true, true, VoipPhase.IN_CALL, true, androidRequested = true))
        assertEquals("no credential yet: today's", KeyguardMode.DISABLED, keyguardMode(true, true, false, false, VoipPhase.NONE, false, androidRequested = true))
        assertEquals("no cover while asked for", KeyguardMode.DISABLED, keyguardMode(true, true, false, true, VoipPhase.NONE, false, androidRequested = true))
        assertEquals("a rollback waiting for its token", KeyguardMode.UNTOUCHED, keyguardMode(true, true, true, false, VoipPhase.NONE, false, androidRequested = false))
        assertEquals(KeyguardMode.UNTOUCHED, keyguardMode(false, true, true, false, VoipPhase.NONE, false, androidRequested = true))
    }

    @Test
    fun `the features - Android mode's bits added and recorded, a parent's kept, the switch back takes out exactly ours`() {
        val parent = 0x40
        val (on, added) = keyguardDisabledFeatures(parent, managed = true, cover = false, coverAdded = 0, android = true)
        assertEquals(parent or ANDROID_KEYGUARD_FEATURES or 2, on)
        assertEquals(ANDROID_KEYGUARD_FEATURES, added)
        assertEquals("again: stable", on to added, keyguardDisabledFeatures(on, true, false, added, android = true))
        val (off, after) = keyguardDisabledFeatures(on, managed = true, cover = false, coverAdded = added)
        assertEquals(parent or 2, off)
        assertEquals(0, after)
        val (fromCover, addedFromCover) = keyguardDisabledFeatures(KEYGUARD_FEATURES_ALL, true, false, KEYGUARD_FEATURES_ALL, android = true)
        assertEquals(ANDROID_KEYGUARD_FEATURES or 2, fromCover)
        assertEquals(ANDROID_KEYGUARD_FEATURES, addedFromCover)
    }

    private class FakePolicy(var features: Int = 0, var t: Long = 0L, var disabled: Boolean? = null, val failFeatures: Boolean = false, val dropCamera: Boolean = false) : KeyguardPolicy {
        val writes = mutableListOf<String>()
        override fun readFeatures(): Int = if (dropCamera) features and 2.inv() else features
        override fun writeFeatures(value: Int) {
            if (failFeatures) throw SecurityException("no")
            writes += "features"
            features = value
        }
        override fun readMaxTimeToLock(): Long = t
        override fun writeMaxTimeToLock(ms: Long) {
            writes += "t"
            t = ms
        }
        override fun writeDisabled(disabled: Boolean): Boolean {
            writes += "disabled=$disabled"
            this.disabled = disabled
            return true
        }
    }

    @Test
    fun `writeKeyguard ANDROID - the keyguard on, features recorded first, T, never setKeyguardDisabled(true)`() {
        val p = FakePolicy()
        var stored = -1
        val flags = mutableListOf<Boolean>()
        val w = writeKeyguard(p, KeyguardMode.ANDROID, managed = true, coverAdded = 0, timeoutMs = { 30_000L },
            storeCoverAdded = { check("features" !in p.writes); stored = it; true }, storeWritten = { flags += it; true })
        assertEquals(KeyguardMode.ANDROID, w.mode)
        assertTrue(w.lockMayRun)
        assertNull(w.coverFailed)
        assertEquals(listOf("disabled=false", "features", "t"), p.writes)
        assertEquals(ANDROID_KEYGUARD_FEATURES or 2, p.features)
        assertEquals(30_000L, p.t)
        assertTrue("the cover's flag is never set", flags.isEmpty())
        assertTrue(secureCameraHeld(true, w.features))
        // Failing steps: reported, never a fallback, never disabled.
        for (q in listOf(FakePolicy(failFeatures = true), FakePolicy(dropCamera = true))) {
            val failed = writeKeyguard(q, KeyguardMode.ANDROID, true, 0, { null }, { true })
            assertEquals(KeyguardMode.ANDROID, failed.mode)
            assertEquals("features", failed.coverFailed)
            assertNull("the camera lock stays engaged", failed.features)
            assertFalse(q.writes.contains("disabled=true"))
        }
        // Back to DISABLED (the rollback's apply): exactly ours out, T 0.
        val back = writeKeyguard(p, KeyguardMode.DISABLED, true, stored, { 30_000L }, { stored = it; true })
        assertEquals(2, p.features)
        assertEquals(0L, p.t)
        assertEquals(true, back.disabled)
    }

    // ---- pins -------------------------------------------------------------------------------------------------------

    @Test
    fun `USER_PRESENT reaches us - its own EXPORTED receiver for that protected action alone (the run's bug 1)`() {
        val runtime = code("$lock/PinLockRuntime.kt")
        assertTrue(runtime.contains("ContextCompat.registerReceiver(\n            app,\n            userPresentReceiver,\n            IntentFilter(Intent.ACTION_USER_PRESENT),\n            ContextCompat.RECEIVER_EXPORTED,\n        )"))
        assertEquals("one exported receiver in the runtime", 1, Regex("\\bRECEIVER_EXPORTED").findAll(runtime).count())
        val receiver = runtime.substringAfter("private val userPresentReceiver").substringBefore("\n    }\n")
        assertTrue(receiver.contains("if (intent.action != Intent.ACTION_USER_PRESENT || !AndroidLock.faceOn) return"))
        assertTrue(receiver.indexOf("AndroidLock.userPresent()") < receiver.indexOf("AndroidLock.step(app, FaceTrigger.USER_PRESENT)"))
        // The mark counts only since our lock last went LOCKED.
        assertTrue(body(runtime, "dispatch").contains("AndroidLock.lockedAgain()"))
        // The sleep check: dark and open - Android locked now.
        assertTrue(runtime.substringAfter("private val androidSleepCheck").substringBefore("\n    }\n")
            .contains("when (AndroidLock.step(app, FaceTrigger.SLEEP_CHECK)) {\n            FaceStep.RELOCK -> AndroidLock.relock(app, \"the sleep check\")"))
    }

    @Test
    fun `the glance's way out is Android's verdict - every leave as the face goes through faceStep with live reads`() {
        val activity = code("$lock/PinLockActivity.kt")
        // The face's unlock() calls: only from actAsFace's LEAVE and the pad's success.
        val face = activity.substringAfter("fun closeAsk(").substringBefore("private val waitTicker")
        assertEquals(2, Regex("unlock\\(").findAll(face).count())
        assertTrue(body(activity, "androidAnswered").contains("if (offs == PinLockRuntime.screenOffs && AndroidLock.step(this, FaceTrigger.PAD_SUCCEEDED) == FaceStep.LEAVE) {\n                leaveFromAsk = true\n                unlock(offs)\n            } else {\n                glance?.back()"))
        assertTrue(body(activity, "actAsFace").contains("FaceStep.LEAVE -> {"))
        assertTrue(body(activity, "resumeAsFace").contains("return actAsFace(AndroidLock.step(this, FaceTrigger.RESUME), \"the glance's resume\")"))
        assertTrue(body(activity, "tapAsFace").contains("val step = AndroidLock.step(this, FaceTrigger.UNLOCK_TAP)"))
        // P59-8: the live reads.
        val android = code("$lock/AndroidLock.kt")
        assertTrue(body(android, "step").contains("faceStep(trigger, deviceSecure(context), androidLocked(context), presentSinceLocked)"))
        assertTrue(body(android, "deviceSecure").contains("} catch (e: Exception) {\n        false"))
        assertTrue(body(android, "androidLocked").contains("km == null || km.isDeviceLocked || km.isKeyguardLocked"))
        assertTrue(body(android, "androidLocked").contains("} catch (e: Exception) {\n        true"))
        // P59-1: no verdict about Recents from lock task.
        assertFalse(android.contains("guarded="))
    }

    @Test
    fun `the credential is written only through WindowRelock's token, never a copy, never the PIN in a log`() {
        val relock = code("$server/WindowRelock.kt")
        assertEquals(2, Regex("resetPasswordWithToken\\(").findAll(relock).count())
        val all = file("main/java").walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertEquals(listOf("WindowRelock.kt"), all.filter { code(it.path.substringAfter("src/")).contains("resetPasswordWithToken(") }.map { it.name })
        assertEquals(listOf("AndroidLock.kt"), all.filter { code(it.path.substringAfter("src/")).contains("WindowRelock.resetCredential(") }.map { it.name })
        val android = code("$lock/AndroidLock.kt")
        assertEquals("one reset, behind the ours flag", 1, Regex("WindowRelock\\.resetCredential\\(").findAll(android).count())
        assertFalse("no token copy", android.contains("token()"))
        for (line in android.lines().filter { it.contains("Log.") }) assertFalse(line, Regex("\\$\\{?pin\\b").containsMatchIn(line))
    }

    @Test
    fun `the restart line - written at the shutdown as the face, cleared at the boot's first CE start and in own mode`() {
        val android = code("$lock/AndroidLock.kt")
        assertTrue(body(android, "onShutdown").contains("if (!faceOn) return"))
        assertTrue(body(android, "onShutdown").contains("app.getString(R.string.android_lock_restart_line)"))
        assertTrue(body(android, "atBootStart").contains("if (!firstCeStartOfBoot) return"))
        assertTrue(body(android, "face").contains("if (was && !face) lockScreenInfo(dpm, admin, null)"))
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "init").contains("AndroidLock.atBootStart(app)"))
    }

    // ---- step 4: set_kid_pin, lock_state, the remote lock, the capability ---------------------------------------

    private fun kidLock(pin: String, rev: Long, salt: String = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"): KidLock {
        val hash = com.kidslauncher.mdm.server.PinHash.derive(pin, com.kidslauncher.mdm.server.PinHash.parseHex(salt)!!)
            .joinToString("") { "%02x".format(it) }
        return KidLock(pinHash = hash, pinSalt = salt, pinLength = pin.length, keyguard = "android", pinRev = rev)
    }

    @Test
    fun `set_kid_pin - each answer, in the contract's words, the PIN and the token asked only when due (59 §3_5)`() {
        // The words the server and the PWA read (§1).
        assertEquals(
            listOf("pin set", "own lock", "refused: no_token", "refused: mismatch", "refused: no_pin", "refused: failed"),
            PinCommandAnswer.entries.map { it.message },
        )
        assertEquals(setOf(PinCommandAnswer.PIN_SET, PinCommandAnswer.OWN_LOCK), PinCommandAnswer.entries.filter { it.success }.toSet())
        val never: (String) -> Boolean = { throw AssertionError("verified") }
        val noToken: () -> Boolean = { throw AssertionError("token asked") }
        assertEquals(PinCommandAnswer.OWN_LOCK, pinCommandStep(requested = false, pin = "2468", verifies = never, tokenActive = noToken))
        assertEquals(PinCommandAnswer.NO_PIN, pinCommandStep(true, null, never, noToken))
        assertEquals(PinCommandAnswer.NO_PIN, pinCommandStep(true, "", never, noToken))
        assertEquals(PinCommandAnswer.MISMATCH, pinCommandStep(true, "2468", { false }, noToken))
        assertEquals(PinCommandAnswer.NO_TOKEN, pinCommandStep(true, "2468", { true }, { false }))
        assertNull("set it", pinCommandStep(true, "2468", { it == "2468" }, { true }))
        // Not "android" in the fresh policy (own, missing, no kid_lock) is own lock (androidRequested, P59-5).
        assertFalse(androidRequested(KidLock(keyguard = "own")))
        assertFalse(androidRequested(null))
    }

    @Test
    fun `the command's PIN is checked against the fresh policy's hash, not the applied one, and counts no try (Q59-1)`() {
        val applied = kidLock("1357", rev = 3)
        val fresh = kidLock("2468", rev = 4)
        assertTrue("the new PIN against the fresh hash", commandPinVerifies(fresh, "2468"))
        assertFalse("the new PIN against the applied (old) hash", commandPinVerifies(applied, "2468"))
        assertFalse("a stale command's old PIN against the fresh hash", commandPinVerifies(fresh, "1357"))
        assertFalse("another length", commandPinVerifies(fresh.copy(pinLength = 5), "2468"))
        assertFalse("unusable hash", commandPinVerifies(fresh.copy(pinHash = "zz"), "2468"))
        assertTrue(commandPinShape("024680", 6))
        assertFalse(commandPinShape("24a8", 4))
        assertFalse(commandPinShape("２４６８", 4))
        val runtime = code("$lock/PinLockRuntime.kt")
        assertTrue(body(runtime, "verifyCommandPin").contains("= commandPinVerifies(kidLock, pin)"))
        val check = runtime.substringAfter("internal fun commandPinVerifies(").substringBefore("\n}\n")
        assertFalse("no backoff, no failure counted", check.contains("ackoff") || check.contains("Attempt") || check.contains("config"))
        assertTrue(check.contains("KidLockConfig(kidLock.pinHash, kidLock.pinSalt, kidPinLength(kidLock.pinLength), kidLock.pinRev)"))
        // The glue checks the command's own fresh kid_lock - its hash and its keyguard (S4-4: never the applied
        // `requested`, which would answer the first command after the switch `own lock') -, and the dispatch hands it
        // the same answer's.
        val apply = body(code("$lock/AndroidLock.kt"), "applyPinCommand")
        assertTrue(apply.contains("PinLockRuntime.verifyCommandPin(kidLock, it)"))
        assertTrue(apply.contains("requested = androidRequested(kidLock),"))
        assertFalse(apply.contains("this.requested") || Regex("requested = requested\\b").containsMatchIn(apply))
        val sync = code("$server/MdmSyncWorker.kt")
        assertTrue(sync.contains("dispatchPendingCommand(context, api, dpm, admin, freshPolicy.pendingCommand, freshPolicy.kidLock)"))
        assertTrue(sync.contains("AndroidLock.applyPinCommand(context, kidLock, pending.pin)"))
    }

    @Test
    fun `the command's set - owned first with the rev Android holds now, the fresh rev only once it is set (M-1)`() {
        assertEquals(3L, commandWriteAheadRev(owned = true, storedRev = 3L))
        assertNull("not ours yet: no rev", commandWriteAheadRev(owned = false, storedRev = 3L))
        assertNull(commandWriteAheadRev(owned = true, storedRev = null))
        val android = code("$lock/AndroidLock.kt")
        val set = body(android, "setFromCommand")
        val ahead = set.indexOf("val before = commandWriteAheadRev(owned, PinLockStore.androidPinRev(app))")
        val written = set.indexOf("PinLockStore.saveAndroid(app, owned = true, rev = before)")
        val reset = set.indexOf("if (!reset(app, pin, settingRev = rev))")
        val after = set.indexOf("commitRev(app, rev)")
        assertTrue(set, ahead in 0 until written && written < reset && reset < after)
        // N1: the final rev is written once more if its commit fails, then logged - never a claim the disk lacks.
        val commit = body(android, "commitRev")
        assertTrue(commit.contains("PinLockStore.saveAndroid(app, owned = true, rev = rev) || PinLockStore.saveAndroid(app, owned = true, rev = rev)"))
        assertTrue(commit.contains("Log.w("))
        // Under the monitor the rollback, the migration and onPasswordChanged share; our change marked through reset().
        assertTrue(body(android, "applyPinCommand").contains("synchronized(this)"))
    }

    /** S4-2: Android locked at once after `pin set` under our lock in the dark - no RELOCK, no count, at the next wake. */
    @Test
    fun `after pin set under our lock in the dark, Android is locked at once and not counted`() {
        assertTrue(lockAfterPinSet(face = true, locked = true, interactive = false))
        assertFalse("the kid is looking", lockAfterPinSet(face = true, locked = true, interactive = true))
        assertFalse("our lock is open", lockAfterPinSet(face = true, locked = false, interactive = false))
        assertFalse("no face: Android isn't the gate yet", lockAfterPinSet(face = false, locked = true, interactive = false))
        val android = code("$lock/AndroidLock.kt")
        assertTrue(body(android, "applyPinCommand").contains("if (answer == PinCommandAnswer.PIN_SET) lockAfterSet = true"))
        val after = body(android, "afterKeyguard")
        assertTrue(after.contains("lockAfterSet = false"))
        assertTrue(after.contains("if (lockAfterPinSet(faceOn, PinLockRuntime.mode == LockMode.LOCKED, interactive(app))) {"))
        assertTrue("not a relock", after.contains("lockNow(app)") && !after.contains("relock("))
        // afterKeyguard runs after the keyguard write in every apply.
        assertTrue(body(code("$lock/PinLockRuntime.kt"), "configure").contains("AndroidLock.afterKeyguard(app)"))
    }

    /**
     * Each log call's whole argument list (to its closing parenthesis, across lines), with string literals' contents
     * kept: a template, a `.pin` or a concatenation never names the PIN or the whole command (S4-3).
     */
    private fun logCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        for (m in Regex("""\bLog\.[a-z]+\(|\blog\(""").findAll(source)) {
            var depth = 0
            var i = m.range.last
            var inString = false
            while (i < source.length) {
                val c = source[i]
                if (c == '"' && source[i - 1] != '\\') inString = !inString
                if (!inString) {
                    if (c == '(') depth++
                    if (c == ')' && --depth == 0) break
                }
                i++
            }
            calls += source.substring(m.range.first, minOf(i + 1, source.length))
        }
        return calls
    }

    private val pinInLog = Regex("\\$\\{?pin\\b|\\.pin\\b|\\+\\s*pin\\b|\\bpin\\s*\\+|\\\$pending\\b(?![.?])|\\$\\{pending\\}|[(,]\\s*pin\\s*[,)]")

    @Test
    fun `the PIN goes no further than its command - never logged, the dispatch returns it stripped`() {
        val sync = code("$server/MdmSyncWorker.kt")
        assertTrue(sync.contains("return if (pending.pin != null) pending.copy(pin = null) else pending"))
        // Every file the PIN passes through, to its last hop (WindowRelock.resetCredential) and the hash (PinHash).
        val files = listOf("$server/MdmSyncWorker.kt", "$lock/AndroidLock.kt", "$lock/PinLockRuntime.kt", "$server/WindowRelock.kt", "$server/PinHash.kt")
        var seen = 0
        for (path in files) {
            // The file as written (stripping `//` would cut a string with a URL and lose its closing quote).
            for (call in logCalls(file(path).readText())) {
                seen++
                assertTrue("$path: a log call that never closes", call.length < 1_000)
                assertFalse("$path: $call", pinInLog.containsMatchIn(call))
            }
        }
        assertTrue(seen > 50)
        // The scanner itself: a multi-line call, a concatenation and a template are all caught (M11, M16).
        for (bad in listOf("Log.i(\n            TAG,\n            \"set \$pin\",\n        )", "Log.i(LOG_TAG, \"reset to \" + pin)", "Log.w(TAG, \"x\", pin)", "Log.d(TAG, \"\${pending}\")")) {
            assertTrue(bad, logCalls(bad).any { pinInLog.containsMatchIn(it) })
        }
        assertFalse(logCalls("Log.i(TAG, \"PIN command: \${answer.message} (rev \${kidLock?.pinRev})\")").any { pinInLog.containsMatchIn(it) })
    }

    @Test
    fun `lock_state's gate - android while it is the face or our credential holds, its rev, the relocks taken`() {
        assertEquals(KeyguardReport("android", 5L), keyguardReport(face = true, owned = true, deviceSecure = true, androidRev = 5L, ownRev = 6L))
        assertEquals("changed on the phone", KeyguardReport("android", null), keyguardReport(true, true, true, null, 6L))
        assertEquals("a credential not ours", KeyguardReport("android", null), keyguardReport(true, owned = false, deviceSecure = true, androidRev = 5L, ownRev = 6L))
        assertEquals("a rollback waiting for the token", KeyguardReport("android", 5L), keyguardReport(false, true, true, 5L, 6L))
        assertEquals("waiting for the migration", KeyguardReport("own", 6L), keyguardReport(false, false, false, null, 6L))
        assertEquals("owned but gone", KeyguardReport("own", 6L), keyguardReport(false, true, false, 5L, 6L))
        assertEquals("no Child PIN", KeyguardReport("own", null), keyguardReport(false, false, false, null, null))
        val android = code("$lock/AndroidLock.kt")
        // N2: read by the report, subtracted only once the report landed - a failed POST keeps them.
        assertTrue(body(android, "report").contains("relocks.get())"))
        assertFalse(body(android, "report").contains("getAndSet"))
        assertTrue(body(android, "relocksReported").contains("relocks.updateAndGet { (it - reported).coerceAtLeast(0) }"))
        assertTrue(code("$server/MdmSyncWorker.kt").contains("if (sent.isSuccessful) com.kidslauncher.mdm.lock.AndroidLock.relocksReported(lockState.keyguardRelocks)"))
        assertTrue(body(android, "relock").contains("relocks.updateAndGet { (it + 1).coerceAtMost(1_000) }"))
        val report = body(code("$lock/PinLockRuntime.kt"), "report")
        assertTrue(report.contains("AndroidLock.report(app, ownRev = (config ?: runCatching { PinLockStore.config(app) }.getOrNull())?.pinRev)"))
        for (field in listOf("keyguard = android.keyguard", "pinRev = android.pinRev", "resetToken = android.resetToken", "keyguardRelocks = android.relocks")) {
            assertTrue(field, report.contains(field))
        }
    }

    @Test
    fun `the remote lock under Android's PIN answers android pin, and the capability comes with the handler (Q59-13)`() {
        assertEquals("locked (android pin)", com.kidslauncher.mdm.server.lockAnswer(ok = true, ours = true, androidGate = true))
        assertEquals("a rollback waiting for the token", "locked (android pin)", com.kidslauncher.mdm.server.lockAnswer(true, false, true))
        assertEquals("locked (handy lock)", com.kidslauncher.mdm.server.lockAnswer(true, true, false))
        assertEquals("locked (Android)", com.kidslauncher.mdm.server.lockAnswer(true, false, false))
        assertEquals("failed to lock", com.kidslauncher.mdm.server.lockAnswer(false, true, true))
        assertTrue(androidGate(face = true, owned = false, deviceSecure = true))
        assertTrue(androidGate(face = false, owned = true, deviceSecure = true))
        assertFalse(androidGate(face = false, owned = true, deviceSecure = false))
        assertTrue(code("$server/MdmSyncWorker.kt").contains("lockAnswer(ok, ours, com.kidslauncher.mdm.lock.AndroidLock.gateNow(context))"))
        assertTrue(ANDROID_KEYGUARD_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
        assertEquals("android_keyguard_v1", ANDROID_KEYGUARD_CAPABILITY)
        assertTrue(code("$server/MdmSyncWorker.kt").contains("com.kidslauncher.mdm.lock.SET_KID_PIN_COMMAND -> {"))
        assertEquals("set_kid_pin", SET_KID_PIN_COMMAND)
    }

    @Test
    fun `the debug switch is gone`() {
        for (dir in listOf("main", "debug", "release")) {
            for (f in file(dir).walk().filter { it.isFile }) {
                val text = f.readText()
                assertFalse(f.path, text.contains("HybridDebug") || text.contains("hybrid59_debug") || text.contains("Hybrid59"))
            }
        }
    }

    @Test
    fun `lock task's KEYGUARD stays forced - without it lock task would disable even a secure keyguard`() {
        assertTrue(code("$server/LockTaskHelpers.kt").contains("or LOCK_TASK_FEATURE_KEYGUARD"))
        assertTrue((com.kidslauncher.mdm.server.lockTaskFeatures(0L, blockActivityStart = false) and com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_KEYGUARD) != 0)
    }
}
