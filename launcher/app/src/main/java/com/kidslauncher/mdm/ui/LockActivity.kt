package com.kidslauncher.mdm.ui

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calendar.CalendarStore
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.EmergencyCall
import com.kidslauncher.mdm.lock.ButtonRole
import com.kidslauncher.mdm.lock.LockClockFace
import com.kidslauncher.mdm.lock.RuleCardView
import com.kidslauncher.mdm.lock.holdForLockGround
import com.kidslauncher.mdm.lock.lockGround
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.databinding.ActivityLockBinding
import com.kidslauncher.mdm.databinding.ItemLockRowBinding
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.timerules.TimeAsk
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.ui.home.AppIconLoader
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.home.TONE_SLATE
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore

/**
 * Full-screen block shown while [LockReason] isn't [LockReason.NONE]: a time rule (school,
 * bedtime, a custom rule) or the used-up screen-time budget (handy step 6). Since launcher round L6
 * in the lock glance's look (one design with it): the glance's clock face ([LockClockFace] - the clock,
 * the date, the next alarm) on the lock's ground, the glance's rule card ([RuleCardView] - the kind's
 * glyph, the rule's name, its certain end, and here what still works), the rows of one card - the
 * phone book while calls are managed and allowed, then the rule's exempt apps or, for the budget,
 * the contacts' messaging apps and the music app ([timeLockRows]) - and always Emergency call (the
 * glance's red text button). No code entry (the user, 2026-10-26: the parent lifts a rule from the
 * PWA only, no path on the phone). No timer: the boundary alarm updates `lock_reason`/`lock_key` and
 * this re-renders or finishes. One instance in Home's task: [start] reuses it (E6's L5).
 */
class LockActivity : UIObjectActivity() {
    private lateinit var binding: ActivityLockBinding

    /** The glance's clock face (L6): the clock, the date and the alarm line, placed as the glance's. */
    private lateinit var clockFace: LockClockFace

