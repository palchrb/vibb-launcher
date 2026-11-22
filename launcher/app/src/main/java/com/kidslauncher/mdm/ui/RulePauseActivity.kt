package com.kidslauncher.mdm.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityRulePauseBinding
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.timerules.RULE_LIFT_MINUTES
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.pauseTitleRules
import com.kidslauncher.mdm.timerules.rulePausePlan
import java.time.Instant
import java.time.LocalDateTime

/**
 * Design 98 §4.4.1: Foreldrekode from the time-rule screen's sheet - choose first, then the parent PIN. The lengths are
 * the PWA's own (`RULE_LIFT_MINUTES`); only the PIN gate ([com.kidslauncher.mdm.server.ParentPin.check] with
 * [ParentPinEntry.RULE_PAUSE]) stores the pause, planned again at that instant. Then the time rules are checked again
 * (the alarm re-armed for the pause's end) and Home opens. Closes on `onStop` and at screen-off: a parent handing the
 * phone back leaves nothing a kid can finish.
 */
class RulePauseActivity : UIObjectActivity() {
    private lateinit var binding: ActivityRulePauseBinding
    private var pinDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulePauseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        binding.rulePauseButton.setOnClickListener { askPin() }
    }

    override fun onStart() {
        super.onStart()
        if (!render()) finish()
    }

    override fun onStop() {
        pinDialog?.dismiss()
        pinDialog = null
        super.onStop()
        // §4.4.1 step 5: never left behind (the screen off stops it too).
        if (!isChangingConfigurations) finish()
    }

    /** The title and the offered lengths from the rules in force now; false with nothing to pause. */
    private fun render(): Boolean {
        val snap = try {
            TimeRulesRuntime.snapshot(this, currentPolicyDecision().policy)
        } catch (e: Exception) {
            return false
        }
        val policy = snap.policy ?: return false
        if (snap.lock.rules.isEmpty() || snap.lock.budgetExhausted) return false
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(snap.clocks.wallMs), snap.zone)
        val lifts = TimeRulesRuntime.ruleLifts(this, snap.records, policy.lifts, snap.clocks)
        val plan = rulePausePlan(policy, at, snap.zone, lifts, budgetExhausted = false, minutes = null, nowMs = snap.clocks.wallMs)
        if (plan.offered.isEmpty()) return false
        val names = pauseTitleRules(snap.lock.rules)
            .map { LockActivity.labelText(this, ruleLabel(it.kind, it.name)) }.distinct()
        binding.rulePauseTitle.text = if (names.size >= 2) {
            getString(R.string.rule_pause_title_two, names[0], names[1])
        } else {
            getString(R.string.rule_pause_title_one, names.firstOrNull() ?: LockActivity.labelText(this, ruleLabel(snap.lock.rules.first().kind, snap.lock.rules.first().name)))
        }
        val checked = binding.rulePauseLengths.checkedRadioButtonId.takeIf { it != View.NO_ID }?.let { id ->
            binding.rulePauseLengths.findViewById<RadioButton>(id)?.tag as? Int
        }
        binding.rulePauseLengths.removeAllViews()
        for (minutes in plan.offered) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                tag = minutes
                text = getString(lengthRes(minutes))
                textSize = 17f
                setTextColor(getColor(R.color.kid_ink))
                buttonTintList = ColorStateList.valueOf(getColor(R.color.kid_core))
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }
            binding.rulePauseLengths.addView(button, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (minutes == (checked ?: plan.offered.first())) button.isChecked = true
        }
        return true
    }

    private fun chosen(): Int? = binding.rulePauseLengths.checkedRadioButtonId.takeIf { it != View.NO_ID }?.let { id ->
        binding.rulePauseLengths.findViewById<RadioButton>(id)?.tag as? Int
    }?.takeIf { it in RULE_LIFT_MINUTES }

    /** §4.4.1 step 2: the parent PIN keypad - the shared lockout counts these tries too. */
    private fun askPin() {
        val minutes = chosen() ?: return
        pinDialog?.dismiss()
        pinDialog = ParentPinDialog.show(
            this, lifecycleScope, ParentPinEntry.RULE_PAUSE, R.string.rule_sheet_parent_code_title, R.string.rule_pause_no_pin,
            minutes = minutes,
        ) {
            // Step 4: the lock is decided again now (Home opens when the rule is paused) and the pause goes to the server
            // at the next sync with a network.
            TimeRulesRuntime.recheck(this)
            com.kidslauncher.mdm.push.SyncRunner.request(applicationContext, "rule_pause")
            finish()
        }
    }

    companion object {
        fun lengthRes(minutes: Int): Int = when (minutes) {
            15 -> R.string.rule_pause_15
            30 -> R.string.rule_pause_30
            60 -> R.string.rule_pause_60
            else -> R.string.rule_pause_120
        }

        fun start(context: Context) {
            context.startActivity(Intent(context, RulePauseActivity::class.java))
        }
    }
}
