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
 *   once unlocked and shown for [COVER_MIN_SHOWN_MS] it disables itself (DONT_KILL_APP) and finishes
 *   as soon as the disable reads back - like AOSP's FallbackHome - so the system resolves HOME again
 *   at once, to HomeActivity, HOME-typed (QA #8, qa-16b-code #1: AMS alone removes it only with the
 *   PACKAGE_CHANGED broadcast, deferred up to 10 s right after boot).
 */
class BootCoverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var shownAtElapsed = -1L
    private var logo: AnimatedVectorDrawable? = null
    private var unlockReceiver: BroadcastReceiver? = null

    private var resumed = false
    private var shownMarked = false

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
            finish()
            return
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.splash_vibb_breathe)
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
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
            BootCoverGuard.markShown(this)
        }
        logo?.start()
        scheduleHandOver()
    }

    override fun onPause() {
        resumed = false
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
            finish()
        } else {
            handler.postDelayed(handOver, HAND_OVER_RETRY_MS)
        }
    }

    override fun onDestroy() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        logo?.unregisterAnimationCallback(loop)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Unlocked: hand over to HomeActivity once shown long enough ([coverHandOverDelayMs]). */
    private fun scheduleHandOver() {
        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        val shownFor = if (shownAtElapsed < 0) -1L else SystemClock.elapsedRealtime() - shownAtElapsed
        val delay = coverHandOverDelayMs(unlocked, shownFor) ?: return
        handler.removeCallbacks(handOver)
        handler.postDelayed(handOver, delay)
    }

    companion object {
        /** A pause between breaths (the generated animation is one breath, < 1 s). */
        private const val BREATH_PAUSE_MS = 700L

        private const val HAND_OVER_RETRY_MS = 200L

        fun component(context: Context) = ComponentName(context, BootCoverActivity::class.java)
    }
}
