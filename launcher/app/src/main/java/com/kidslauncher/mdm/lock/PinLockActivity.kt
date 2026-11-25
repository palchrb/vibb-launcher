package com.kidslauncher.mdm.lock

import android.app.ActivityManager
import android.app.Dialog
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.home.lockLeaveArrival
import com.kidslauncher.mdm.calls.EmergencyCall
import com.kidslauncher.mdm.databinding.ActivityPinLockBinding
import com.kidslauncher.mdm.databinding.ViewVoipRingBinding
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore

private const val LOG_TAG = "PinLockActivity"

/**
 * The lock's glance, Android's face (design 59, the host of handy step 10's lock screen; design 132: the only lock). Shown by
 * [PinLockRuntime] while LOCKED - over every activity, kiosk or not: its own task
 * (`taskAffinity .pinlock`, single task, excluded from recents, portrait), always in lock task
 * while locked (QA 10 #1): with the kiosk on it joins the kiosk's lock task - rooted by Home, never
 * by the lock if it can help it, and it leaves through Home (design 16, [lockTaskEntry],
 * [lockLeave]) - with the kiosk off it starts lock task itself (our package plus the emergency
 * helpers are pinned for it) and stops it on unlock, and leaves through Home too unless a call is
 * live (design 32); with Android locked and the kiosk off it takes none (design 59b R2). Back does nothing; leaving
 * the front any other way is the face's loss rule ([androidLossStep]).
 *
 * It always comes up on its glance page (design 25, [GlancePanel]): the clock, the music tile, the
 * newest missed calls and messages, the swipe hint and Emergency call; a swipe up (design 59d step J) or a row asks
 * Android for its PIN pad (the ASK stage) - Android's PIN is the only unlock (design 132: no keypad of ours). The
 * window is `FLAG_SECURE`: names and texts never reach a screenshot, a recording or a cast. Not direct-boot-aware.
 */
class PinLockActivity : AppCompatActivity() {

