package com.kidslauncher.mdm.lock

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.badges.GlanceLevel
import com.kidslauncher.mdm.badges.GlanceMessageRow
import com.kidslauncher.mdm.badges.GlanceMissedRow
import com.kidslauncher.mdm.badges.GlanceRow
import com.kidslauncher.mdm.badges.GlanceStore
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.MissedCallNotifier
import com.kidslauncher.mdm.calls.RelativeDay
import com.kidslauncher.mdm.calls.relativeDay
import com.kidslauncher.mdm.databinding.ViewLockGlanceBinding
import com.kidslauncher.mdm.databinding.ViewLockGlanceRowBinding
import com.kidslauncher.mdm.databinding.ViewLockMediaRowBinding
import com.kidslauncher.mdm.music.MusicLockPanel
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.home.KidAvatars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private const val LOG_TAG = "GlancePanel"

/**
 * The PIN lock's glance page (design 25, mockup LockGlance.dc.html; the rules are the pure
 * lock/LockGlance.kt and badges/GlanceRows.kt): in the lock's frame over the keypad stage and under
 * the VoIP ring screen. Holds the stage, the rows and the fit; listens to [GlanceStore] and
 * [MissedCallNotifier] between the lock's onStart and onStop. Every time the lock comes up with the
 * screen on ([lockStageDue]) it shows the glance page.
 *
 * It weakens nothing (LockGlanceGuardTest): "Lås opp" and a row lead to the keypad, the music tile's
 * controls are [MusicLockPanel]'s, "Nødsamtale" is the lock's own button ([onEmergency]) - nothing
 * here opens an app, a notification or a setting. Every entry point catches everything and logs the
 * exception class only: a glance fault must never trip the lock's crash guard (QA Medium 2).
 */
