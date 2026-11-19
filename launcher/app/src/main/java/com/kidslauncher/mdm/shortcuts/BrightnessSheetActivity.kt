package com.kidslauncher.mdm.shortcuts

import android.app.Activity
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.core.view.WindowCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.setUpKidSheet
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.ui.look.lookOver

/**
 * Snarveier's Lysstyrke (2026-11-19, the user's mockup "Valg 1"): a tap opens a small sheet at the bottom over whatever
 * is in front - the title, a small sun, kid Settings' slider (0-255, [QuickControls.setBrightness] while dragging) and a
 * big sun, nothing else. Tap outside, Back or a drag down closes it.
 *
 * Started from the panel's cell ([shortcutWindow]): Android closes the shade to start an activity from a notification
 * (no `ACTION_CLOSE_SYSTEM_DIALOGS` on 12+). The window is [ShortcutAirplaneActivity]'s one pixel ([claimNoBars]), so
 * the bars stay as the app in front left them; the sheet is a [Dialog] over it (`KidSheetDialog`, [setUpKidSheet]: edge
 * to edge, the drag down) with its own scrim, the screen dimmed behind it (the mockup; code QA M3: a window dim would
 * leave 3-button navigation's buttons white on Lys's white sheet). The bars' buttons take the look's ink. It shows only once [ShortcutsRuntime.verdict] - the one tap check, on the tap
 * thread - allows Lysstyrke now; anything else finishes at once.
 *
 * - **The kiosk**: its own task (`taskAffinity=""`, `excludeFromRecents`, `noHistory`), the launcher's own package, so
 *   lock task lets it start; it holds no link, button or intent out, and it finishes when it leaves the front
 *   ([onStop]) - Home, the lock, the screen going off.
 * - **The lock screen**: never over it. Snarveier is hidden on every keyguard (`VISIBILITY_SECRET`; checked on a Swipe
 *   one too, Android 14 and 16, code QA L1), and the tap check refuses Lysstyrke there as every other item, and under
 *   Vibb's own lock (LOCKED).
 * - **The look**: the kid's (Mørk or Lys, [lookOver]): this activity's context with the look's night bit and the page
 *   colours, so the layout's page tokens resolve as on a page.
 */
class BrightnessSheetActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var sheet: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        claimNoBars()
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        val app = applicationContext
        ShortcutsRuntime.taps.execute {
            val verdict = try {
                ShortcutsRuntime.verdict(app, ShortcutKey.BRIGHTNESS.wire)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Lysstyrke: the check failed: ${e.javaClass.simpleName}")
                null
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (verdict == ShortcutTap.ALLOW) show() else finish()
            }
        }
    }

    /** One pixel in the top-left corner, as [ShortcutAirplaneActivity]'s: a window that covers nothing claims no bars. */
    private fun claimNoBars() {
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(1, 1)
    }

    private fun show() {
        val context = lookOver(this)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_brightness, null)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)
        view.findViewById<SeekBar>(R.id.brightness_sheet_seekbar).apply {
            progress = QuickControls.currentBrightness(this@BrightnessSheetActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) QuickControls.setBrightness(dpm, admin, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        val dialog = Dialog(context, R.style.KidSheetDialog)
        dialog.setUpKidSheet(view, ownScrim = true)
        dialog.window?.let {
            // The status bar over the scrim stays light-on-dark; the navigation bar's buttons sit on the sheet, in the
            // look's ink. No contrast scrim: Android shows a bar with one as semi-transparent and ignores its light flag.
            it.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = context.resources.getBoolean(R.bool.kid_page_light_bars)
            }
        }
        dialog.setOnDismissListener { finish() }
        sheet = dialog
        dialog.show()
    }

    /** Leaving the front - Home, another app, the lock, the screen off - ends it: nothing of it stays behind. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        sheet?.setOnDismissListener(null)
        sheet?.dismiss()
        sheet = null
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "Shortcuts"
    }
}