    /** Design 78 §5.3: the lock is night as a constant, like every screen that is not a page - it reads nothing. */
    override fun attachBaseContext(newBase: Context) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.attachBaseContext(newBase)
    }

    private lateinit var binding: ActivityPinLockBinding
    private val handler = Handler(Looper.getMainLooper())
    private var finishingAfterUnlock = false
    private var startedLockTask = false
    private var resumed = false

    /** The lock's content root and the metrics its texts were sized at (N8, [FontScaleRefresh]). */
    private var content: FrameLayout? = null
    private var textMetrics: android.util.DisplayMetrics? = null
    private var textFontScale = 0f

    /** The glance page (design 25); `null` only if it couldn't be built - the plain ground with Emergency call then. */
    private var glance: GlancePanel? = null

    /** A glance row's target to open after the right code (design 25 §1.4): set only in [unlock],
     * used and cleared only in [go] (dropped by [leave] when the glance isn't in front, design 59b step 0). */
    private var pendingOpen: PendingOpen? = null

    private val modeListener: () -> Unit = {
        if (PinLockRuntime.mode != LockMode.LOCKED && !isFinishing) leave()
    }

    /** The last [ensureLockTask] found our package not pinned (kiosk off, the chrome not yet set). */
    private var waitingForPin = false

    /** The policy's [EntryPolicy] at the last [ensureLockTask] or chrome pass (`null` = not seen yet). */
    private var policySeen: EntryPolicy? = null

    /** A chrome pass landed (off the main thread since 16c; a policy's too): with the kiosk off it pins our package for
     * the lock - enter lock task now if the resume found it not permitted; and a policy that changed the kiosk or the
     * face re-runs the entry check now, not at the next resume (E7 M2, [chromeEntryRecheckDue]). */
    private val chromeListener: () -> Unit = {
        val now = entryPolicy()
        val seen = policySeen
        policySeen = now
        if (resumed && PinLockRuntime.mode == LockMode.LOCKED && chromeEntryRecheckDue(waitingForPin, seen, now)) {
            ensureLockTask(fallbackDue = false)
        }
    }

    private fun entryPolicy() = EntryPolicy(LockTaskChrome.kioskOn, AndroidLock.faceOn)

    private lateinit var voipRing: ViewVoipRingBinding
    /** The package the ring screen was filled for. */
    private var voipShownFor: String? = null
    /** A VoIP call held lock task off at the last check (qa-16-17-code #6). */
    private var voipHeldLockTask = false

    private val voipRender = Runnable { renderVoip() }

    /** Gap closed 2026-11-02: a still-ringing alarm's screen, sent once per resume (and per screen-on while resumed)
     * after [ALARM_SCREEN_SETTLE_MS] ([PinLockRuntime.sendAlarmScreen]); held, it looks again after the settle; a pause before it (the platform's own launch, an unlock) drops
     * it. A send is checked after [ALARM_SCREEN_CHECK_MS] ([PinLockRuntime.alarmScreenCheck]). */
    private val alarmScreenSend: Runnable = object : Runnable {
        override fun run() {
            if (!resumed || !openDialogs.isEmpty()) return
            when (PinLockRuntime.sendAlarmScreen(this@PinLockActivity, typing = false)) {
                AlarmScreenStep.LATER -> handler.postDelayed(this, ALARM_SCREEN_SETTLE_MS)
                AlarmScreenStep.SEND -> handler.postDelayed(alarmScreenCheck, ALARM_SCREEN_CHECK_MS)
                AlarmScreenStep.NONE, AlarmScreenStep.SCREEN_ON -> {}
            }
        }
    }
    private val alarmScreenCheck = Runnable { PinLockRuntime.alarmScreenCheck() }

    /** The screen came on while resumed (a lock resumed in the dark gets no new resume): the alarm-screen look again. */
    private val alarmScreenOn: () -> Unit = {
        if (resumed) {
            handler.removeCallbacks(alarmScreenSend)
            handler.postDelayed(alarmScreenSend, ALARM_SCREEN_SETTLE_MS)
        }
    }

    /** Svar went out but the lock never left the front: the start was refused (17b QA #3) - the card
     * says so, and the ring is as before Svar (qa-17b-code #1). */
    private val voipAnswerCheck = Runnable {
        if (resumed && voipRing.root.visibility == View.VISIBLE) {
            voipRing.voipRingError.visibility = View.VISIBLE
            PinLockRuntime.voipAnswerRefused(this)
        }
    }

    /** The lock's own dialog (Nødsamtale's confirm) while open: no ringing alarm's screen is sent over it. */
    private val openDialogs = mutableSetOf<Dialog>()

    private fun trackDialog(dialog: Dialog) {
        openDialogs += dialog
        dialog.setOnDismissListener {
            openDialogs -= dialog
            renderVoip()
        }
    }

    // ---- design 59: the glance as Android's face ([AndroidLock], 59 §3.3) -----------------------------------------

    /** The open ask for Android's PIN pad: when (elapsed; `null` = none), the screen-offs then, and its id (a newer
     * ask makes an older one's cancel or error moot). */
    private var askedAt: Long? = null
    private var askedOffs = 0

    /** Design 59d §8.16: the open ask is a home gesture's ([askAfterGesture]), and whether its one re-ask went. */
    private var askFromGesture = false
    private var gestureAskRetried = false

    /** 2026-11-17 check, item 4: the open ask is the one re-ask after SystemUI dropped its pad ([padDropRetryDue]). */
    private var padDropRetried = false
    private var askId = 0

    private fun askAge(): Long? = askedAt?.let { SystemClock.elapsedRealtime() - it }

    private fun closeAsk(cancelled: Boolean = false) {
        askedAt = null
        handler.removeCallbacks(askCheck)
        PinLockRuntime.noteAndroidAsk(open = false, cancelled = cancelled)
    }

    /** qa-59b-code B-4: this leave is the pad's success - the glance's own ask, so it leaves as in front (a row's target
     * opens over Home) whatever the lifecycle reads while the keyguard goes away. Set only in [androidAnswered]. */
    private var leaveFromAsk = false

    /** Design 59d step B: the ASK stage - Android's PIN pad asked for from the glance page. */
    private fun asking(): Boolean = glance?.state?.stage == LockStage.ASK

    /** The glance's stage changed: ASK asks for Android's PIN pad; GLANCE closes an open ask; rendered. */
    private fun onStage() {
        // RC3-2: while Android's pad is asked for, our (opaque) window also shows the wallpaper - set well before the pad's
        // success, since WM reads it when the leave's transition collects us. Home's open is then the shell's
        // wallpaper-intra transition (the glance fades out in place, 280 ms) instead of wallpaper_open_exit (its ground
        // sliding down off the screen, 225 ms). Nothing changes on screen. Never cleared by the leave itself.
        if (!finishingAfterUnlock) showWallpaperBelow(asking())
        if (!asking()) closeAsk() else tapAsFace()
        render()
    }

    /** RC3-2: [onStage]'s wallpaper flag, set and cleared only on a change. */
    private fun showWallpaperBelow(on: Boolean) {
        val now = window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0
        if (on == now) return
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    }

    /**
     * The swipe up ("Lås opp" before step J) or a row ([FaceTrigger.UNLOCK_TAP], live reads - P59-8): Android's PIN pad
     * while it is locked; a tap on an ask older than [ANDROID_ASK_RETRY_MS] asks again (the run's bug 2: never dead
     * taps); no credential now - the glance leaves (design 132: no lock left to keep).
     */
    private fun tapAsFace() {
        val step = AndroidLock.step(this, FaceTrigger.UNLOCK_TAP)
        if (step == FaceStep.ASK_ANDROID) {
            if (askAgain(askAge())) askAndroid()
        } else {
            actAsFace(step, "Lås opp")
        }
    }

    /** Design 59d §8.16: the gesture's ask - the glance page's own unlock ([GlancePanel.askAfterGesture]), marked so its
     * cancel by the keyguard's re-occlusion can ask once more ([gestureAskRetryDue]). */
    private fun askAfterGesture(retry: Boolean) {
        // Q-2: never over a call screen, never dark.
        if (!resumed || isFinishing || finishingAfterUnlock || PinLockRuntime.mode != LockMode.LOCKED) return
        if (!interactiveNow() || PinLockRuntime.callLiveNow(this)) return
        gestureAskRetried = retry
        askFromGesture = true
        glance?.askAfterGesture()
        if (askedAt == null) askFromGesture = false
    }

    /** `requestDismissKeyguard` - Android's PIN pad over the glance; its answer within [ANDROID_ASK_TIMEOUT_MS] or the
     * state is read again once nothing covers us ([askCheck]). */
    private fun askAndroid(padRetry: Boolean = false) {
        val id = ++askId
        val offs = PinLockRuntime.screenOffs
        askedAt = SystemClock.elapsedRealtime()
        askedOffs = offs
        padDropRetried = padRetry
        // Design 59b §3: the pad (and its Emergency dialer) is ours for the face's loss rule.
        PinLockRuntime.noteAndroidAsk(open = true)
        AndroidLock.note(this, "Lås opp: requestDismissKeyguard (Android's PIN pad)")
        try {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, askCallback(id, offs))
        } catch (e: Exception) {
            closeAsk()
            Log.w(ANDROID_LOCK_TAG, "requestDismissKeyguard failed: ${e.javaClass.simpleName}")
            glance?.back()
            return
        }
        handler.removeCallbacks(askCheck)
        handler.postDelayed(askCheck, ANDROID_ASK_TIMEOUT_MS)
    }

    private fun askCallback(id: Int, offs: Int) = object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() = androidAnswered(id, offs, "succeeded")
        override fun onDismissCancelled() = androidAnswered(id, offs, "cancelled")
        override fun onDismissError() = androidAnswered(id, offs, "error")
    }

    /**
     * Android's answer. Succeeded ([FaceTrigger.PAD_SUCCEEDED]): the glance leaves (a row's target opens) - any ask's
     * success is Android's verdict, but never after a screen-off since that ask (design 24 QA #4) and never without
     * a credential now. Cancelled: back to the glance. An error: the state read again ([recoverAsk]).
     */
    private fun androidAnswered(id: Int, offs: Int, result: String) {
        val current = id == askId && askedAt != null
        // Design 59d §8.16: a gesture's ask cancelled by the keyguard's own re-occlusion asks once more.
        val gestureRetry = current && result == "cancelled" &&
            gestureAskRetryDue(askFromGesture, gestureAskRetried, offs == PinLockRuntime.screenOffs, askedAt, SystemClock.elapsedRealtime())
        if (current) askFromGesture = false
        // B-3: a cancel (the pad's Emergency button hides the bouncer) stays "ours" for the ask's window.
        if (current) closeAsk(cancelled = result == "cancelled")
        AndroidLock.note(this, "Android's PIN pad: $result${if (current) "" else " (an earlier ask)"}")
        if (isFinishing || finishingAfterUnlock || PinLockRuntime.mode != LockMode.LOCKED) return
        if (result == "succeeded") {
            if (offs == PinLockRuntime.screenOffs && AndroidLock.step(this, FaceTrigger.PAD_SUCCEEDED) == FaceStep.LEAVE) {
                leaveFromAsk = true
                unlock(offs)
            } else {
                glance?.back()
            }
            return
        }
        if (!current) return
        if (result == "cancelled") glance?.back() else recoverAsk()
        if (gestureRetry) {
            Log.i(ANDROID_LOCK_TAG, "Lås opp: the gesture's ask was cancelled by the keyguard settling - asking once more")
            handler.postDelayed({ askAfterGesture(retry = true) }, GESTURE_ASK_RETRY_DELAY_MS)
        }
    }

    /** The run's bug 2: an ask with no answer for [ANDROID_ASK_TIMEOUT_MS] while our window has the focus - no pad and
     * no shade over us - died: the state is read again. Covered: checked again when the focus comes back. */
    private val askCheck = Runnable {
        if (askRecoveryDue(askAge(), hasWindowFocus())) {
            Log.w(ANDROID_LOCK_TAG, "Lås opp: no answer from Android's PIN pad - reading the state again")
            recoverAsk()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && askedAt != null) {
            handler.removeCallbacks(askCheck)
            val wait = ANDROID_ASK_TIMEOUT_MS - (askAge() ?: 0L)
            handler.postDelayed(askCheck, wait.coerceAtLeast(0L))
            // Item 4 (Android 16+): the focus back this soon with no answer - SystemUI dropped the pad for its shade.
            if (padDropRetryDue(Build.VERSION.SDK_INT, padDropRetried, askAge(), askedOffs == PinLockRuntime.screenOffs, keyguardLockedNow())) {
                val id = askId
                handler.postDelayed({ padDropReask(id) }, PAD_DROP_RETRY_DELAY_MS)
            }
        }
    }

    /** [padDropRetryDue]'s one re-ask, by [padDropReaskNow]: never over a pad that is up (it holds the focus). */
    private fun padDropReask(id: Int) {
        val lockAsking = resumed && !isFinishing && !finishingAfterUnlock && PinLockRuntime.mode == LockMode.LOCKED && asking()
        if (!padDropReaskNow(id == askId && askedAt != null, hasWindowFocus(), keyguardLockedNow(), interactiveNow(), lockAsking, PinLockRuntime.callLiveNow(this))) return
        Log.i(ANDROID_LOCK_TAG, "Lås opp: the pad went away with no answer - asking once more")
        askAndroid(padRetry = true)
    }

    /** `isKeyguardLocked`; unreadable = no (item 4's re-ask fails safe: none). */
    private fun keyguardLockedNow(): Boolean = try {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (e: Exception) {
        false
    }

    /** A dead ask, or an error: [FaceTrigger.RESUME]'s rule now - Android locked: the glance (a new tap asks again). */
    private fun recoverAsk() {
        closeAsk()
        val step = AndroidLock.step(this, FaceTrigger.RESUME)
        if (step != FaceStep.LEAVE) glance?.back()
        actAsFace(step, "the ask's recovery")
    }

    /** One [faceStep] verdict carried out. Returns whether the glance left. */
    private fun actAsFace(step: FaceStep, why: String): Boolean {
        when (step) {
            FaceStep.LEAVE -> {
                closeAsk()
                Log.i(ANDROID_LOCK_TAG, "$why: Android's lock is open (its PIN pad or USER_PRESENT) - the glance leaves")
                unlock(PinLockRuntime.screenOffs)
                return true
            }
            FaceStep.WAIT -> {
                waitOffs = PinLockRuntime.screenOffs
                handler.removeCallbacks(faceWait)
                handler.postDelayed(faceWait, AndroidLock.waitMs(PinLockRuntime.screenOffs))
            }
            FaceStep.RELOCK -> {
                glance?.back()
                AndroidLock.relock(this, why)
            }
            FaceStep.STAY -> if (askedAt == null && asking()) glance?.back()
            FaceStep.ASK_ANDROID -> Unit
        }
        return false
    }

    /** The screen-offs when the wait began: a screen-off since makes the sleep check the judge. */
    private var waitOffs = 0

    /** [faceWaitMs] after a not-locked read with no `USER_PRESENT` ([FaceStep.WAIT]): [FaceTrigger.AFTER_WAIT]
     * - still open and none came: RELOCK (P59-6, Q59-3: a read alone never opens the phone). */
    private val faceWait = Runnable {
        if (isFinishing || finishingAfterUnlock || PinLockRuntime.mode != LockMode.LOCKED || waitOffs != PinLockRuntime.screenOffs) return@Runnable
        actAsFace(AndroidLock.step(this, FaceTrigger.AFTER_WAIT), "the glance's wait")
    }

    /** `USER_PRESENT` with Android open ([PinLockRuntime.androidUnlockHook]), or the re-front check finding Android open
     * with that mark (design 59b §2): an open ask's answer goes first (it carries a row's target), else the glance
     * leaves now. */
    private val androidUnlockHook: () -> Unit = {
        handler.removeCallbacks(faceWait)
        handler.removeCallbacks(presentLeave)
        presentReread = false
        if (askedAt != null) handler.postDelayed(presentLeave, ANDROID_ANSWER_WAIT_MS) else presentLeave.run()
    }

    /** Design 59b §2 (Q59b-6, N3): a `USER_PRESENT` whose read wasn't LEAVE is read once more - only once. */
    private var presentReread = false

    private val presentLeave: Runnable = Runnable {
        if (!isFinishing && !finishingAfterUnlock && PinLockRuntime.mode == LockMode.LOCKED) {
            val step = AndroidLock.step(this, FaceTrigger.USER_PRESENT)
            if (step == FaceStep.LEAVE) {
                actAsFace(step, "USER_PRESENT")
            } else if (!presentReread) {
                presentReread = true
                handler.postDelayed(presentLeave, ANDROID_ANSWER_WAIT_MS)
            }
        }
    }

    /**
     * At the resume ([FaceTrigger.RESUME]): logged; with the screen on, Android open - LEAVE only with a
     * `USER_PRESENT` since our lock went LOCKED, else the wait and then RELOCK. An ask from before a screen-off died
     * with it. Returns whether the glance left.
     */
    private fun resumeAsFace(): Boolean {
        AndroidLock.note(this, "the glance resumed")
        AndroidLock.noteLockTask(this)
        if (askedAt != null && askedOffs != PinLockRuntime.screenOffs) closeAsk()
        if (askedAt != null || !interactiveNow()) return false
        return actAsFace(AndroidLock.step(this, FaceTrigger.RESUME), "the glance's resume")
    }

    private fun interactiveNow(): Boolean = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instances++
        // The crash guard, before any other work (QA 10 #14, qa-10-code #6): crashes recorded by
        // the uncaught-exception handler while this screen existed - not recreations or kills.
        // Unreadable (qa-16c-code #3): an armed guard - the lock must come up.
        val stored = runCatching { PinLockStore.guard(this) }.getOrDefault(CrashGuard())
        val guard = guardOnCreate(stored, System.currentTimeMillis())
        if (guard != stored) runCatching { PinLockStore.saveGuard(this, guard) }
        if (guard.trippedAtMs != null) {
            PinLockRuntime.guardTripped(this)
            finish()
            return
        }
        // Design 25 §1.2: no screenshot, recording or cast - before any content.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Design 32 (QA M3): the stand-in WM shows before the lock's first frame at a screen-on - its
        // app-theme snapshot - is pure black, bars included, like the theme's window and splash.
        setTaskDescription(
            ActivityManager.TaskDescription.Builder()
                .setBackgroundColor(Color.BLACK)
                .setStatusBarColor(Color.BLACK)
                .setNavigationBarColor(Color.BLACK)
                .build(),
        )
        // Bug 25b-B: no open transition - at a screen-on the lock is simply there.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        binding = ActivityPinLockBinding.inflate(layoutInflater)
        // The VoIP ring screen (design 17) lies over the glance while an allowed app's call rings.
        val frame = FrameLayout(this)
        frame.addView(binding.root)
        // The glance page (design 25) under the ring screen. Built defensively: a fault here leaves the plain ground with
        // Emergency call, never a crash that trips the guard.
        glance = try {
            // Design 59d step B: the swipe up (step J) and a row lead to the ASK stage.
            GlancePanel(this, frame, binding.root, ::onStage) { binding.pinEmergency.performClick() }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "The glance page couldn't be built: ${e.javaClass.simpleName}")
            null
        }
        voipRing = ViewVoipRingBinding.inflate(layoutInflater, frame, true)
        setContentView(frame)
        content = frame
        textMetrics = FontScaleRefresh.snapshot(resources.displayMetrics)
        textFontScale = resources.configuration.fontScale
        // Status bar inset once, plus the layout's own <= 8 dp (fix round 2026-10-06).
        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)
        com.kidslauncher.mdm.ui.KidInsets.apply(voipRing.root)
        voipRing.voipRingAnswer.setOnClickListener { onVoipAnswer() }
        voipRing.voipRingDecline.setOnClickListener { onVoipDecline() }
        // Emergency call stays reachable during a ring (qa-16-17-code #3).
        voipRing.voipRingEmergency.setOnClickListener {
            trackDialog(EmergencyCall.confirm(this, locked = true) { PinLockRuntime.emergencyFlowStarted() })
        }
        // Both Nødsamtale pills announce as a button (qa-34-s8-code L3).
        binding.pinEmergency.accessibilityDelegate = ButtonRole
        voipRing.voipRingEmergency.accessibilityDelegate = ButtonRole

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        binding.pinEmergency.setOnClickListener {
            trackDialog(EmergencyCall.confirm(this, locked = true) { PinLockRuntime.emergencyFlowStarted() })
        }
        PinLockRuntime.addModeListener(modeListener)
        PinLockRuntime.addScreenOnListener(alarmScreenOn)
        PinLockRuntime.addChromeListener(chromeListener)
        VoipCalls.addListener(voipListener)
        // Design 59: USER_PRESENT after Android's keyguard went comes here first.
        PinLockRuntime.androidUnlockHook = androidUnlockHook
        render()
        // Design 25, "Lock background": Home's wallpaper (or the parent's lock picture) behind
        // the glance once the store has worked it out; the plain ground until then.
        WallpaperStore.ensureLoaded(this)
        applyLockGround()
        // qa-25b-25f L2: after a process start the store hasn't worked the background out yet - the
        // first frame waits for it (at most LOCK_GROUND_WAIT_MS) rather than switching from the
        // plain ground to the wallpaper in view.
        if (!WallpaperStore.state.lockReady) {
            var since = 0L
            frame.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (since == 0L) since = now
                    if (holdForLockGround(WallpaperStore.state.lockReady, since, now)) return false
                    if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnPreDrawListener(this)
                    applyLockGround()
                    return true
                }
            })
        }
    }

    private val wallpaperListener: () -> Unit = { applyLockGround() }

    /** The lock's background under the glance page and on it ([lockGround]). A fault keeps
     * the plain ground - never a crash that trips the guard. */
    private fun applyLockGround() {
        try {
            val state = WallpaperStore.state
            if (!state.lockReady) return
            binding.root.background = lockGround(state)
            glance?.ground(lockGround(state), GlassGround(state.lock.fill, state.lockBitmap, state.lockScrimArgb))
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "The lock's background couldn't be set: ${e.javaClass.simpleName}")
        }
    }

    private val voipListener: () -> Unit = {
        renderVoip()
        // The lock re-enters lock task as soon as a VoIP call stops holding it off (qa-16-17-code #6).
        val holds = VoipCalls.phase != VoipPhase.NONE
        if (holds != voipHeldLockTask) {
            voipHeldLockTask = holds
            if (resumed && PinLockRuntime.mode == LockMode.LOCKED) ensureLockTask(fallbackDue = false)
        }
    }

    /**
     * A ring while LOCKED (design 59b §4, [voipRingUi]): the platform's full-screen intent first (the grace in
     * [PinLockRuntime]), the ring card as the fallback. Never after Avvis for this ring (qa-16-17-code #3), never over
     * another call, the emergency flow or an alarm (17b QA #5).
     */
    private fun renderVoip() {
        if (!::voipRing.isInitialized) return
        val pkg = VoipCalls.ringingPackage
        if (PinLockRuntime.voipRingUiNow(this) != VoipRingUi.CARD || pkg == null) {
            voipRing.root.visibility = View.GONE
            voipRing.voipRingError.visibility = View.GONE
            voipShownFor = null
            // Design r11 (qa-r11-glance-code Q-2, the user's 2026-10-24 rule): the glance is the face again, from the top.
            glance?.covered(false)
            return
        }
        if (pkg != voipShownFor) {
            voipShownFor = pkg
            val label = VoipCalls.ringingLabel(this).orEmpty()
            voipRing.voipRingName.text = label
            voipRing.voipRingStatus.text = getString(R.string.voip_ring_via, label)
            voipRing.voipRingIcon.setImageDrawable(
                try {
                    packageManager.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                },
            )
            voipRing.voipRingError.visibility = View.GONE
        }
        voipRing.root.visibility = View.VISIBLE
        // The glance under the card: hidden from accessibility, no row dismissed (qa-r11-glance-code Q-2).
        glance?.covered(true)
    }

    /** Answer (17b): the CallStyle answer action - one tap answers - sent from this visible lock;
     * this ring goes silent. The lock not leaving the front within [VOIP_FSI_CHECK_MS] means the
     * start was refused: the card says so. */
    private fun onVoipAnswer() {
        handler.removeCallbacks(voipAnswerCheck)
        val sent = VoipCalls.answer(this)
        if (sent == VoipAnswerSent.NOTHING) {
            voipRing.voipRingError.visibility = View.VISIBLE
            return
        }
        voipRing.voipRingError.visibility = View.GONE
        PinLockRuntime.answeredVoipRing(this, sent)
        handler.postDelayed(voipAnswerCheck, VOIP_FSI_CHECK_MS)
    }

    /** Avvis always hides the card and silences this ring, whatever the decline action did. */
    private fun onVoipDecline() {
        if (!VoipCalls.decline(this)) Log.w(LOG_TAG, "The VoIP decline action couldn't be sent")
        PinLockRuntime.dismissVoipRing(this)
        renderVoip()
    }

    override fun onStart() {
        super.onStart()
        glance?.start()
        WallpaperStore.addListener(wallpaperListener)
        applyLockGround()
    }

    /**
     * The lock handles every configuration change itself (manifest `configChanges`): a font-scale
     * change re-sizes its texts in place, then the glance layout follows (emulator round
     * 2026-10-14 N8). Never a recreation; a fault keeps the old sizes, never a crash.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val old = textMetrics ?: return
        val root = content ?: return
        if (!FontScaleRefresh.changed(textFontScale, newConfig.fontScale)) return
        try {
            FontScaleRefresh.rescale(root, old, emptyList())
            textMetrics = FontScaleRefresh.snapshot(resources.displayMetrics)
            textFontScale = newConfig.fontScale
            glance?.refit()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "The font scale couldn't be followed: ${e.javaClass.simpleName}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Design 32: a start onto the resumed lock (the ScreenOn backstop) pauses it only to deliver this.
        PinLockRuntime.onLockNewIntent()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (PinLockRuntime.mode != LockMode.LOCKED) {
            leave()
            return
        }
        PinLockRuntime.onLockResumed(this)
        // Design 59: the glance as Android's face (§3.3) - it may leave here, on Android's verdict.
        if (resumeAsFace()) return
        glance?.resume()
        // Design 59d §8.16: a home gesture got through and brought the lock back - ask for the unlock now, as the
        // handle's swipe does (not under the VoIP ring card, not with an ask open). After the views' resume, as a swipe's.
        if (PinLockRuntime.takeGestureAsk(this) && askedAt == null && voipRing.root.visibility != View.VISIBLE) {
            handler.post { askAfterGesture(retry = false) }
        }
        ensureLockTask(fallbackDue = false)
        renderVoip()
        handler.removeCallbacks(alarmScreenSend)
        handler.postDelayed(alarmScreenSend, ALARM_SCREEN_SETTLE_MS)
        // Step 11: the lock in front ends the update fence in the new build (lock task not required).
        com.kidslauncher.mdm.server.UpdateFence.onFront(this)
    }

    /**
     * Design 59d step J: the glance page's swipe up sees every touch first ([GlancePanel.swipe]); once it is recognised
     * the views get a cancel (a row's tap, the rows' scroll) and the rest of the touch is the swipe's. A DOWN always
     * reaches the views (and [onUserInteraction]). Every touch is fed, with `covered` while the VoIP ring card is over
     * the page: then no swipe starts, and a row dragged sideways (design r11 §3) springs back.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val panel = glance ?: return super.dispatchTouchEvent(ev)
        return when (panel.swipe(ev, covered = voipRing.root.visibility == View.VISIBLE)) {
            GlancePanel.SwipeTouch.PASS -> super.dispatchTouchEvent(ev)
            GlancePanel.SwipeTouch.CONSUME -> true
            GlancePanel.SwipeTouch.CANCEL_VIEWS -> {
                val cancel = android.view.MotionEvent.obtain(ev)
                cancel.action = android.view.MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                true
            }
        }
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(voipAnswerCheck)
        handler.removeCallbacks(alarmScreenSend)
        // A pause after a re-send: the screen came up - no check (the runtime's key stays until the next send's).
        handler.removeCallbacks(alarmScreenCheck)
        // 59b §4: the heads-up send (or Svar) came up - the lock left the front.
        PinLockRuntime.voipLockLeft()
        PinLockRuntime.onLockPaused(isFinishing || finishingAfterUnlock)
        super.onPause()
    }

    override fun onStop() {
        WallpaperStore.removeListener(wallpaperListener)
        glance?.stop()
        PinLockRuntime.onLockStopped(isChangingConfigurations, isFinishing || finishingAfterUnlock)
        super.onStop()
    }

    override fun onDestroy() {
        glance?.stop()
        PinLockRuntime.onLockDestroyed()
        PinLockRuntime.removeModeListener(modeListener)
        PinLockRuntime.removeScreenOnListener(alarmScreenOn)
        PinLockRuntime.removeChromeListener(chromeListener)
        VoipCalls.removeListener(voipListener)
        if (PinLockRuntime.androidUnlockHook === androidUnlockHook) PinLockRuntime.androidUnlockHook = null
        handler.removeCallbacksAndMessages(null)
        // Its dialogs go with it (a leaked window fires no dismiss listener).
        openDialogs.clear()
        instances--
        super.onDestroy()
    }

    /**
     * Always in lock task while LOCKED: our package is permitted (kiosk list, or ours + helpers
     * with the kiosk off, set by [LockTaskChrome] before the lock is shown). Never started for a
     * package that isn't permitted - that would ask for screen pinning instead. With the kiosk on
     * the lock is never the root if it can help it (design 16, QA #2, [lockTaskEntry]): it starts
     * Home, whose resume roots lock task and shows the lock again; only [LOCK_FALLBACK_MS] later
     * without lock task does it start it itself. Kiosk off, as Android's face with Android locked, it starts none and
     * stops a running one (design 59b R2: Android's locked keyguard is the gate, and a helper unlocked into isn't cleared
     * at the leave).
     */
    private fun ensureLockTask(fallbackDue: Boolean) {
        handler.removeCallbacks(lockTaskFallback)
        val running = lockTaskRunning()
        val permitted = getSystemService(DevicePolicyManager::class.java)?.isLockTaskPermitted(packageName) == true
        val now = SystemClock.elapsedRealtime()
        val since = homeAskedAtElapsed.takeIf { it > 0L }?.let { now - it }
        // Design 17 (QA #10, qa-16-17-code #6): kiosk off, the lock starts no lock task while a VoIP
        // call rings or lives (not while merely pinned).
        voipHeldLockTask = VoipCalls.phase != VoipPhase.NONE
        waitingForPin = !running && !permitted
        // Nothing runs: this instance roots nothing (a lock task it started has ended) - STOP_OWN and the leave read it.
        if (!running) startedLockTask = false
        val androidLocked = AndroidLock.refrontHeld(this)
        policySeen = entryPolicy()
        when (lockTaskEntry(running, permitted, LockTaskChrome.kioskOn, since, fallbackDue, voipCall = voipHeldLockTask, androidLocked = androidLocked, glanceRoot = startedLockTask)) {
            LockTaskEntry.NONE -> when {
                !running && !permitted -> Log.w(LOG_TAG, "Lock task not permitted for the lock screen - re-front only")
                !running && androidLocked && !voipHeldLockTask && !LockTaskChrome.kioskOn ->
                    Log.i(ANDROID_LOCK_TAG, "lock task: none of the glance's own (kiosk off, Android locked - design 59b R2)")
            }
            LockTaskEntry.STOP_OWN -> {
                Log.i(ANDROID_LOCK_TAG, "lock task: the glance's own stopped (kiosk off, Android locked - design 59b R2)")
                stopLockTaskQuietly()
            }
            LockTaskEntry.START_SELF -> try {
                startLockTask()
                startedLockTask = true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "startLockTask failed", e)
            }
            LockTaskEntry.START_HOME -> {
                homeAskedAtElapsed = now
                HomeFront.bring(this, "to root lock task under the lock")
                handler.postDelayed(lockTaskFallback, LOCK_FALLBACK_MS)
            }
            LockTaskEntry.WAIT_FOR_HOME -> handler.postDelayed(lockTaskFallback, LOCK_FALLBACK_MS)
        }
    }

    private val lockTaskFallback = Runnable {
        if (resumed && PinLockRuntime.mode == LockMode.LOCKED) ensureLockTask(fallbackDue = true)
    }

    private fun lockTaskRunning(): Boolean =
        getSystemService(ActivityManager::class.java)?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun render() {
        if (!::binding.isInitialized) return
        // A glance row's hint (design 25 §1.1), else the plain prompt - for accessibility under the glance page.
        binding.pinPrompt.text = when (glancePrompt(glance?.state?.target)) {
            GlancePrompt.OPEN_APP -> getString(R.string.pin_lock_prompt_open, glance?.promptLabel.orEmpty())
            GlancePrompt.SEE_MISSED -> getString(R.string.pin_lock_prompt_missed)
            GlancePrompt.PLAIN -> getString(R.string.pin_lock_prompt)
        }
    }

    // ---- leaving ----------------------------------------------------------------------------

    /** [checkedAt]: the screen-off count when Android's verdict came - checked again by the runtime's state machine. */
    private fun unlock(checkedAt: Int) {
        finishingAfterUnlock = true
        // Design 25 §1.4: the glance row's target (null after "Lås opp"), with whether the lock is
        // the kiosk's lock-task root - read before the lock task stops.
        pendingOpen = glance?.takeTarget(lockWasRoot = LockTaskChrome.kioskOn && startedLockTask)
        // The lock task first: with the kiosk off the chrome change that follows unpins our
        // package, which would otherwise tear this task down under us.
        stopOwnLockTask()
        PinLockRuntime.unlocked(this, checkedAt)
        leave()
    }

    /**
     * Unlocked or the lock was switched off ([lockLeave]): kiosk off - stop the lock task we
     * started, then go through Home unless a call is live (design 32); kiosk on - always through Home
     * (design 16, QA #5(a)): Home is started *before* the lock finishes, so it sits above any latent
     * task (the stock launcher's preloaded Recents, a BlockedAppActivity in kiosk) and is never
     * finished by it. A refused finish means the lock is the lock-task root after all ([rootLeave]).
     * Not resumed (design 59b step 0, [leaveInFront]): no Home - the glance was stopped under the app Android's own
     * face or pad unlocked into, and its finish exposes nothing latent.
     */
    private fun leave() {
        finishingAfterUnlock = true
        handler.removeCallbacks(lockTaskFallback)
        // Twice in a row (the unlock, then its mode change): once is enough.
        if (isFinishing) return
        val kioskOn = LockTaskChrome.kioskOn
        // Design 59b step 0: as Android's face, stopped under the app the kid unlocked into on SystemUI's face or pad -
        // the glance finishes under it: no Home, no row target (none exists without our ask).
        val inFront = leaveInFront(resumed, fromAsk = leaveFromAsk)
        if (!inFront) pendingOpen = null
        // Design 59d step B: as the face and in front, the page's content goes first - the leave shows only the ground and
        // the mark, then Home with no slide. The leave itself was decided already (faceStep's LEAVE).
        val hides = inFront
        if (hides) glance?.leaving()
        // Design 59d step H: as the face, no Home over a live call (kiosk on too), except as the kiosk root.
        go(lockLeave(kioskOn, startedLockTask, lockTaskRunning(), PinLockRuntime.callLiveNow(this), glanceInFront = inFront))
        if (!isFinishing && kioskOn) {
            Log.w(LOG_TAG, "Finishing the lock was refused - it is the lock-task root; stopping it first")
            go(rootLeave)
        }
        // §8.5: still refused after the root's retry - the content shows again at once.
        if (hides && !isFinishing) glance?.leaveRefused()
    }

    /**
     * The glance row's target opens here (design 25 §1.4): after Home is in front (the lock leaves
     * through Home - design 16, and design 32 with the kiosk off) and before the lock finishes,
     * while its window is still visible - a PendingIntent's sender may start an activity only then.
     * Only when the runtime
     * really unlocked (a refused unlock, the switch-off, the crash guard and onResume's leave have
     * no target, or the mode isn't UNLOCKED). [GlanceOpener] never throws.
     */
    private fun go(plan: LockLeave) {
        if (plan.stopLockTaskFirst) stopLockTaskQuietly()
        // Design 59d step B: as the face, Home comes with no slide (the dark mark still ends, Q59d-7).
        val target = pendingOpen?.takeIf { PinLockRuntime.mode == LockMode.UNLOCKED }
        // PLAN "Home starts at the top after an unlock": Home goes back to the top when it is what the kid sees - not
        // when a row's target opens over it (homeResetDue); a target that opens nothing is Home (Q-3).
        val arrival = lockLeaveArrival(hasTarget = target != null && GlanceOpener.opens(this, target), lockWasRoot = target?.lockWasRoot == true)
        if (plan.homeFirst) HomeFront.bring(this, "the lock left", noAnimation = true, arrival = arrival)
        target?.let { GlanceOpener.open(this, it, homeBelow = plan.homeFirst) }
        pendingOpen = null
        // Design 59d step B: the glance's own finish is unanimated too - no fade, no swipe-away (its content is hidden
        // already; 59b's close fade would fade nothing).
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        if (!isFinishing) finishAndRemoveTask()
    }

    /** Before the unlock's chrome change: kiosk off, that change unpins our package, which would
     * tear this task down under us; kiosk on, only a lock that is the root stops it. A live call
     * changes only Home's start, not the stop. */
    private fun stopOwnLockTask() {
        if (lockLeave(LockTaskChrome.kioskOn, startedLockTask, lockTaskRunning(), callLive = false).stopLockTaskFirst) stopLockTaskQuietly()
    }

    private fun stopLockTaskQuietly() {
        startedLockTask = false
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "stopLockTask failed", e)
        }
    }

    companion object {
        /** Live instances: the uncaught-exception handler counts a crash for the guard only then. */
        @Volatile
        var instances = 0
            private set

        /** When a lock last started Home to root lock task (elapsed realtime, 0 = never) - kept
         * across instances, so a Home that can't root it is asked at most every 3 s. */
        private var homeAskedAtElapsed = 0L
    }
}

/** The lock's background (design 25, "Lock background"): its wallpaper under its black scrim -
 * drawn by us, the window stays opaque (never Android's wallpaper showing through). */
internal fun lockGround(state: WallpaperStore.State): android.graphics.drawable.Drawable =
    com.kidslauncher.mdm.ui.wallpaper.WallpaperRender.GroundDrawable(state.lock.fill, state.lockBitmap, state.lockScrimArgb)

/** qa-25b-25f L2: how long the lock's first frame may wait for its background after a process start. */
internal const val LOCK_GROUND_WAIT_MS = 150L

/** The first frame waits while the background isn't worked out yet, at most [LOCK_GROUND_WAIT_MS]. */
internal fun holdForLockGround(lockReady: Boolean, heldSinceMs: Long, nowMs: Long): Boolean =
    !lockReady && nowMs - heldSinceMs < LOCK_GROUND_WAIT_MS
