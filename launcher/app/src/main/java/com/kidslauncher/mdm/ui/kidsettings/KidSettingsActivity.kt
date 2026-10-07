package com.kidslauncher.mdm.ui.kidsettings

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityKidSettingsBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.SoundChoice
import com.kidslauncher.mdm.server.SoundNote
import com.kidslauncher.mdm.server.SoundRowState
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.describeSoundRow
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.ui.quickcontrols.BluetoothDevicesActivity
import com.kidslauncher.mdm.ui.quickcontrols.WifiNetworksActivity
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.wallpaper.InkChoice
import com.kidslauncher.mdm.ui.wallpaper.KidInk
import com.kidslauncher.mdm.ui.wallpaper.Wallpaper
import com.kidslauncher.mdm.ui.wallpaper.WallpaperFill
import com.kidslauncher.mdm.ui.wallpaper.WallpaperGround
import com.kidslauncher.mdm.ui.wallpaper.WallpaperRender
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore

/**
 * The kid's own Settings (design 08-ui-polish.md §2, mockup KidSettings.dc.html), opened by the
 * Home grid's last tile and by swiping left on Home. It replaces Quick Controls: the same
 * device-owner switches ([QuickControls] - never Android's Quick Settings tiles or
 * `Settings.Panel` intents, so no third-party tile or system screen is reachable from here, see
 * KidScreensEscapeTest), shown only as the parent's `quick_controls_mask` allows
 * ([controlsSection]), plus the wallpaper picker. Nothing here is PIN-gated because nothing here
 * changes a rule; the parent's Settings stay in the drawer behind the PIN.
 *
 * The screen re-renders when a sync stores a new policy (the mask can change while it is open).
 * The sound row (design 18) re-reads the phone on every ringer-mode or Do Not Disturb change
 * while the screen is shown - the volume keys change it underneath.
 */
class KidSettingsActivity : UIObjectActivity() {
    private lateinit var binding: ActivityKidSettingsBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private var lastLogged: String? = null
    private var lastSoundLogged: String? = null
    private var section: ControlsSection? = null
    private var ink: InkChoice? = null

