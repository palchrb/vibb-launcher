package com.kidslauncher.mdm.ui.kidsettings

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityKidSoundPickerBinding
import com.kidslauncher.mdm.databinding.ItemKidSoundBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.ui.UIObjectActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The kid's ringtone or message sound picker ([SoundPicks], mockup "Velg ringetone"), opened by Kid Settings' row: the
 * phone's built-in sounds as radio rows ([pickerRows]); a tap plays the sound ([KidSounds.Preview], the media stream)
 * and, when it isn't the phone's already, makes it the default ([pickerTapWrites], [KidSounds.set]); the checked row
 * is then read back from the phone, so a refused write flips back. The sound stops when the page goes. Not exported,
 * our own page in the launcher's lock task; it leaves when the parent's sound bit goes or the app-op is gone, and a
 * lock comes first.
 */
class KidSoundPickerActivity : UIObjectActivity() {
    private lateinit var binding: ActivityKidSoundPickerBinding
    private lateinit var kind: SoundKind
    private lateinit var preview: KidSounds.Preview

    /** The phone's sounds as first listed (a failed or empty listing is read again on the next resume) and the leading
     * row ([leadSound]), taken once when the page opened and kept across a recreate ([onSaveInstanceState]); main thread. */
    private var sounds: List<PickerSound>? = null
    private var lead: PickerSound? = null
    private var leadTaken = false
    private var saved: String? = null

    /** The rows drawn now: their sounds and buttons, rebuilt only when the sounds change. */
    private var drawn: List<PickerSound> = emptyList()
    private var buttons: List<RadioButton> = emptyList()
    private var writesRunning = 0
    private var loadJob: Job? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            LauncherPreferences.mdm().keys().kidModePolicy() -> finishIfNotAllowed()
            LauncherPreferences.mdm().keys().lockReason() -> redirectIfLocked()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra(EXTRA_KIND)?.let { name -> SoundKind.entries.firstOrNull { it.name == name } }
            ?: SoundKind.RINGTONE
        binding = ActivityKidSoundPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preview = KidSounds.Preview(this)
        if (savedInstanceState?.getBoolean(STATE_LEAD_TAKEN) == true) {
            leadTaken = true
            val uri = savedInstanceState.getString(STATE_LEAD_URI)
            val title = savedInstanceState.getString(STATE_LEAD_TITLE)
            lead = if (uri != null && title != null) PickerSound(uri, title) else null
        }
        KidInsets.apply(binding.root)
        KidHeader.bind(this, binding.kidSoundHeader, titleFor(kind))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LEAD_TAKEN, leadTaken)
        lead?.let {
            outState.putString(STATE_LEAD_URI, it.uri)
            outState.putString(STATE_LEAD_TITLE, it.title)
        }
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        if (redirectIfLocked() || finishIfNotAllowed()) return
        load()
    }

    /** Leaving stops the sound (the footer's promise), and so does the screen going off. */
    override fun onStop() {
        preview.stop()
        LauncherPreferences.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    /** A belt to [onStop]: the player is never left behind. */
    override fun onDestroy() {
        preview.stop()
        super.onDestroy()
    }

    private fun redirectIfLocked(): Boolean {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false
        preview.stop()
        LockActivity.start(this)
        finish()
        return true
    }

    /** True when it finished: the rows aren't Kid Settings' any more (the parent's sound bit, or the app-op). */
    private fun finishIfNotAllowed(): Boolean {
        if (isFinishing) return true
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val section = controlsSection(dpm.isDeviceOwnerApp(packageName), cachedPolicy())
        val state = soundPicksState((section as? ControlsSection.Rows)?.sound == true, KidSounds.canWrite(this))
        if (state?.writable == true) return false
        preview.stop()
        finish()
        return true
    }

    /** The phone's list (once) and its current sound, off the main thread; then the rows. */
    private fun load() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val list = sounds ?: KidSounds.sounds(this@KidSoundPickerActivity, kind).also { if (it.isNotEmpty()) sounds = it }
            val (uri, value) = KidSounds.currentValue(this@KidSoundPickerActivity, kind)
            saved = uri
            if (!leadTaken && list.isNotEmpty()) {
                lead = leadSound(list, uri, (value as? SoundValue.Named)?.title)
                leadTaken = true
            }
            if (writesRunning == 0) render(list)
        }
    }

    private fun render(list: List<PickerSound>) {
        val rows = pickerRows(list, lead, saved)
        val group = binding.kidSoundList
        binding.kidSoundMessage.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) binding.kidSoundMessage.setText(R.string.kid_sound_empty)
        group.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        val shown = rows.map { it.sound }
        if (shown != drawn) {
            group.removeAllViews()
            buttons = rows.map { row ->
                ItemKidSoundBinding.inflate(layoutInflater, group, false).root.also { button ->
                    button.id = View.generateViewId()
                    button.text = row.sound.title
                    group.addView(button)
                    // The click has already checked the row; the read-back puts back what the phone has.
                    button.setOnClickListener { tap(row.sound) }
                }
            }
            drawn = shown
        }
        val checked = rows.indexOfFirst { it.checked }
        if (checked >= 0) group.check(buttons[checked].id) else group.clearCheck()
    }

    private fun tap(sound: PickerSound) {
        preview.play(sound.uri)
        if (!pickerTapWrites(saved, sound.uri)) return
        writesRunning++
        lifecycleScope.launch {
            try {
                KidSounds.set(this@KidSoundPickerActivity, kind, sound.uri)
            } finally {
                writesRunning--
            }
            if (writesRunning == 0) load()
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val STATE_LEAD_TAKEN = "lead_taken"
        private const val STATE_LEAD_URI = "lead_uri"
        private const val STATE_LEAD_TITLE = "lead_title"

        fun titleFor(kind: SoundKind): Int = when (kind) {
            SoundKind.RINGTONE -> R.string.kid_settings_ringtone
            SoundKind.MESSAGE -> R.string.kid_settings_message_sound
        }

        fun start(context: Context, kind: SoundKind) {
            context.startActivity(Intent(context, KidSoundPickerActivity::class.java).putExtra(EXTRA_KIND, kind.name))
        }
    }
}