    /** The next alarm changed (design 42 §1.3): the line under the date again. */
    private val alarmChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = clockFace.bindAlarm()
    }

    /** The lock's ground changed (the parent's lock picture or Home's wallpaper). */
    private val wallpaperListener: () -> Unit = { applyGround() }

    /** E7's M1: an allowed clock's alarm began or stopped ringing, the notification listener came or went, or a silent
     * rule began or stopped silencing alarms - the alarm row (or the clock row) comes or goes. */
    private val alarmListener: () -> Unit = { if (!isFinishing) render() }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** E7's M1: after a PIN unlock the alarm may still ring - once settled ([ALARM_SCREEN_SETTLE_MS]) the resumed screen
     * hands the front to its ringing screen, as the PIN lock does ([PinLockRuntime.sendAlarmScreen]); a pause drops it. */
    private val alarmScreenSend: Runnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            // E7 code QA 3, E7b's L1: a ring this screen (any instance) already sent this screen-on isn't sent again - the
            // kid back from it (Stop, Snooze, Back, Home) lands on the card, the row there for a ring still going.
            val step = PinLockRuntime.sendAlarmScreen(this@LockActivity, typing = false, timeRuleScreen = true)
            if (step == com.kidslauncher.mdm.lock.AlarmScreenStep.LATER) handler.postDelayed(this, com.kidslauncher.mdm.lock.ALARM_SCREEN_SETTLE_MS)
        }
    }

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
        clockFace = LockClockFace(this, binding.root, binding.lockTop, binding.lockClock, binding.lockDate, binding.lockAlarm, logTag = "LockActivity")
        clockFace.follow()
        // The glance's card is information only; here it names the rule, so TalkBack's heading navigation lands on it
        // (the old title's heading; the glance's card stays no heading).
        binding.lockRuleCard.root.isAccessibilityHeading = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        // Every other way to the system dialer is closed during the lock, and a phone without a
        // secure lock screen has no keyguard Emergency button - so this one is always shown,
        // whatever the call state (QA step 4 #1).
        binding.lockEmergencyButton.setOnClickListener { EmergencyCall.confirm(this) }
        // Design 98 §4.3: the dim "···" opens the sheet (Flymodus, Foreldrekode) - a plain tap, never a swipe.
        binding.lockMore.setOnClickListener { openSheet() }
        // TalkBack reads it as a button ("Nødsamtale, knapp"), as the keypad's pill (design 34 P7).
        binding.lockEmergencyButton.accessibilityDelegate = ButtonRole
        // Design 100 §8.4: "Be om mer tid" / "Spør igjen" - the one request path; `askStep` decides again inside, so a
        // stale screen can't ask twice. The listener is set once here; renderAsk makes the pill clickable or not.
        binding.lockAskButton.setOnClickListener {
            if (shownAsk.hasButton) {
                TimeAsk.askNow(this)
                render()
            }
        }
    }

    /** Design 98 §4.3: the lock of the last render, for the sheet's rows. */
    private var shownLock: com.kidslauncher.mdm.timerules.TimeLock? = null
    private var sheetDialog: RuleSheetDialog? = null

    /** Design 98 §4.1: bit 64 with the assistant role (the sheet's full Flymodus row). */
    private fun fullAirplane(): Boolean {
        val mask = (com.kidslauncher.mdm.server.cachedPolicy() as? com.kidslauncher.mdm.server.CachedPolicy.Ok)?.policy?.quickControlsMask ?: 0L
        return mask and com.kidslauncher.mdm.server.QuickControlFeature.AIRPLANE != 0L && com.kidslauncher.mdm.assistant.AirplaneRuntime.roleHeld(this)
    }

    /** Design 98 §4.3: the sheet's rows now ([com.kidslauncher.mdm.assistant.ruleSheet]). */
    private fun sheetNow(): com.kidslauncher.mdm.assistant.RuleSheet {
        val lock = shownLock
        val kind = when {
            lock == null || !lock.locked -> ""
            lock.rules.isEmpty() -> com.kidslauncher.mdm.assistant.RuleSheetLock.BUDGET
            lock.budgetExhausted -> com.kidslauncher.mdm.assistant.RuleSheetLock.RULE_AND_BUDGET
            else -> com.kidslauncher.mdm.assistant.RuleSheetLock.RULE
        }
        return com.kidslauncher.mdm.assistant.ruleSheet(
            kind,
            airplaneRow = fullAirplane() || com.kidslauncher.mdm.assistant.AirplaneRuntime.isOn(this),
            pinConfigured = pinConfigured,
            rulePause = (com.kidslauncher.mdm.server.cachedPolicy() as? com.kidslauncher.mdm.server.CachedPolicy.Ok)?.policy?.rulePause == true,
        )
    }

    private fun openSheet() {
        if (!sheetNow().button || isFinishing) return
        sheetDialog?.dismiss()
        sheetDialog = RuleSheetDialog(this, ::sheetNow, ::fullAirplane) { RulePauseActivity.start(this) }.also { it.show() }
    }

    /** Design 98 §4.3 (Q98-R3-L6): Foreldrekode only with a PIN on the server - the vault's state, read without hashing
     * at each start. */
    private var pinConfigured = false

    private fun readPinConfigured() {
        pinConfigured = try {
            com.kidslauncher.mdm.server.ParentPin.status(this).configured
        } catch (e: Exception) {
            false
        }
    }

    /** Airplane mode or the route changed: the "···" and an open sheet follow. */
    private val airplaneListener: () -> Unit = {
        if (!isFinishing) {
            binding.lockMore.visibility = if (sheetNow().button) View.VISIBLE else View.GONE
            sheetDialog?.render()
        }
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
        ContextCompat.registerReceiver(
            this, alarmChanged, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The glance's ground (design 25, "Lock background"): its picture under its scrim once the store has worked it
        // out, the plain night until then.
        WallpaperStore.addListener(wallpaperListener)
        WallpaperStore.ensureLoaded(this)
        applyGround()
        // As the PIN lock's (qa-25b-25f L2): after a process start the first frame waits for the store (at most
        // LOCK_GROUND_WAIT_MS, `holdForLockGround`) rather than switching from the night to the picture in view.
        if (!WallpaperStore.state.lockReady) {
            var since = 0L
            val tree = binding.root.viewTreeObserver
            tree.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (since == 0L) since = now
                    if (holdForLockGround(WallpaperStore.state.lockReady, since, now)) return false
                    if (tree.isAlive) tree.removeOnPreDrawListener(this)
                    applyGround()
                    return true
                }
            })
        }
        // A fresh look at the clock on top of the boundary alarm; the listener re-renders.
        reevaluateLockReasonFromCache(this)
        // The Kalender row follows Home's tile (the calendars read, the policy's switches).
        CalendarStore.addListener(calendarListener)
        // Design 77 §6.7 (code QA L3): the Meldinger row follows the history and the role.
        com.kidslauncher.mdm.sms.SmsHome.addListener(calendarListener)
        com.kidslauncher.mdm.sms.SmsHome.refresh(this)
        com.kidslauncher.mdm.badges.AlarmNoticeReader.addListener(alarmListener)
        com.kidslauncher.mdm.timerules.ZenRuleRuntime.addListener(alarmListener)
        com.kidslauncher.mdm.assistant.AirplaneRuntime.addListener(airplaneListener)
        // Design 100 §8.4 (Q100-H1): the request's changes - sent, taken, answered - on the main thread.
        com.kidslauncher.mdm.server.AnchorNotice.addListener(askListener)
        readPinConfigured()
        render()
    }

    /** The request for more time changed (design 100 §8.4): the ask block again, from a fresh read (nothing passed). */
    private val askListener: () -> Unit = { if (!isFinishing) render() }

    /** The calendars or Dagen's policy changed: the Kalender row may come or go. */
    private val calendarListener: () -> Unit = { if (!isFinishing) render() }

    /** A redirect brought this instance back ([start]'s SINGLE_TOP, E6's L5): already resumed, onStart doesn't run, so
     * a moved end or budget is drawn here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    /** Design 25 (QA Q1): a glance row the kid tapped on the PIN lock under this rule, handed over
     * by the unlock - its conversation (or the phone book) opens over this screen if the rule
     * leaves it usable, so Back returns here. Else, right after an unlock (its one-shot mark,
     * [markUnlock]), the card's only row opens over this screen as its tap would (PLAN 2026-11-02,
     * [unlockOpenRow]) - Back returns here, and no later resume (Back, Home) opens it again. */
    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(alarmScreenSend)
        handler.postDelayed(alarmScreenSend, com.kidslauncher.mdm.lock.ALARM_SCREEN_SETTLE_MS)
        val handedOver = com.kidslauncher.mdm.lock.GlanceOpener.handOverPending()
        com.kidslauncher.mdm.lock.GlanceOpener.openHandedOver(this)
        val marked = takeUnlockMark() && PinLockRuntime.mode != com.kidslauncher.mdm.lock.LockMode.LOCKED
        if (isFinishing) return
        val only = unlockOpenRow(marked, handedOver, shownRows, shownAsk.hasButton) ?: return
        Log.i("LockActivity", "One thing usable: opened after the unlock (${only.javaClass.simpleName})")
        open(only)
    }

    override fun onPause() {
        handler.removeCallbacks(alarmScreenSend)
        super.onPause()
    }

    override fun onStop() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        ClockFormat.unwatch(this, clockWatch)
        clockWatch = null
        try {
            unregisterReceiver(alarmChanged)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        WallpaperStore.removeListener(wallpaperListener)
        CalendarStore.removeListener(calendarListener)
        com.kidslauncher.mdm.sms.SmsHome.removeListener(calendarListener)
        com.kidslauncher.mdm.badges.AlarmNoticeReader.removeListener(alarmListener)
        com.kidslauncher.mdm.timerules.ZenRuleRuntime.removeListener(alarmListener)
        com.kidslauncher.mdm.assistant.AirplaneRuntime.removeListener(airplaneListener)
        com.kidslauncher.mdm.server.AnchorNotice.removeListener(askListener)
        // Design 98 §4.3/§4.4.1: the sheet never outlives the screen in view (a parent handing the phone back).
        sheetDialog?.dismiss()
        sheetDialog = null
        icons.cancel()
        super.onStop()
    }

    /** The lock's ground, as the glance's ([lockGround]); a fault keeps the plain night. */
    private fun applyGround() {
        try {
            val state = WallpaperStore.state
            if (state.lockReady) binding.root.background = lockGround(state)
        } catch (e: Throwable) {
            Log.w("LockActivity", "The lock's ground couldn't be set: ${e.javaClass.simpleName}")
        }
    }

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
        shownLock = lock
        // Design 98 §4.3: the "···" only with a row to show; an open sheet follows (it closes when the rule ends).
        binding.lockMore.visibility = if (sheetNow().button) View.VISIBLE else View.GONE
        sheetDialog?.render()
        // Design 93 §9.2: the rule the card names - a turned-on rule with a certain end ahead of the kind order - and
        // its end (design r13 D2: the certain end only, as the glance card's).
        val pick = try {
            snap?.let { com.kidslauncher.mdm.lock.cardPick(it) }
        } catch (e: Exception) {
            Log.w("LockActivity", "Couldn't work out the rule's end: ${e.javaClass.simpleName}")
            null
        }
        val rule = pick?.rule ?: lock?.rules?.firstOrNull()
        clockFace.bindAlarm()
        // Emergency call is shown in every state (QA step 4 #1, qa-34-s7-code L1): set on every render,
        // and the only line that touches its visibility (TimeLockModelTest).
        binding.lockEmergencyButton.visibility = View.VISIBLE
        val callsManaged = CallPolicyStore.callsState.managed
        // What still works, as one paragraph: the card's line 3.
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
        // Design r13 D2: the certain end only (`lockEnd`) - never an end the next rule, the budget or the clock would break.
        val end = pick?.end
        // The glance's card (L6): design r13 D1's title (one rule, `ruleLabel`), the kind's glyph, the certain end, and
        // what still works; shown on every render, whatever the rule.
        RuleCardView.bind(
            this, binding.lockRuleCard, glyphRes(lockGlyph(rule?.kind)), labelText(this, ruleLabel(rule?.kind, rule?.name.orEmpty())),
            end, sentences.joinToString(" ").takeIf { it.isNotEmpty() },
        )
        // Design 100 §8.2 (D4): the ask block on the used-up budget's screen only (no rule named).
        renderAsk(
            try {
                snap?.let { TimeAsk.lockAskFor(this, it, ruleInForce = rule != null) }
            } catch (e: Exception) {
                Log.w("LockActivity", "Couldn't work out the request for more time: ${e.javaClass.simpleName}")
                null
            },
        )
        // Kalender (PLAN 2026-11-02): when every rule in force allows it and Home shows its tile (code QA L2: a phone
        // with no calendar set up gets no row, nor an empty Kalender after every unlock).
        val calendar = getString(R.string.day_title).takeIf { lock?.calendarUsable == true && CalendarStore.tileShown() }
        // E7's M1: a ringing alarm's row first, whatever the rule.
        val alarm = getString(R.string.lock_alarm_stop).takeIf { PinLockRuntime.alarmToStop() }
        // Design 77 §6.7: Meldinger where the rule allows calls, while Home shows its tile (design 82 W1, W2: under
        // the parent's name, and not while it is off Home).
        val messages = com.kidslauncher.mdm.appdisplay.AppDisplay.messagesName(this).takeIf { com.kidslauncher.mdm.sms.SmsHome.tileShown() }
        renderRows(
            timeLockRows(callsManaged, lock?.callsAllowed != false, getString(R.string.calls_phone_book), openableApps(rowPackages(lock?.usableApps.orEmpty())), calendar, alarm, messages),
        )
    }

    /** The ask block of the last render, for the tap and the open after an unlock (D6). */
    private var shownAsk: LockAsk? = null

    /**
     * Design 100 §8.3: the ask block - the accent pill "Be om mer tid"; the open request's note in a dim pill (text, not
     * clickable); the answer's line and the ringed "Spør igjen"; or the line and "Du har spurt tre ganger i dag.";
     * gone for `null`. TalkBack: one stop for the note, one for the button ("…, knapp").
     */
    private fun renderAsk(ask: LockAsk?) {
        shownAsk = ask
        val line = binding.lockAskLine
        val button = binding.lockAskButton
        val capped = binding.lockAskCapped
        if (ask == null) {
            binding.lockAsk.visibility = View.GONE
            return
        }
        binding.lockAsk.visibility = View.VISIBLE
        val note = (ask as? LockAsk.Note)?.let { com.kidslauncher.mdm.server.AnchorNotice.noteText(this, it.note) }
        fun pill(text: String, background: Int, color: Int, sizeSp: Float, clickable: Boolean) {
            button.visibility = View.VISIBLE
            // Code QA L2: a live region speaks every text set - only a changed one is set (render runs on many signals).
            if (button.text.toString() != text) button.text = text
            button.setBackgroundResource(background)
            button.setTextColor(ContextCompat.getColor(this, color))
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
            button.isClickable = clickable
            // Code QA L3: always focusable - TalkBack's focus stays on the pill as "Be om mer tid" turns into "Spurt kl.
            // … · sender …" after the tap (read out by its live region), never dropped to the top of the screen.
            button.isFocusable = true
            button.accessibilityDelegate = if (clickable) ButtonRole else null
        }
        when {
            ask == LockAsk.Button -> {
                line.visibility = View.GONE
                capped.visibility = View.GONE
                pill(getString(R.string.anchor_ask_more_time), R.drawable.bg_lock_ask_fill, R.color.kid_ground, 17f, clickable = true)
            }
            ask is LockAsk.Note && ask.again == AgainPart.NONE -> {
                line.visibility = View.GONE
                capped.visibility = View.GONE
                if (note == null) button.visibility = View.GONE else pill(note, R.drawable.bg_lock_ask_dim, R.color.kid_core, 16f, clickable = false)
            }
            ask is LockAsk.Note -> {
                if (line.text.toString() != note.orEmpty()) line.text = note
                line.visibility = if (note == null) View.GONE else View.VISIBLE
                if (ask.again == AgainPart.ASK_AGAIN) {
                    capped.visibility = View.GONE
                    pill(getString(R.string.lock_ask_again), R.drawable.bg_lock_ask_outline, R.color.kid_accent, 17f, clickable = true)
                } else {
                    button.visibility = View.GONE
                    capped.visibility = View.VISIBLE
                }
            }
        }
    }

    /** The rows of the last [renderRows], for the open after an unlock. */
    private var shownRows: List<LockRow> = emptyList()

    /** A row's tap: the phone book or Kalender over this screen, an app in its own task. */
    private fun open(row: LockRow) {
        when (row) {
            // Calls aren't part of the lock unless the rule says so (school): every other app is suspended, but the
            // phone book (our own package) still calls the allowed contacts. With calls unmanaged only Emergency call
            // remains.
            is LockRow.PhoneBook -> startActivity(PhoneBookActivity.intent(this))
            // Its own task (MessagesScreens.listIntent), as Kalender's; Back from it comes back here.
            is LockRow.Messages -> startActivity(com.kidslauncher.mdm.ui.messages.MessagesScreens.listIntent(this))
            // Its own task (`DayActivity.intent`); Back from it comes back here.
            is LockRow.Calendar -> startActivity(com.kidslauncher.mdm.ui.day.DayActivity.intent(this))
            // The clock's own ringing screen (Stop, Snooze); Stop brings this screen back.
            is LockRow.Alarm -> PinLockRuntime.openRingingAlarm(this)
            is LockRow.App -> {
                val launch = packageManager.getLaunchIntentForPackage(row.packageName) ?: return
                try {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w("LockActivity", "Couldn't open ${row.packageName}", e)
                }
            }
        }
    }

    /** E7 code QA 1: the rule's usable apps, and the lock's clock apps while the notification listener isn't connected
     * ([lockRowPackages]). */
    private fun rowPackages(usable: Set<String>): Set<String> =
        lockRowPackages(usable, PinLockRuntime.clockPackages(), com.kidslauncher.mdm.badges.AlarmNoticeReader.listening())

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
        shownRows = rows
        for (row in rows) {
            val view = ItemLockRowBinding.inflate(LayoutInflater.from(this), container, false)
            view.lockRowLabel.text = row.label
            view.root.contentDescription = row.label
            view.root.accessibilityDelegate = ButtonRole
            when (row) {
                is LockRow.PhoneBook -> glyphIcon(view.lockRowIcon, R.color.kid_phone_book, R.drawable.ic_kid_phone_book)
                // Design 82 D9: Home's Meldinger tile, as the parent chose it - read at each bind.
                is LockRow.Messages -> com.kidslauncher.mdm.appdisplay.AppDisplay.messagesLook().let { look ->
                    glyphIcon(view.lockRowIcon, R.color.kid_messages_tile, look.glyph)
                    view.lockRowIcon.backgroundTintList = ColorStateList.valueOf(look.tileColor(this))
                }
                // Home's Kalender tile: the calendar glyph on its colour.
                is LockRow.Calendar -> glyphIcon(view.lockRowIcon, R.color.kid_day_tile, R.drawable.app_glyph_calendar_month)
                is LockRow.Alarm -> glyphIcon(view.lockRowIcon, R.color.kid_lock_alarm_row, R.drawable.app_glyph_alarm)
                is LockRow.App -> {
                    rowIcons[row.packageName] = view.lockRowIcon
                    showAppIcon(view.lockRowIcon, icons.cached(row.packageName))
                }
            }
            view.root.setOnClickListener { open(row) }
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

        /** A [RuleLabel] as the kid reads it: the rule's own name, else its kind's label (design r13 D1). */
        fun labelText(context: Context, label: RuleLabel): String = when (label) {
            is RuleLabel.Named -> label.name
            is RuleLabel.Kind -> context.getString(kindLabelRes(label.kind))
        }

        /** The kind's fallback label: "Skole", "Leggetid", "Skjermfri tid", or the used-up budget's title. */
        fun kindLabelRes(kind: com.kidslauncher.mdm.lock.RuleCardKind): Int = when (kind) {
            com.kidslauncher.mdm.lock.RuleCardKind.SCHOOL -> R.string.lock_kind_school
            com.kidslauncher.mdm.lock.RuleCardKind.BEDTIME -> R.string.lock_kind_bedtime
            com.kidslauncher.mdm.lock.RuleCardKind.RULE -> R.string.lock_kind_rule
            com.kidslauncher.mdm.lock.RuleCardKind.SCREEN_TIME -> R.string.lock_title_screen_time
        }

        /** The kind's icon (design 34 P6): the two new converted Material Symbols, the book, a dot. */
        fun glyphRes(glyph: LockGlyph): Int = when (glyph) {
            LockGlyph.HOURGLASS -> R.drawable.lock_glyph_hourglass
            LockGlyph.MOON -> R.drawable.lock_glyph_bedtime
            LockGlyph.BOOK -> R.drawable.lock_glyph_school
            LockGlyph.DOT -> R.drawable.lock_glyph_dot
        }

        /** When the last unlock marked the time-rule screen's coming up (elapsed realtime; 0 = none, main thread). */
        private var unlockMarkAt = 0L

        /** The kid unlocked under a time rule and this screen is about to come up ([PinLockRuntime]'s after-unlock,
         * own mode and every Android-mode unlock): its next resume may open the only usable thing (PLAN 2026-11-02). */
        fun markUnlock() {
            unlockMarkAt = android.os.SystemClock.elapsedRealtime()
        }

        /** The mark, once: fresh ([unlockMarkFresh]) or not, it is gone after this. */
        private fun takeUnlockMark(): Boolean {
            val at = unlockMarkAt
            unlockMarkAt = 0L
            return unlockMarkFresh(at, android.os.SystemClock.elapsedRealtime())
        }

        /** Starts the time-rule screen. It is never held back by handy's PIN lock (QA 10 #4): while
         * that is LOCKED it is brought back on top right away, so the kid sees the PIN first, then
         * this screen. One instance (E6's L5): a redirect while one is in Home's task reuses it -
         * [startNow]'s CLEAR_TOP | SINGLE_TOP, what it opened above it there (the phone book) closes. */
        fun start(context: Context) {
            // Design 59d step A (qa-59b-code B-1): not showWhenLocked - as Android's face with its keyguard locked it
            // waits for the unlock (it would land behind the keyguard and un-occlude it); at once otherwise.
            com.kidslauncher.mdm.lock.KeyguardCover.afterAndroidUnlock(context, "the time-rule screen") { startNow(it) }
        }

        private fun startNow(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
            // E6's L5: each redirect (the rule's start, Home, Dagen, Kid Settings) stacked another instance in Home's
            // task - the one there is brought back instead (onNewIntent, then onStart's render).
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Design 59b §3: one of our own starts - the face's loss rule doesn't cover it (the glance comes on top next).
            PinLockRuntime.startOwn(context, intent, "the time-rule screen")
            PinLockRuntime.afterTimeRuleShown(context)
        }
    }
}