    /** Volume keys, the volume panel or a DND schedule changed the ringer: show what is true now. */
    private val soundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            section?.let { renderControls(it) }
        }
    }

    private val wallpaperListener: () -> Unit = { render() }
    /** A thumbnail arrived: only the picker is drawn again (qa-08-code.md #4). */
    private val thumbnailListener: () -> Unit = {
        val wallpaper = WallpaperStore.state
        renderWallpapers(wallpaperTiles(wallpaper.choices, wallpaper.current))
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            LauncherPreferences.mdm().keys().kidModePolicy() -> {
                render()
                WallpaperStore.refreshAsync(this)
            }
            // A time rule or the budget began while this screen was open: the lock comes first.
            LauncherPreferences.mdm().keys().lockReason() -> redirectIfLocked()
        }
    }

    private val wifiListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        QuickControls.setWifiEnabled(this, checked)
        setManageEnabled(binding.kidSettingsWifiManage, checked)
    }

    private val bluetoothListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        QuickControls.setBluetoothEnabled(this, dpm, admin, checked)
        setManageEnabled(binding.kidSettingsBluetoothManage, checked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKidSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)

        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)
        com.kidslauncher.mdm.ui.KidHeader.bind(this, binding.kidSettingsHeader, R.string.kid_settings_title)

        binding.kidSettingsWifiSwitch.setOnCheckedChangeListener(wifiListener)
        binding.kidSettingsWifiManage.setOnClickListener {
            if (it.isEnabled) startActivity(Intent(this, WifiNetworksActivity::class.java))
        }
        binding.kidSettingsBluetoothSwitch.setOnCheckedChangeListener(bluetoothListener)
        binding.kidSettingsBluetoothManage.setOnClickListener {
            if (it.isEnabled) startActivity(Intent(this, BluetoothDevicesActivity::class.java))
        }
        for ((button, choice) in soundButtons()) {
            // The click has already checked the button; the render puts back what the phone has.
            button.setOnClickListener {
                QuickControls.setSound(this, choice)
                section?.let { renderControls(it) }
            }
        }
        binding.kidSettingsBrightnessSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) QuickControls.setBrightness(dpm, admin, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefsListener)
        WallpaperStore.addListener(wallpaperListener)
        WallpaperStore.addThumbnailListener(thumbnailListener)
        val soundChanges = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(this, soundReceiver, soundChanges, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (redirectIfLocked()) return
        // Also refreshes whether the system wallpaper is still ours (in the background).
        WallpaperStore.refreshAsync(this)
        render()
    }

    override fun onStop() {
        LauncherPreferences.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(prefsListener)
        WallpaperStore.removeListener(wallpaperListener)
        WallpaperStore.removeThumbnailListener(thumbnailListener)
        runCatching { unregisterReceiver(soundReceiver) }
        super.onStop()
    }

    private fun redirectIfLocked(): Boolean {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false
        LockActivity.start(this)
        return true
    }

    /** The kid's wallpaper behind this screen too, the same way as Home. */
    override fun showsSystemWallpaper() = true

    private fun render() {
        val wallpaper = WallpaperGround.apply(this, binding.root)
        val model = kidSettingsModel(
            dpm.isDeviceOwnerApp(packageName), cachedPolicy(), wallpaper.choices, wallpaper.current,
        )
        renderInk(wallpaper.ink)
        renderWallpapers(model.wallpapers)
        val section = model.controls
        val described = section.describe()
        if (described != lastLogged) {
            // So "no switches" can be told apart on a device (design 08 §2, QA 08 #9).
            Log.i(TAG, "Connection and screen: $described")
            lastLogged = described
        }
        this.section = section
        renderControls(section)
    }

    private fun renderControls(section: ControlsSection) {
        val message = when (section) {
            ControlsSection.NotOwner -> R.string.kid_settings_not_owner
            ControlsSection.NoPolicyYet -> R.string.kid_settings_no_policy
            ControlsSection.Unreadable -> R.string.kid_settings_unreadable
            ControlsSection.NoneEnabled -> null
            is ControlsSection.Rows -> null
        }
        val rows = section as? ControlsSection.Rows
        val soundShown = renderSound(rows?.sound == true)
        // Mask 0 (or only the sound bit on a phone without a vibrator): no card and no heading -
        // there is simply nothing to show.
        val anyRow = rows != null && (rows.wifi || rows.bluetooth || rows.brightness || soundShown)
        binding.kidSettingsControlsHeading.visibility =
            if (section == ControlsSection.NoneEnabled || (rows != null && !anyRow)) View.GONE else View.VISIBLE
        binding.kidSettingsControlsMessage.visibility = if (message != null) View.VISIBLE else View.GONE
        message?.let { binding.kidSettingsControlsMessage.setText(it) }
        binding.kidSettingsCard.visibility = if (anyRow) View.VISIBLE else View.GONE

        binding.kidSettingsWifiRow.visibility = if (rows?.wifi == true) View.VISIBLE else View.GONE
        if (rows?.wifi == true) {
            val on = QuickControls.isWifiEnabled(this)
            // Showing the state must not switch the radio.
            binding.kidSettingsWifiSwitch.setOnCheckedChangeListener(null)
            binding.kidSettingsWifiSwitch.isChecked = on
            binding.kidSettingsWifiSwitch.setOnCheckedChangeListener(wifiListener)
            setManageEnabled(binding.kidSettingsWifiManage, on)
        }
        binding.kidSettingsBluetoothRow.visibility = if (rows?.bluetooth == true) View.VISIBLE else View.GONE
        if (rows?.bluetooth == true) {
            val on = QuickControls.isBluetoothEnabled(this)
            binding.kidSettingsBluetoothSwitch.setOnCheckedChangeListener(null)
            binding.kidSettingsBluetoothSwitch.isChecked = on
            binding.kidSettingsBluetoothSwitch.setOnCheckedChangeListener(bluetoothListener)
            setManageEnabled(binding.kidSettingsBluetoothManage, on)
        }
        binding.kidSettingsBrightnessRow.visibility = if (rows?.brightness == true) View.VISIBLE else View.GONE
        if (rows?.brightness == true) {
            binding.kidSettingsBrightnessSeekbar.progress = QuickControls.currentBrightness(this)
        }
    }

    /**
     * The sound row (design 18) when the parent's bit is on: hidden without a vibrator, read-only
     * with a note while Do Not Disturb is on ([com.kidslauncher.mdm.server.soundRowState]).
     * Returns whether it shows.
     */
    private fun renderSound(wanted: Boolean): Boolean {
        val state: SoundRowState? = if (wanted) QuickControls.soundState(this) else null
        if (wanted) {
            val described = state.describeSoundRow()
            if (described != lastSoundLogged) {
                Log.i(TAG, "Sound row: $described")
                lastSoundLogged = described
            }
        }
        binding.kidSettingsSoundRow.visibility = if (state != null) View.VISIBLE else View.GONE
        if (state == null) return false
        when (state.selected) {
            SoundChoice.SOUND -> binding.kidSettingsSoundGroup.check(R.id.kid_settings_sound_on)
            SoundChoice.SILENT_VIBRATE -> binding.kidSettingsSoundGroup.check(R.id.kid_settings_sound_vibrate)
            null -> binding.kidSettingsSoundGroup.clearCheck()
        }
        for ((button, _) in soundButtons()) {
            button.isEnabled = state.enabled
            button.alpha = if (state.enabled) 1.0f else 0.5f
        }
        val note = when (state.note) {
            SoundNote.DND_ON -> R.string.kid_settings_sound_dnd
            SoundNote.FULLY_SILENT -> R.string.kid_settings_sound_fully_silent
            SoundNote.USE_VOLUME_KEYS -> R.string.kid_settings_sound_volume_keys
            null -> null
        }
        binding.kidSettingsSoundNote.visibility = if (note != null) View.VISIBLE else View.GONE
        note?.let { binding.kidSettingsSoundNote.setText(it) }
        return true
    }

    private fun soundButtons(): List<Pair<RadioButton, SoundChoice>> = listOf(
        binding.kidSettingsSoundOn to SoundChoice.SOUND,
        binding.kidSettingsSoundVibrate to SoundChoice.SILENT_VIBRATE,
    )

    private fun renderInk(ink: InkChoice) {
        for (view in listOf(
            binding.kidSettingsHeader.kidHeaderTitle, binding.kidSettingsWallpaperHeading, binding.kidSettingsWallpaperCaption,
            binding.kidSettingsControlsHeading, binding.kidSettingsControlsMessage, binding.kidSettingsWifiSwitch,
            binding.kidSettingsBluetoothSwitch, binding.kidSettingsBrightnessLabel, binding.kidSettingsSoundLabel,
            binding.kidSettingsSoundNote,
        )) {
            KidInk.label(view, ink)
        }
        if (ink != this.ink) {
            this.ink = ink
            for ((button, _) in soundButtons()) styleSegment(button, ink)
        }
    }

    /**
     * One choice of the sound row: the chosen one filled with the accent and night text (9.9:1),
     * the other a thin ring in the ink with ink text - like the wallpaper tiles.
     */
    private fun styleSegment(button: RadioButton, ink: InkChoice) {
        KidInk.label(button, ink)
        val checked = intArrayOf(android.R.attr.state_checked)
        button.setTextColor(
            ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(getColor(R.color.kid_ground), ink.ink)),
        )
        val radius = KidAvatars.dp(this, 12f).toFloat()
        button.background = StateListDrawable().apply {
            addState(checked, GradientDrawable().apply {
                cornerRadius = radius
                setColor(getColor(R.color.kid_accent))
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = radius
                setStroke(KidAvatars.dp(this@KidSettingsActivity, 1f), (0x66 shl 24) or (ink.ink and 0xFFFFFF))
            })
        }
    }

    /**
     * The picker (mockup KidSettings): four 64 dp tiles a row, 8 dp apart, radius 12; the shown
     * one with a 3 dp ring in the ink colour and a check, the others a thin ring. TalkBack reads
     * the name and whether it is chosen. Our own grid only - never the system picker.
     */
    private fun renderWallpapers(tiles: List<WallpaperTile>) {
        binding.kidSettingsWallpaperSection.visibility = if (tiles.isEmpty()) View.GONE else View.VISIBLE
        val grid = binding.kidSettingsWallpapers
        grid.removeAllViews()
        if (tiles.isEmpty()) return
        val ink = WallpaperStore.state.ink
        val gap = KidAvatars.dp(this, 8f)
        val tileHeight = KidAvatars.dp(this, 64f)
        val radius = KidAvatars.dp(this, 12f).toFloat()
        tiles.chunked(4).forEachIndexed { rowIndex, rowTiles ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, tileHeight).apply {
                    if (rowIndex > 0) topMargin = gap
                }
            }
            for (i in 0 until 4) {
                val tile = rowTiles.getOrNull(i)
                val cell = FrameLayout(this)
                cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = gap
                }
                if (tile != null) bindTile(cell, tile, ink, radius)
                row.addView(cell)
            }
            grid.addView(row)
        }
    }

    private fun bindTile(cell: FrameLayout, tile: WallpaperTile, ink: InkChoice, radius: Float) {
        val wallpaper = tile.wallpaper
        val fill = wallpaper.fill
        val thumb = (fill as? WallpaperFill.Image)?.let { WallpaperStore.thumbnail(this, it.hash, KidAvatars.dp(this, 64f)) }
        cell.background = WallpaperRender.GroundDrawable(fill, thumb, 0)
        cell.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        cell.clipToOutline = true
        cell.foreground = GradientDrawable().apply {
            cornerRadius = radius
            if (tile.selected) {
                setStroke(KidAvatars.dp(this@KidSettingsActivity, 3f), ink.ink)
            } else {
                setStroke(KidAvatars.dp(this@KidSettingsActivity, 1f), (0x40 shl 24) or (ink.ink and 0xFFFFFF))
            }
        }
        if (tile.selected) {
            val check = ImageView(this).apply {
                setImageResource(R.drawable.ic_kid_check)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val size = KidAvatars.dp(this, 22f)
            cell.addView(check, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        val label = wallpaperLabel(wallpaper)
        cell.contentDescription = if (tile.selected) getString(R.string.kid_settings_wallpaper_selected, label) else label
        cell.isSelected = tile.selected
        cell.isClickable = true
        cell.isFocusable = true
        cell.setOnClickListener {
            if (!tile.selected) WallpaperStore.pick(this, wallpaper.id)
        }
    }

    /** Built-ins in the kid's language; uploads with the parent's name for them. */
    private fun wallpaperLabel(wallpaper: Wallpaper): String = when (wallpaper.builtinKey) {
        "vibb_night" -> getString(R.string.wallpaper_vibb_night)
        "navy" -> getString(R.string.wallpaper_navy)
        "forest" -> getString(R.string.wallpaper_forest)
        "plum" -> getString(R.string.wallpaper_plum)
        "green" -> getString(R.string.wallpaper_green)
        "sky" -> getString(R.string.wallpaper_sky)
        "sunset" -> getString(R.string.wallpaper_sunset)
        else -> wallpaper.label.ifBlank { getString(R.string.wallpaper_photo) }
    }

    /** Dims the "manage" link while its radio is off (a disabled TextView alone looks the same). */
    private fun setManageEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.4f
    }

    companion object {
        private const val TAG = "KidSettings"
    }
}
