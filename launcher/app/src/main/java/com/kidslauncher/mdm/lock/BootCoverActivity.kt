package com.kidslauncher.mdm.lock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import com.kidslauncher.mdm.R

private const val LOG_TAG = "BootCover"

/**
 * The boot cover (design 16b, QA #7-#11): a direct-boot-aware HOME that shows the breathing Vibb
 * mark from the end of the boot animation until our Home can run, instead of the stock launcher.
 * Rules that keep a crash-looping HOME from locking the phone out (QA #10, the BFU rule):
 * - own process `:bootcover` - `Application.onCreate` does nothing there, so nothing of the call
 *   path, enforcement, kiosk, services or tsnet runs in it;
 * - a plain [Activity] with a framework theme, APK resources only: no credential-encrypted storage,
 *   no native code, no lock task, no service, no AppCompat;
 * - a crash counter in device-protected storage: the [COVER_MAX_CRASHES]th crash in a boot
 *   disables the component, so the next HOME resolution picks another one;
 * - it is enabled only from shutdown to the next unlock ([bootCoverEnabled], main process), and
 *   once unlocked and shown for [COVER_MIN_SHOWN_MS] it disables itself (DONT_KILL_APP): the system
 *   finishes it and resolves HOME again - to HomeActivity, HOME-typed (QA #8).
 */
class BootCoverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var shownAtElapsed = -1L
    private var logo: AnimatedVectorDrawable? = null
    private var unlockReceiver: BroadcastReceiver? = null

    private val loop = object : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            handler.postDelayed({ logo?.start() }, BREATH_PAUSE_MS)
        }
    }

    private val handOver = Runnable { disableSelf(this, "unlocked") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCounter(applicationContext)
        if (coverGuardTripped(readCrashes(this), bootCount(this))) {
            disableSelf(this, "crash guard")
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
        if (shownAtElapsed < 0) shownAtElapsed = SystemClock.elapsedRealtime()
        logo?.start()
        scheduleHandOver()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        logo?.stop()
        super.onPause()
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

        private const val GUARD_PREFS = "boot_cover_guard"
        private const val KEY_BOOT = "boot"
        private const val KEY_CRASHES = "crashes"

        @Volatile
        private var counterInstalled = false

        fun component(context: Context) = ComponentName(context, BootCoverActivity::class.java)

        /** Disables the cover (DONT_KILL_APP). Never throws. */
        fun disableSelf(context: Context, why: String) {
            try {
                context.packageManager.setComponentEnabledSetting(
                    component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
                )
                Log.i(LOG_TAG, "Boot cover disabled: $why")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't disable the boot cover ($why)", e)
            }
        }

        private fun guardPrefs(context: Context) =
            context.createDeviceProtectedStorageContext().getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)

        private fun bootCount(context: Context): Int = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            -1
        }

        fun readCrashes(context: Context): CoverCrashes? = try {
            val prefs = guardPrefs(context)
            if (prefs.contains(KEY_BOOT)) CoverCrashes(prefs.getInt(KEY_BOOT, -1), prefs.getInt(KEY_CRASHES, 0)) else null
        } catch (e: Exception) {
            null
        }

        /** In the cover's own process only (Application.onCreate installs nothing there): counts
         * the crash in device-protected storage (commit) and disables the cover at the
         * [COVER_MAX_CRASHES]th of this boot, then lets the crash go on. */
        private fun installCrashCounter(app: Context) {
            if (counterInstalled) return
            counterInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val boot = bootCount(app)
                    val next = coverCrashed(readCrashes(app), boot)
                    guardPrefs(app).edit().putInt(KEY_BOOT, next.bootCount).putInt(KEY_CRASHES, next.crashes).commit()
                    if (coverGuardTripped(next, boot)) disableSelf(app, "crash guard (${next.crashes} crashes)")
                } catch (t: Throwable) {
                    // Never in the way of the crash itself.
                }
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
