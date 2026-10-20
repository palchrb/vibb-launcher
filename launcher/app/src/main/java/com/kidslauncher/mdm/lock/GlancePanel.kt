package com.kidslauncher.mdm.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.badges.GlanceLevel
import com.kidslauncher.mdm.badges.GlanceMessageRow
import com.kidslauncher.mdm.badges.GlanceMissedRow
import com.kidslauncher.mdm.badges.GlanceRow
import com.kidslauncher.mdm.badges.GlanceStore
import com.kidslauncher.mdm.badges.GlanceSubtitle
import com.kidslauncher.mdm.badges.glanceSubtitle
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.MissedCallNotifier
import com.kidslauncher.mdm.calls.RelativeDay
import com.kidslauncher.mdm.calls.relativeDay
import com.kidslauncher.mdm.databinding.ViewLockGlanceBinding
import com.kidslauncher.mdm.databinding.ViewLockGlanceRowBinding
import com.kidslauncher.mdm.music.MusicLockPanel
import com.kidslauncher.mdm.torch.Torch
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.home.AppIconLoader
import com.kidslauncher.mdm.ui.home.KidAvatars
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

private const val LOG_TAG = "GlancePanel"

/**
 * The phone's torch as the lock's [TorchPort] (design 41 §1.3, §1.5): a flash unit when `Torch`
 * found one, lit as it reports, and refused while LOCKED when it hides the lock's button. The
 * button's toggle is `Torch.toggle` and nothing else.
 */
internal fun lockTorch(context: Context): TorchPort = object : TorchPort {
    override val hasFlash: Boolean get() = Torch.state != null
    override val lit: Boolean get() = Torch.state?.lit == true
    override val refusedWhileLocked: Boolean get() = Torch.state != null && !Torch.lockButtonShown
    override fun toggle() = Torch.toggle(context.applicationContext)
    override fun addListener(listener: () -> Unit) = Torch.addListener(listener)
    override fun removeListener(listener: () -> Unit) = Torch.removeListener(listener)
}

/**
 * The PIN lock's glance page (design 25, mockup LockGlance.dc.html; the rules are the pure
 * lock/LockGlance.kt and badges/GlanceRows.kt): in the lock's frame over the keypad stage and under
 * the VoIP ring screen. Holds the stage, the rows and the fit; listens to [GlanceStore] and
 * [MissedCallNotifier] between the lock's onStart and onStop. Every time the lock comes up with the
 * screen on ([lockStageDue]) it shows the glance page.
 *
 * It weakens nothing (LockGlanceGuardTest): "Lås opp" and a row lead to the keypad, the music tile's
 * controls are [MusicLockPanel]'s, "Nødsamtale" is the lock's own button ([onEmergency]), the
 * flashlight (design 41 §1.3) only toggles the torch ([torch]), the timer tile (design 42) and a
 * row lead to the keypad - nothing here opens an app, a notification or a setting. A row's tap only takes its target (§1.4: under a
 * time rule only when the rule leaves it usable, QA Q1) and the content intent it would open (kept,
 * never sent here); the unlock takes it ([takeTarget]) and [GlanceOpener] opens it after the code.
 * Every entry point catches everything and logs the exception class only: a glance fault must never
 * trip the lock's crash guard (QA Medium 2).
 */
