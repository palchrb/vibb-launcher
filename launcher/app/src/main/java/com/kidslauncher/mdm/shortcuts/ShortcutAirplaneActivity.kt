package com.kidslauncher.mdm.shortcuts

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Flymodus On from the shade (design 99 §4.1, Q99-H1): a window of ours with nothing on it, so the route starts with the
 * shade closed. Android collapses the shade when a notification starts an activity, and this window only gets focus
 * once the shade has let it go - the kid's finger is in the shade at the tap, and a touch during SystemUI's ~300 ms
 * collapse would keep it open through design 98's window (`docs/testing/emulator.md` §6am, "Addendum").
 *
 * On the first focus the tap goes to [ShortcutsRuntime.tap] (the one check, then `AirplaneRoute.turnOn`) on the tap
 * thread; the window finishes when the route returns. No focus within [FOCUS_WAIT_MS] (the shade held open): it
 * finishes without the route. Its own task (`taskAffinity=""`), so finishing returns to whatever was in front.
 */
class ShortcutAirplaneActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private val giveUp = Runnable {
        if (!started) {
            Log.i("Shortcuts", "Flymodus: no focus within ${FOCUS_WAIT_MS} ms - nothing done")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        main.postDelayed(giveUp, FOCUS_WAIT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        main.removeCallbacks(giveUp)
        Log.i("Shortcuts", "Flymodus: the window has focus - the route may start")
        val app = applicationContext
        ShortcutsRuntime.taps.execute {
            try {
                ShortcutsRuntime.tap(app, ShortcutKey.AIRPLANE.wire, window = true)
            } finally {
                main.post { finish() }
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        super.onDestroy()
    }

    private companion object {
        const val FOCUS_WAIT_MS = 3_000L
    }
}
