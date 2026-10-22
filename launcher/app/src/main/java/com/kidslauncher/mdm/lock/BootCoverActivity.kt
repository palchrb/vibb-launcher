package com.kidslauncher.mdm.lock

import android.app.Activity
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
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
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
 * - it is enabled only from shutdown to the next unlock ([bootCoverEnabled], main process), and
 *   once unlocked and shown for [COVER_MIN_SHOWN_MS] (the boot mark's 3 s, design 16e - the main
 *   process's Home finishes them when it takes over earlier) it disables itself (DONT_KILL_APP) and finishes
 *   as soon as the disable reads back - like AOSP's FallbackHome - so the system resolves HOME again
 *   at once, to HomeActivity, HOME-typed (QA #8, qa-16b-code #1: AMS alone removes it only with the
 *   PACKAGE_CHANGED broadcast, deferred up to 10 s right after boot).
 *
 * Design 59 F3, android mode ([BootCoverGuard.androidMode]): behind Android's keyguard before the PIN (never
 * showWhenLocked), it is what the PIN's dismissal reveals, and it waits for our main process instead of the 3 s:
 * that process disables it right before the boot's typed Home start, and the cover leaves once our Home is over it
 * (its stop) - never earlier, since below it may be the stock launcher a boot without `ACTION_SHUTDOWN` started.
 * Never stranded: taken over and still in front it starts our Home itself, then leaves [COVER_TAKEOVER_GRACE_MS] after
 * the take-over, and with no take-over
 * [COVER_LAUNCHER_WAIT_MS] after the unlock it hands over by itself and starts our Home ([coverWaitStep]).
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
    private var disabledAtElapsed = -1L

    /** It started our Home itself ([CoverStep.START_HOME] or its hand-over): once. */
    private var homeStarted = false

    /** Its own hand-over's disables that didn't read back (F3C-7, [coverHandOverStartsHome]). */
    private var failedDisables = 0

    /** f3-run B2: it finished itself (else the system did - AMS on `PACKAGE_CHANGED`). */
    private var ownFinish = false

    /** f3-run B2: the system finished it while it was in front (paused already finishing, not by its own finish). */
    private var finishedInFront = false
    private val waitTick = Runnable { waitStep(visible = true) }

    private val loop = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            // Not while paused or under Home (qa-16b-code #4).
            if (resumed) handler.postDelayed({ if (resumed) logo?.start() }, BREATH_PAUSE_MS)
        }
    }

    private val handOver = Runnable { handOverNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (coverGuardTripped(BootCoverGuard.read(this))) {
            BootCoverGuard.disable(this, "crash guard")
            ownFinish = true
            finish()
            return
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.splash_vibb_breathe)
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        waitForLauncher = BootCoverGuard.androidMode(this)
        logo = image.drawable as? AnimatedVectorDrawable
        logo?.registerAnimationCallback(loop)
        setContentView(FrameLayout(this).apply { addView(image, FrameLayout.LayoutParams(-1, -1)) })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = scheduleHandOver()
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
    }

    /** F3: stopped while taken over = our Home (or a call) is over the cover - leaving reveals nothing. */
    override fun onStop() {
        if (waitForLauncher && !isFinishing) waitStep(visible = false)
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
        // f3-run B2: on every way out, once unlocked, the cover is disabled (idempotent) - the one HOME PPA of ours
        // that may resolve once unlocked is HomeActivity's. Not on a configuration change (it comes back).
        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (unlocked && !isChangingConfigurations) {
            if (!BootCoverGuard.isDisabled(this)) BootCoverGuard.disable(this, "destroyed, unlocked")
            if (waitForLauncher && !ownFinish) {
                // N2: the system finished it - the recipe's number for that boot.
                val unlockedFor = if (unlockedAtElapsed < 0) -1L else SystemClock.elapsedRealtime() - unlockedAtElapsed
                Log.i(LOG_TAG, "Boot cover finished by the system (${if (finishedInFront) "in front" else "behind"}): $unlockedFor ms after the unlock")
            }
            if (coverDestroyStartsHome(waitForLauncher, unlocked, ownFinish, finishedInFront, homeStarted)) {
                homeStarted = true
                startHome()
            }
        }
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
        val disabled = unlocked && BootCoverGuard.isDisabled(this)
        if (disabled && disabledAtElapsed < 0) disabledAtElapsed = now
        val unlockedFor = if (unlockedAtElapsed < 0) 0L else now - unlockedAtElapsed
        val disabledFor = if (disabledAtElapsed < 0) 0L else now - disabledAtElapsed
        val callLive = unlocked && telecomInCall()
        when (coverWaitStep(unlocked, disabled, visible, unlockedFor, disabledFor, homeStarted, callLive)) {
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
                Log.i(LOG_TAG, "Boot cover starts Home: taken over $disabledFor ms ago, still in front (call $callLive)")
                homeStarted = true
                startHome()
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

    /** F3's fallback: the typed HOME start (unlocked, the cover disabled: HomeActivity, which starts our main
     * process). Marked as ours ([HomeFront.EXTRA_BROUGHT]: not the kid's Home press). Never throws. */
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

        fun component(context: Context) = ComponentName(context, BootCoverActivity::class.java)
    }
}
