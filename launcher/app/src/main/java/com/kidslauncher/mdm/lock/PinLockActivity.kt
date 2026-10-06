package com.kidslauncher.mdm.lock

import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
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
import com.kidslauncher.mdm.server.OfflineOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val LOG_TAG = "PinLockActivity"
private const val STATE_ENTERED = "entered"

/**
 * Handy's own lock screen (step 10, design 10-lock-and-call-ui.md, mockup Lock.dc.html). Shown by
 * [PinLockRuntime] while LOCKED - over every activity, kiosk or not: its own task
 * (`taskAffinity .pinlock`, single task, excluded from recents, portrait), always in lock task
 * while locked (QA 10 #1): with the kiosk on it joins the kiosk's lock task - rooted by Home, never
 * by the lock if it can help it, and it leaves through Home (design 16, [lockTaskEntry],
 * [lockLeave]) - with the kiosk off it starts lock task itself (our package plus the emergency
 * helpers are pinned for it) and stops it on unlock. Back does nothing; leaving the front any other way brings it back (re-front, never
 * giving up, except for our call screen, the system dialer/Telecom and the alarm).
 *
 * Only the keypad, Emergency call and the Parent code link are reachable. The PIN is checked off
 * the main thread (PBKDF2), the failure counted first ([PinLockRuntime.checkPin]). Not
 * direct-boot-aware (it can't be: the kid PIN lives in CE storage).
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
    /** [PinKeypadLayout.COMPACT_STEPS]: how much of the clock/date gave way to the keypad. */
    private var compactStep = 0

    private val modeListener: () -> Unit = {
        if (PinLockRuntime.mode != LockMode.LOCKED && !isFinishing) leave()
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
        val stored = PinLockStore.guard(this)
        val guard = guardOnCreate(stored, System.currentTimeMillis())
        if (guard != stored) PinLockStore.saveGuard(this, guard)
        if (guard.trippedAtMs != null) {
            PinLockRuntime.guardTripped(this)
            finish()
            return
        }
        binding = ActivityPinLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Status bar inset once, plus the layout's own <= 8 dp (fix round 2026-10-06).
        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)
        entered = savedInstanceState?.getString(STATE_ENTERED).orEmpty()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val datePattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        binding.pinDate.format12Hour = datePattern
        binding.pinDate.format24Hour = datePattern

        buildKeypad()
        binding.pinKeypad.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> sizeKeys(bottom - top) }
        binding.pinEmergency.setOnClickListener {
            EmergencyCall.confirm(this) { PinLockRuntime.emergencyFlowStarted() }
        }
        binding.pinParentCode.setOnClickListener { showParentCodeDialog() }
        PinLockRuntime.addModeListener(modeListener)
        render()
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
        ensureLockTask(fallbackDue = false)
        // Step 11: the lock in front ends the update fence in the new build (lock task not required).
        com.kidslauncher.mdm.server.UpdateFence.onFront(this)
        handler.removeCallbacks(waitTicker)
        handler.post(waitTicker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(waitTicker)
        PinLockRuntime.onLockPaused()
        super.onPause()
    }

    override fun onStop() {
        PinLockRuntime.onLockStopped(isChangingConfigurations, isFinishing || finishingAfterUnlock)
        super.onStop()
    }

    override fun onDestroy() {
        PinLockRuntime.removeModeListener(modeListener)
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
        when (lockTaskEntry(running, permitted, LockTaskChrome.kioskOn, since, fallbackDue)) {
            LockTaskEntry.NONE -> if (!running) Log.w(LOG_TAG, "Lock task not permitted for the lock screen - re-front only")
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
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                        setBackgroundResource(R.drawable.bg_pin_key)
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
     * Keys sized to the keypad's measured height ([PinKeypadLayout]): 48-72 dp. Below 48 dp the
     * date, then the clock's size, then the clock give way (one step per layout pass); after
     * that the keys shrink rather than clip ([PinKeypadLayout.finalKeySizePx]). The new
     * params are set with [View.setLayoutParams] - mutating them in place left the measure cache
     * of the rows untouched, so the first 2026-10-06 build kept 72 dp keys and clipped 7-8-9 and 0.
     */
    private fun sizeKeys(keypadHeight: Int) {
        if (keypadHeight <= 0) return
        val density = resources.displayMetrics.density
        var size = PinKeypadLayout.keySizePx(keypadHeight, binding.pinKeypad.width, density)
        if (!PinKeypadLayout.fits(size, density) && compactStep < PinKeypadLayout.COMPACT_STEPS) {
            compactStep++
            applyCompactStep()
            return
        }
        size = PinKeypadLayout.finalKeySizePx(size, density, compactStep >= PinKeypadLayout.COMPACT_STEPS)
        val changed = keys.filter { it.layoutParams.width != size }
        if (changed.isEmpty()) return
        binding.pinKeypad.post {
            for (key in changed) {
                key.layoutParams = key.layoutParams.apply {
                    width = size
                    height = size
                }
            }
        }
    }

    private fun applyCompactStep() {
        binding.pinKeypad.post {
            if (compactStep >= 1) binding.pinDate.visibility = View.GONE
            if (compactStep >= 2) binding.pinClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, PinKeypadLayout.CLOCK_SMALL_SP)
            if (compactStep >= 3) binding.pinClock.visibility = View.GONE
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
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { PinLockRuntime.checkPin(this@PinLockActivity, pin) }
            checking = false
            entered = ""
            when (result) {
                PinLockRuntime.PinResult.Ok -> {
                    unlock()
                    return@launch
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
        binding.pinPrompt.text = when {
            !PinLockRuntime.pinUsable -> getString(R.string.pin_lock_unusable)
            waitMs > 0L -> getString(R.string.pin_lock_wait, backoffText(waitMs))
            checking -> getString(R.string.pin_lock_checking)
            wrongShown -> getString(R.string.pin_lock_wrong)
            else -> getString(R.string.pin_lock_prompt)
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

    private fun showParentCodeDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pin_parent_code, null)
        val input = view.findViewById<EditText>(R.id.parent_code_input)
        val error = view.findViewById<TextView>(R.id.parent_code_error)
        fun showError(text: Int) {
            error.setText(text)
            error.visibility = View.VISIBLE
        }
        val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(R.string.pin_lock_parent_code)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        if (!OfflineOverride.isConfigured()) showError(R.string.pin_lock_parent_not_set)
        // Overridden after show() so a wrong code keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            when {
                !OfflineOverride.isConfigured() -> showError(R.string.pin_lock_parent_not_set)
                OfflineOverride.isLockedOut() -> showError(R.string.pin_lock_parent_locked_out)
                else -> {
                    val code = input.text.toString()
                    button.isEnabled = false
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.Default) { PinLockRuntime.checkParentCode(this@PinLockActivity, code) }
                        button.isEnabled = true
                        if (ok) {
                            dialog.dismiss()
                            unlock()
                        } else {
                            input.text.clear()
                            showError(if (OfflineOverride.isLockedOut()) R.string.pin_lock_parent_locked_out else R.string.pin_lock_parent_wrong)
                        }
                    }
                }
            }
        }
    }

    // ---- leaving ----------------------------------------------------------------------------

    private fun unlock() {
        finishingAfterUnlock = true
        // The lock task first: with the kiosk off the chrome change that follows unpins our
        // package, which would otherwise tear this task down under us.
        stopOwnLockTask()
        PinLockRuntime.unlocked(this)
        leave()
    }

    /**
     * Unlocked or the lock was switched off ([lockLeave]): kiosk off - stop the lock task we
     * started, then go; kiosk on - always through Home (design 16, QA #5(a)): Home is started
     * *before* the lock finishes, so it sits above any latent task (the stock launcher's preloaded
     * Recents, a BlockedAppActivity in kiosk) and is never finished by it. A refused finish means
     * the lock is the lock-task root after all ([rootLeave]).
     */
    private fun leave() {
        finishingAfterUnlock = true
        handler.removeCallbacks(lockTaskFallback)
        // Twice in a row (the unlock, then its mode change): once is enough.
        if (isFinishing) return
        val kioskOn = LockTaskChrome.kioskOn
        go(lockLeave(kioskOn, startedLockTask, lockTaskRunning()))
        if (!isFinishing && kioskOn) {
            Log.w(LOG_TAG, "Finishing the lock was refused - it is the lock-task root; stopping it first")
            go(rootLeave)
        }
    }

    private fun go(plan: LockLeave) {
        if (plan.stopLockTaskFirst) stopLockTaskQuietly()
        if (plan.homeFirst) HomeFront.bring(this, "the lock left")
        if (!isFinishing) finishAndRemoveTask()
    }

    /** Before the unlock's chrome change: kiosk off, that change unpins our package, which would
     * tear this task down under us; kiosk on, only a lock that is the root stops it. */
    private fun stopOwnLockTask() {
        if (lockLeave(LockTaskChrome.kioskOn, startedLockTask, lockTaskRunning()).stopLockTaskFirst) stopLockTaskQuietly()
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
