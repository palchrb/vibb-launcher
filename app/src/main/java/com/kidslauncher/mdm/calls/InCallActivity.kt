package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityInCallBinding

/**
 * The in-call screen for every call [KidInCallService] lets through: the caller's name (from the
 * call rules) or number, status/timer, and Answer (ringing only), Hang up/Decline, Speaker, Mute.
 * Shown over the lock screen and turns the screen on (manifest), without dismissing the keyguard -
 * the kid answers from the lock screen like on any phone. Our package is always a lock-task
 * package, so this also works in kiosk mode. Holds a proximity wake lock while a call is active.
 */
class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private val handler = Handler(Looper.getMainLooper())
    private var proximityLock: PowerManager.WakeLock? = null

    private val listener: () -> Unit = { handler.post { render() } }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CallPolicyStore.ensureLoaded(this)

        binding.inCallAnswer.setOnClickListener { OngoingCalls.current?.let(OngoingCalls::answer) }
        binding.inCallHangUp.setOnClickListener { OngoingCalls.current?.let(OngoingCalls::hangUp) }
        binding.inCallSpeaker.setOnClickListener { OngoingCalls.toggleSpeaker(this) }
        binding.inCallMute.setOnClickListener { OngoingCalls.toggleMute() }
    }

    override fun onStart() {
        super.onStart()
        OngoingCalls.addListener(listener)
        handler.post(ticker)
    }

    override fun onStop() {
        OngoingCalls.removeListener(listener)
        handler.removeCallbacks(ticker)
        releaseProximity()
        super.onStop()
    }

    private fun render() {
        val call = OngoingCalls.current
        if (call == null) {
            releaseProximity()
            finish()
            return
        }
        val number = PhoneNumbers.numberFromHandle(call.details.handle?.toString())
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        binding.inCallName.text = rules?.contactFor(number)?.name ?: number ?: getString(R.string.calls_unknown_caller)

        val state = call.details.state
        binding.inCallStatus.text = when (state) {
            Call.STATE_RINGING -> getString(R.string.calls_incoming)
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> getString(R.string.calls_dialing)
            Call.STATE_HOLDING -> getString(R.string.calls_on_hold)
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> getString(R.string.calls_ended)
            else -> {
                val since = call.details.connectTimeMillis
                if (since > 0) DateUtils.formatElapsedTime((System.currentTimeMillis() - since) / 1000) else ""
            }
        }
        val ringing = state == Call.STATE_RINGING
        binding.inCallAnswer.visibility = if (ringing) View.VISIBLE else View.GONE
        binding.inCallHangUp.setText(if (ringing) R.string.calls_decline else R.string.calls_hang_up)
        binding.inCallSpeaker.setText(if (OngoingCalls.speakerOn) R.string.calls_speaker_on else R.string.calls_speaker)
        binding.inCallMute.setText(if (OngoingCalls.muted) R.string.calls_muted else R.string.calls_mute)

        if (state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
            acquireProximity()
        } else {
            releaseProximity()
        }
    }

    private fun acquireProximity() {
        if (proximityLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "kidslauncher:in_call")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseProximity() {
        proximityLock?.let { if (it.isHeld) it.release() }
        proximityLock = null
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