class GlancePanel(
    private val activity: AppCompatActivity,
    frame: FrameLayout,
    /** The keypad stage under the page: hidden from accessibility while covered. */
    private val keypad: View,
    row: ViewLockMediaRowBinding,
    /** The stage changed: the activity renders the keypad's prompt. */
    private val onStage: () -> Unit,
    private val onEmergency: () -> Unit,
) {
    private val glance = ViewLockGlanceBinding.inflate(activity.layoutInflater, frame, true)
    private val music = MusicLockPanel(activity, glance, row) { render() }
    private var started = false

    /** The stage (memory only). */
    var state = GlanceStageState.GLANCE
        private set

    /** `PinLockRuntime.screenOffs` when the stage was last decided (null = not yet). */
    private var decidedAt: Int? = null
    private var tileUp = false
    private var rows: List<GlanceRow> = emptyList()
    private val rowViews = mutableListOf<ViewLockGlanceRowBinding>()

    /** The root height the current fit was made for (0 = a new fit is due). */
    private var fitFor = 0
    private var fit: GlanceFit? = null

    private val labels = HashMap<String, String>()
    private val missingIcons = LinkedHashSet<String>()
    private val failedIcons = HashSet<String>()
    private var iconJob: Job? = null
    private val iconSizePx = KidAvatars.dp(activity, 36f)
    private val changed: () -> Unit = { render() }

    init {
        KidInsets.apply(glance.root)
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        val datePattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        glance.lockGlanceDate.format12Hour = datePattern
        glance.lockGlanceDate.format24Hour = datePattern
        glance.lockGlanceUnlock.setOnClickListener { step(GlanceStageEvent.Unlock) }
        glance.lockGlanceEmergency.setOnClickListener { onEmergency() }
        // The fit needs the page's height: never before the first layout, again after every change
        // (gesture <-> 3-button navigation, QA Low 4).
        glance.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > 0 && bottom - top != fitFor) guarded("fit") { fitNow() }
        }
    }

    /** onStart: watching the rows and the session; the stage decided if the lock comes up now. */
    fun start() = guarded("start") {
        if (!started) {
            started = true
            GlanceStore.addListener(changed)
            MissedCallNotifier.addListener(changed)
            music.start()
        }
        resumeNow()
    }

    /** onResume (and onStart): the glance page when the lock comes up with the screen on
     * ([lockStageDue]); otherwise the stage the kid is on (back from the emergency dialer, an alarm,
     * a call). */
    fun resume() = guarded("resume") { resumeNow() }

    private fun resumeNow() {
        if (!started) return
        val interactive = activity.getSystemService(PowerManager::class.java)?.isInteractive == true
        val offs = PinLockRuntime.screenOffs
        if (lockStageDue(interactive, decidedAt, offs)) {
            decidedAt = offs
            tileUp = false
            state = glanceStageStep(state, GlanceStageEvent.LockUp)
            onStage()
        }
        renderNow()
    }

    /** onStop (and onDestroy): nothing watched, ticking or drawn while the lock isn't shown. */
    fun stop() = guarded("stop") {
        iconJob?.cancel()
        iconJob = null
        music.stop()
        if (!started) return@guarded
        started = false
        GlanceStore.removeListener(changed)
        MissedCallNotifier.removeListener(changed)
        labels.clear()
    }

    /** The keypad's back arrow: the glance page again. */
    fun back() = step(GlanceStageEvent.Back)

    private fun step(event: GlanceStageEvent) = guarded("stage") {
        state = glanceStageStep(state, event)
        renderNow()
        onStage()
    }

    /** Re-render (rows, the session, a store change): never throws. */
    private fun render() = guarded("render") { renderNow() }

    private fun renderNow() {
        val shown = state.stage == LockStage.GLANCE
        glance.root.visibility = if (shown) View.VISIBLE else View.GONE
        keypad.importantForAccessibility =
            if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        tileUp = glanceTile(music.lookUp(), tileUp)
        try {
            rows = GlanceStore.rows(MissedCallNotifier.current)
            bindRows()
        } catch (e: Throwable) {
            // A row fault hides the rows; the page, "Lås opp" and "Nødsamtale" stay.
            Log.w(LOG_TAG, "Glance rows failed: ${e.javaClass.simpleName}")
            rows = emptyList()
            rowViews.forEach { it.root.visibility = View.GONE }
        }
        // The tile's content first (the fit measures it), then the fit decides which tile shows.
        music.show(fit?.tile?.takeIf { tileUp } ?: if (tileUp) GlanceTileSize.FULL else GlanceTileSize.NONE)
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
                view.root.setOnClickListener { step(GlanceStageEvent.Row(null)) }
            }
        }
        if (missingIcons.isNotEmpty()) renderIcons()
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
        val subtitle = when {
            row.level == GlanceLevel.APP_ONLY -> res.getQuantityString(R.plurals.glance_notices, row.count, row.count)
            row.level == GlanceLevel.TEXT && row.text != null -> row.text
            else -> "$label · $messages"
        }
        val time = timeText(row.timeMs)
        view.root.visibility = View.VISIBLE
        view.glanceRowTitle.text = title
        view.glanceRowSubtitle.text = subtitle
        view.glanceRowSubtitle.visibility = View.VISIBLE
        view.glanceRowTime.text = time
        view.glanceRowInitial.visibility = View.GONE
        view.glanceRowBadge.visibility = View.GONE
        val icon = appIcon(row.packageName)
        if (icon != null) {
            view.glanceRowIcon.backgroundTintList = null
            view.glanceRowIcon.setImageBitmap(icon)
        } else {
            // A placeholder circle until the background job has drawn Home's icon (QA Low 6).
            view.glanceRowIcon.setImageDrawable(null)
            view.glanceRowIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.kid_music_control))
        }
        val count = if (row.level == GlanceLevel.APP_ONLY) subtitle else messages
        describe(
            view,
            listOfNotNull(label, row.title, row.text.takeIf { row.level == GlanceLevel.TEXT }, count, time).distinct().joinToString(", "),
            activity.getString(R.string.glance_open_action),
        )
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

    private fun phoneGlyph(view: ViewLockGlanceRowBinding) {
        view.glanceRowIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.kid_emergency))
        view.glanceRowIcon.setImageResource(R.drawable.ic_kid_phone)
        view.glanceRowIcon.scaleType = android.widget.ImageView.ScaleType.CENTER
    }

    /** One TalkBack node per row (§1.2): its description, and the click action named for what the
     * code opens. */
    private fun describe(view: ViewLockGlanceRowBinding, description: String, action: String) {
        view.glanceRowIcon.scaleType = if (view.glanceRowIcon.drawable is android.graphics.drawable.BitmapDrawable) {
            android.widget.ImageView.ScaleType.CENTER_CROP
        } else {
            view.glanceRowIcon.scaleType
        }
        view.root.contentDescription = description
        view.root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, action))
            }
        }
    }

    /** The app's name as the kid sees it (the parent's, the kid's rename, the app's own). */
    private fun label(packageName: String): String =
        labels.getOrPut(packageName) { AppDisplay.shownLabel(activity, packageName) }

    /** `HH:mm` today, "i går" / "yesterday", else `d. MMM` (§1.2). */
    private fun timeText(ms: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        return when (relativeDay(day, LocalDate.now(zone).toEpochDay())) {
            RelativeDay.TODAY -> DateFormat.format(if (DateFormat.is24HourFormat(activity)) "HH:mm" else "h:mm", ms).toString()
            RelativeDay.YESTERDAY -> activity.getString(R.string.glance_yesterday)
            RelativeDay.EARLIER -> DateFormat.format(DateFormat.getBestDateTimePattern(locale, "dMMM"), ms).toString()
        }
    }

    // ---- icons: Home's, drawn off the main thread (QA Low 6) ----------------------------------------

    private fun iconKey(packageName: String): Pair<String, () -> Drawable> {
        val app = activity.application as? com.kidslauncher.mdm.Application
        val info = app?.apps?.value?.firstOrNull { (it.getRawInfo() as? AppInfo)?.packageName == packageName }
        return if (info != null) {
            info.getRawInfo().serialize() to { info.getIcon(activity) }
        } else {
            AppInfo(packageName, null).serialize() to { activity.packageManager.getApplicationIcon(packageName) }
        }
    }

    /** An already drawn icon, or null (then it is drawn in the background). Main thread, no work. */
    private fun appIcon(packageName: String): Bitmap? {
        val cached = KidAvatars.cachedAppIcon(activity, iconKey(packageName).first, iconSizePx)
        if (cached == null && packageName !in failedIcons) missingIcons += packageName
        return cached
    }

    /** One background job draws the missing icons (Home's renderAppIcon), then the rows bind again. */
    private fun renderIcons() {
        if (iconJob?.isActive == true || !started) return
        val wanted = missingIcons.toList()
        missingIcons.clear()
        val keys = wanted.associateWith { iconKey(it) }
        iconJob = activity.lifecycleScope.launch {
            try {
                val drawn = withContext(Dispatchers.Default) {
                    keys.filter { (pkg, key) ->
                        try {
                            KidAvatars.renderAppIcon(activity, key.first, key.second, iconSizePx)
                            true
                        } catch (e: Throwable) {
                            Log.w(LOG_TAG, "Couldn't draw the icon of $pkg: ${e.javaClass.simpleName}")
                            failedIcons += pkg
                            false
                        }
                    }
                }
                if (started && drawn.isNotEmpty()) render()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(LOG_TAG, "Glance icons failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---- the fit (§1.2) ---------------------------------------------------------------------------

    /** Measures every part at the page's width once, picks the first candidate that fits and shows it.
     * Only once the page is laid out; a re-render starts again from candidate 1. */
    private fun fitNow() {
        val root = glance.root
        val height = root.height
        if (height <= 0 || root.visibility != View.VISIBLE) return
        val width = root.width - root.paddingStart - root.paddingEnd
        if (width <= 0) return
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
            fixed = withMargins(glance.lockGlanceUnlock, width) + withMargins(glance.lockGlanceEmergency, width),
            clockBig = clockBig,
            clockSmall = clockSmall,
            date = withMargins(glance.lockGlanceDate, width),
            tileFull = withMargins(glance.lockMediaTile, width),
            tileCompact = withMargins(glance.lockMediaCompact, width),
            rowsTop = dp(ROWS_TOP_DP).toFloat(),
            rowsTopNoTile = dp(ROWS_TOP_NO_TILE_DP).toFloat(),
            row = shownRows.maxOfOrNull { measured(it.root, width) } ?: 0f,
            rowGap = dp(8f).toFloat(),
            more = measured(more, width),
        )
        val next = glanceFit(available, heights, tileUp, rows.size)
        if (next != fit) Log.i(LOG_TAG, "Glance fit: candidate ${next.candidate}, ${next.rows} rows, ${next.more} more")
        fit = next
        apply(next, shownRows)
    }

    private fun apply(fit: GlanceFit, shownRows: List<ViewLockGlanceRowBinding>) {
        glance.lockGlanceClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fit.bigClock) CLOCK_BIG_SP else CLOCK_SMALL_SP)
        glance.lockGlanceDate.visibility = if (fit.date) View.VISIBLE else View.GONE
        music.show(fit.tile)
        val list = glance.lockGlanceList
        list.visibility = if (fit.rows == 0 && fit.more == 0) View.GONE else View.VISIBLE
        val top = dp(if (fit.tile == GlanceTileSize.NONE) ROWS_TOP_NO_TILE_DP else ROWS_TOP_DP)
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
