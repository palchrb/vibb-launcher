package com.kidslauncher.mdm.ui.kidsettings

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityShortcutsPickerBinding
import com.kidslauncher.mdm.databinding.ItemShortcutPickBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.QuickControlFeature
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.shortcuts.SHORTCUTS_MAX
import com.kidslauncher.mdm.shortcuts.ShortcutItem
import com.kidslauncher.mdm.shortcuts.ShortcutKey
import com.kidslauncher.mdm.shortcuts.ShortcutsNotification
import com.kidslauncher.mdm.shortcuts.ShortcutsPlatform
import com.kidslauncher.mdm.shortcuts.ShortcutsRuntime
import com.kidslauncher.mdm.shortcuts.movePick
import com.kidslauncher.mdm.shortcuts.pickerStart
import com.kidslauncher.mdm.shortcuts.picksToStore
import com.kidslauncher.mdm.shortcuts.shortcutCandidates
import com.kidslauncher.mdm.shortcuts.shortcutItem
import com.kidslauncher.mdm.shortcuts.togglePick
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.ui.look.KidPageActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The kid's Snarveier picker (design 99 §4.2), opened by Kid Settings' "Snarveier" row: up to four of the items the
 * parent's switches allow, in the kid's order. A row's check box chooses it; a chosen row has "Flytt opp" / "Flytt ned"
 * buttons - buttons, not dragging, so gesture and 3-button navigation and TalkBack work alike. A fifth check is refused
 * with a line. Each change is stored at once ([ShortcutsRuntime.storePicks]) and the panel follows. While the kid has
 * hidden the panel (§2.6), "Vis i varsellisten igjen" brings it back on a new channel - the only place that does.
 * Never PIN-gated: it changes no rule. It leaves when bit 128 goes, and a lock comes first.
 */
class ShortcutsPickerActivity : KidPageActivity() {
    private lateinit var binding: ActivityShortcutsPickerBinding

    /** What the page shows (main thread): the candidates, the chosen ones in order, the stored picks it started from,
     * the platform it read and whether the panel is hidden. */
    private var candidates: List<ShortcutKey> = emptyList()
    private var chosen: List<ShortcutKey> = emptyList()
    private var stored: List<String>? = null
    private var platform = ShortcutsPlatform()
    private var mask = 0L
    private var hidden = false

    /** The refusal line stands in the count's place for a moment - the same height, so no row moves under the kid's
     * next tap (emulator run, 2026-11-15: a line added above the list shifted every row down). */
    private val hideFull = Runnable {
        binding.shortcutsFull.visibility = View.GONE
        binding.shortcutsCount.visibility = View.VISIBLE
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            LauncherPreferences.mdm().keys().kidModePolicy() -> if (!finishIfNotAllowed()) load()
            LauncherPreferences.mdm().keys().lockReason() -> redirectIfLocked()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        binding = ActivityShortcutsPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        KidHeader.bind(this, binding.shortcutsHeader, R.string.kid_settings_shortcuts, back = R.string.kid_back_settings)
        binding.shortcutsShowAgain.setOnClickListener {
            val context = this
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { ShortcutsRuntime.showAgain(context) }
                load()
            }
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

    override fun onStop() {
        LauncherPreferences.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(prefsListener)
        binding.root.removeCallbacks(hideFull)
        super.onStop()
    }

    private fun redirectIfLocked(): Boolean {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false
        LockActivity.start(this)
        finish()
        return true
    }

    /** True when it finished: not device owner, no accepted policy, or the parent's bit 128 is off. */
    private fun finishIfNotAllowed(): Boolean {
        if (isFinishing) return true
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val bits = (cachedPolicy() as? CachedPolicy.Ok)?.policy?.quickControlsMask
        if (dpm.isDeviceOwnerApp(packageName) && bits != null && bits and QuickControlFeature.SHORTCUTS != 0L) return false
        finish()
        return true
    }

    /** The policy's mask, the platform, the stored picks and the channel, off the main thread; then the rows. */
    private fun load() {
        val context = this
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) {
                val bits = (cachedPolicy() as? CachedPolicy.Ok)?.policy?.quickControlsMask ?: 0L
                Triple(bits, ShortcutsRuntime.platform(context, bits), ShortcutsRuntime.storedPicks(context) to ShortcutsRuntime.hidden(context))
            }
            mask = read.first
            platform = read.second
            stored = read.third.first
            hidden = read.third.second
            candidates = shortcutCandidates(mask, platform)
            chosen = pickerStart(stored, candidates)
            render()
        }
    }

    private fun render() {
        binding.shortcutsHiddenBox.visibility = if (hidden) View.VISIBLE else View.GONE
        binding.shortcutsCount.text = getString(R.string.shortcuts_picker_count, chosen.size, SHORTCUTS_MAX)
        val list = binding.shortcutsList
        list.removeAllViews()
        val order = chosen + (candidates - chosen.toSet())
        for (key in order) {
            val row = ItemShortcutPickBinding.inflate(layoutInflater, list, false)
            val name = getString(ShortcutsNotification.fullNameRes(key))
            val picked = key in chosen
            val glyph = getDrawable(ShortcutsNotification.glyphRes(shortcutItem(key, mask, platform) ?: ShortcutItem(key, on = true)))
            row.shortcutPickBox.text = name
            row.shortcutPickBox.setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
            row.shortcutPickBox.isChecked = picked
            row.shortcutPickBox.setOnClickListener { toggle(key) }
            row.root.alpha = if (picked) 1.0f else 0.55f
            // §4.2: an allowed item that doesn't show today says when it does.
            val absentToday = key == ShortcutKey.MORE_TIME && shortcutItem(key, mask, platform) == null
            row.shortcutPickLine.visibility = if (absentToday) View.VISIBLE else View.GONE
            if (absentToday) row.shortcutPickLine.setText(R.string.shortcuts_picker_more_time_line)
            val index = chosen.indexOf(key)
            for ((button, up) in listOf(row.shortcutPickUp to true, row.shortcutPickDown to false)) {
                val usable = picked && (if (up) index > 0 else index < chosen.size - 1)
                button.visibility = if (picked) View.VISIBLE else View.INVISIBLE
                button.isEnabled = usable
                button.alpha = if (usable) 1.0f else 0.3f
                button.contentDescription = getString(if (up) R.string.shortcuts_picker_move_up else R.string.shortcuts_picker_move_down, name)
                button.setOnClickListener { move(key, up) }
            }
            list.addView(row.root)
        }
    }

    private fun toggle(key: ShortcutKey) {
        val next = togglePick(chosen, key)
        if (next == null) {
            // A fifth: refused, with the line for a moment.
            binding.shortcutsCount.visibility = View.GONE
            binding.shortcutsFull.visibility = View.VISIBLE
            binding.root.removeCallbacks(hideFull)
            binding.root.postDelayed(hideFull, 3_000)
            render()
            return
        }
        save(next)
    }

    private fun move(key: ShortcutKey, up: Boolean) = save(movePick(chosen, key, up))

    private fun save(next: List<ShortcutKey>) {
        binding.root.removeCallbacks(hideFull)
        hideFull.run()
        chosen = next
        val picks = picksToStore(next, stored, candidates)
        stored = picks
        ShortcutsRuntime.storePicks(this, picks)
        render()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ShortcutsPickerActivity::class.java))
        }
    }
}
