package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.text.format.DateUtils
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityInCallBinding

/**
 * The call screen for every call [KidInCallService] lets through (design 10-lock-and-call-ui.md
 * §6, mockups IncomingCall.dc.html / InCall.dc.html): the caller's photo (contact photo, else the
 * placeholder figure) with a ring, name (from the call rules) or number, and
 * - ringing ([renderIncoming]): "Calling you…", Decline and Answer;
 * - otherwise ([renderActive]): the timer or "Calling…"/"On hold"/"Ended", Speaker and Mute (on =
 *   white circle, navy icon) and Hang up.
 * Nothing on it leads anywhere else (no contact sheet, keypad or message), so it is the same over
 * handy's PIN lock (QA 10 #16). Shown over the lock screen and turns the screen on (manifest);
 * our package is always a lock-task package, so this also works in kiosk mode and over the PIN
 * lock. Holds a proximity wake lock while a call is active. Back does nothing while a call exists,
 * and the PIN lock brings this screen back whenever it comes to the front during our call
 * (qa-10-code #1).
 * Direct-boot-aware: no contact photos before the first unlock.
 */
class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private val handler = Handler(Looper.getMainLooper())
    private var proximityLock: PowerManager.WakeLock? = null
    private var shownPhoto: String? = null

    private val listener: () -> Unit = { handler.post { render() } }
    private val photoListener: () -> Unit = { shownPhoto = null; render() }

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
        binding.inCallDecline.setOnClickListener { OngoingCalls.current?.let(OngoingCalls::hangUp) }
        binding.inCallHangUp.setOnClickListener { OngoingCalls.current?.let(OngoingCalls::hangUp) }
        binding.inCallSpeaker.setOnClickListener { OngoingCalls.toggleSpeaker(this) }
        binding.inCallMute.setOnClickListener { OngoingCalls.toggleMute() }
        // Back never leaves a ringing or active call behind the PIN lock (qa-10-code #1) - the
        // lock has no "return to call" and the shade is off while it is locked.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (OngoingCalls.calls.isEmpty()) finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // No heads-up over this screen (emulator run 2026-10-06): the notification goes silent.
        CallNotifications.screenVisibilityChanged(this, visible = true)
        OngoingCalls.addListener(listener)
        ContactPhotos.addListener(photoListener)
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        OngoingCalls.removeListener(listener)
        ContactPhotos.removeListener(photoListener)
        handler.removeCallbacks(ticker)
        releaseProximity()
        CallNotifications.screenVisibilityChanged(this, visible = false)
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
        val contact = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules?.contactFor(number)
        val name = contact?.name?.takeIf { it.isNotBlank() } ?: number ?: getString(R.string.calls_unknown_caller)
        binding.inCallName.text = name
        renderPhoto(contact?.photo, name)

        val state = call.details.state
        if (state == Call.STATE_RINGING) renderIncoming() else renderActive(call, state)

        if (state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
            acquireProximity()
        } else {
            releaseProximity()
        }
    }

    private fun renderIncoming() {
        binding.inCallStatus.setText(R.string.calls_calling_you)
        binding.inCallIncomingRow.visibility = View.VISIBLE
        binding.inCallActiveRows.visibility = View.GONE
    }

    private fun renderActive(call: Call, state: Int) {
        binding.inCallIncomingRow.visibility = View.GONE
        binding.inCallActiveRows.visibility = View.VISIBLE
        binding.inCallStatus.text = when (state) {
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL, Call.STATE_NEW,
            Call.STATE_SELECT_PHONE_ACCOUNT -> getString(R.string.calls_dialing)
            Call.STATE_HOLDING -> getString(R.string.calls_waiting)
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> getString(R.string.calls_finished)
            else -> {
                val since = call.details.connectTimeMillis
                if (since > 0) DateUtils.formatElapsedTime((System.currentTimeMillis() - since) / 1000) else ""
            }
        }
        renderToggle(binding.inCallSpeaker, binding.inCallSpeakerIcon, OngoingCalls.speakerOn)
        binding.inCallSpeakerLabel.setText(if (OngoingCalls.speakerOn) R.string.calls_speaker_on else R.string.calls_speaker)
        renderToggle(binding.inCallMute, binding.inCallMuteIcon, OngoingCalls.muted)
        binding.inCallMuteLabel.setText(if (OngoingCalls.muted) R.string.calls_muted else R.string.calls_mute)
    }

    /** On = white circle with a navy icon; off = white at 16 % with a white icon. */
    private fun renderToggle(button: View, icon: android.widget.ImageView, on: Boolean) {
        button.isActivated = on
        icon.imageTintList = ColorStateList.valueOf(getColor(if (on) R.color.kid_ground else R.color.kid_ink))
        button.stateDescription = getString(if (on) R.string.call_toggle_on else R.string.call_toggle_off)
    }

    /** The contact photo (only after the first unlock - photos are in CE storage), else the
     * placeholder figure on #DCE7F2. */
    private fun renderPhoto(hash: String?, name: String) {
        val bitmap = if (hash != null && CallPolicyStore.userUnlocked(this)) ContactPhotos.cached(this, hash) else null
        val key = if (bitmap != null) hash else null
        binding.inCallAvatar.contentDescription = getString(R.string.calls_photo_of, name)
        if (key == shownPhoto && binding.inCallPhoto.drawable != null) return
        shownPhoto = key
        if (bitmap != null) {
            binding.inCallPhoto.setImageDrawable(
                RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true },
            )
        } else {
            binding.inCallPhoto.setImageResource(R.drawable.ic_call_avatar_placeholder)
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
        /** The call screen is in front (KidInCallService's retry over the PIN lock, QA 10 #8). */
        @Volatile
        var resumed = false
            private set

        fun intent(context: Context): Intent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
