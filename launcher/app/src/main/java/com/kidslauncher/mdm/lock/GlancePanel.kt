package com.kidslauncher.mdm.lock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.os.Build
import android.os.Bundle
import android.text.method.TransformationMethod
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.badges.ClockReader
import com.kidslauncher.mdm.badges.GlanceLevel
import com.kidslauncher.mdm.badges.GlanceMessageRow
import com.kidslauncher.mdm.badges.GlanceMissedRow
import com.kidslauncher.mdm.badges.GlanceNameSuffix
import com.kidslauncher.mdm.badges.GlanceRowBadge
import com.kidslauncher.mdm.badges.GlanceSpokenPart
import com.kidslauncher.mdm.badges.GlanceReader
import com.kidslauncher.mdm.badges.GlanceRow
import com.kidslauncher.mdm.badges.GlanceStore
import com.kidslauncher.mdm.badges.GlanceSubtitle
import com.kidslauncher.mdm.badges.glanceNameSuffix
import com.kidslauncher.mdm.badges.glanceRowContact
import com.kidslauncher.mdm.badges.glanceRowBadge
import com.kidslauncher.mdm.badges.glanceSpokenParts
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
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
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
 * the VoIP ring screen. Holds the stage, the rows and the layout (design r11: the page scrolls, nothing is fitted);
 * listens to [GlanceStore] and
 * [MissedCallNotifier] between the lock's onStart and onStop. Every time the lock comes up with the
 * screen on ([lockStageDue]) it shows the glance page.
 *
 * It weakens nothing (LockGlanceGuardTest): the swipe up (design 59d step J, [swipe]) and a row lead to the keypad, the music tile's
 * controls are [MusicLockPanel]'s, "Nødsamtale" is the lock's own button ([onEmergency]), the
 * flashlight (design 41 §1.3) only toggles the torch ([torch]), the timer tile (design 42) and a
 * row lead to the keypad - nothing here opens an app, a notification or a setting. A row's tap only takes its target (§1.4: under a
 * time rule only when the rule leaves it usable, QA Q1) and the content intent it would open (kept,
 * never sent here); the unlock takes it ([takeTarget]) and [GlanceOpener] opens it after the code.
 * The one thing that changes state without the code (design r11 §3, as on Android's own lock screen): a row swiped
 * sideways, or TalkBack's "Fjern" on it, dismisses that row's notification(s) ([commitDismiss]) - nothing else is
 * dismissable (not the music tile, the timer tile, the alarm line or the "more" line), and no new path leaves the lock.
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
    /** Design 59d step B (§8.5): whether this glance is Android's face - the swipe up (step J) and a row lead to the ASK stage
     * ([glanceStageStep]). Read at each stage step, not fixed at construction: the lock activity lives across
     * screen-offs, and an apply may turn the face on or off under it. */
    private val face: () -> Boolean = { false },
    private val onEmergency: () -> Unit,
) {
    private val glance = ViewLockGlanceBinding.inflate(activity.layoutInflater, frame, true)

    /** Design 59d step L: Android's small clock's geometry on this phone, else `null` (the centred clock). */
    private val profile: ClockGeometry? = try {
        val density = activity.resources.displayMetrics.density
        val widthDp = activity.resources.configuration.screenWidthDp
        glanceClockProfile(Build.MODEL, Build.MANUFACTURER, density, widthDp).also {
            // JL-6: a phone with no profile says so once (nothing here is personal).
            if (it == null) Log.i(LOG_TAG, "Glance clock: no profile (model ${Build.MODEL}, maker ${Build.MANUFACTURER}, density $density, width $widthDp dp) - the centred clock")
        }
    } catch (e: Throwable) {
        null
    }

    /**
     * Design 59d §8.13: Android's gesture handle, drawn by us where SystemUI's is (lock task hides SystemUI's while we are
     * LOCKED). A view of its own in the lock's frame, over the page and under the VoIP ring card, on the window's bottom
     * edge ([glanceHandle], [glanceHandleViewHeightDp]); not clickable, so every touch on it reaches the page and its swipe.
     * TalkBack's node for the unlock ([handleAction]).
     */
    private val handle: View = View(activity).also { view ->
        val pill = glanceHandle(profile)
        val density = activity.resources.displayMetrics.density
        val heightPx = (pill.heightDp * density).roundToInt()
        val insetPx = (pill.bottomDp * density).roundToInt()
        val shape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = heightPx / 2f
            setColor(android.graphics.Color.argb((pill.alpha * 255).roundToInt(), 255, 255, 255))
        }
        view.id = R.id.lock_glance_handle
        view.background = android.graphics.drawable.InsetDrawable(shape, 0, insetPx, 0, insetPx)
        view.visibility = View.GONE
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                (pill.widthDp * density).roundToInt(),
                (glanceHandleViewHeightDp(pill) * density).roundToInt(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
    }

    /** r6 §2: the session's and the timer's callbacks of one frame render once - each source's tile
     * alone (qa-r6-b-code L4: the clock app posts every second, the music tile isn't touched for it). */
    private val tileChanges: GlanceCoalescer = GlanceCoalescer({ run -> Choreographer.getInstance().postFrameCallback { run() } }) { tileChanged() }
    private var musicDirty = false
    private var clockDirty = false
    private val music = MusicLockPanel(activity, glance) { musicDirty = true; tileChanges.request() }
    private val clock = ClockLockPanel(activity, glance) { clockDirty = true; tileChanges.request() }
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

    /** A timer runs (design 42): the timer tile shows. */
    private var clockUp = false

    /** The time rule's card (design r13 §1.4), kept from the last re-check's snapshot or the last resume's own; null =
     * no card. [renderNow] binds only from this - a notification's render never computes a snapshot. */
    private var ruleCard: RuleCard? = null

    /** `TimeRulesRuntime.recheck`'s snapshot (main thread, between start and stop): a changed card re-renders. */
    private val lockListener: (TimeRulesRuntime.Snapshot) -> Unit = { snap ->
        guarded("rule card") {
            if (started) {
                val card = ruleCardOf(LauncherPreferences.mdm().lockReason(), snap)
                if (card != ruleCard) {
                    ruleCard = card
                    renderNow()
                }
            }
        }
    }
    private var rows: List<GlanceRow> = emptyList()
    private val rowViews = mutableListOf<ViewLockGlanceRowBinding>()

    /** A layout or a profile placement changed the page in this traversal: its frame is skipped (the next one is laid
     * out). */
    private var refitPending = false

    /** The available height ([available]) the current layout was placed for (0 = a new placement is due). */
    private var placedFor = 0
    private var laidOut: GlanceLayout? = null

    /** The available height the last log line named (it is written when this or the layout changes). */
    private var loggedFor = 0

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
        val datePattern = DateFormat.getBestDateTimePattern(locale, glanceDateSkeleton(profile != null))
        glance.lockGlanceDate.format12Hour = datePattern
        glance.lockGlanceDate.format24Hour = datePattern
        profile?.let { profileLook(it, locale) }
        handleAction(handle)
        glance.lockGlanceEmergency.setOnClickListener { onEmergency() }
        glance.lockGlanceTorch.setOnClickListener { torch?.toggle() }
        glance.lockTimerTile.setOnClickListener { tapClock() }
        // The user (2026-10-24): the "more" line leads to the keypad as a row without a target does (Home after the
        // code) - a way to see the rest. It is no row: never dismissable, nothing captured.
        glance.lockGlanceMore.setOnClickListener { tapMore() }
        openAction(glance.lockGlanceMore, activity.getString(R.string.lock_media_unlock))
        glance.lockTimerStop.setOnClickListener { ClockActions.stop(activity, ClockReader.stopIntent) }
        openAction(glance.lockTimerTile)
        // The clock app's text, like a row's: only accessibility tools read it.
        sensitive(glance.lockTimerTile)
        // The layout is placed once the page has its room, and again after every change of the height or of the
        // system bars' padding (r9 §1: edge-to-edge, the bars arrive as padding at the same height - at the screen-on
        // of a lock started dark, a gesture <-> 3-button switch, QA Low 4; the bars move the profile's clock, r11 §1.2).
        glance.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > 0 && available(bottom - top) != placedFor) guarded("layout") { layoutNow() }
        }
        // Bug 25b-B/C: a layout that changed the page skips the frame it was made in - the first frame
        // drawn is the laid-out page, never the clock and tile settling a moment later.
        glance.root.viewTreeObserver.addOnPreDrawListener {
            if (!refitPending) return@addOnPreDrawListener true
            refitPending = false
            false
        }
    }

    /**
     * Design 59d step L: the clock, the date and the alarm line left-aligned as Android's small clock - Nunito Light for
     * the clock (JL-2), the short date capitalised as Android's ("Fre. 23. okt."), the date's
     * size; where they sit is [placeProfile]'s, at every layout.
     */
    private fun profileLook(p: ClockGeometry, locale: Locale) {
        Log.i(LOG_TAG, "Glance clock: ${p.name} profile")
        val clock = glance.lockGlanceClock
        // JL-2: Android's small clock is thin - Nunito Light (300), bundled for this clock only (not in nunito.xml's family).
        ResourcesCompat.getFont(activity, R.font.nunito_light)?.let { clock.typeface = it }
        val date = glance.lockGlanceDate
        date.transformationMethod = FirstCapital(locale)
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.dateSp)
        for (view in listOf(clock, date, glance.lockGlanceAlarm)) {
            (view.layoutParams as LinearLayout.LayoutParams).let { view.layoutParams = it.apply { gravity = Gravity.START } }
        }
        date.gravity = Gravity.START
        glance.lockGlanceAlarm.gravity = Gravity.START
        // The first digit's ink keeps its place when the hour's first digit changes (its side bearing does).
        clock.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = guarded("clock start") {
                if (glance.root.height > 0) margins(clock, clockStartPx(p), (clock.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
            }
        })
    }

    /** Design 59d step L: the clock's start margin that puts its first digit's ink at the profile's
     * [ClockGeometry.clockInkStartDp] in the window ([startMarginPx] less the glyph's side bearing). */
    private fun clockStartPx(p: ClockGeometry): Int {
        val at = IntArray(2)
        glance.root.getLocationOnScreen(at)
        val contentStart = (at[0] + glance.root.paddingStart + glance.lockGlanceTop.paddingStart).toFloat()
        val clock = glance.lockGlanceClock
        val text = clock.text?.toString().orEmpty()
        val first = android.graphics.Rect()
        if (text.isNotEmpty()) clock.paint.getTextBounds(text, 0, 1, first)
        return startMarginPx(p.clockInkStartDp * activity.resources.displayMetrics.density - first.left, contentStart)
    }

    /** The date's first letter capitalised on screen ([glanceDateCapitalised]); the TextClock's own text is unchanged. */
    private class FirstCapital(private val locale: Locale) : TransformationMethod {
        override fun getTransformation(source: CharSequence?, view: View?): CharSequence? = source?.let { glanceDateCapitalised(it.toString(), locale) }

        override fun onFocusChanged(view: View?, sourceText: CharSequence?, focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) = Unit
    }

    /**
     * Design 59d step L: the clock's and the date's margins so their boxes start and their baselines sit where the
     * profile says, in the window's coordinates ([baselineMarginPx], [startMarginPx]) - the baselines under SystemUI's
     * keyguard status bar ([keyguardBarPx], the glance-topinset round); the alarm line under the date at the date's
     * start. Measured at [width] first; a margin is written only when it changed (no layout loop). Returns whether one
     * was.
     */
    private fun placeProfile(p: ClockGeometry, width: Int): Boolean {
        val density = activity.resources.displayMetrics.density
        val at = IntArray(2)
        glance.root.getLocationOnScreen(at)
        val contentTop = (at[1] + glance.root.paddingTop + glance.lockGlanceTop.paddingTop).toFloat()
        val contentStart = (at[0] + glance.root.paddingStart + glance.lockGlanceTop.paddingStart).toFloat()
        val bar = keyguardBarPx(density)
        val clock = glance.lockGlanceClock
        measured(clock, width)
        // The clock's box may reach above the page's content (the emulator's 136 px status bar ends below Android's
        // clock's top), as long as its digits' ink stays inside: its floor is minus the empty room above the ink.
        val ink = android.graphics.Rect()
        clock.paint.getTextBounds(CLOCK_DIGITS, 0, CLOCK_DIGITS.length, ink)
        val clockTarget = profileBaselinePx(bar, p.clockBaselineBelowBarDp, density)
        val clockTop = baselineMarginPx(clockTarget, contentTop, clock.baseline.toFloat(), floorPx = -(clock.baseline + ink.top).toFloat())
        var moved = margins(clock, clockStartPx(p), clockTop)
        val date = glance.lockGlanceDate
        measured(date, width)
        val clockBottom = contentTop + clockTop + clock.measuredHeight
        val descent = (clock.measuredHeight - clock.baseline).toFloat()
        val dateStart = startMarginPx(p.dateStartDp * density, contentStart)
        val dateTarget = profileBaselinePx(bar, p.dateBaselineBelowBarDp, density)
        moved = margins(date, dateStart, baselineMarginPx(dateTarget, clockBottom, date.baseline.toFloat(), floorPx = -descent)) || moved
        if (bar != placedBarPx) {
            placedBarPx = bar
            Log.i(LOG_TAG, "Glance clock: keyguard status bar ${bar.roundToInt()} px, the clock's baseline at ${clockTarget.roundToInt()} px, the date's at ${dateTarget.roundToInt()} px")
        }
        val alarm = glance.lockGlanceAlarm
        return margins(alarm, dateStart, (alarm.layoutParams as ViewGroup.MarginLayoutParams).topMargin) || moved
    }

    /** The keyguard status bar [placeProfile] last placed under (logged once per value). */
    private var placedBarPx = -1f

    /**
     * SystemUI's keyguard status bar on this window ([keyguardStatusBarPx]): the status bar's top inset whether shown or
     * not (lock task may hide its content, never its height) and the cutout's, from the window's own insets - the root's
     * listener consumes them for the views, not for `rootWindowInsets`. No insets yet: the 40 dp floor alone.
     */
    private fun keyguardBarPx(density: Float): Float {
        val insets = glance.root.rootWindowInsets
        val bar = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0
        val cutout = insets?.getInsets(WindowInsets.Type.displayCutout())?.top ?: 0
        return keyguardStatusBarPx(bar, cutout, density)
    }

    /** Writes the margins only when they differ; returns whether it did. */
    private fun margins(view: View, start: Int, top: Int): Boolean {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        if (params.marginStart == start && params.topMargin == top) return false
        params.marginStart = start
        params.topMargin = top
        view.layoutParams = params
        return true
    }

    /** onStart: watching the rows and the session; the stage decided if the lock comes up now. */
    fun start() = guarded("start") {
        if (!started) {
            started = true
            GlanceStore.addListener(changed)
            MissedCallNotifier.addListener(changed)
            TimeRulesRuntime.addLockListener(lockListener)
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
    fun resume() = guarded("resume") {
        // Design 59d step B (§8.5): a leave that didn't finish within its resume shows the content again here.
        leaving = false
        resumeNow()
    }

    private fun resumeNow() {
        if (!started) return
        // A timer the listener no longer has goes before the page is drawn (fix 2026-10-21).
        ClockReader.reconcile()
        // Design 34 P12: the clock follows the phone's format, not Vibb's own language.
        ClockFormat.bind(glance.lockGlanceClock)
        val interactive = activity.getSystemService(PowerManager::class.java)?.isInteractive == true
        val offs = PinLockRuntime.screenOffs
        if (lockStageDue(interactive, decidedAt, offs)) {
            decidedAt = offs
            tileUp = false
            // r11 §5: a drag in progress springs back, a dismissal already released commits - at once.
            settleDismiss()
            // Every lock-up starts the page at the top (r9 §2; the whole page since r11 §1).
            glance.lockGlanceScroll.scrollTo(0, 0)
            state = glanceStageStep(state, GlanceStageEvent.LockUp)
            dropTargetIfGone()
            onStage()
        } else {
            // The user (2026-10-24): back from a call, the emergency dialer, an alarm or an app over the lock, the glance
            // is the face again - from the top.
            backToTop()
        }
        ruleCard = currentRuleCard()
        renderNow()
    }

    /** The card for now (design r13 §1.4): its own snapshot - the time-rule screen's call - only while a rule or the
     * budget locks (one pref read otherwise). Its own catch, as the rows have in [renderNow]: a fault is no card, never a
     * skipped stage decision or render; logged by its class only. A boundary passed while the screen was off lands here. */
    private fun currentRuleCard(): RuleCard? = try {
        val reason = LauncherPreferences.mdm().lockReason()
        if (reason == LockReason.NONE) null else ruleCardOf(reason, TimeRulesRuntime.snapshot(activity, currentPolicyDecision().policy))
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Glance rule card failed: ${e.javaClass.simpleName}")
        null
    }

    /** The card from the kept model: the kind's glyph, line 1 the rule's name or the kind's label, line 2 "Til 14:30"
     * only with a certain end; TalkBack reads one text ("Skole, til 14:30"). No tap, no action. */
    private fun bindRuleCard() {
        val view = glance.lockRuleCard
        val card = ruleCard
        if (card == null) {
            view.visibility = View.GONE
            return
        }
        val label = com.kidslauncher.mdm.ui.LockActivity.labelText(activity, card.label)
        val until = card.until?.let { ClockFormat.formatUntil(activity, it) }
        glance.lockRuleCardGlyph.setImageResource(com.kidslauncher.mdm.ui.LockActivity.glyphRes(card.kind.glyph()))
        glance.lockRuleCardTitle.text = label
        glance.lockRuleCardUntil.visibility = if (until != null) View.VISIBLE else View.GONE
        if (until != null) glance.lockRuleCardUntil.text = activity.getString(R.string.lock_until, until)
        view.contentDescription = if (until != null) activity.getString(R.string.glance_rule_card_description, label, until) else label
        view.visibility = View.VISIBLE
    }

    /**
     * The user's rule (2026-10-24, "må alltid komme til utgangspunktet igjen"): whenever the glance is the visible face
     * again - a lock-up, back from the keypad or Android's pad ([glanceBackToTop]), a resume that is no lock-up, the
     * VoIP ring card gone ([covered]) - the page starts at the top; a drag in progress settles first.
     */
    private fun backToTop() {
        settleDismiss()
        glance.lockGlanceScroll.scrollTo(0, 0)
    }

    /** The VoIP ring card is over the page ([up]) or gone (PinLockActivity.renderVoip, on every change). Under it the
     * page is hidden from accessibility, so TalkBack can't reach a row's "Fjern" or tap behind the card
     * (qa-r11-glance-code Q-2), and a drag springs back; when it goes the page is the face again, from the top. */
    fun covered(up: Boolean) = guarded("covered") {
        if (up == cardUp) return@guarded
        cardUp = up
        glance.root.importantForAccessibility =
            if (up) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        handle.importantForAccessibility =
            if (up) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (up) dropDrag(smooth = false) else backToTop()
    }

    /** The VoIP ring card is up ([covered]). */
    private var cardUp = false

    /** onStop (and onDestroy): nothing watched, ticking or drawn while the lock isn't shown. */
    fun stop() = guarded("stop") {
        // r11 §5: a screen-off mid-drag dismisses nothing; a released dismissal commits (before the tiles stop).
        settleDismiss()
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
        TimeRulesRuntime.removeLockListener(lockListener)
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
     * texts were resized by the activity; the layout is placed again at the same height (the clock's size back). */
    fun refit() = guarded("refit") {
        placedFor = 0
        laidOut = null
        layoutNow()
        glance.root.requestLayout()
    }

    // ---- the swipe up (design 59d step J) -------------------------------------------------------------------------

    /** What the activity does with a touch ([swipe]). */
    enum class SwipeTouch {
        /** The views get it as usual. */
        PASS,

        /** The swipe was just recognised: the views get a cancel, the touch is ours. */
        CANCEL_VIEWS,

        /** The rest of a recognised swipe: ours. */
        CONSUME,
    }

    private var swipeDown: MotionEvent? = null
    private var swipeVelocity: VelocityTracker? = null
    private var swipeBlocked = false

    /** What the gesture became (r11 §3.2; a plain "taken" flag before): nothing yet, the unlock, or a row's dismiss. */
    private enum class SwipeMode { NONE, UNLOCK, DISMISS }

    private var swipeMode = SwipeMode.NONE

    /** The gesture's axis, decided once by its first movement past the touch slop ([glanceTouchAxis]). */
    private var swipeAxis = GlanceAxis.NONE

    /** The bound row view the DOWN hit, by index - only on the GLANCE stage, inside the scroller, no dismissal running. */
    private var dismissRow: Int? = null

    /** The row being dragged sideways ([SwipeMode.DISMISS]); null once it is released or sprung back (the rest of
     * that gesture is then consumed and does nothing). */
    private var dragged: Dragged? = null

    /** A dragged row: its view's index and view, the row it shows (rows aren't rebound during the drag) and the touch
     * slop (dp) its offset starts after (Q-9: the row doesn't jump by the slop when the drag is taken). */
    private class Dragged(val index: Int, val view: View, val row: GlanceRow, val slopDp: Float)

    /**
     * Every touch of the lock's window, before its views ([PinLockActivity.dispatchTouchEvent]; [covered]: the VoIP
     * ring card or a dialog is over the page): on the glance page - under Android's pad too (ASK: a second swipe asks
     * again, as the button's second tap did, JL-1) - a vertical swipe up anywhere, the handle and the bottom inset
     * included (§8.13), not on a control that keeps the touch and not a drag in the scroller while it can scroll
     * ([glanceSwipeBlocked]), is "Lås opp" ([glanceSwipeStep]: the ASK stage as the face, our keypad in own mode). The
     * system taking the gesture sends ACTION_CANCEL, which ends it with nothing asked. It only asks; a row's tap and the
     * tile's controls are unchanged. Design r11 §3.2: the gesture's first movement past the touch slop decides its axis
     * ([glanceTouchAxis]) - sideways on a row (the GLANCE stage only) is that row's dismiss ([dismissTouch]); a sideways
     * start on a row that can't be taken blocks the unlock for that gesture, anywhere else the unlock's own rule stands
     * (qa-r11-glance-code Q-8). While a finger is down the rows are never rebound (Q-1, [rowsHeld]): a render held then
     * runs when the gesture ends. Never throws; a fault springs a drag back and commits a released dismissal (Q-7).
     */
    fun swipe(ev: MotionEvent, covered: Boolean): SwipeTouch = try {
        swipeNow(ev, covered).also { if (swipeDown == null) flushRender() }
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Glance swipe failed: ${e.javaClass.simpleName}")
        settleDismiss()
        swipeReset()
        flushRender()
        SwipeTouch.PASS
    }

    private fun swipeNow(ev: MotionEvent, covered: Boolean): SwipeTouch {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            dropDrag(smooth = false)
            // Q-9: a spring-back still running ends before a new drag can take the row.
            springing?.end()
            swipeReset()
            flushRender()
            val root = glance.root
            if (covered || !started || leaving || !glancePageShown(state.stage) || root.visibility != View.VISIBLE || root.height <= 0) {
                return SwipeTouch.PASS
            }
            val scroll = glance.lockGlanceScroll
            val scrollTakes = hit(scroll, ev) && (scroll.canScrollVertically(1) || scroll.canScrollVertically(-1))
            swipeBlocked = glanceSwipeBlocked(swipeControls().any { hit(it, ev) }, scrollTakes)
            // r11 §3.1: only a row, only on the GLANCE stage (not under Android's pad), never while a dismissal runs.
            dismissRow = if (state.stage == LockStage.GLANCE && dismissing == null && hit(scroll, ev)) rowAt(ev) else null
            swipeDown = MotionEvent.obtain(ev)
            swipeVelocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            return SwipeTouch.PASS
        }
        val down = swipeDown ?: return SwipeTouch.PASS
        if (swipeMode == SwipeMode.UNLOCK) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swipeReset()
            return SwipeTouch.CONSUME
        }
        if (swipeMode == SwipeMode.DISMISS) return dismissTouch(ev, down, covered)
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            // A second finger or the system took it (a gesture from the edge): no swipe of ours.
            swipeReset()
            return SwipeTouch.PASS
        }
        val tracker = swipeVelocity ?: return SwipeTouch.PASS
        tracker.addMovement(ev)
        val lifted = action == MotionEvent.ACTION_UP
        if (lifted) tracker.computeCurrentVelocity(1000)
        val density = activity.resources.displayMetrics.density
        val dx = (ev.rawX - down.rawX) / density
        val dy = (down.rawY - ev.rawY) / density
        if (swipeAxis == GlanceAxis.NONE && !lifted) {
            swipeAxis = glanceTouchAxis(dx, dy, ViewConfiguration.get(activity).scaledTouchSlop / density)
            if (swipeAxis == GlanceAxis.HORIZONTAL) {
                // r11 §3.2: sideways on a row is its dismiss - the views get a cancel (the row's press ends, the scroll
                // gets nothing) and the row follows the finger. A sideways start on a row it can't take is no unlock
                // for this gesture; anywhere else (the clock, the tiles, the bottom row, the handle) the unlock's own
                // rule stands - only a mostly vertical swipe up asks (qa-r11-glance-code Q-8).
                val slop = ViewConfiguration.get(activity).scaledTouchSlop / density
                val drag = dismissRow?.let { dragOf(it, slop) }
                if (drag != null) {
                    swipeMode = SwipeMode.DISMISS
                    dragged = drag
                    follow(drag, dx)
                    return SwipeTouch.CANCEL_VIEWS
                }
                if (dismissRow != null) swipeBlocked = true
            }
        }
        val verdict = glanceSwipeStep(
            dy = dy,
            vy = if (lifted) -tracker.yVelocity / density else 0f,
            startedOnTappable = swipeBlocked,
            dx = dx,
            page = glance.root.height / density,
            lifted = lifted,
        )
        if (verdict != GlanceSwipe.UNLOCK) {
            if (lifted) swipeReset()
            return SwipeTouch.PASS
        }
        Log.i(LOG_TAG, "Glance swipe up: unlock asked")
        if (lifted) swipeReset() else swipeMode = SwipeMode.UNLOCK
        // After the views' cancel, as a click would come after its UP.
        glance.root.post { step(GlanceStageEvent.Unlock) }
        return SwipeTouch.CANCEL_VIEWS
    }

    private fun swipeReset() {
        swipeDown?.recycle()
        swipeDown = null
        swipeVelocity?.recycle()
        swipeVelocity = null
        swipeBlocked = false
        swipeMode = SwipeMode.NONE
        swipeAxis = GlanceAxis.NONE
        dismissRow = null
        dragged = null
    }

    // ---- a row swiped away (design r11 §3) --------------------------------------------------------------------------

    /** A row released past the threshold: sliding off and collapsing, then committed ([commitDismiss]) - once. */
    private class Dismissal(val index: Int, val view: View, val row: GlanceRow) {
        var animator: ValueAnimator? = null
    }

    /** The dismissal running (null: none) - the rows aren't rebound meanwhile ([dismissBusy]). */
    private var dismissing: Dismissal? = null

    /** A row springing back to its place. */
    private var springing: ValueAnimator? = null

    /** A render came while the rows waited for a drag or a dismissal: it runs once they are free ([flushRender]). */
    private var renderPending = false

    /** The missed-call rows dismissed and not yet answered by the notifier's pass for them (number -> passes
     * outstanding; [hiddenPlain] for the plain row): left out of the rows ([withoutDismissed]) so none flashes back
     * (qa-r11-glance-code Q-5: each until its own pass, not the first change of `MissedCallNotifier.current`). */
    private val hiddenNumbers = HashMap<String, Int>()
    private var hiddenPlain = 0

    /** Every rebinding of the rows (commitDismiss renders only when none ran during it). */
    private var rowBinds = 0

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** While a row is dragged or dismissed the rows (and the layout) aren't rebound, so a post that arrives meanwhile
     * never swaps a row's content under the finger (r11 §3.3). */
    private fun dismissBusy(): Boolean = swipeMode == SwipeMode.DISMISS || dismissing != null

    /** qa-r11-glance-code Q-1: no rebind under a finger that is down either - a tap that lands during a row's slide or
     * collapse (or while a post arrives) clicks the row it pressed, never the one a rebind moved under it. A held render
     * runs at the gesture's end ([swipe], [flushRender]). */
    private fun rowsHeld(): Boolean = swipeDown != null || dismissBusy()

    /** The rest of a row's sideways gesture ([SwipeMode.DISMISS]): it follows the finger until the lift, then
     * [glanceDismissStep] decides. A second finger, the system's cancel or the VoIP card springs it back. */
    private fun dismissTouch(ev: MotionEvent, down: MotionEvent, covered: Boolean): SwipeTouch {
        val action = ev.actionMasked
        val drag = dragged
        if (drag == null) {
            // Sprung back already (a second finger, the VoIP card): the gesture ends with nothing done.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                swipeReset()
                flushRender()
            }
            return SwipeTouch.CONSUME
        }
        if (covered || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            // Nothing is dismissed or asked: the row goes back (its end runs a render the drag held back).
            dragged = null
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swipeReset()
            springBack(drag.view, smooth = true)
            return SwipeTouch.CONSUME
        }
        val tracker = swipeVelocity ?: return SwipeTouch.CONSUME
        tracker.addMovement(ev)
        val lifted = action == MotionEvent.ACTION_UP
        if (lifted) tracker.computeCurrentVelocity(1000)
        val density = activity.resources.displayMetrics.density
        val dx = slopped((ev.rawX - down.rawX) / density, drag.slopDp)
        val vx = if (lifted) tracker.xVelocity / density else 0f
        when (glanceDismissStep(dx, vx, drag.view.width / density, lifted)) {
            GlanceDismiss.FOLLOW -> follow(drag, (ev.rawX - down.rawX) / density)
            GlanceDismiss.DISMISS -> {
                swipeReset()
                release(drag, dx)
            }
            GlanceDismiss.BACK -> {
                swipeReset()
                springBack(drag.view, smooth = true)
            }
        }
        return SwipeTouch.CONSUME
    }

    /** The bound row under the touch (inside the scroller), by index. */
    private fun rowAt(ev: MotionEvent): Int? =
        (0 until minOf(boundRows(), rowViews.size)).firstOrNull { i ->
            rowViews[i].root.visibility == View.VISIBLE && hit(rowViews[i].root, ev)
        }

    private fun boundRows(): Int = minOf(rows.size, GLANCE_MAX_ROWS)

    private fun dragOf(index: Int, slopDp: Float): Dragged? =
        rows.getOrNull(index)?.takeIf { index < boundRows() }?.let { Dragged(index, rowViews[index].root, it, slopDp) }

    /** The drag's travel past the slop it was taken after (Q-9, as Android's shade): 0 within it. */
    private fun slopped(dxDp: Float, slopDp: Float): Float =
        if (kotlin.math.abs(dxDp) <= slopDp) 0f else dxDp - kotlin.math.sign(dxDp) * slopDp

    /** The row follows the finger (its travel past the slop, [dx] raw) and fades ([glanceDismissAlpha]). */
    private fun follow(drag: Dragged, dx: Float) {
        val density = activity.resources.displayMetrics.density
        val travel = slopped(dx, drag.slopDp)
        drag.view.translationX = travel * density
        drag.view.alpha = glanceDismissAlpha(travel, drag.view.width / density)
    }

    /** A drag still in progress goes back - at once (a lock-up, a stop, a new DOWN) or animated. */
    private fun dropDrag(smooth: Boolean) = guarded("dismiss") {
        val drag = dragged ?: return@guarded
        dragged = null
        springBack(drag.view, smooth)
    }

    /** Back to 0 and alpha 1 in [DISMISS_ANIM_MS], decelerating; at once with "Remove animations" on. */
    private fun springBack(view: View, smooth: Boolean) {
        springing?.end()
        if (!smooth || !ValueAnimator.areAnimatorsEnabled()) {
            view.translationX = 0f
            view.alpha = 1f
            flushRender()
            return
        }
        val fromX = view.translationX
        val fromAlpha = view.alpha
        springing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                guarded("dismiss") {
                    val f = a.animatedFraction
                    view.translationX = fromX * (1f - f)
                    view.alpha = fromAlpha + (1f - fromAlpha) * f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = guarded("dismiss") {
                    if (springing === animation) springing = null
                    view.translationX = 0f
                    view.alpha = 1f
                    flushRender()
                }
            })
            start()
        }
    }

    /**
     * A row released past the threshold (r11 §3.1): a message row needs the listener ([GlanceReader.canDismiss]) - without
     * it the row springs back and nothing changes. Else it slides off the screen's edge, fading, then collapses with its
     * gap ([collapse]), then [commitDismiss]; with "Remove animations" on the commit follows at once.
     */
    private fun release(drag: Dragged, dx: Float) {
        if (drag.row is GlanceMessageRow && !GlanceReader.canDismiss()) {
            Log.i(LOG_TAG, "Glance: a row sprang back - no listener")
            springBack(drag.view, smooth = true)
            return
        }
        val d = Dismissal(drag.index, drag.view, drag.row)
        dismissing = d
        // Q-7: a fault before the slide runs commits at once - `dismissing` never outlives a dismissal that can't end.
        var running = false
        try {
            if (ValueAnimator.areAnimatorsEnabled()) running = slideOff(d, dx)
        } finally {
            if (!running) commitDismiss(d)
        }
    }

    /** The row slides off the screen's edge, fading; then [collapse]. Returns whether the slide runs. */
    private fun slideOff(d: Dismissal, dx: Float): Boolean {
        val view = d.view
        val edge = glance.lockGlanceScroll.width.toFloat()
        val toX = if (dx < 0f) -edge else edge
        val fromX = view.translationX
        val fromAlpha = view.alpha
        d.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                guarded("dismiss") {
                    val f = a.animatedFraction
                    view.translationX = fromX + (toX - fromX) * f
                    view.alpha = fromAlpha * (1f - f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = guarded("dismiss") { collapse(d) }
            })
            start()
        }
        return true
    }

    /** The slid-off row's height and the 8 dp gap above it (the first row's: below it) close in [DISMISS_ANIM_MS]. */
    private fun collapse(d: Dismissal) {
        if (dismissing !== d) return
        // Q-7: a fault before the collapse runs commits at once.
        var running = false
        try {
            running = collapseRuns(d)
        } finally {
            if (!running) commitDismiss(d)
        }
    }

    private fun collapseRuns(d: Dismissal): Boolean {
        val view = d.view
        val gapView = if (d.index == 0) rowViews.getOrNull(1)?.root?.takeIf { it.visibility == View.VISIBLE } else view
        val gap = gapView?.layoutParams as? ViewGroup.MarginLayoutParams
        val gapFrom = gap?.topMargin ?: 0
        val params = view.layoutParams
        val from = view.height
        d.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                guarded("dismiss") {
                    val f = a.animatedFraction
                    params.height = (from * (1f - f)).roundToInt()
                    view.layoutParams = params
                    if (gapView != null && gap != null) {
                        gap.topMargin = (gapFrom * (1f - f)).roundToInt()
                        gapView.layoutParams = gap
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = guarded("dismiss") { commitDismiss(d) }
            })
            start()
        }
        return true
    }

    /**
     * The commit (r11 §3.3), once, on the row bound when the kid saw it: a message row - [GlanceReader.dismiss] (that
     * conversation's keys up to the row's newest post; a `false` renders the row back in place); a contact's
     * missed-call row - [MissedCallNotifier.dismissContact] (its dismissed mark, the phone book's badge stays); the plain
     * one - [MissedCallNotifier.dismiss] (ours cancelled, its range marked read, as the phone book's opening). A
     * missed-call row stays out until the notifier's pass for it has answered (Q-5, [withoutDismissed]). Then the
     * deferred render - every row view reset and rebound - unless the reader's change already ran it, and never under a
     * finger that is down (Q-1). Returns whether it dismissed.
     */
    private fun commitDismiss(d: Dismissal): Boolean {
        var done = false
        val bound = rowBinds
        guarded("dismiss") {
            if (dismissing !== d) return@guarded
            dismissing = null
            d.animator = null
            done = when (val row = d.row) {
                is GlanceMessageRow -> GlanceReader.dismiss(row.conversation, upToPostTimeMs = row.postTimeMs).also { ok ->
                    Log.i(LOG_TAG, if (ok) "Glance: a row dismissed (message)" else "Glance: a row came back - not dismissed")
                }
                is GlanceMissedRow -> {
                    val number = row.number
                    hideMissed(number)
                    if (number != null) {
                        MissedCallNotifier.dismissContact(activity, number, upToMs = row.timeMs) { main.post { missedAnswered(number) } }
                    } else {
                        MissedCallNotifier.dismiss(activity) { main.post { missedAnswered(null) } }
                    }
                    Log.i(LOG_TAG, if (number != null) "Glance: a row dismissed (missed call)" else "Glance: a row dismissed (missed calls)")
                    true
                }
            }
        }
        // The reader's own change (GlanceStore.hide) may have rendered already; else render now (or at the lift).
        if (rowBinds == bound) {
            renderPending = true
            flushRender()
        }
        return done
    }

    /** Leaves [number]'s row (null: the plain row) out until the notifier's pass for it answers ([missedAnswered]). */
    private fun hideMissed(number: String?) {
        if (number != null) hiddenNumbers[number] = (hiddenNumbers[number] ?: 0) + 1 else hiddenPlain++
    }

    /** The notifier's pass for [number] (null: the plain row) is done and `MissedCallNotifier.current` says what is
     * left: the row is no longer held out by us (Q-5: a pass for another contact doesn't release it). Main thread. */
    private fun missedAnswered(number: String?) = guarded("dismiss") {
        if (number != null) {
            val left = (hiddenNumbers[number] ?: 0) - 1
            if (left > 0) hiddenNumbers[number] = left else hiddenNumbers.remove(number)
        } else {
            hiddenPlain = (hiddenPlain - 1).coerceAtLeast(0)
        }
        renderPending = true
        flushRender()
    }

    /** The render a drag, a dismissal or a finger held back, once the rows are free. */
    private fun flushRender() {
        if (!renderPending || rowsHeld()) return
        renderPending = false
        render()
    }

    /**
     * r11 §5: a lock-up or a stop - a drag in progress springs back at once (nothing dismissed), a dismissal already
     * released commits at once (its animation ended, its listeners dropped). A screen-off mid-drag dismisses nothing.
     * Also every return to the glance ([backToTop]): any gesture still recorded ends here (one whose UP never reached
     * us must not hold the rows, Q-1), and a render held back runs.
     */
    private fun settleDismiss() = guarded("dismiss") {
        dropDrag(smooth = false)
        swipeReset()
        springing?.end()
        dismissing?.let { d ->
            d.animator?.let {
                it.removeAllListeners()
                it.cancel()
            }
            commitDismiss(d)
        }
        flushRender()
    }

    /**
     * Design r11 §3.4: TalkBack's touch exploration takes the drag, so every row has the custom action "Fjern" /
     * "Remove" next to its click label - set on the rows only (the timer tile keeps [openAction], F7). The action
     * commits at once, without the animation, on the row bound now; it answers `false` when the commit can't run (not
     * on the GLANCE stage, under the VoIP ring card - qa-r11-glance-code Q-2 -, a dismissal running, no listener for a
     * message row). Focus stays on the view, which then reads
     * the next row; nothing is announced (`announceForAccessibility` is deprecated, F8).
     */
    private fun rowActions(view: View, clickLabel: String, row: GlanceRow) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, clickLabel))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.glance_action_dismiss, activity.getString(R.string.glance_dismiss_action)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action != R.id.glance_action_dismiss) return super.performAccessibilityAction(host, action, args)
                return removeByAction(host, row)
            }
        }
    }

    private fun removeByAction(view: View, row: GlanceRow): Boolean = try {
        val index = rowViews.indexOfFirst { it.root === view }
        val free = started && !leaving && !cardUp && state.stage == LockStage.GLANCE && !rowsHeld() && index in 0 until boundRows()
        if (!free || (row is GlanceMessageRow && !GlanceReader.canDismiss())) {
            false
        } else {
            val d = Dismissal(index, view, row)
            dismissing = d
            commitDismiss(d)
        }
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Glance dismiss failed: ${e.javaClass.simpleName}")
        false
    }

    /** The controls that keep their whole touch (a swipe from them is theirs): the music tile's buttons, the timer's
     * Stopp, the flashlight and Nødsamtale. A row and the timer tile itself lead to the unlock anyway. */
    private fun swipeControls(): List<View> = listOf(
        glance.lockMediaPrevious, glance.lockMediaPlay, glance.lockMediaNext, glance.lockMediaCompactPlay,
        glance.lockTimerStop, glance.lockGlanceTorch, glance.lockGlanceEmergency,
    )

    private fun hit(view: View, ev: MotionEvent): Boolean {
        if (!view.isShown) return false
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return ev.rawX >= at[0] && ev.rawX < at[0] + view.width && ev.rawY >= at[1] && ev.rawY < at[1] + view.height
    }

    /**
     * Design 59d step J, §8.13: TalkBack (whose touch exploration takes the swipe) reaches the unlock through the handle -
     * one node, described "Lås opp", whose click action, named "Lås opp", asks as the swipe does. A sighted tap on it does
     * nothing (it is no button: Android's own handle isn't either).
     */
    private fun handleAction(view: View) {
        view.contentDescription = activity.getString(R.string.lock_media_unlock)
        view.isScreenReaderFocusable = true
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, activity.getString(R.string.lock_media_unlock)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return super.performAccessibilityAction(host, action, args)
                step(GlanceStageEvent.Unlock)
                return true
            }
        }
    }

    /** The keypad's back arrow (as the face also the pad's cancel, an error, the recovery, STAY): the glance page again. */
    fun back() = step(GlanceStageEvent.Back)

    /** Design 59d step B: as the face with no Android credential at the tap - our keys, the target kept. */
    fun ownKeypad() = step(GlanceStageEvent.OwnKeypad)

    /** Design 59d step B: the glance leaves as the face, in front - its content goes before Home ([glanceLeaveHides]);
     * the root keeps its ground (the lock wallpaper under its scrim), and the window stays visible (a row's `PendingIntent` send needs it). */
    fun leaving() = guarded("leaving") {
        leaving = true
        showContent()
    }

    /** The finish was refused after all (the root's retry too): the content shows again. Also at the next resume. */
    fun leaveRefused() = guarded("leave refused") {
        if (!leaving) return@guarded
        leaving = false
        showContent()
    }

    /** The glance is leaving ([leaving]) - set only by the leave, cleared by a refused finish or the next resume. */
    private var leaving = false

    /** The page's content (the root's children) per [glanceContentShown]; the root itself, with the ground, stays. The
     * handle (§8.13) shows with the content, only while the page does. */
    private fun showContent() {
        val content = if (glanceContentShown(leaving)) View.VISIBLE else View.INVISIBLE
        val root = glance.root
        for (i in 0 until root.childCount) root.getChildAt(i).visibility = content
        handle.visibility = if (root.visibility == View.VISIBLE && glanceContentShown(leaving)) View.VISIBLE else View.GONE
    }

    /**
     * Design 59d §8.16: a home gesture got through and the system's Home brought the lock back - the page asks for the
     * unlock as the handle's swipe does ([GlanceStageEvent.Unlock]: Android's pad as the face, our keypad in own mode),
     * only while the page shows and isn't leaving.
     */
    fun askAfterGesture() = guarded("gesture ask") {
        if (leaving || !glancePageShown(state.stage)) return@guarded
        Log.i(LOG_TAG, "Glance: a home gesture got through - unlock asked")
        step(GlanceStageEvent.Unlock)
    }

    private fun step(event: GlanceStageEvent) = guarded("stage") {
        val from = state.stage
        state = glanceStageStep(state, event, face())
        // The user (2026-10-24): back on the glance from the keypad or Android's pad, the page starts at the top.
        if (glanceBackToTop(from, state.stage)) backToTop()
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

    /** The "more" line's tap (the user, 2026-10-24): the keypad without a target, as a row the time rule leaves no
     * target - the plain prompt, and Home after the code. Nothing is captured. */
    private fun tapMore() = guarded("tap") {
        captured = null
        promptLabel = null
        step(GlanceStageEvent.Row(null))
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
     * A session callback (r6 §2, coalesced per frame): the tile rendered alone - the rows aren't rebound and the clock
     * isn't touched. Since r11 nothing is refitted: the tile shows by the rule ([glanceLayout]) and its coming or going
     * is plain layout (the scroller takes it); only the list's margin under it follows.
     */
    private fun tileChanged() = guarded("tile") {
        val musicChanged = musicDirty
        val clockChanged = clockDirty
        musicDirty = false
        clockDirty = false
        if (!started) return@guarded
        val page = glancePageShown(state.stage)
        if (musicChanged) {
            tileUp = glanceTile(music.lookUp())
            music.show(shownTile(), page)
        }
        if (clockChanged) {
            clockUp = clock.up
            clock.show(shownClock(), page)
        }
        val layout = glanceLayout(tileUp, rows.size, clockUp, ruleCard != null)
        placeList(layout)
        // qa-r11-glance-code Q-10: a tile coming or going is a new layout - logged now, not at the next render.
        if (layout != laidOut) {
            logLayout(layout, available(glance.root.height))
            laidOut = layout
        }
    }

    /** The timer tile while a timer runs (always full, r11 §1.2). */
    private fun shownClock(): Boolean = clockUp

    /** The music tile by the rule ([glanceLayout]): none without a session. */
    private fun shownTile(): GlanceTileSize = glanceLayout(tileUp, rows.size, clockUp, ruleCard != null).tile

    /** The page's content width - the top block's, less its side padding (r11 §1.1) - once it is laid out and shown,
     * else null. */
    private fun pageWidth(): Int? {
        val root = glance.root
        if (root.height <= 0 || root.visibility != View.VISIBLE) return null
        val top = glance.lockGlanceTop
        return (root.width - root.paddingStart - root.paddingEnd - top.paddingStart - top.paddingEnd).takeIf { it > 0 }
    }

    /** The next alarm under the date (design 42 §1.3): the system clock app's or the pinned clock
     * app's only ([PinLockRuntime.clockPackages]), "Alarm 07:00" - with the weekday more than 24 h
     * away; always shown while there is one (r11 §1.2: nothing drops it). */
    private fun bindAlarm() {
        val next = try {
            activity.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        } catch (e: Exception) {
            null
        }
        val alarm = glanceAlarm(next?.triggerTime, next?.showIntent?.creatorPackage, PinLockRuntime.clockPackages(), System.currentTimeMillis())
        val view = glance.lockGlanceAlarm
        view.text = alarm?.let { activity.getString(R.string.lock_alarm, alarmText(it)) }
        view.visibility = if (alarm != null) View.VISIBLE else View.GONE
    }

    private fun alarmText(alarm: GlanceAlarm): String {
        val time = ClockFormat.formatUntil(activity, alarm.atMs)
        if (!alarm.withWeekday) return time
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        val day = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEE"), alarm.atMs).toString().trimEnd('.')
        return "$day $time"
    }

    /** The flashlight button (design 41 §1.3): hidden, off, or lit - a filled peach disc with the
     * ground's glyph. It toggles the torch and opens nothing; its presence never moves the page (the
     * row is as tall as "Nødsamtale" either way). */
    private fun bindTorch() {
        val t = torch
        val button = glance.lockGlanceTorch
        val look = glanceTorchButton(t?.hasFlash == true, t?.refusedWhileLocked == true, t?.lit == true)
        button.visibility = if (look == GlanceTorchButton.HIDDEN) View.GONE else View.VISIBLE
        if (look == GlanceTorchButton.HIDDEN) return
        val lit = look == GlanceTorchButton.LIT
        button.setBackgroundResource(if (lit) R.drawable.bg_music_play else R.drawable.bg_music_control)
        button.setImageResource(R.drawable.torch_glyph_on) // one glyph, no strike-through (the user, 2026-10-23): the state is the colours
        button.imageTintList = ColorStateList.valueOf(activity.getColor(if (lit) R.color.kid_ground else R.color.kid_core))
        button.stateDescription = activity.getString(if (lit) R.string.lock_torch_on else R.string.lock_torch_off)
    }

    private fun renderNow() {
        val shown = glancePageShown(state.stage)
        glance.root.visibility = if (shown) View.VISIBLE else View.GONE
        // Design 59d step B: while the glance leaves, only the root's ground.
        showContent()
        keypad.importantForAccessibility =
            if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        tileUp = glanceTile(music.lookUp())
        clockUp = clock.up
        // r11 §3.3: while a row is dragged or dismissed everything but the rows and the layout is bound; the rest waits
        // for the commit (or the spring-back), which renders once more.
        val busy = rowsHeld()
        if (busy) {
            renderPending = true
        } else {
            renderPending = false
            rowBinds++
            try {
                rows = shownRows()
                bindRows()
            } catch (e: Throwable) {
                // A row fault hides the rows; the page, the swipe and "Nødsamtale" stay.
                Log.w(LOG_TAG, "Glance rows failed: ${e.javaClass.simpleName}")
                rows = emptyList()
                rowViews.forEach { it.root.visibility = View.GONE }
            }
        }
        bindTorch()
        bindAlarm()
        bindRuleCard()
        // The tiles by the rule, then the layout (and the profile's placement once the page has its room).
        music.show(shownTile(), page = shown)
        clock.show(shownClock(), page = shown)
        if (busy) return
        placedFor = 0
        layoutNow()
    }

    /** The store's rows, less the missed-call rows dismissed and not yet answered by the notifier (r11 §3.3, Q-5). */
    private fun shownRows(): List<GlanceRow> {
        val all = GlanceStore.rows(MissedCallNotifier.current)
        return if (hiddenNumbers.isEmpty() && hiddenPlain == 0) all else withoutDismissed(all, hiddenNumbers.keys, hiddenPlain > 0)
    }

    // ---- rows -----------------------------------------------------------------------------------

    private fun bindRows() {
        val shown = rows.take(GLANCE_MAX_ROWS)
        while (rowViews.size < shown.size) rowViews += newRow(rowViews.size)
        // r11 §3.3: a spring-back still running ends here; every view is reset below.
        springing?.let {
            it.removeAllListeners()
            it.cancel()
        }
        springing = null
        rowViews.forEachIndexed { i, view ->
            // r11 §3.3: views are reused by index - after a commit view i shows row i + 1, at rest.
            rest(view.root, i)
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

    /** A row view at rest (r11 §3.3): no slide, opaque, its own height and its 8 dp gap above (none for the first) -
     * what a drag, a spring-back or a collapse left is undone. Layout params are written only when they differ. */
    private fun rest(view: View, index: Int) {
        view.translationX = 0f
        view.alpha = 1f
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val gap = if (index > 0) dp(8f) else 0
        if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT || params.topMargin != gap) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.topMargin = gap
            view.layoutParams = params
        }
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
        // 2026-10-25: a group's newest sender in "Name only", the count of one sender's new messages in "Full text".
        val suffix = when (val s = glanceNameSuffix(row)) {
            is GlanceNameSuffix.Sender -> s.name
            is GlanceNameSuffix.NewCount -> res.getQuantityString(R.plurals.glance_new_count, s.count, s.count)
            null -> null
        }
        view.root.visibility = View.VISIBLE
        // Q-7: only the name is cut - the suffix is its own view after it ("· 3 nye" stays readable).
        view.glanceRowTitle.text = title
        view.glanceRowSuffix.text = suffix?.let { activity.getString(R.string.glance_name_suffix, it) }
        view.glanceRowSuffix.visibility = if (suffix != null) View.VISIBLE else View.GONE
        view.glanceRowSubtitle.text = subtitle
        view.glanceRowSubtitle.visibility = View.VISIBLE
        view.glanceRowTime.text = time
        view.glanceRowInitial.visibility = View.GONE
        val icon = icons.cached(row.packageName)
        plainIcon(view)
        // 2026-10-25: the sender's phone-book contact (Element X's link, WhatsApp's tel: URI) shows its Home avatar in the
        // icon's place - our own photo, never the notification's picture - with the app's icon as the corner mark.
        val contact = glanceRowContact(row, CallPolicyStore.state)
        when {
            contact != null -> KidAvatars.bindContact(view.glanceRowIcon, view.glanceRowInitial, contact, isEmergency = false, initialSp = 16f)
            icon != null -> view.glanceRowIcon.setImageBitmap(icon)
            else -> {
                // A placeholder circle until the background job has drawn Home's icon (QA Low 6).
                view.glanceRowIcon.setImageDrawable(null)
                view.glanceRowIcon.backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_music_control))
            }
        }
        // The red phone badge for a missed call, as on a phone call's row; else the app's mark on a contact's photo.
        when (glanceRowBadge(missedShown, hasContact = contact != null, hasIcon = icon != null)) {
            GlanceRowBadge.MISSED -> missedBadge(view)
            GlanceRowBadge.APP_MARK -> appMark(view, icon)
            GlanceRowBadge.NONE -> view.glanceRowBadge.visibility = View.GONE
        }
        val count = if (row.level == GlanceLevel.APP_ONLY) subtitle else messages
        val spoken = when {
            missedShown && row.title != null -> listOf(label, activity.getString(R.string.glance_missed_from, row.title), messages.takeIf { row.count > 1 }, time)
            missedShown -> listOf(label, missed, messages.takeIf { row.count > 1 }, time)
            // The same words the row shows, each once (Q-4: the pure parts).
            else -> listOf(label) + glanceSpokenParts(row).map { part -> spokenPart(part, count) } + time
        }
        describe(view, spoken.filterNotNull().distinct().joinToString(", "), activity.getString(R.string.glance_open_action), row)
    }

    private fun bindMissed(view: ViewLockGlanceRowBinding, row: GlanceMissedRow) {
        val res = activity.resources
        val time = timeText(row.timeMs)
        view.root.visibility = View.VISIBLE
        view.glanceRowTime.text = time
        view.glanceRowSuffix.visibility = View.GONE
        val number = row.number
        if (number == null) {
            // The plain row (fail-closed, or unmanaged with our dialer role): no names.
            val title = res.getQuantityString(R.plurals.glance_missed_many, row.count, row.count)
            view.glanceRowTitle.text = title
            view.glanceRowSubtitle.visibility = View.GONE
            view.glanceRowInitial.visibility = View.GONE
            view.glanceRowBadge.visibility = View.GONE
            phoneGlyph(view)
            describe(view, "$title, $time", activity.getString(R.string.glance_missed_action), row)
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
        missedBadge(view)
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
        describe(view, "$spoken, $time", activity.getString(R.string.glance_missed_action), row)
    }

    /** A row view is reused: every icon path sets the scale and the tints it needs. */
    private fun plainIcon(view: ViewLockGlanceRowBinding) {
        view.glanceRowIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        view.glanceRowIcon.imageTintList = null
        view.glanceRowIcon.backgroundTintList = null
    }

    /** One part of a message row's TalkBack text ([glanceSpokenParts]); [count] is the messages count as shown. */
    private fun spokenPart(part: GlanceSpokenPart, count: String): String = when (part) {
        is GlanceSpokenPart.Name -> part.name
        is GlanceSpokenPart.Group -> activity.getString(R.string.glance_group_spoken, part.name, part.sender)
        is GlanceSpokenPart.NewCount -> activity.resources.getQuantityString(R.plurals.glance_new_count, part.count, part.count)
        is GlanceSpokenPart.Text -> part.text
        GlanceSpokenPart.Count -> count
    }

    /** The corner's red phone badge (a missed call) - the layout's own look, put back on a reused view. */
    private fun missedBadge(view: ViewLockGlanceRowBinding) {
        val badge = view.glanceRowBadge
        badge.setBackgroundResource(R.drawable.bg_glance_missed_badge)
        badge.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        badge.scaleType = ImageView.ScaleType.FIT_CENTER
        badge.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_ink))
        badge.setImageResource(R.drawable.ic_kid_phone)
        badge.visibility = View.VISIBLE
    }

    /** 2026-10-25: the app's own (round) icon as the corner mark on a contact's photo, ringed in the row's colour. */
    private fun appMark(view: ViewLockGlanceRowBinding, icon: android.graphics.Bitmap?) {
        val badge = view.glanceRowBadge
        badge.setBackgroundResource(R.drawable.bg_glance_app_mark)
        badge.setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f))
        badge.scaleType = ImageView.ScaleType.FIT_CENTER
        badge.imageTintList = null
        badge.setImageBitmap(icon)
        badge.visibility = View.VISIBLE
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
    private fun describe(view: ViewLockGlanceRowBinding, description: String, action: String, row: GlanceRow) {
        view.root.contentDescription = description
        rowActions(view.root, action, row)
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

    // ---- the layout (r11 §1.2) ---------------------------------------------------------------------------

    /** The page's room at [height]: the root less its paddings, which carry the visible system bars (`KidInsets`). */
    private fun available(height: Int): Int = glance.root.let { height - it.paddingTop - it.paddingBottom }

    /**
     * The layout (r11 §1.2): nothing is measured to make room any more - the no-profile clock's 56 sp, or the profile's
     * clock placed under the keyguard status bar ([placeProfile]), then the rule's layout ([glanceLayout]) applied.
     * Only once the page is laid out (the profile reads the window's insets); the listener keys it on the available
     * height, so the bars arriving as padding still move the profile's clock.
     */
    private fun layoutNow() {
        val root = glance.root
        val width = pageWidth() ?: return
        val room = available(root.height)
        placedFor = room
        val clock = glance.lockGlanceClock
        val p = profile
        var moved = false
        if (p != null) {
            // Design 59d step L: one clock, Android's size, placed under the keyguard status bar.
            clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, p.clockDp)
            moved = placeProfile(p, width)
        } else {
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_BIG_SP)
        }
        val layout = glanceLayout(tileUp, rows.size, clockUp, ruleCard != null)
        if (layout != laidOut || room != loggedFor) logLayout(layout, room)
        if (layout != laidOut || moved) refitPending = true
        laidOut = layout
        apply(layout)
    }

    /** The layout's log line - counts and dp only (LockGlanceGuardTest): the room and the paddings say which bars it saw. */
    private fun logLayout(layout: GlanceLayout, room: Int) {
        val root = glance.root
        val roomDp = px2dp(room)
        val padTop = px2dp(root.paddingTop)
        val padBottom = px2dp(root.paddingBottom)
        Log.i(LOG_TAG, "Glance layout: tile ${layout.tile}, ${layout.rows} rows, ${layout.more} more, available $roomDp dp (pad $padTop/$padBottom)")
        loggedFor = room
    }

    private fun apply(layout: GlanceLayout) {
        val page = glancePageShown(state.stage)
        music.show(layout.tile, page)
        clock.show(layout.timer, page)
        placeList(layout)
        rowViews.forEachIndexed { i, view -> view.root.visibility = if (i < layout.rows) View.VISIBLE else View.GONE }
        val more = glance.lockGlanceMore
        more.visibility = if (layout.more > 0) View.VISIBLE else View.GONE
        if (layout.more > 0) more.text = activity.resources.getQuantityString(R.plurals.glance_more, layout.more, layout.more)
    }

    /** The list (the rows and the "more" line): gone only without a row; 22 dp under the date without a tile, the timer
     * or the rule's card (design r13 §1.3), else 14. */
    private fun placeList(layout: GlanceLayout) {
        val list = glance.lockGlanceList
        list.visibility = if (layout.rows > 0) View.VISIBLE else View.GONE
        val top = dp(if (layout.tile == GlanceTileSize.NONE && !layout.timer && !layout.rule) ROWS_TOP_NO_TILE_DP else ROWS_TOP_DP)
        (list.layoutParams as ViewGroup.MarginLayoutParams).let {
            if (it.topMargin != top) list.layoutParams = it.apply { topMargin = top }
        }
    }

    private fun measured(view: View, width: Int): Float {
        val wide = view.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, if (wide) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight.toFloat()
    }
    private fun dp(value: Float): Int = KidAvatars.dp(activity, value)

    private fun px2dp(px: Int): Int = (px / activity.resources.displayMetrics.density).roundToInt()

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

        /** Design 59d step L: the digits whose ink the profile's clock keeps inside the page. */
        const val CLOCK_DIGITS = "0123456789"
        const val ROWS_TOP_DP = 14f
        const val ROWS_TOP_NO_TILE_DP = 22f

        /** r11 §3.1: a row's slide off, its collapse and a spring-back, each (ms). */
        const val DISMISS_ANIM_MS = 150L
    }
}
