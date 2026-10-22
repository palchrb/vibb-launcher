package com.kidslauncher.mdm.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.kidslauncher.mdm.R
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.EmergencyCall
import com.kidslauncher.mdm.lock.ButtonRole
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.databinding.ActivityLockBinding
import com.kidslauncher.mdm.databinding.ItemLockRowBinding
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.ParentPin
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.timerules.KIND_BEDTIME
import com.kidslauncher.mdm.timerules.KIND_SCHOOL
import com.kidslauncher.mdm.timerules.TimeRule
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.ui.home.AppIconLoader
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.home.TONE_SLATE

/**
 * Full-screen block shown while [LockReason] isn't [LockReason.NONE]: a time rule (school,
 * bedtime, a custom rule) or the used-up screen-time budget (handy step 6). Since design 34 P6 one
 * calm card: the time, the kind's icon and the rule's name, what still works as rows of one card -
 * the phone book while calls are managed and allowed, then the rule's exempt apps or, for the
 * budget, the contacts' messaging apps and the music app ([timeLockRows]) - and always Emergency
 * call (a red text button) and "Enter unlock code" (a deliberately undisguised entry point for
 * [OfflineOverride]; the PIN is the security boundary, not the button being hard to find). No
 * timer: the boundary alarm updates `lock_reason`/`lock_key` and this re-renders or finishes.
 */
class LockActivity : UIObjectActivity() {
    private lateinit var binding: ActivityLockBinding

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            val keys = LauncherPreferences.mdm().keys()
            if (prefKey == keys.lockReason() || prefKey == keys.lockKey()) {
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Status bar inset once, plus the layout's own <= 8 dp (fix round 2026-10-06).
        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        binding.lockUnlockCodeButton.setOnClickListener { showUnlockCodeDialog() }
        // Every other way to the system dialer is closed during the lock, and a phone without a
        // secure lock screen has no keyguard Emergency button - so this one is always shown,
        // whatever the call state (QA step 4 #1).
        binding.lockEmergencyButton.setOnClickListener { EmergencyCall.confirm(this) }
        // TalkBack reads both as buttons ("Nødsamtale, knapp"), as the keypad's pill (design 34 P7).
        binding.lockEmergencyButton.accessibilityDelegate = ButtonRole
        binding.lockUnlockCodeButton.accessibilityDelegate = ButtonRole
    }

    /** The parent PIN (design 24): checked with the escalating lockout by [ParentPin], which also
     * starts the override; every result shows in the dialog. A wrong code keeps it open - the
     * whole point of a failsafe is not making the parent re-type everything after one typo. */
    private fun showUnlockCodeDialog() {
        ParentPinDialog.show(
            this,
            lifecycleScope,
            ParentPinEntry.OVERRIDE,
            title = R.string.lock_unlock_code_dialog_title,
            notConfigured = R.string.lock_unlock_code_not_configured,
        ) { finish() }
    }

