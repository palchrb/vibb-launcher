package com.kidslauncher.mdm.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.hardeningManaged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LOG_TAG = "VolumeCap"

/** AudioManager's hidden broadcast and extra (stable since API 1; registered receivers only). */
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

/** A new output settles its own volume a moment after it appears (BT absolute volume): looked at again then. */
private const val ROUTE_SETTLE_MS = 1_500L

/** CE prefs with the last policy cap read (`-1` = none), kept while the policy's music object can't be read. */
private const val CAP_PREFS = "volume_cap"
private const val CAP_KEY = "policy_cap_pct"

/**
 * The parent's volume cap (design 21 §3, user decision: off by default, Off/90/80/70/60 % per
 * phone, every app and every output): while managed and without the offline override or the
 * pause, `STREAM_MUSIC` above [volumeCapIndex] is set back at once, on every volume change,
 * whenever an output comes or goes, and whenever a player starts or moves to another output.
 * Never `DISALLOW_ADJUST_VOLUME` (it master-mutes the phone).
 * The cap in force comes from `AppEnforcer.apply()` ([refresh]) - every sync, override and pause
 * edge - and from the cached policy at process start.
 */
object VolumeCap {
    @Volatile
    private var pct: Int? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(app: Context) {
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) clamp(context)
                }
            },
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val am = app.getSystemService(AudioManager::class.java)
        am?.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = routeChanged(app)
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = routeChanged(app)
            },
            main,
        )
        // A player starting, or moving to another output that was already connected - the music
        // app's own "Spill av på" picker routes explicitly, with no device coming or going and no
        // volume change (qa-21-step4-code L1): each output keeps its own index.
        am?.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = routeChanged(app)
            },
            main,
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val policy = currentPolicyDecision().policy
                refresh(app, policy, hardeningManaged(policy?.allowlist, CallPolicyStore.state.managed), OfflineOverride.isActive() || RestrictionsPause.isActive())
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't read the volume cap at start", e)
            }
        }
    }

    /** The cap in force now (null = none): also the music app's `volume_cap_pct` (design 26a §5.3). */
    val pctInForce: Int? get() = pct

    @Volatile
    private var pushed = false

    /**
     * The cap for the enforced [policy] (`AppEnforcer.apply()`), applied at once. Any thread. A cap
     * in force that changed (or the first of this process) goes to the music app's managed config
     * too: its Sonos rooms are outside `STREAM_MUSIC` (design 26a §5.3).
     */
    fun refresh(context: Context, policy: PolicyResponse?, managed: Boolean, overrideActive: Boolean) {
        val before = pct
        val app = context.applicationContext
        pct = capPctInForce(policyCap(app, policy), managed, overrideActive)
        main.post { clamp(app) }
        if (!pushed || before != pct) {
            pushed = true
            MusicSync.capChanged(app, policy)
        }
    }

    /** The last policy cap read (CE prefs `volume_cap`): what a refused music object keeps (qa-30-s2-code L1). */
    @Synchronized
    private fun policyCap(app: Context, policy: PolicyResponse?): Int? {
        val prefs = app.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE)
        val remembered = prefs.getInt(CAP_KEY, -1).takeIf { it >= 0 }
        val cap = policyCapPct(policy?.music, remembered)
        if (cap != remembered) prefs.edit().putInt(CAP_KEY, cap ?: -1).commit()
        return cap
    }

    private var settle: Runnable? = null

    /** An output came, went or took a player: the cap now, and once more when it has settled (main thread). */
    private fun routeChanged(app: Context) {
        clamp(app)
        val again = settle ?: Runnable { clamp(app) }.also { settle = it }
        main.removeCallbacks(again)
        main.postDelayed(again, ROUTE_SETTLE_MS)
    }

    /** `STREAM_MUSIC` back to the cap when above it (main thread). */
    private fun clamp(context: Context) {
        val cap = pct ?: return
        try {
            val am = context.getSystemService(AudioManager::class.java) ?: return
            if (am.isVolumeFixed) return
            val index = volumeCapIndex(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), am.getStreamMinVolume(AudioManager.STREAM_MUSIC), cap)
            volumeToSet(am.getStreamVolume(AudioManager.STREAM_MUSIC), index)?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't hold the volume cap", e)
        }
    }
}
