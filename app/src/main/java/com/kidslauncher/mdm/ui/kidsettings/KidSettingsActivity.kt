package com.kidslauncher.mdm.ui.kidsettings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.widget.SeekBar
import android.widget.TextView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityKidSettingsBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.cachedPolicy
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
 */
class KidSettingsActivity : UIObjectActivity() {
    private lateinit var binding: ActivityKidSettingsBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private var lastLogged: String? = null

    private val wallpaperListener: () -> Unit = { render() }

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

        binding.kidSettingsBack.setOnClickListener { finish() }

        binding.kidSettingsWifiSwitch.setOnCheckedChangeListener(wifiListener)
        binding.kidSettingsWifiManage.setOnClickListener {
            if (it.isEnabled) startActivity(Intent(this, WifiNetworksActivity::class.java))
        }
        binding.kidSettingsBluetoothSwitch.setOnCheckedChangeListener(bluetoothListener)
        binding.kidSettingsBluetoothManage.setOnClickListener {
            if (it.isEnabled) startActivity(Intent(this, BluetoothDevicesActivity::class.java))
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
    }

    override fun onResume() {
        super.onResume()
        if (redirectIfLocked()) return
        WallpaperStore.ensureLoaded(this)
        render()
    }

    override fun onStop() {
        LauncherPreferences.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(prefsListener)
        WallpaperStore.removeListener(wallpaperListener)
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
        // Mask 0: no card and no heading - there is simply nothing to show.
        binding.kidSettingsControlsHeading.visibility =
            if (section == ControlsSection.NoneEnabled) View.GONE else View.VISIBLE
        binding.kidSettingsControlsMessage.visibility = if (message != null) View.VISIBLE else View.GONE
        message?.let { binding.kidSettingsControlsMessage.setText(it) }
        binding.kidSettingsCard.visibility = if (rows != null) View.VISIBLE else View.GONE

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
            val on = QuickControls.isBluetoothEnabled()
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

    private fun renderInk(ink: InkChoice) {
        for (view in listOf(
            binding.kidSettingsTitle, binding.kidSettingsWallpaperHeading, binding.kidSettingsWallpaperCaption,
            binding.kidSettingsControlsHeading, binding.kidSettingsControlsMessage, binding.kidSettingsWifiSwitch,
            binding.kidSettingsBluetoothSwitch, binding.kidSettingsBrightnessLabel,
        )) {
            KidInk.label(view, ink)
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