    /** The phone's clock format changed (design 34 P12): the clock and the rule's end again. */
    private var clockWatch: ClockFormat.Watch? = null

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        // The clock follows the phone's format (design 34 P12), never Vibb's own language's.
        ClockFormat.bind(binding.lockClock)
        clockWatch = ClockFormat.watch(this) {
            ClockFormat.bind(binding.lockClock)
            render()
        }
        // A fresh look at the clock on top of the boundary alarm; the listener re-renders.
        reevaluateLockReasonFromCache(this)
        render()
    }

    /** Design 25 (QA Q1): a glance row the kid tapped on the PIN lock under this rule, handed over
     * by the unlock - its conversation (or the phone book) opens over this screen if the rule
     * leaves it usable, so Back returns here. */
    override fun onResume() {
        super.onResume()
        com.kidslauncher.mdm.lock.GlanceOpener.openHandedOver(this)
    }

    override fun onStop() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        ClockFormat.unwatch(this, clockWatch)
        clockWatch = null
        icons.cancel()
        super.onStop()
    }

    private fun kindLabel(rule: TimeRule): String = rule.name.takeIf { it.isNotBlank() } ?: getString(
        when (rule.kind) {
            KIND_SCHOOL -> R.string.lock_kind_school
            KIND_BEDTIME -> R.string.lock_kind_bedtime
            else -> R.string.lock_kind_rule
        }
    )

    private fun render() {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) {
            finish()
            return
        }
        val snap = try {
            TimeRulesRuntime.snapshot(this, currentPolicyDecision().policy)
        } catch (e: Exception) {
            Log.w("LockActivity", "Couldn't evaluate the time rules", e)
            null
        }
        val lock = snap?.lock
        val rule = lock?.rules?.firstOrNull()
        binding.lockTitle.text = when {
            rule != null -> kindLabel(rule)
            else -> getString(R.string.lock_title_screen_time)
        }
        binding.lockKindIcon.setImageResource(glyphRes(lockGlyph(rule?.kind)))
        // Emergency call is shown in every state (QA step 4 #1, qa-34-s7-code L1): set on every render,
        // and the only line that touches its visibility (TimeLockModelTest).
        binding.lockEmergencyButton.visibility = View.VISIBLE
        val callsManaged = CallPolicyStore.state.managed
        // What still works, as one paragraph; the rule's end on its own line above it.
        val sentences = mutableListOf<String>()
        when (val line = budgetLine(rule != null, snap?.budget, lock?.budgetExhausted == true)) {
            is BudgetLine.Used -> sentences += getString(
                R.string.lock_budget_allowance,
                durationText(line.minutes, getString(R.string.duration_unit_hours), getString(R.string.duration_unit_minutes)),
            )
            BudgetLine.NoneToday -> sentences += getString(R.string.lock_budget_none)
            BudgetLine.AlsoUsed -> sentences += getString(R.string.lock_budget_also_used)
            null -> {}
        }
        if (lock?.callsAllowed == false) {
            sentences += getString(R.string.lock_calls_emergency_only)
        } else if (callsManaged) {
            sentences += getString(R.string.lock_calls_allowed)
        }
        val until = lock?.until?.let { getString(R.string.lock_until, ClockFormat.formatUntil(this, it)) }
        binding.lockMessage.text = listOfNotNull(until, sentences.joinToString(" ").takeIf { it.isNotEmpty() })
            .joinToString("\n")
        renderRows(timeLockRows(callsManaged, lock?.callsAllowed != false, getString(R.string.calls_phone_book), openableApps(lock?.usableApps.orEmpty())))
    }

    /** The usable apps that can actually be opened (installed, launchable, not suspended - the
     * allowlist still decides, through AppEnforcer's suspension), with the parent's names (design 14). */
    private fun openableApps(packages: Set<String>): List<LockApp> {
        val pm = packageManager
        return packages.mapNotNull { pkg ->
            if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
            val usable = try {
                // A camera the PIN lock holds counts as usable (it is back at the unlock).
                !com.kidslauncher.mdm.lock.CameraLock.suspendedForLists(pkg, pm.isPackageSuspended(pkg))
            } catch (e: Exception) {
                false
            }
            if (!usable) return@mapNotNull null
            LockApp(pkg, com.kidslauncher.mdm.appdisplay.AppDisplay.shownLabel(this, pkg))
        }
    }

    /** The card: one row per [rows] entry; the card is gone when nothing still works. */
    private fun renderRows(rows: List<LockRow>) {
        val container = binding.lockRows
        container.removeAllViews()
        container.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rowIcons.clear()
        for (row in rows) {
            val view = ItemLockRowBinding.inflate(LayoutInflater.from(this), container, false)
            view.lockRowLabel.text = row.label
            view.root.contentDescription = row.label
            view.root.accessibilityDelegate = ButtonRole
            when (row) {
                is LockRow.PhoneBook -> {
                    glyphIcon(view.lockRowIcon, R.color.kid_phone_book, R.drawable.ic_kid_phone_book)
                    // Calls aren't part of the lock unless the rule says so (school): every other
                    // app is suspended, but the phone book (our own package) still calls the
                    // allowed contacts. With calls unmanaged only Emergency call remains.
                    view.root.setOnClickListener { startActivity(PhoneBookActivity.intent(this)) }
                }
                is LockRow.App -> {
                    rowIcons[row.packageName] = view.lockRowIcon
                    showAppIcon(view.lockRowIcon, icons.cached(row.packageName))
                    view.root.setOnClickListener {
                        val launch = packageManager.getLaunchIntentForPackage(row.packageName) ?: return@setOnClickListener
                        try {
                            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Log.w("LockActivity", "Couldn't open ${row.packageName}", e)
                        }
                    }
                }
            }
            container.addView(view.root)
        }
        icons.load(::iconsDrawn)
    }

    private fun glyphIcon(icon: ImageView, colour: Int, glyph: Int) {
        icon.backgroundTintList = ColorStateList.valueOf(getColor(colour))
        icon.setImageResource(glyph)
        val pad = KidAvatars.dp(this, 8f)
        icon.setPadding(pad, pad, pad, pad)
    }

    // ---- app icons: Home's tone icons, drawn off the main thread (the glance page's loader) --------

    private val icons by lazy { AppIconLoader(this, KidAvatars.dp(this, ROW_ICON_DP), "LockActivity") }

    /** The rows' app icons by package, from the last [renderRows]. */
    private val rowIcons = HashMap<String, ImageView>()

    /** The cached tone icon, or a slate disc until the loader has drawn it. */
    private fun showAppIcon(icon: ImageView, bitmap: Bitmap?) {
        icon.setPadding(0, 0, 0, 0)
        if (bitmap != null) {
            icon.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            icon.setImageDrawable(KidAvatars.circular(this, bitmap))
        } else {
            icon.backgroundTintList = ColorStateList.valueOf(TONE_SLATE)
            icon.setImageDrawable(null)
        }
    }

    /** The loader drew [drawn]: only those icons change, in place - the rows stay, so TalkBack's
     * focus and a press in progress survive (qa-34-s7-code L2). */
    private fun iconsDrawn(drawn: Set<String>) {
        for (pkg in drawn) {
            val icon = rowIcons[pkg] ?: continue
            showAppIcon(icon, icons.cached(pkg))
        }
    }

    companion object {
        /** The rows' tone icons, as the mockup's 34 dp. */
        const val ROW_ICON_DP = 34f

        /** The kind's icon (design 34 P6): the two new converted Material Symbols, the book, a dot. */
        fun glyphRes(glyph: LockGlyph): Int = when (glyph) {
            LockGlyph.HOURGLASS -> R.drawable.lock_glyph_hourglass
            LockGlyph.MOON -> R.drawable.lock_glyph_bedtime
            LockGlyph.BOOK -> R.drawable.lock_glyph_school
            LockGlyph.DOT -> R.drawable.lock_glyph_dot
        }

        /** Starts the time-rule screen. It is never held back by handy's PIN lock (QA 10 #4): while
         * that is LOCKED it is brought back on top right away, so the kid sees the PIN first, then
         * this screen. */
        fun start(context: Context) {
            // Design 59d step A (qa-59b-code B-1): not showWhenLocked - as Android's face with its keyguard locked it
            // waits for the unlock (it would land behind the keyguard and un-occlude it); at once otherwise.
            com.kidslauncher.mdm.lock.KeyguardCover.afterAndroidUnlock(context, "the time-rule screen") { startNow(it) }
        }

        private fun startNow(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Design 59b §3: one of our own starts - the face's loss rule doesn't cover it (the glance comes on top next).
            PinLockRuntime.startOwn(context, intent, "the time-rule screen")
            PinLockRuntime.afterTimeRuleShown(context)
        }
    }
}
