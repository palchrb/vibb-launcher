package com.kidslauncher.mdm.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.kidslauncher.mdm.R

/**
 * The boot cover (design 16b, QA #7-#11): a direct-boot-aware HOME that shows the breathing Vibb
 * mark from the end of the boot animation until our Home can run, instead of the stock launcher.
 * Rules that keep a crash-looping HOME from locking the phone out (QA #10, the BFU rule):
 * - own process `:bootcover` - `Application.onCreate` does nothing there, so nothing of the call
 *   path, enforcement, kiosk, services or tsnet runs in it;
 * - a plain [Activity] with a framework theme, APK resources only: no credential-encrypted storage,
 *   no native code, no lock task, no service, no AppCompat;
 * - a crash counter in device-protected storage ([BootCoverGuard], installed by the process's
 *   `Application.onCreate`): the [COVER_MAX_CRASHES]th crash in a boot disables the component and
 *   trips the guard for good, until the server switch goes off and on (qa-16b-code #2);
 * - own mode: it is enabled only from shutdown to the next unlock ([bootCoverEnabled], main process), and
 *   once unlocked and shown for [COVER_MIN_SHOWN_MS] (the boot mark's 3 s, design 16e - the main
 *   process's Home finishes them when it takes over earlier) it disables itself (DONT_KILL_APP) and finishes
 *   as soon as the disable reads back - like AOSP's FallbackHome - so the system resolves HOME again
 *   at once, to HomeActivity, HOME-typed (QA #8, qa-16b-code #1: AMS alone removes it only with the
 *   PACKAGE_CHANGED broadcast, deferred up to 10 s right after boot).
 *
 * Design 59 F3, android mode ([BootCoverGuard.androidMode]): before the PIN the BFU face over the keyguard while the
 * pad gate holds (59d step F, below), else behind it; it is what the PIN's dismissal reveals, and it waits for our main process instead of the 3 s:
 * that process writes its take-over stamp right before the boot's typed Home start (59d step M: the component stays
 * enabled in android mode, for the next boot the shutdown doesn't arm), and the cover leaves once our Home is over it
 * (its stop) - never earlier, since below it may be the stock launcher a boot without `ACTION_SHUTDOWN` started. Its
 * own Home starts disable it first only when our typed HOME would resolve to itself ([coverHomeStartDisablesFirst]).
 * Never stranded: taken over and still in front it starts our Home itself, then leaves [COVER_TAKEOVER_GRACE_MS] after
 * the take-over, and with no take-over
 * [COVER_LAUNCHER_WAIT_MS] after the unlock it hands over by itself and starts our Home ([coverWaitStep]).
 *
 * Design 59d step F (§5, §8.3): while the pad gate holds ([padGate]: the android marker and the user still locked) it
 * is showWhenLocked at runtime ([applyLockedFace]) - the BFU face, the mark on the night ground - and it only *asks*
 * for Android's PIN pad ([askPad], `requestDismissKeyguard`) by [bootPadStep]; after a cancel "Trykk for å låse opp"
 * shows under the mark and a tap asks. It dismisses nothing itself, and once unlocked it drops the flag and runs F3's
 * wait above. Without the gate (own mode, unlocked) it is the F3/16b cover as before.
 */
class BootCoverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var shownAtElapsed = -1L
    private var logo: AnimatedVectorDrawable? = null
    private var unlockReceiver: BroadcastReceiver? = null

    private var resumed = false
    private var shownMarked = false

    /** F3: android mode - wait for our main process ([coverWaitStep]). Read once at create. */
    private var waitForLauncher = false
    private var unlockedAtElapsed = -1L
    private var takenOverAtElapsed = -1L

    /** Step M: created after our main process took over in this boot (the system resolved HOME to the cover once
     * unlocked) - it starts Home at once ([coverWaitStep]'s `lateStart`). Read once at create. */
    private var lateStart = false

    /** It started our Home itself ([CoverStep.START_HOME] or its hand-over): once. */
    private var homeStarted = false

    /** Its own hand-over's disables that didn't read back (F3C-7, [coverHandOverStartsHome]). */
    private var failedDisables = 0

    /** f3-run B2: it finished itself (else the system did - AMS on `PACKAGE_CHANGED`). */
    private var ownFinish = false

    /** f3-run B2: the system finished it while it was in front (paused already finishing, not by its own finish). */
    private var finishedInFront = false
    private val waitTick = Runnable { waitStep(visible = true) }

    /** Step F: the pad rule's state ([bootPadStep]); its count lives in [autoAsksThisProcess]. */
    private var pad = BootPad(autoAsks = autoAsksThisProcess)

    /** M-4: this instance replaces one destroyed for a configuration change ([carriedPad]); its first resume is
     * [PadEvent.RELAUNCHED]. */
    private var relaunched = false

    /** M-1: android mode with a tripped crash guard - enabled as the boot's HOME, but no face, no ask, no mark. */
    private var inert = false

    /** Step F: the current ask's id - an answer of an earlier one is dropped. */
    private var askId = 0

    /** Step F: what [applyLockedFace] last set. */
    private var showsWhenLocked = false

    /** Step F: "Trykk for å låse opp" under the mark. */
    private var tapLine: TextView? = null
    private val padRetry = Runnable { padStep(PadEvent.RETRY) }

    /** F59d-6: the content root (the tap target, its TalkBack label) and the bottom system-bar inset for the line. */
    private var root: FrameLayout? = null
    private var insetBottom = 0

    /** F59d-1: Back on the BFU face does nothing - the cover is HOME, not LAUNCHER, so the default Back finishes it and
     * SystemUI's face shows until HOME resolves to a new cover. Registered only while the flag is on. */
    private val backOnFace = OnBackInvokedCallback { Log.i(LOG_TAG, "Boot cover: Back on the BFU face, held") }

    private val loop = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            // Not while paused or under Home (qa-16b-code #4).
            if (resumed) handler.postDelayed({ if (resumed) logo?.start() }, BREATH_PAUSE_MS)
        }
    }

    private val handOver = Runnable { handOverNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tripped = coverGuardTripped(BootCoverGuard.read(this))
        // M-1: own mode's tripped cover goes away; android mode's stays the boot's HOME, inert (coverInert).
        inert = coverInert(BootCoverGuard.androidMode(this), tripped)
        if (tripped && !inert) {
            BootCoverGuard.disable(this, "crash guard")
            ownFinish = true
            finish()
            return
        }
        if (inert) Log.i(LOG_TAG, "Boot cover inert: the crash guard tripped (android mode) - SystemUI's lock is the face")
        // M-4: a relaunch for a configuration change carries the pad over (no stop, no new automatic ask).
        carriedPad?.let {
            pad = it
            relaunched = true
        }
        carriedPad = null
        val image = ImageView(this).apply {
            if (!inert) setImageResource(R.drawable.splash_vibb_breathe)
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        waitForLauncher = BootCoverGuard.androidMode(this)
        applyLockedFace()
        lateStart = waitForLauncher && getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
            BootCoverGuard.takenOverThisBoot(this)
        logo = image.drawable as? AnimatedVectorDrawable
        logo?.registerAnimationCallback(loop)
        val line = tapLineView()
        tapLine = line
        val content = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(
                line,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    leftMargin = dp(TAP_LINE_SIDE_DP)
                    rightMargin = dp(TAP_LINE_SIDE_DP)
                },
            )
            // Step F: a tap reaching the cover means no pad is over it - ask (bootPadStep; nothing without the gate).
            setOnClickListener { padStep(PadEvent.TAPPED) }
            setOnApplyWindowInsetsListener { _, insets ->
                insetBottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
                placeTapLine()
                insets
            }
        }
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeTapLine() }
        content.addOnLayoutChangeListener(relayout)
        line.addOnLayoutChangeListener(relayout)
        root = content
        setContentView(content)
        faceViews(showsWhenLocked)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applyLockedFace()
                scheduleHandOver()
            }
        }
        unlockReceiver = receiver
        registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (shownAtElapsed < 0) shownAtElapsed = SystemClock.elapsedRealtime()
        if (!shownMarked) {
            shownMarked = true
            // Design 16e: the 3 s count from the cover's first frame in this boot.
            BootCoverGuard.markShown(this, shownAtElapsed)?.let { shownAtElapsed = minOf(shownAtElapsed, it) }
        }
        logo?.start()
        scheduleHandOver()
        // Step F: the gate read again at every resume (the flag drops once unlocked), then the resume's ask, posted
        // past the resume. A retry the pause dropped is posted again.
        applyLockedFace()
        val event = if (relaunched) PadEvent.RELAUNCHED else PadEvent.RESUMED
        relaunched = false
        handler.post { if (resumed) padStep(event) }
        if (pad.retryDue) handler.postDelayed(padRetry, BOOT_PAD_RETRY_MS)
    }

    /** F3: stopped while taken over = our Home (or a call) is over the cover - leaving reveals nothing. */
    override fun onStop() {
        // M-4: a configuration-change relaunch is no stop - neither F3's nor the pad's (onDestroy carries the pad over).
        if (!isChangingConfigurations) {
            if (waitForLauncher && !isFinishing) waitStep(visible = false)
            padStep(PadEvent.STOPPED)
        }
        super.onStop()
    }

    override fun onPause() {
        resumed = false
        if (isFinishing && !ownFinish) finishedInFront = true
        // Stop first: its end callback would post the next breath (qa-16b-code #4).
        logo?.stop()
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** Disable, then finish once the disable reads back - the system re-resolves HOME at once
     * (qa-16b-code #1). Not disabled yet: look again shortly. */
    private fun handOverNow() {
        BootCoverGuard.disable(this, "unlocked")
        if (BootCoverGuard.isDisabled(this)) {
            BootCoverGuard.markHandedOver(this)
            ownFinish = true
            finish()
        } else {
            handler.postDelayed(handOver, HAND_OVER_RETRY_MS)
        }
    }

    override fun onDestroy() {
        // f3-run B2 as amended by step M: on every way out, once unlocked, an own-mode cover is disabled (idempotent);
        // an android-mode one stays enabled for the next unarmed boot. Not on a configuration change (it comes back).
        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (unlocked && !isChangingConfigurations) {
            if (coverDisablesOnDestroy(waitForLauncher, unlocked) && !BootCoverGuard.isDisabled(this)) {
                BootCoverGuard.disable(this, "destroyed, unlocked")
            }
            if (waitForLauncher && !ownFinish) {
                // N2: the system finished it - the recipe's number for that boot.
                val unlockedFor = if (unlockedAtElapsed < 0) -1L else SystemClock.elapsedRealtime() - unlockedAtElapsed
                Log.i(LOG_TAG, "Boot cover finished by the system (${if (finishedInFront) "in front" else "behind"}): $unlockedFor ms after the unlock")
            }
            if (coverDestroyStartsHome(waitForLauncher, unlocked, ownFinish, finishedInFront, homeStarted)) {
                homeStarted = true
                startOwnHome("on its way out")
            }
        }
        if (isChangingConfigurations) carriedPad = pad
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        logo?.unregisterAnimationCallback(loop)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Unlocked: hand over to HomeActivity once shown long enough ([coverHandOverDelayMs]); in android mode the
     * wait for our main process ([waitStep]). */
    private fun scheduleHandOver() {
        if (waitForLauncher) {
            handler.removeCallbacks(waitTick)
            if (resumed) handler.post(waitTick)
            return
        }
        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        val shownFor = if (shownAtElapsed < 0) -1L else SystemClock.elapsedRealtime() - shownAtElapsed
        val delay = coverHandOverDelayMs(unlocked, shownFor) ?: return
        handler.removeCallbacks(handOver)
        handler.postDelayed(handOver, delay)
    }

    /** F3 (android mode): one look at [coverWaitStep]; while in front, again every [COVER_WAIT_POLL_MS]. */
    private fun waitStep(visible: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (unlocked && unlockedAtElapsed < 0) unlockedAtElapsed = now
        // Step M: the main process's stamp for this boot, or its own disable (the 30 s hand-over).
        val takenOver = unlocked && (BootCoverGuard.takenOverThisBoot(this) || BootCoverGuard.isDisabled(this))
        if (takenOver && takenOverAtElapsed < 0) takenOverAtElapsed = now
        val unlockedFor = if (unlockedAtElapsed < 0) 0L else now - unlockedAtElapsed
        val takenOverFor = if (takenOverAtElapsed < 0) 0L else now - takenOverAtElapsed
        // Step F: never showWhenLocked once unlocked - this poll (every 250 ms in front) bounds the receiver's delay.
        if (unlocked) applyLockedFace()
        val callLive = unlocked && telecomInCall()
        when (coverWaitStep(unlocked, takenOver, visible, unlockedFor, takenOverFor, homeStarted, callLive, lateStart)) {
            CoverStep.STAY -> Unit
            CoverStep.FINISH -> {
                Log.i(LOG_TAG, "Boot cover leaves (${if (visible) "taken over, grace over after its Home start" else "taken over, Home over it"}): $unlockedFor ms after the unlock")
                handler.removeCallbacksAndMessages(null)
                ownFinish = true
                finish()
                return
            }
            CoverStep.START_HOME -> {
                // F3C-2: taken over, but no Home came over us (our process died between the take-over and its start,
                // or BootHome waits for a call's end - F3R-1: in front, no call screen is over the cover).
                Log.i(LOG_TAG, "Boot cover starts Home: taken over $takenOverFor ms ago, still in front (call $callLive, late start $lateStart)")
                homeStarted = true
                startOwnHome("taken over, still in front")
            }
            CoverStep.HAND_OVER -> if (!homeStarted) {
                BootCoverGuard.disable(this, "no take-over $unlockedFor ms after the unlock")
                val readBack = BootCoverGuard.isDisabled(this)
                if (coverHandOverStartsHome(readBack, failedDisables)) {
                    BootCoverGuard.markHandedOver(this)
                    homeStarted = true
                    startHome()
                } else {
                    failedDisables++
                }
            }
        }
        if (resumed) {
            handler.removeCallbacks(waitTick)
            handler.postDelayed(waitTick, COVER_WAIT_POLL_MS)
        }
    }

    /**
     * Step F's gate ([bootPadGate]): the android marker - read at create ([waitForLauncher]) and again now - and the user
     * still locked. An unreadable unlock state counts as unlocked (the cover stays inert). Never throws.
     */
    private fun padGate(): Boolean = try {
        bootPadGate(
            androidMarker = waitForLauncher && BootCoverGuard.androidMode(this),
            unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked != false,
            inert = inert,
        )
    } catch (e: Exception) {
        false
    }

    /** Step F: showWhenLocked exactly while [padGate] holds - at create, every resume, the unlock and every pad step;
     * never once the user is unlocked. Runtime only: the manifest has no flag (BootCoverManifestTest). */
    private fun applyLockedFace() {
        val show = padGate()
        faceViews(show)
        if (show == showsWhenLocked) return
        showsWhenLocked = show
        setShowWhenLocked(show)
        holdBack(show)
        Log.i(LOG_TAG, "Boot cover showWhenLocked $show")
    }

    /** F59d-1: Back is held while the cover is the BFU face, and the default (finish) again once it isn't. */
    private fun holdBack(hold: Boolean) {
        try {
            if (hold) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backOnFace)
            } else {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backOnFace)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Boot cover couldn't change its Back: ${e.javaClass.simpleName}")
        }
    }

    /** F59d-3/6: without the gate the line is hidden and the root is no tap target or TalkBack node (own mode, F3's wait
     * after the unlock); with it the root is the tap with the line's words as its label. */
    private fun faceViews(face: Boolean) {
        if (!face) tapLine?.visibility = View.GONE
        root?.apply {
            isClickable = face
            contentDescription = if (face) getString(R.string.boot_cover_tap_to_unlock) else null
            importantForAccessibility = if (face) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** F59d-6: the line under the mark, but above the navigation bar ([tapLineTopPx]). */
    private fun placeTapLine() {
        val content = root ?: return
        val line = tapLine ?: return
        if (content.height <= 0) return
        val top = tapLineTopPx(content.height, line.height, insetBottom, dp(TAP_LINE_OFFSET_DP), dp(TAP_LINE_GAP_DP))
        line.translationY = (top - line.top).toFloat()
    }

    /** Step F: one pass of [bootPadStep] with the gate, the screen and Telecom read now; the Activity only acts on it. */
    private fun padStep(event: PadEvent) {
        val gate = padGate()
        applyLockedFace()
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        val inCall = gate && telecomInCall()
        val step = bootPadStep(pad, event, gate, interactive, inCall)
        pad = step.pad
        autoAsksThisProcess = pad.autoAsks
        tapLine?.visibility = if (gate && pad.line) View.VISIBLE else View.GONE
        when (step.action) {
            PadAction.ASK -> askPad(event)
            PadAction.RETRY_LATER -> {
                handler.removeCallbacks(padRetry)
                handler.postDelayed(padRetry, BOOT_PAD_RETRY_MS)
            }
            PadAction.NONE -> Unit
        }
    }

    /** Step F: ask for Android's PIN pad (the BFU bouncer) - the gate read again first. It only asks; the answer goes
     * back through [padStep], an earlier ask's answer is dropped by its id. */
    private fun askPad(why: PadEvent) {
        if (!padGate()) return
        val id = ++askId
        Log.i(LOG_TAG, "Boot cover asks for Android's PIN pad (${why.name.lowercase()}, automatic ${pad.autoAsks} of $BOOT_PAD_AUTO_ASKS)")
        try {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = padAnswer(id, PadEvent.SUCCEEDED)
                    override fun onDismissCancelled() = padAnswer(id, PadEvent.CANCELLED)
                    override fun onDismissError() = padAnswer(id, PadEvent.ERROR)
                },
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Boot cover's ask failed: ${e.javaClass.simpleName}")
            padAnswer(id, PadEvent.ERROR)
        }
    }

    private fun padAnswer(id: Int, event: PadEvent) {
        if (id != askId || isDestroyed) return
        Log.i(LOG_TAG, "Boot cover: Android's PIN pad ${event.name.lowercase()}")
        padStep(event)
    }

    /** Step F: "Trykk for å låse opp" - APK resources only (the framework font call, no AppCompat). */
    private fun tapLineView() = TextView(this).apply {
        setText(R.string.boot_cover_tap_to_unlock)
        setTextColor(getColor(R.color.kid_ink_dim))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TAP_LINE_SP)
        gravity = Gravity.CENTER
        typeface = runCatching { resources.getFont(R.font.nunito_semibold) }.getOrNull()
        visibility = View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Telecom's view of a call (the cover's process can't see our in-call service): `isInManagedCall` (the lock's
     * rule, VoipCallGuardTest), else the audio mode,
     * else no call - an unreadable answer must not strand the cover (f3-run B5). Never throws. */
    private fun telecomInCall(): Boolean = try {
        getSystemService(android.telecom.TelecomManager::class.java)?.isInManagedCall == true
    } catch (e: SecurityException) {
        try {
            getSystemService(android.media.AudioManager::class.java)?.mode.let {
                it == android.media.AudioManager.MODE_IN_CALL || it == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                    it == android.media.AudioManager.MODE_RINGTONE
            }
        } catch (e2: Exception) {
            false
        }
    } catch (e: Exception) {
        false
    }

    /** Step M: one of its own Home starts that isn't its hand-over (which disables it first anyway): our typed HOME
     * resolves to the first PPA of ours in this boot's order, so it disables itself first unless that is HomeActivity
     * ([coverHomeStartDisablesFirst]); the main process's next apply or take-over enables it again. */
    private fun startOwnHome(why: String) {
        if (waitForLauncher && coverHomeStartDisablesFirst(typedHomeIsHomeActivity())) {
            BootCoverGuard.disable(this, "its own Home start ($why): HOME would resolve to the cover")
        }
        startHome()
    }

    /** Our package's typed HOME resolves to HomeActivity now (`null` = unreadable). The class by name: this process
     * never loads HomeActivity. */
    private fun typedHomeIsHomeActivity(): Boolean? = try {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.name == HOME_ACTIVITY_CLASS
    } catch (e: Exception) {
        null
    }

    /** F3's fallback: the typed HOME start (unlocked, our PPA order fixed or the cover disabled: HomeActivity, which
     * starts our main process). Marked as ours ([HomeFront.EXTRA_BROUGHT]: not the kid's Home press). Never throws. */
    private fun startHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(HomeFront.EXTRA_BROUGHT, true),
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Boot cover couldn't start Home: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val LOG_TAG = "BootCover"

        /** A pause between breaths (the generated animation is one breath, < 1 s). */
        private const val BREATH_PAUSE_MS = 700L

        private const val HAND_OVER_RETRY_MS = 200L

        /** Step F: the line's top below the screen's centre (under the 288 dp mark), its size, its side margins and
         * its least gap above the navigation bar (F59d-6). */
        private const val TAP_LINE_OFFSET_DP = 156
        private const val TAP_LINE_SP = 18f
        private const val TAP_LINE_SIDE_DP = 24
        private const val TAP_LINE_GAP_DP = 16

        /** Step F: automatic asks in this `:bootcover` process (§8.3: per process, in memory) - a new cover instance
         * in the same process carries the count on. Main thread only. */
        private var autoAsksThisProcess = 0

        /** M-4: the pad of an instance destroyed for a configuration change, for its replacement. Main thread only. */
        private var carriedPad: BootPad? = null

        fun component(context: Context) = ComponentName(context, BootCoverActivity::class.java)
    }
}
