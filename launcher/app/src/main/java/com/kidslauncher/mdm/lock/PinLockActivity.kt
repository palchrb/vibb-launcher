package com.kidslauncher.mdm.lock

import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.app.Dialog
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.EmergencyCall
import com.kidslauncher.mdm.databinding.ActivityPinLockBinding
import com.kidslauncher.mdm.databinding.ViewVoipRingBinding
import com.kidslauncher.mdm.server.ParentPinResult
import com.kidslauncher.mdm.ui.ParentPinDialog
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PinLockActivity"
private const val STATE_ENTERED = "entered"

/**
 * Handy's own lock screen (step 10, design 10-lock-and-call-ui.md, mockup Lock.dc.html). Shown by
 * [PinLockRuntime] while LOCKED - over every activity, kiosk or not: its own task
 * (`taskAffinity .pinlock`, single task, excluded from recents, portrait), always in lock task
 * while locked (QA 10 #1): with the kiosk on it joins the kiosk's lock task - rooted by Home, never
 * by the lock if it can help it, and it leaves through Home (design 16, [lockTaskEntry],
 * [lockLeave]) - with the kiosk off it starts lock task itself (our package plus the emergency
 * helpers are pinned for it) and stops it on unlock, and leaves through Home too unless a call is
 * live (design 32). Back does nothing; leaving the front any other way brings it back (re-front, never
 * giving up, except for our call screen, the system dialer/Telecom and the alarm).
 *
 * It always comes up on its glance page (design 25, [GlancePanel]): the clock, the music tile, the
 * newest missed calls and messages, "Lås opp" and Emergency call; "Lås opp" or a row leads to the
 * keypad stage - the keypad, Emergency call and the Parent code link. The PIN is checked off the
 * main thread (PBKDF2), the failure counted first ([PinLockRuntime.checkPin]). The window is
 * `FLAG_SECURE` on both stages: names, texts and the keys' ripples never reach a screenshot, a
 * recording or a cast. Not direct-boot-aware (it can't be: the kid PIN lives in CE storage).
 */
class PinLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinLockBinding
    private val handler = Handler(Looper.getMainLooper())
    private var entered = ""
    private var checking = false
    private var waitMs = 0L
    private var wrongShown = false
    private var finishingAfterUnlock = false
    private var startedLockTask = false
    private var resumed = false
    private val keys = mutableListOf<View>()

    /** The lock's content root and the metrics its texts were sized at (N8, [FontScaleRefresh]). */
    private var content: FrameLayout? = null
    private var textMetrics: android.util.DisplayMetrics? = null
    private var textFontScale = 0f

    /** The glance page over the keypad (design 25); `null` only if it couldn't be built - the plain
     * keypad then, as before step 4. */
    private var glance: GlancePanel? = null

    /** A glance row's target to open after the right code (design 25 §1.4): set only in [unlock],
     * used and cleared only in [go]. */
    private var pendingOpen: PendingOpen? = null

    private val modeListener: () -> Unit = {
        if (PinLockRuntime.mode != LockMode.LOCKED && !isFinishing) leave()
    }

    /** The last [ensureLockTask] found our package not pinned (kiosk off, the chrome not yet set). */
    private var waitingForPin = false

    /** A chrome pass landed (it runs off the main thread since 16c): with the kiosk off it pins our
     * package for the lock - enter lock task now if the resume found it not permitted. */
    private val chromeListener: () -> Unit = {
        if (waitingForPin && resumed && PinLockRuntime.mode == LockMode.LOCKED) ensureLockTask(fallbackDue = false)
    }

    private lateinit var voipRing: ViewVoipRingBinding
    /** The package the ring screen was filled for. */
    private var voipShownFor: String? = null
    /** A VoIP call held lock task off at the last check (qa-16-17-code #6). */
    private var voipHeldLockTask = false

    /** Design 17b: the app's own ring screen, sent once the lock has been resumed
     * [VOIP_FSI_SETTLE_MS] (the wake activity comes over it right after its first resume). */
    private var voipFsiPending = false
    private val voipFsiSend = Runnable {
        voipFsiPending = false
        if (resumed && openDialogs.isEmpty()) {
            PinLockRuntime.sendVoipRingScreen(this)?.let { recheck -> handler.postDelayed(voipRender, recheck) }
            renderVoip()
        }
    }
    private val voipRender = Runnable { renderVoip() }

    /** Svar went out but the lock never left the front: the start was refused (17b QA #3) - the card
     * says so, and the ring is as before Svar (qa-17b-code #1). */
    private val voipAnswerCheck = Runnable {
        if (resumed && voipRing.root.visibility == View.VISIBLE) {
            voipRing.voipRingError.visibility = View.VISIBLE
            PinLockRuntime.voipAnswerRefused(this)
        }
    }

    /** The lock's own dialogs (Nødsamtale's confirm, Foreldrekode) while open: the app's ring
     * screen is never sent over them (qa-17b-code #3); it goes when the last one closes. */
    private val openDialogs = mutableSetOf<Dialog>()

    private fun trackDialog(dialog: Dialog) {
        openDialogs += dialog
        handler.removeCallbacks(voipFsiSend)
        voipFsiPending = false
        dialog.setOnDismissListener {
            openDialogs -= dialog
            renderVoip()
        }
    }

    // ---- the keyguard cover's dismissal (design 32 step 2, M7; only with the debug switch on) ----------

    /** The first frame since this resume was drawn, and this resume asked once already ([dismissDue]). */
    private var drawnSinceResume = false
    private var dismissTried = false
    private var firstFrameWatch: ViewTreeObserver.OnDrawListener? = null

    /** The state check ([KeyguardCover.check]): [DISMISS_CHECK_MS] after a request, then once a second
     * while resumed. */
    private val keyguardCheck = Runnable { checkKeyguard() }

    /** Logged only (M7): the count goes by state; a success lets the waiting screens go at once. */
    private val dismissCallback = object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() {
            KeyguardCover.dismissCallback(this@PinLockActivity, "succeeded")
            renderVoip()
        }

        override fun onDismissError() = KeyguardCover.dismissCallback(this@PinLockActivity, "error")
        override fun onDismissCancelled() = KeyguardCover.dismissCallback(this@PinLockActivity, "cancelled")
    }

    /** At the resume: watch for the first frame (it is drawn after the display is lit, design 32 §3). */
    private fun watchFirstFrame() {
        stopKeyguardWatch()
        drawnSinceResume = false
        dismissTried = false
        if (!KeyguardCover.switchOn) return
        val decor = window.decorView
        val watch = object : ViewTreeObserver.OnDrawListener {
            private var seen = false
            override fun onDraw() {
                if (seen) return
                seen = true
                // Not from inside onDraw: the frame is out, then the dismissal.
                decor.post { firstFrame(this) }
            }
        }
        firstFrameWatch = watch
        decor.viewTreeObserver.addOnDrawListener(watch)
    }

    private fun firstFrame(watch: ViewTreeObserver.OnDrawListener) {
        if (firstFrameWatch !== watch) return
        stopFirstFrameWatch()
        if (!resumed) return
        drawnSinceResume = true
        if (KeyguardCover.dismissDueNow(this, resumed, drawnSinceResume, dismissTried)) {
            dismissTried = true
            requestDismiss()
        } else {
            handler.removeCallbacks(keyguardCheck)
            handler.postDelayed(keyguardCheck, DISMISS_CHECK_MS)
        }
    }

    /** The only dismissal of the cover's keyguard (besides Home's at boot): after the lock's first frame. */
    private fun requestDismiss() {
        if (KeyguardCover.dismissRequested()) {
            try {
                getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, dismissCallback)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "requestDismissKeyguard failed", e)
            }
        }
        handler.removeCallbacks(keyguardCheck)
        handler.postDelayed(keyguardCheck, DISMISS_CHECK_MS)
    }

    private fun checkKeyguard() {
        // Only while the cover is in force (qa-32-s2-code nit 3): no polling under the fallback or VoIP.
        if (!resumed || !KeyguardCover.coverOn) return
        if (KeyguardCover.check(this, resumed, drawnSinceResume)) {
            requestDismiss()
            return
        }
        // A keyguard that unlocked lets the ring screen go (it waited, [PinLockRuntime.sendVoipRingScreen]).
        renderVoip()
        handler.removeCallbacks(keyguardCheck)
        handler.postDelayed(keyguardCheck, DISMISS_CHECK_MS)
    }

    private fun stopFirstFrameWatch() {
        val watch = firstFrameWatch ?: return
        firstFrameWatch = null
        val observer = window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnDrawListener(watch)
    }

    private fun stopKeyguardWatch() {
        stopFirstFrameWatch()
        handler.removeCallbacks(keyguardCheck)
    }

    private val waitTicker: Runnable = object : Runnable {
        override fun run() {
            val ticker = this
            lifecycleScope.launch {
                waitMs = withContext(Dispatchers.IO) { PinLockRuntime.waitRemaining(this@PinLockActivity) }
                render()
                if (waitMs > 0L) handler.postDelayed(ticker, 1_000L)
            }
        }
    }

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
        // Design 25 §1.2: no screenshot, recording or cast of either stage - before any content.
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
        // The VoIP ring screen (design 17) lies over the keypad while an allowed app's call rings.
        val frame = FrameLayout(this)
        frame.addView(binding.root)
        // The glance page (design 25) between the keypad and the ring screen. Built defensively: a
        // fault here leaves the plain keypad, never a crash that trips the guard.
        glance = try {
            GlancePanel(this, frame, binding.root, ::render) { binding.pinEmergency.performClick() }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "The glance page couldn't be built: ${e.javaClass.simpleName}")
            null
        }
        binding.pinBack.visibility = if (glance != null) View.VISIBLE else View.GONE
        binding.pinBack.setOnClickListener { glance?.back() }
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
        // Emergency call and Parent code stay reachable during a ring (qa-16-17-code #3).
        voipRing.voipRingEmergency.setOnClickListener {
            trackDialog(EmergencyCall.confirm(this) { PinLockRuntime.emergencyFlowStarted() })
        }
        voipRing.voipRingParentCode.setOnClickListener { showParentCodeDialog() }
        // Both Nødsamtale pills announce as a button (qa-34-s8-code L3).
        binding.pinEmergency.accessibilityDelegate = ButtonRole
        voipRing.voipRingEmergency.accessibilityDelegate = ButtonRole
        entered = savedInstanceState?.getString(STATE_ENTERED).orEmpty()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        buildKeypad()
        binding.pinKeypad.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> sizeKeys(bottom - top) }
        binding.pinEmergency.setOnClickListener {
            trackDialog(EmergencyCall.confirm(this) { PinLockRuntime.emergencyFlowStarted() })
        }
        binding.pinParentCode.setOnClickListener { showParentCodeDialog() }
        PinLockRuntime.addModeListener(modeListener)
        PinLockRuntime.addChromeListener(chromeListener)
        VoipCalls.addListener(voipListener)
        render()
        // Design 25, "Lock background": Home's wallpaper (or the parent's lock picture) behind
        // both stages once the store has worked it out; the plain ground until then.
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

    /** The lock's background on the keypad stage and the glance page ([lockGround]). A fault keeps
     * the plain ground - never a crash that trips the guard. */
    private fun applyLockGround() {
        try {
            val state = WallpaperStore.state
            if (!state.lockReady) return
            binding.root.background = lockGround(state)
            glance?.ground(lockGround(state))
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
     * A ring while LOCKED (design 17b, [voipRingUi]): first the app's own ring screen, sent by this
     * lock once it is resumed and settled; the ring card only as the fallback - no full-screen
     * intent, the send threw, nothing came up within [VOIP_FSI_CHECK_MS], or the lock is back during
     * the same ring. Never after Avvis for this ring (qa-16-17-code #3), never over another call,
     * the emergency flow or an alarm (17b QA #5).
     */
    private fun renderVoip() {
        if (!::voipRing.isInitialized) return
        if (resumed && openDialogs.isEmpty() && !voipFsiPending && PinLockRuntime.voipFsiDueNow(this)) {
            voipFsiPending = true
            handler.postDelayed(voipFsiSend, VOIP_FSI_SETTLE_MS)
        }
        val pkg = VoipCalls.ringingPackage
        if (PinLockRuntime.voipRingUiNow(this) != VoipRingUi.CARD || pkg == null) {
            voipRing.root.visibility = View.GONE
            voipRing.voipRingError.visibility = View.GONE
            voipShownFor = null
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
     * change re-sizes its texts in place, then the keys and the glance fit follow (emulator round
     * 2026-10-14 N8). Never a recreation; a fault keeps the old sizes, never a crash.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val old = textMetrics ?: return
        val root = content ?: return
        if (!FontScaleRefresh.changed(textFontScale, newConfig.fontScale)) return
        try {
            FontScaleRefresh.rescale(root, old, keys)
            textMetrics = FontScaleRefresh.snapshot(resources.displayMetrics)
            textFontScale = newConfig.fontScale
            sizeKeys(binding.pinKeypad.height)
            binding.pinKeypad.requestLayout()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ENTERED, entered)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (PinLockRuntime.mode != LockMode.LOCKED) {
            leave()
            return
        }
        PinLockRuntime.onLockResumed(this)
        // Design 32 step 2: the keyguard cover is dismissed after this resume's first frame.
        watchFirstFrame()
        glance?.resume()
        ensureLockTask(fallbackDue = false)
        renderVoip()
        // Step 11: the lock in front ends the update fence in the new build (lock task not required).
        com.kidslauncher.mdm.server.UpdateFence.onFront(this)
        handler.removeCallbacks(waitTicker)
        handler.post(waitTicker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(waitTicker)
        stopKeyguardWatch()
        // 17b: a sent try (or Svar) came up; a pause while one was settling (not our wake
        // activity's) is the app's screen coming up by itself (qa-17b-code #5).
        val sendPending = voipFsiPending
        handler.removeCallbacks(voipFsiSend)
        handler.removeCallbacks(voipAnswerCheck)
        voipFsiPending = false
        PinLockRuntime.voipLockLeft(sendPending)
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
        PinLockRuntime.removeChromeListener(chromeListener)
        VoipCalls.removeListener(voipListener)
        handler.removeCallbacksAndMessages(null)
        instances--
        super.onDestroy()
    }

    /**
     * Always in lock task while LOCKED: our package is permitted (kiosk list, or ours + helpers
     * with the kiosk off, set by [LockTaskChrome] before the lock is shown). Never started for a
     * package that isn't permitted - that would ask for screen pinning instead. With the kiosk on
     * the lock is never the root if it can help it (design 16, QA #2, [lockTaskEntry]): it starts
     * Home, whose resume roots lock task and shows the lock again; only [LOCK_FALLBACK_MS] later
     * without lock task does it start it itself.
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
        when (lockTaskEntry(running, permitted, LockTaskChrome.kioskOn, since, fallbackDue, voipCall = voipHeldLockTask)) {
            LockTaskEntry.NONE -> if (!running && !permitted) Log.w(LOG_TAG, "Lock task not permitted for the lock screen - re-front only")
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

    // ---- keypad -----------------------------------------------------------------------------

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun buildKeypad() {
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del")
        val bold = ResourcesCompat.getFont(this, R.font.nunito_bold)
        labels.chunked(3).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (rowIndex > 0) topMargin = dp(PinKeypadLayout.GAP_DP) }
            }
            for (label in row) {
                val cell = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val key: View = when (label) {
                    "" -> View(this)
                    "del" -> ImageView(this).apply {
                        setImageResource(R.drawable.ic_pin_backspace)
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = getString(R.string.pin_lock_delete)
                        setOnClickListener { onDelete() }
                    }
                    else -> TextView(this).apply {
                        text = label
                        typeface = bold
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(getColor(R.color.kid_ink))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, PinKeypadLayout.DIGIT_SP)
                        // Design 34 P7: the translucent key with its 1 px top highlight on top.
                        background = LayerDrawable(arrayOf(getDrawable(R.drawable.bg_pin_key), PinKeyHighlight(resources.displayMetrics.density)))
                        contentDescription = label
                        setOnClickListener { onDigit(label[0]) }
                    }
                }
                cell.addView(key, FrameLayout.LayoutParams(dp(PinKeypadLayout.MIN_DP), dp(PinKeypadLayout.MIN_DP), Gravity.CENTER))
                if (label.isNotEmpty()) keys += key
                rowView.addView(cell)
            }
            binding.pinKeypad.addView(rowView)
        }
    }

    /**
     * Keys sized to the keypad's measured height ([PinKeypadLayout]): 48-72 dp; with no room for
     * 48 dp the keys shrink rather than clip ([PinKeypadLayout.finalKeySizePx]) - the keypad stage
     * has no clock or date to give way since design 25. The new params are set with
     * [View.setLayoutParams] - mutating them in place left the measure cache of the rows untouched,
     * so the first 2026-10-06 build kept 72 dp keys and clipped 7-8-9 and 0.
     */
    private fun sizeKeys(keypadHeight: Int) {
        if (keypadHeight <= 0) return
        val density = resources.displayMetrics.density
        // A digit never outgrows its key (design 34 P7: at font scale 2.0 the keys give way). The size
        // and the digit are checked apart, so keys that stay at their first 48 dp get the cap too (L1).
        val style = PinKeypadLayout.keyStyle(keypadHeight, binding.pinKeypad.width, density,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, PinKeypadLayout.DIGIT_SP, resources.displayMetrics))
        val stale = keys.filter { PinKeypadLayout.keyStale(it.layoutParams.width, (it as? TextView)?.textSize, style) }
        if (stale.isEmpty()) return
        binding.pinKeypad.post {
            for (key in stale) {
                if (key.layoutParams.width != style.sizePx) {
                    key.layoutParams = key.layoutParams.apply {
                        width = style.sizePx
                        height = style.sizePx
                    }
                }
                if (key is TextView) key.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.digitPx)
            }
        }
    }

    private fun onDigit(digit: Char) {
        if (!keypadEnabled()) return
        wrongShown = false
        entered = appendDigit(entered, digit, PinLockRuntime.pinLength)
        render()
        if (entered.length == PinLockRuntime.pinLength) check(entered)
    }

    private fun onDelete() {
        if (!keypadEnabled()) return
        entered = deleteDigit(entered)
        render()
    }

    private fun keypadEnabled() = !checking && waitMs <= 0L && PinLockRuntime.pinUsable

    private fun check(pin: String) {
        checking = true
        render()
        val checkedAt = PinLockRuntime.screenOffs
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { PinLockRuntime.checkPin(this@PinLockActivity, pin) }
            checking = false
            entered = ""
            when (result) {
                PinLockRuntime.PinResult.Ok -> {
                    // A screen-off during the check: the lock stays (design 24 QA #4) - type again.
                    if (PinLockRuntime.screenOffs == checkedAt) {
                        unlock(checkedAt)
                        return@launch
                    }
                }
                is PinLockRuntime.PinResult.Wrong -> {
                    wrongShown = true
                    waitMs = result.waitMs
                    shake()
                }
                is PinLockRuntime.PinResult.Waiting -> waitMs = result.waitMs
                PinLockRuntime.PinResult.Unusable -> Unit
            }
            render()
            if (waitMs > 0L) {
                handler.removeCallbacks(waitTicker)
                handler.postDelayed(waitTicker, 1_000L)
            }
        }
    }

    private fun shake() {
        ObjectAnimator.ofFloat(binding.pinDots, View.TRANSLATION_X, 0f, 18f, -18f, 12f, -12f, 6f, -6f, 0f)
            .setDuration(400L)
            .start()
    }

    private fun render() {
        if (!::binding.isInitialized) return
        val length = PinLockRuntime.pinLength
        // The usual texts first; else a glance row's hint (design 25 §1.1); else the plain prompt.
        binding.pinPrompt.text = when (glancePrompt(PinLockRuntime.pinUsable, waitMs > 0L, checking, wrongShown, glance?.state?.target)) {
            GlancePrompt.UNUSABLE -> getString(R.string.pin_lock_unusable)
            GlancePrompt.WAIT -> getString(R.string.pin_lock_wait, backoffText(waitMs))
            GlancePrompt.CHECKING -> getString(R.string.pin_lock_checking)
            GlancePrompt.WRONG -> getString(R.string.pin_lock_wrong)
            GlancePrompt.OPEN_APP -> getString(R.string.pin_lock_prompt_open, glance?.promptLabel.orEmpty())
            GlancePrompt.SEE_MISSED -> getString(R.string.pin_lock_prompt_missed)
            GlancePrompt.PLAIN -> getString(R.string.pin_lock_prompt)
        }
        val dots = binding.pinDots
        if (dots.childCount != length) {
            dots.removeAllViews()
            repeat(length) { index ->
                dots.addView(View(this), LinearLayout.LayoutParams(dp(14f), dp(14f)).apply {
                    if (index > 0) marginStart = dp(14f)
                })
            }
        }
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).setBackgroundResource(if (i < entered.length) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty)
        }
        dots.contentDescription = getString(R.string.pin_lock_dots, entered.length, length)
        val enabled = keypadEnabled()
        binding.pinKeypad.alpha = if (enabled) 1f else 0.4f
        keys.forEach { it.isEnabled = enabled }
    }

    // ---- parent code ------------------------------------------------------------------------

    /** The parent PIN through its gate ([ParentPin], design 24): every result in the dialog's
     * error line; no PIN or a running lockout opens it in that state with OK disabled. Typed on our
     * own keypad (design 24b §1.8), kept out of screenshots. */
    private fun showParentCodeDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pin_parent_code, null)
        val keypad = com.kidslauncher.mdm.ui.ParentPinKeypad.bind(view.findViewById(R.id.parent_code_keypad))
        val error = view.findViewById<TextView>(R.id.parent_code_error)
        fun showError(text: String?) {
            error.text = text
            error.visibility = if (text == null) View.GONE else View.VISIBLE
        }
        val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(R.string.pin_lock_parent_code)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        com.kidslauncher.mdm.ui.ParentPinKeypad.secure(dialog)
        dialog.show()
        trackDialog(dialog)
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ParentPinDialog.opening(this, R.string.pin_lock_parent_not_set, R.string.pin_lock_parent_locked_out).let {
            showError(it.text)
            ok.isEnabled = it.okEnabled
            keypad.setEnabled(it.okEnabled)
        }
        // Set after show() so a wrong code keeps the dialog open.
        ok.setOnClickListener {
            val code = keypad.pin
            ok.isEnabled = false
            val checkedAt = PinLockRuntime.screenOffs
            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) { PinLockRuntime.checkParentCode(this@PinLockActivity, code) }
                if (result == ParentPinResult.Ok) {
                    dialog.dismiss()
                    // After a screen-off during the check the lock stays; the use is recorded.
                    if (PinLockRuntime.screenOffs == checkedAt) unlock(checkedAt)
                    return@launch
                }
                if (result is ParentPinResult.Wrong || result is ParentPinResult.LockedOut) keypad.clear()
                showError(
                    ParentPinDialog.resultText(
                        this@PinLockActivity, result,
                        wrong = R.string.pin_lock_parent_wrong,
                        notConfigured = R.string.pin_lock_parent_not_set,
                        lockedOut = R.string.pin_lock_parent_locked_out,
                    ),
                )
                ok.isEnabled = ParentPinDialog.okAfter(result)
                keypad.setEnabled(ParentPinDialog.okAfter(result))
            }
        }
    }

    // ---- leaving ----------------------------------------------------------------------------

    /** [checkedAt]: the screen-off count when the PIN check started - checked again by the
     * runtime's state machine. */
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
     */
    private fun leave() {
        finishingAfterUnlock = true
        handler.removeCallbacks(lockTaskFallback)
        // Twice in a row (the unlock, then its mode change): once is enough.
        if (isFinishing) return
        val kioskOn = LockTaskChrome.kioskOn
        go(lockLeave(kioskOn, startedLockTask, lockTaskRunning(), PinLockRuntime.callLiveNow(this)))
        if (!isFinishing && kioskOn) {
            Log.w(LOG_TAG, "Finishing the lock was refused - it is the lock-task root; stopping it first")
            go(rootLeave)
        }
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
        if (plan.homeFirst) HomeFront.bring(this, "the lock left")
        pendingOpen?.takeIf { PinLockRuntime.mode == LockMode.UNLOCKED }?.let { GlanceOpener.open(this, it, homeBelow = plan.homeFirst) }
        pendingOpen = null
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