class GlancePanel(
    private val activity: AppCompatActivity,
    frame: FrameLayout,
    /** The keypad stage under the page: hidden from accessibility while covered. */
    private val keypad: View,
    /** The stage changed: the activity renders the keypad's prompt. */
    private val onStage: () -> Unit,
    /** The flashlight (design 41): the phone's `Torch` ([lockTorch]); `null` = no button. */
    private val torch: TorchPort? = lockTorch(activity),
    private val onEmergency: () -> Unit,
) {
    private val glance = ViewLockGlanceBinding.inflate(activity.layoutInflater, frame, true)

    /** r6 §2: the session's and the timer's callbacks of one frame render the tiles once, alone. */
    private val tileChanges: GlanceCoalescer = GlanceCoalescer({ run -> Choreographer.getInstance().postFrameCallback { run() } }) { tileChanged() }
    private val music = MusicLockPanel(activity, glance) { tileChanges.request() }
    private val clock = ClockLockPanel(activity, glance) { tileChanges.request() }
    private var started = false

    /** The stage (memory only). */
    var state = GlanceStageState.GLANCE
        private set

    /** The target's content intent captured at the tap, and its label for the keypad's hint. */
    private var captured: PendingIntent? = null

    /** The keypad's hint: "Skriv koden for å åpne <this>" (cut to 20 characters, QA Low 5). */
    var promptLabel: String? = null
        private set

    /** `PinLockRuntime.screenOffs` when the stage was last decided (null = not yet). */
    private var decidedAt: Int? = null
    private var tileUp = false

    /** A timer runs (design 42): the timer tile is in the fit. */
    private var clockUp = false
    private var rows: List<GlanceRow> = emptyList()
    private val rowViews = mutableListOf<ViewLockGlanceRowBinding>()

    /** A fit changed the page in this traversal: its frame is skipped (the next one is fitted). */
    private var refitPending = false

    /** The root height the current fit was made for (0 = a new fit is due). */
    private var fitFor = 0
    private var fit: GlanceFit? = null

    /** The shown tiles' heights (px, with their margins) the current fit was made with (null: none). */
    private var fitTilePx: Int? = null
    private var fitClockPx: Int? = null

    /** The next alarm changed (design 42 §1.3): the line under the date again. */
    private val alarmChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    private val labels = HashMap<String, String>()
    /** Home's icons for the rows, shared with the time-rule screen (qa-34-s7-code N4). */
    private val icons = AppIconLoader(activity, KidAvatars.dp(activity, 36f), LOG_TAG)
    private val changed: () -> Unit = { render() }
    private val torchChanged: () -> Unit = { activity.runOnUiThread { guarded("torch") { bindTorch() } } }

    /** The phone's clock format changed (design 34 P12): the clock and the rows' times again. */
    private var clockWatch: ClockFormat.Watch? = null

    init {
        KidInsets.apply(glance.root)
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        val datePattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        glance.lockGlanceDate.format12Hour = datePattern
        glance.lockGlanceDate.format24Hour = datePattern
        glance.lockGlanceUnlock.setOnClickListener { step(GlanceStageEvent.Unlock) }
        glance.lockGlanceEmergency.setOnClickListener { onEmergency() }
        glance.lockGlanceTorch.setOnClickListener { torch?.toggle() }
        glance.lockTimerTile.setOnClickListener { tapClock() }
        openAction(glance.lockTimerTile)
        // The clock app's text, like a row's: only accessibility tools read it.
        sensitive(glance.lockTimerTile)
        // The fit needs the page's height: never before the first layout, again after every change
        // (gesture <-> 3-button navigation, QA Low 4).
        glance.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > 0 && bottom - top != fitFor) guarded("fit") { fitNow() }
        }
        // Bug 25b-B/C: a fit that changed the page skips the frame it was made in - the first frame
        // drawn is the fitted page, never the big clock and tile settling a moment later.
        glance.root.viewTreeObserver.addOnPreDrawListener {
            if (!refitPending) return@addOnPreDrawListener true
            refitPending = false
            false
        }
    }

    /** onStart: watching the rows and the session; the stage decided if the lock comes up now. */
    fun start() = guarded("start") {
        if (!started) {
            started = true
            GlanceStore.addListener(changed)
            MissedCallNotifier.addListener(changed)
            torch?.addListener(torchChanged)
            music.start()
            clock.start()
            ContextCompat.registerReceiver(
                activity, alarmChanged, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            clockWatch = ClockFormat.watch(activity) {
                guarded("clock") {
                    ClockFormat.bind(glance.lockGlanceClock)
                    renderNow()
                }
            }
        }
        resumeNow()
    }

    /** onResume (and onStart): the glance page when the lock comes up with the screen on
     * ([lockStageDue]); otherwise the stage the kid is on (back from the emergency dialer, an alarm,
     * a call). */
    fun resume() = guarded("resume") { resumeNow() }

    private fun resumeNow() {
        if (!started) return
        // Design 34 P12: the clock follows the phone's format, not Vibb's own language.
        ClockFormat.bind(glance.lockGlanceClock)
        val interactive = activity.getSystemService(PowerManager::class.java)?.isInteractive == true
        val offs = PinLockRuntime.screenOffs
        if (lockStageDue(interactive, decidedAt, offs)) {
            decidedAt = offs
            tileUp = false
            state = glanceStageStep(state, GlanceStageEvent.LockUp)
            dropTargetIfGone()
            onStage()
        }
        renderNow()
    }

    /** onStop (and onDestroy): nothing watched, ticking or drawn while the lock isn't shown. */
    fun stop() = guarded("stop") {
        icons.cancel()
        music.stop()
        clock.stop()
        if (!started) return@guarded
        try {
            activity.unregisterReceiver(alarmChanged)
        } catch (e: IllegalArgumentException) {
            // Never registered (start failed before it).
        }
        started = false
        GlanceStore.removeListener(changed)
        MissedCallNotifier.removeListener(changed)
        torch?.removeListener(torchChanged)
        ClockFormat.unwatch(activity, clockWatch)
        clockWatch = null
        labels.clear()
    }

    /** The lock's background behind the page (design 25, "Lock background") - opaque, the page
     * still covers the keypad stage. */
    fun ground(drawable: android.graphics.drawable.Drawable) = guarded("ground") {
        glance.root.background = drawable
    }

    /** The font scale changed under the lock (it handles `fontScale` itself, emulator round N8): the
     * texts were resized by the activity; the fit starts again at the same height. */
    fun refit() = guarded("refit") {
        fitFor = 0
        fit = null
        fitNow()
        glance.root.requestLayout()
    }

    /** The keypad's back arrow: the glance page again. */
    fun back() = step(GlanceStageEvent.Back)

    private fun step(event: GlanceStageEvent) = guarded("stage") {
        state = glanceStageStep(state, event)
        dropTargetIfGone()
        renderNow()
        onStage()
    }

    /** The timer tile's tap (design 42 §1.4): the keypad with the clock app as the target - under a
     * time rule only when the rule leaves it usable, as a row's. Nothing is captured: it opens by its
     * launch intent after the code. */
    private fun tapClock() = guarded("tap") {
        val target = GlanceTarget.ClockApp
        val timeRule = LauncherPreferences.mdm().lockReason() != LockReason.NONE
        val taken = glanceTargetTaken(timeRule, timeRule && GlanceOpener.usableUnderTimeRule(activity, target))
        captured = null
        promptLabel = if (taken) glancePromptLabel(label(target.packageName)) else null
        step(GlanceStageEvent.Row(target.takeIf { taken }))
    }

    private fun dropTargetIfGone() {
        if (state.target != null) return
        captured = null
        promptLabel = null
    }

    /**
     * A row's tap (§1.4): the keypad with the row's target - or, while a time rule locks the phone
     * and leaves the row's app (or calls) unusable, the plain keypad without one (QA Q1).
     */
    private fun tap(row: GlanceRow) = guarded("tap") {
        val target = when (row) {
            is GlanceMessageRow -> GlanceTarget.Conversation(row.conversation, row.notificationKey, row.postTimeMs)
            is GlanceMissedRow -> GlanceTarget.MissedCalls(row.number)
        }
        val timeRule = LauncherPreferences.mdm().lockReason() != LockReason.NONE
        val taken = glanceTargetTaken(timeRule, timeRule && GlanceOpener.usableUnderTimeRule(activity, target))
        val message = row as? GlanceMessageRow
        captured = message?.takeIf { taken }?.let { GlanceStore.contentIntent(it.notificationKey) }
        promptLabel = message?.takeIf { taken }?.let { glancePromptLabel(label(it.packageName)) }
        step(GlanceStageEvent.Row(target.takeIf { taken }))
    }

    /**
     * The unlock (the right code, `PinLockActivity.unlock`): the target, if any, with what was
     * captured - and dropped here, so it is opened once. [lockWasRoot]: read before the lock task stops.
     */
    fun takeTarget(lockWasRoot: Boolean): PendingOpen? = try {
        state.target?.let { PendingOpen(it, captured, lockWasRoot) }.also {
            state = state.copy(target = null)
            captured = null
            promptLabel = null
        }
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Glance target failed: ${e.javaClass.simpleName}")
        null
    }

    /** Re-render (the rows, a store change): never throws. */
    private fun render() = guarded("render") { renderNow() }

    /**
     * A session callback (r6 §2, coalesced per frame): the tile rendered alone - the rows aren't
     * rebound and the clock isn't touched. The page refits only when the tile came or went or the shown
     * tile's height changed (a two-line title, the entry line coming or going; [glanceRefitDue]).
     */
    private fun tileChanged() = guarded("tile") {
        if (!started) return@guarded
        val wasUp = tileUp
        val clockWasUp = clockUp
        tileUp = glanceTile(music.lookUp())
        clockUp = clock.up
        val page = state.stage == LockStage.GLANCE
        music.show(shownTile(), page)
        clock.show(shownClock(), page)
        val width = pageWidth()
        val clockPx = if (fit?.clock == true && width != null) withMargins(glance.lockTimerTile, width).roundToInt() else null
        if (glanceRefitDue(wasUp, tileUp, fitTilePx, shownTilePx()) || glanceRefitDue(clockWasUp, clockUp, fitClockPx, clockPx)) {
            fitFor = 0
            fitNow()
        }
    }

    /** The timer tile while a timer runs and the fit keeps it (before the first fit: shown). */
    private fun shownClock(): Boolean = clockUp && fit?.clock != false

    /** The fit's tile while one is up (the full one before the first fit). */
    private fun shownTile(): GlanceTileSize =
        if (!tileUp) GlanceTileSize.NONE else fit?.tile?.takeIf { it != GlanceTileSize.NONE } ?: GlanceTileSize.FULL

    /** The shown tile's height with its margins at the page's width; null before the page is laid out. */
    private fun shownTilePx(): Int? {
        val width = pageWidth() ?: return null
        val view = when (fit?.tile) {
            GlanceTileSize.FULL -> glance.lockMediaTile
            GlanceTileSize.COMPACT -> glance.lockMediaCompact
            else -> return null
        }
        return withMargins(view, width).roundToInt()
    }

    /** The page's content width once it is laid out and shown, else null. */
    private fun pageWidth(): Int? {
        val root = glance.root
        if (root.height <= 0 || root.visibility != View.VISIBLE) return null
        return (root.width - root.paddingStart - root.paddingEnd).takeIf { it > 0 }
    }

    /** The next alarm under the date (design 42 §1.3): the system clock app's or the pinned clock
     * app's only ([PinLockRuntime.clockPackages]), "Alarm 07:00" - with the weekday more than 24 h
     * away; it goes with the date when the fit drops that. */
    private fun bindAlarm() {
        val next = try {
            activity.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        } catch (e: Exception) {
            null
        }
        val alarm = glanceAlarm(next?.triggerTime, next?.showIntent?.creatorPackage, PinLockRuntime.clockPackages(), System.currentTimeMillis())
        val view = glance.lockGlanceAlarm
        view.text = alarm?.let { activity.getString(R.string.lock_alarm, alarmText(it)) }
        view.visibility = if (alarm != null && fit?.date != false) View.VISIBLE else View.GONE
    }

    private fun alarmText(alarm: GlanceAlarm): String {
        val time = ClockFormat.formatUntil(activity, alarm.atMs)
        if (!alarm.withWeekday) return time
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        val day = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEE"), alarm.atMs).toString().trimEnd('.')
        return "$day $time"
    }

    /** The flashlight button (design 41 §1.3): hidden, off, or lit - a filled peach disc with the
     * ground's glyph. It toggles the torch and opens nothing; its presence never moves the fit (the
     * row is as tall as "Nødsamtale" either way). */
    private fun bindTorch() {
        val t = torch
        val button = glance.lockGlanceTorch
        val look = glanceTorchButton(t?.hasFlash == true, t?.refusedWhileLocked == true, t?.lit == true)
        button.visibility = if (look == GlanceTorchButton.HIDDEN) View.GONE else View.VISIBLE
        if (look == GlanceTorchButton.HIDDEN) return
        val lit = look == GlanceTorchButton.LIT
        button.setBackgroundResource(if (lit) R.drawable.bg_music_play else R.drawable.bg_music_control)
        button.setImageResource(if (lit) R.drawable.torch_glyph_on else R.drawable.torch_glyph_off)
        button.imageTintList = ColorStateList.valueOf(activity.getColor(if (lit) R.color.kid_ground else R.color.kid_core))
        button.stateDescription = activity.getString(if (lit) R.string.lock_torch_on else R.string.lock_torch_off)
    }

    private fun renderNow() {
        val shown = state.stage == LockStage.GLANCE
        glance.root.visibility = if (shown) View.VISIBLE else View.GONE
        keypad.importantForAccessibility =
            if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        tileUp = glanceTile(music.lookUp())
        clockUp = clock.up
        try {
            rows = GlanceStore.rows(MissedCallNotifier.current)
            bindRows()
        } catch (e: Throwable) {
            // A row fault hides the rows; the page, "Lås opp" and "Nødsamtale" stay.
            Log.w(LOG_TAG, "Glance rows failed: ${e.javaClass.simpleName}")
            rows = emptyList()
            rowViews.forEach { it.root.visibility = View.GONE }
        }
        bindTorch()
        bindAlarm()
        // The tile's content first (the fit measures it), then the fit decides which tile shows.
        music.show(shownTile(), page = shown)
        clock.show(shownClock(), page = shown)
        fitFor = 0
        fitNow()
    }

    // ---- rows -----------------------------------------------------------------------------------

    private fun bindRows() {
        val shown = rows.take(GLANCE_MAX_ROWS)
        while (rowViews.size < shown.size) rowViews += newRow(rowViews.size)
        rowViews.forEachIndexed { i, view ->
            val row = shown.getOrNull(i)
            if (row == null) {
                view.root.visibility = View.GONE
            } else {
                when (row) {
                    is GlanceMessageRow -> bindMessage(view, row)
                    is GlanceMissedRow -> bindMissed(view, row)
                }
                view.root.setOnClickListener { tap(row) }
            }
        }
        if (icons.hasMissing) renderIcons()
    }

    private fun newRow(index: Int): ViewLockGlanceRowBinding {
        val view = ViewLockGlanceRowBinding.inflate(activity.layoutInflater, glance.lockGlanceRows, false)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (index > 0) params.topMargin = dp(8f)
        glance.lockGlanceRows.addView(view.root, params)
        // Only accessibility tools read them - TalkBack, and UiAutomation for the UI dumps (§1.2).
        sensitive(view.root)
        return view
    }

    private fun sensitive(view: View) {
        view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        if (view is ViewGroup) for (i in 0 until view.childCount) sensitive(view.getChildAt(i))
    }

    private fun bindMessage(view: ViewLockGlanceRowBinding, row: GlanceMessageRow) {
        val label = label(row.packageName)
        val res = activity.resources
        val messages = res.getQuantityString(R.plurals.glance_messages, row.count, row.count)
        val title = row.title ?: label
        val missed = activity.getString(R.string.glance_missed)
        // A missed call in the app (Element Call, bug 25b-E) - never at APP_ONLY (qa-25b-25f L4).
        val kind = glanceSubtitle(row.level, row.missedCall, row.count, row.text != null)
        val missedShown = kind == GlanceSubtitle.MISSED_CALL || kind == GlanceSubtitle.MISSED_CALL_AND_COUNT
        val subtitle = when (kind) {
            GlanceSubtitle.MISSED_CALL -> "$missed · $label"
            GlanceSubtitle.MISSED_CALL_AND_COUNT -> "$missed · $messages"
            GlanceSubtitle.NOTICES -> res.getQuantityString(R.plurals.glance_notices, row.count, row.count)
            GlanceSubtitle.TEXT -> row.text ?: "$label · $messages"
            GlanceSubtitle.COUNT -> "$label · $messages"
        }
        val time = timeText(row.timeMs)
        view.root.visibility = View.VISIBLE
        view.glanceRowTitle.text = title
        view.glanceRowSubtitle.text = subtitle
        view.glanceRowSubtitle.visibility = View.VISIBLE
        view.glanceRowTime.text = time
        view.glanceRowInitial.visibility = View.GONE
        // The red phone badge on the app's icon for a missed call, as on a phone call's row.
        view.glanceRowBadge.visibility = if (missedShown) View.VISIBLE else View.GONE
        val icon = icons.cached(row.packageName)
        plainIcon(view)
        if (icon != null) {
            view.glanceRowIcon.setImageBitmap(icon)
        } else {
            // A placeholder circle until the background job has drawn Home's icon (QA Low 6).
            view.glanceRowIcon.setImageDrawable(null)
            view.glanceRowIcon.backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_music_control))
        }
        val count = if (row.level == GlanceLevel.APP_ONLY) subtitle else messages
        val spoken = when {
            missedShown && row.title != null -> listOf(label, activity.getString(R.string.glance_missed_from, row.title), messages.takeIf { row.count > 1 }, time)
            missedShown -> listOf(label, missed, messages.takeIf { row.count > 1 }, time)
            else -> listOfNotNull(label, row.title, row.text.takeIf { row.level == GlanceLevel.TEXT }, count, time)
        }
        describe(view, spoken.filterNotNull().distinct().joinToString(", "), activity.getString(R.string.glance_open_action))
    }

    private fun bindMissed(view: ViewLockGlanceRowBinding, row: GlanceMissedRow) {
        val res = activity.resources
        val time = timeText(row.timeMs)
        view.root.visibility = View.VISIBLE
        view.glanceRowTime.text = time
        val number = row.number
        if (number == null) {
            // The plain row (fail-closed, or unmanaged with our dialer role): no names.
            val title = res.getQuantityString(R.plurals.glance_missed_many, row.count, row.count)
            view.glanceRowTitle.text = title
            view.glanceRowSubtitle.visibility = View.GONE
            view.glanceRowInitial.visibility = View.GONE
            view.glanceRowBadge.visibility = View.GONE
            phoneGlyph(view)
            describe(view, "$title, $time", activity.getString(R.string.glance_missed_action))
            return
        }
        val name = row.name ?: number
        view.glanceRowTitle.text = name
        view.glanceRowSubtitle.visibility = View.VISIBLE
        view.glanceRowSubtitle.text = if (row.count == 1) {
            activity.getString(R.string.glance_missed)
        } else {
            res.getQuantityString(R.plurals.glance_missed_many, row.count, row.count)
        }
        view.glanceRowBadge.visibility = View.VISIBLE
        val contact = (CallPolicyStore.effectiveState() as? CallPolicyState.Managed)?.rules?.contacts?.firstOrNull { it.number == number }
        if (contact != null) {
            // The contact's Home avatar: its photo, else the initial on its colour.
            plainIcon(view)
            KidAvatars.bindContact(view.glanceRowIcon, view.glanceRowInitial, contact, isEmergency = false, initialSp = 16f)
        } else {
            view.glanceRowInitial.visibility = View.GONE
            phoneGlyph(view)
        }
        val spoken = if (row.count == 1) {
            activity.getString(R.string.glance_missed_from, name)
        } else {
            res.getQuantityString(R.plurals.missed_calls_from, row.count, row.count, name)
        }
        describe(view, "$spoken, $time", activity.getString(R.string.glance_missed_action))
    }

    /** A row view is reused: every icon path sets the scale and the tints it needs. */
    private fun plainIcon(view: ViewLockGlanceRowBinding) {
        view.glanceRowIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        view.glanceRowIcon.imageTintList = null
        view.glanceRowIcon.backgroundTintList = null
    }

    /** A phone on a red circle: the plain missed-call row, or a number no contact has any more. */
    private fun phoneGlyph(view: ViewLockGlanceRowBinding) {
        view.glanceRowIcon.scaleType = ImageView.ScaleType.CENTER
        view.glanceRowIcon.backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_emergency))
        view.glanceRowIcon.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_ink))
        view.glanceRowIcon.setImageResource(R.drawable.ic_kid_phone)
    }

    /** One TalkBack node per row (§1.2): its description, and the click action named for what the
     * code opens. */
    private fun describe(view: ViewLockGlanceRowBinding, description: String, action: String) {
        view.root.contentDescription = description
        openAction(view.root, action)
    }

    /** The click action TalkBack names ("Lås opp og åpne" - the keypad first). */
    private fun openAction(view: View, action: String = activity.getString(R.string.glance_open_action)) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, action))
            }
        }
    }

    /** The app's name as the kid sees it (the parent's, the kid's rename, the app's own). */
    private fun label(packageName: String): String =
        labels.getOrPut(packageName) { AppDisplay.shownLabel(activity, packageName) }

    /** The time today in the phone's format ([ClockFormat]), "i går" / "yesterday", else `d. MMM` (§1.2). */
    private fun timeText(ms: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        return when (relativeDay(day, LocalDate.now(zone).toEpochDay())) {
            RelativeDay.TODAY -> ClockFormat.formatTime(activity, ms, zone)
            RelativeDay.YESTERDAY -> activity.getString(R.string.glance_yesterday)
            RelativeDay.EARLIER -> DateFormat.format(DateFormat.getBestDateTimePattern(locale, "dMMM"), ms).toString()
        }
    }

    // ---- icons: Home's, drawn off the main thread (QA Low 6) ----------------------------------------

    /** One background job (the shared loader, which catches everything) draws the missing icons,
     * then the rows bind again. */
    private fun renderIcons() {
        if (!started) return
        try {
            icons.load { if (started) render() }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Glance icons failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- the fit (§1.2) ---------------------------------------------------------------------------

    /** Measures every part at the page's width once, picks the first candidate that fits and shows it.
     * Only once the page is laid out; a re-render starts again from candidate 1. */
    private fun fitNow() {
        val root = glance.root
        val width = pageWidth() ?: return
        val height = root.height
        fitFor = height
        val available = (height - root.paddingTop - root.paddingBottom).toFloat()
        val clock = glance.lockGlanceClock
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_BIG_SP)
        val clockBig = measured(clock, width)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_SMALL_SP)
        val clockSmall = measured(clock, width)
        val shownRows = rowViews.take(minOf(rows.size, GLANCE_MAX_ROWS))
        val more = glance.lockGlanceMore
        more.text = activity.resources.getQuantityString(R.plurals.glance_more, 9, 9)
        val heights = GlanceHeights(
            fixed = withMargins(glance.lockGlanceUnlock, width) + withMargins(glance.lockGlanceBottom, width),
            clockBig = clockBig,
            clockSmall = clockSmall,
            // The alarm line goes with the date (design 42 §1.3).
            date = withMargins(glance.lockGlanceDate, width) +
                (if (glance.lockGlanceAlarm.text.isNullOrEmpty()) 0f else withMargins(glance.lockGlanceAlarm, width)),
            tileFull = withMargins(glance.lockMediaTile, width),
            tileCompact = withMargins(glance.lockMediaCompact, width),
            clockFull = withMargins(glance.lockTimerTile, width),
            rowsTop = dp(ROWS_TOP_DP).toFloat(),
            rowsTopNoTile = dp(ROWS_TOP_NO_TILE_DP).toFloat(),
            row = shownRows.maxOfOrNull { measured(it.root, width) } ?: 0f,
            rowGap = dp(8f).toFloat(),
            more = measured(more, width),
        )
        val next = glanceFit(available, heights, tileUp, rows.size, clockUp)
        if (next != fit) {
            Log.i(LOG_TAG, "Glance fit: candidate ${next.candidate}, ${next.rows} rows, ${next.more} more")
            refitPending = true
        }
        fit = next
        fitTilePx = when (next.tile) {
            GlanceTileSize.FULL -> heights.tileFull
            GlanceTileSize.COMPACT -> heights.tileCompact
            GlanceTileSize.NONE -> null
        }?.roundToInt()
        fitClockPx = heights.clockFull.roundToInt().takeIf { next.clock }
        apply(next, shownRows)
    }

    private fun apply(fit: GlanceFit, shownRows: List<ViewLockGlanceRowBinding>) {
        glance.lockGlanceClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fit.bigClock) CLOCK_BIG_SP else CLOCK_SMALL_SP)
        glance.lockGlanceDate.visibility = if (fit.date) View.VISIBLE else View.GONE
        glance.lockGlanceAlarm.visibility = if (fit.date && !glance.lockGlanceAlarm.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        music.show(fit.tile, page = true)
        clock.show(fit.clock, page = true)
        val list = glance.lockGlanceList
        list.visibility = if (fit.rows == 0 && fit.more == 0) View.GONE else View.VISIBLE
        val top = dp(if (fit.tile == GlanceTileSize.NONE && !fit.clock) ROWS_TOP_NO_TILE_DP else ROWS_TOP_DP)
        (list.layoutParams as ViewGroup.MarginLayoutParams).let {
            if (it.topMargin != top) list.layoutParams = it.apply { topMargin = top }
        }
        shownRows.forEachIndexed { i, view -> view.root.visibility = if (i < fit.rows) View.VISIBLE else View.GONE }
        val more = glance.lockGlanceMore
        more.visibility = if (fit.more > 0) View.VISIBLE else View.GONE
        if (fit.more > 0) more.text = activity.resources.getQuantityString(R.plurals.glance_more, fit.more, fit.more)
    }

    private fun measured(view: View, width: Int): Float {
        val wide = view.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, if (wide) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight.toFloat()
    }

    private fun withMargins(view: View, width: Int): Float {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        return measured(view, width) + (params?.topMargin ?: 0) + (params?.bottomMargin ?: 0)
    }

    private fun dp(value: Float): Int = KidAvatars.dp(activity, value)

    /** Every entry point: a glance fault is logged by its class and never reaches the crash guard. */
    private inline fun guarded(what: String, work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Glance $what failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val CLOCK_BIG_SP = 56f
        const val CLOCK_SMALL_SP = 40f
        const val ROWS_TOP_DP = 14f
        const val ROWS_TOP_NO_TILE_DP = 22f
    }
}
