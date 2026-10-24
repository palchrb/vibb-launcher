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
import android.os.Build
import android.os.Bundle
import android.text.method.TransformationMethod
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
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
            // Every lock-up starts the rows at the top (r9 §2).
            glance.lockGlanceScroll.scrollTo(0, 0)
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
    private var swipeTaken = false

    /**
     * Every touch of the lock's window, before its views ([PinLockActivity.dispatchTouchEvent]; [covered]: the VoIP
     * ring card or a dialog is over the page): on the glance page - under Android's pad too (ASK: a second swipe asks
     * again, as the button's second tap did, JL-1) - a vertical swipe up anywhere, the handle and the bottom inset
     * included (§8.13), not on a control that keeps the touch and not a drag in the rows' scroll while it can scroll
     * ([glanceSwipeBlocked]), is "Lås opp" ([glanceSwipeStep]: the ASK stage as the face, our keypad in own mode). The
     * system taking the gesture sends ACTION_CANCEL, which ends it with nothing asked. It only asks; a row's tap and the
     * tile's controls are unchanged. Never throws.
     */
    fun swipe(ev: MotionEvent, covered: Boolean): SwipeTouch = try {
        swipeNow(ev, covered)
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Glance swipe failed: ${e.javaClass.simpleName}")
        swipeReset()
        SwipeTouch.PASS
    }

    private fun swipeNow(ev: MotionEvent, covered: Boolean): SwipeTouch {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            swipeReset()
            val root = glance.root
            if (covered || !started || leaving || !glancePageShown(state.stage) || root.visibility != View.VISIBLE || root.height <= 0) {
                return SwipeTouch.PASS
            }
            val scroll = glance.lockGlanceScroll
            val scrollTakes = hit(scroll, ev) && (scroll.canScrollVertically(1) || scroll.canScrollVertically(-1))
            swipeBlocked = glanceSwipeBlocked(swipeControls().any { hit(it, ev) }, scrollTakes)
            swipeDown = MotionEvent.obtain(ev)
            swipeVelocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            return SwipeTouch.PASS
        }
        val down = swipeDown ?: return SwipeTouch.PASS
        if (swipeTaken) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swipeReset()
            return SwipeTouch.CONSUME
        }
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
        val verdict = glanceSwipeStep(
            dy = (down.rawY - ev.rawY) / density,
            vy = if (lifted) -tracker.yVelocity / density else 0f,
            startedOnTappable = swipeBlocked,
            dx = (ev.rawX - down.rawX) / density,
            page = glance.root.height / density,
            lifted = lifted,
        )
        if (verdict != GlanceSwipe.UNLOCK) {
            if (lifted) swipeReset()
            return SwipeTouch.PASS
        }
        Log.i(LOG_TAG, "Glance swipe up: unlock asked")
        if (lifted) swipeReset() else swipeTaken = true
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
        swipeTaken = false
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
        state = glanceStageStep(state, event, face())
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
        placeList(glanceLayout(tileUp, rows.size, clockUp))
    }

    /** The timer tile while a timer runs (always full, r11 §1.2). */
    private fun shownClock(): Boolean = clockUp

    /** The music tile by the rule ([glanceLayout]): none without a session. */
    private fun shownTile(): GlanceTileSize = glanceLayout(tileUp, rows.size, clockUp).tile

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
        try {
            rows = GlanceStore.rows(MissedCallNotifier.current)
            bindRows()
        } catch (e: Throwable) {
            // A row fault hides the rows; the page, the swipe and "Nødsamtale" stay.
            Log.w(LOG_TAG, "Glance rows failed: ${e.javaClass.simpleName}")
            rows = emptyList()
            rowViews.forEach { it.root.visibility = View.GONE }
        }
        bindTorch()
        bindAlarm()
        // The tiles by the rule, then the layout (and the profile's placement once the page has its room).
        music.show(shownTile(), page = shown)
        clock.show(shownClock(), page = shown)
        placedFor = 0
        layoutNow()
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
        val layout = glanceLayout(tileUp, rows.size, clockUp)
        if (layout != laidOut || room != loggedFor) {
            // Counts and dp only (LockGlanceGuardTest): the room and the paddings say which bars it saw.
            val roomDp = px2dp(room)
            val padTop = px2dp(root.paddingTop)
            val padBottom = px2dp(root.paddingBottom)
            Log.i(LOG_TAG, "Glance layout: tile ${layout.tile}, ${layout.rows} rows, ${layout.more} more, available $roomDp dp (pad $padTop/$padBottom)")
            loggedFor = room
        }
        if (layout != laidOut || moved) refitPending = true
        laidOut = layout
        apply(layout)
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

    /** The list (the rows and the "more" line): gone only without a row; 22 dp under the date without a tile, else 14. */
    private fun placeList(layout: GlanceLayout) {
        val list = glance.lockGlanceList
        list.visibility = if (layout.rows > 0) View.VISIBLE else View.GONE
        val top = dp(if (layout.tile == GlanceTileSize.NONE && !layout.timer) ROWS_TOP_NO_TILE_DP else ROWS_TOP_DP)
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
    }
}
