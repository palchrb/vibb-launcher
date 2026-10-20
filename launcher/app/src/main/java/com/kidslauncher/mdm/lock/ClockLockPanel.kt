package com.kidslauncher.mdm.lock

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.badges.ClockReader
import com.kidslauncher.mdm.databinding.ViewLockGlanceBinding

/**
 * The glance page's timer tile (design 42 §1.4): binds [ClockReader.current] - the pinned clock
 * app's running timer, stopwatch or ended timer, parsed from its notification - into
 * `lock_timer_tile`, moves the time locally once a second between the app's own updates
 * ([clockTileTime]: down for a timer, stopping at 0:00; up for the stopwatch; every update corrects
 * it), and tells the panel when it came, went or changed ([onChange]; the panel coalesces and renders
 * this tile alone, round 6's rule). An ended timer shows its text and the "Stopp" button (wired by
 * the panel to `ClockActions`). The panel decides whether the tile shows ([show]). The tick sets only
 * the time's text and the description: no layout. It opens nothing itself.
 */
class ClockLockPanel(
    private val activity: Activity,
    private val glance: ViewLockGlanceBinding,
    private val onChange: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var shown = false
    private var pageShown = false
    private val ticker = Runnable { tick() }
    private val changed: () -> Unit = { onChange() }

    /** Whether the clock app shows something now (the panel's fit input). */
    val up: Boolean get() = ClockReader.current != null

    fun start() {
        if (started) return
        started = true
        ClockReader.addListener(changed)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        if (!started) return
        started = false
        ClockReader.removeListener(changed)
    }

    /** The panel's choice: the tile shows ([visible]) on the glance page ([page]: the tick runs only then). */
    fun show(visible: Boolean, page: Boolean) {
        val tile = ClockReader.current
        shown = visible && tile != null
        pageShown = page
        glance.lockTimerTile.visibility = if (shown) View.VISIBLE else View.GONE
        val ended = tile?.kind == ClockKind.ENDED
        glance.lockTimerLabel.setText(if (tile?.kind == ClockKind.STOPWATCH) R.string.lock_stopwatch else R.string.lock_timer)
        // An ended timer's text is its big line; the small one is for a running timer's "1 timer er i gang".
        val small = tile?.text?.takeIf { !ended }
        glance.lockTimerText.text = small
        glance.lockTimerText.visibility = if (small != null) View.VISIBLE else View.GONE
        glance.lockTimerStop.visibility = if (ended && ClockReader.stopIntent != null) View.VISIBLE else View.GONE
        tick()
    }

    private fun tick() {
        handler.removeCallbacks(ticker)
        val tile = ClockReader.current ?: return
        val now = SystemClock.elapsedRealtime()
        val time = clockTileTime(tile, ClockReader.receivedAtMs, now)
        glance.lockTimerTime.text = time
        glance.lockTimerTile.contentDescription = activity.getString(R.string.lock_timer_described, glance.lockTimerLabel.text, time.orEmpty())
        if (clockTicks(tile, ClockReader.receivedAtMs, now, shown && pageShown)) handler.postDelayed(ticker, 1_000)
    }
}
