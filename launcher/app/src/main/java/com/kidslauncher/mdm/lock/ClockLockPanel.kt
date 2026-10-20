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
 * app's running timer, parsed from its notification's title - into `lock_timer_tile`, counts it down
 * locally once a second between the app's own updates ([clockTileTime]; every update corrects it,
 * it stops at 0:00), and tells the panel when the timer came, went or changed ([onChange]; the panel
 * coalesces and renders the tile alone, round 6's rule). The panel decides whether it shows ([show]).
 * The tick sets only the time's text: no layout. It opens nothing: the tile's tap is the panel's
 * (the keypad first, `GlanceTarget.ClockApp`).
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

    /** Whether a timer runs now (the panel's fit input). */
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
        glance.lockTimerText.text = tile?.text
        glance.lockTimerText.visibility = if (tile?.text != null) View.VISIBLE else View.GONE
        tick()
    }

    private fun tick() {
        handler.removeCallbacks(ticker)
        val tile = ClockReader.current ?: return
        val now = SystemClock.elapsedRealtime()
        val time = clockTileTime(tile, ClockReader.receivedAtMs, now)
        glance.lockTimerTime.text = time
        glance.lockTimerTile.contentDescription = activity.getString(R.string.lock_timer_described, time.orEmpty())
        if (clockTicks(tile, ClockReader.receivedAtMs, now, shown && pageShown)) handler.postDelayed(ticker, 1_000)
    }
}
