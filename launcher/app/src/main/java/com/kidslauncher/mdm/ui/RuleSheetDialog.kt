package com.kidslauncher.mdm.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.assistant.AirplaneRoute
import com.kidslauncher.mdm.assistant.AirplaneRowState
import com.kidslauncher.mdm.assistant.AirplaneRuntime
import com.kidslauncher.mdm.assistant.RuleSheet
import com.kidslauncher.mdm.assistant.airplaneLineRes
import com.kidslauncher.mdm.assistant.airplaneRow
import com.kidslauncher.mdm.databinding.ViewRuleSheetBinding
import com.kidslauncher.mdm.server.QuickControls

/**
 * Design 98 §4.3 (concept C): the time-rule screen's sheet - Flymodus (the kid's own switch, §4.1's states and lines,
 * Off-only included) and Foreldrekode (§4.4). A tap outside or Back closes it; it closes when the rule ends
 * ([LockActivity] finishes). Plain taps only: gesture and 3-button navigation alike; its bottom sits above the
 * navigation bar in both ([KidInsets] on the root).
 */
class RuleSheetDialog(
    private val activity: Activity,
    /** §4.3's rows as [com.kidslauncher.mdm.assistant.ruleSheet] decides them now. */
    private val sheet: () -> RuleSheet,
    /** The airplane row's inputs: bit 64 with the role. */
    private val fullAirplane: () -> Boolean,
    private val onParentCode: () -> Unit,
) : AppCompatDialog(activity, R.style.RuleSheetTheme) {
    private lateinit var binding: ViewRuleSheetBinding

    private val airplaneListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        val app = activity.applicationContext
        Thread { if (checked) AirplaneRoute.turnOn(app) else AirplaneRoute.turnOff(app) }.start()
        render()
    }

    private val changed: () -> Unit = { if (isShowing) render() }

    /** The row's own redraw while its cool-down or failure line shows. */
    private val redraw = Runnable { if (isShowing) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewRuleSheetBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.let { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false) }
        KidInsets.apply(binding.root)
        setCanceledOnTouchOutside(true)
        // The scrim is "outside": a tap there closes the sheet; the sheet itself takes its taps.
        binding.ruleSheetRoot.setOnClickListener { dismiss() }
        binding.ruleSheetAirplaneSwitch.setOnCheckedChangeListener(airplaneListener)
        binding.ruleSheetParentCode.setOnClickListener {
            dismiss()
            onParentCode()
        }
    }

    override fun onStart() {
        super.onStart()
        AirplaneRuntime.addListener(changed)
        render()
    }

    override fun onStop() {
        AirplaneRuntime.removeListener(changed)
        if (::binding.isInitialized) binding.root.removeCallbacks(redraw)
        super.onStop()
    }

    fun render() {
        if (!::binding.isInitialized) return
        val rows = sheet()
        if (!rows.button) {
            dismiss()
            return
        }
        binding.ruleSheetParentCode.visibility = if ("parent_code" in rows.rows) View.VISIBLE else View.GONE
        val showAirplane = "airplane" in rows.rows
        binding.ruleSheetAirplaneRow.visibility = if (showAirplane) View.VISIBLE else View.GONE
        if (!showAirplane) return
        val context = activity
        val now = System.currentTimeMillis()
        val full = fullAirplane()
        val restricted = AirplaneRuntime.restricted(context)
        val on = AirplaneRuntime.isOn(context)
        val wifiUp = QuickControls.isWifiEnabled(context)
        val busy = AirplaneRoute.busy
        val state = AirplaneRuntime.rowState(context, now) { error ->
            airplaneRow(
                bit = full, role = full,
                restricted = restricted,
                on = on,
                wifiUp = wifiUp,
                busy = busy,
                lastError = error?.first,
                errorAgeMs = error?.let { now - it.second },
            )
        }
        binding.ruleSheetAirplaneSwitch.setOnCheckedChangeListener(null)
        binding.ruleSheetAirplaneSwitch.isChecked = state.checked
        binding.ruleSheetAirplaneSwitch.setOnCheckedChangeListener(airplaneListener)
        binding.ruleSheetAirplaneSwitch.isEnabled = state.clickable
        binding.ruleSheetAirplaneSwitch.alpha = if (state.dimmed) 0.5f else 1.0f
        val line = airplaneLineRes(state)
        binding.ruleSheetAirplaneLine.visibility = if (line != null) View.VISIBLE else View.GONE
        line?.let { binding.ruleSheetAirplaneLine.setText(it) }
        // One pending redraw at most (code QA L3): every listener-driven render would start another chain.
        binding.root.removeCallbacks(redraw)
        if (state == AirplaneRowState.COOLDOWN || state == AirplaneRowState.FAILED) binding.root.postDelayed(redraw, 2_000)
    }
}
