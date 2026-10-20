package com.kidslauncher.mdm.torch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.lock.LockMode
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.music.CallEdge
import com.kidslauncher.mdm.music.MusicCalls
import java.util.concurrent.CopyOnWriteArrayList

private const val LOG_TAG = "Torch"

/** The auto-off alarm's request code (the other alarms: 0, 7, 11, 12). */
private const val AUTO_OFF_REQUEST = 41

/**
 * The flashlight (design 41): the back camera's flash through `CameraManager.setTorchMode` - no
 * permission, no camera session, nothing opened. Home's tile and the lock's button read [state] and
 * call [toggle]; [addListener] tells them when it changes. The state follows the platform's
 * `TorchCallback` (a call, the camera app or the system turning it off), so it is process memory
 * only - nothing stored. After a process restart the platform has already turned our torch off (a
 * dead client's torch goes off), and the callback says so. The rules are the pure [torchStep]. The
 * camera policy while LOCKED (design 25b) is never lifted: a `CAMERA_DISABLED` refusal under the lock
 * only hides the lock's button ([lockButtonShown]). Main thread.
 */
object Torch {
    private var initialised = false
    private var cameraId: String? = null

    /** `null`: no flash unit (the emulator) - no tile and no button. */
    var state: TorchState? = null
        private set

    private var refusedOnLock = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The lock's button (design 41 §1.3, §1.5). */
    val lockButtonShown: Boolean get() = torchLockButton(state, refusedOnLock)

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** From `Application.initRest` (main thread). Everything caught: no torch is no tile. */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val app = context.applicationContext
        try {
            val cameras = app.getSystemService(CameraManager::class.java) ?: return
            val id = torchCamera(
                cameras.cameraIdList.map { id ->
                    val traits = cameras.getCameraCharacteristics(id)
                    TorchCamera(
                        id = id,
                        back = traits.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK,
                        flash = traits.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                    )
                },
            ) ?: run {
                Log.i(LOG_TAG, "No back camera with a flash unit: no flashlight")
                return
            }
            cameraId = id
            state = TorchState(lit = false)
            // Called at once with the current mode, then on every change.
            cameras.registerTorchCallback(
                app.mainExecutor,
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == this@Torch.cameraId) seen(app, enabled)
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        // The camera took the flash: dark for us.
                        if (cameraId == this@Torch.cameraId) seen(app, lit = false)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No flashlight: ${e.javaClass.simpleName}")
            cameraId = null
            state = null
            return
        }
        // Off when a call begins - the three call sources where they converge (design 41 §1.4).
        MusicCalls.addCallEdgeListener { edge ->
            act(app, torchStep(state?.lit == true, if (edge == CallEdge.BEGAN) TorchEvent.CALL_BEGAN else TorchEvent.CALL_ENDED))
        }
        // The next unlock lets the lock's button try again (§1.5).
        PinLockRuntime.addModeListener {
            if (refusedOnLock && PinLockRuntime.mode != LockMode.LOCKED) {
                refusedOnLock = false
                changed()
            }
        }
    }

    /** A tap on the tile or the lock's button: on when off, off when on. */
    fun toggle(context: Context) {
        val id = cameraId ?: return
        val lit = state?.lit ?: return
        setTorch(context.applicationContext, id, !lit)
    }

    /** [TorchOffReceiver]: the 10 minutes are up. */
    fun autoOff(context: Context) {
        act(context.applicationContext, torchStep(state?.lit == true, TorchEvent.AUTO_OFF))
    }

    private fun seen(app: Context, lit: Boolean) {
        val before = state
        state = TorchState(lit)
        act(app, torchStep(lit, if (lit) TorchEvent.LIT else TorchEvent.DARK))
        if (before != state) changed()
    }

    private fun act(app: Context, step: TorchStep) {
        if (step.switchOff) cameraId?.let { setTorch(app, it, on = false) }
        when (step.alarm) {
            TorchAlarm.ARM -> armAutoOff(app)
            TorchAlarm.CANCEL -> cancelAutoOff(app)
            TorchAlarm.KEEP -> Unit
        }
    }

    private fun setTorch(app: Context, id: String, on: Boolean) {
        try {
            app.getSystemService(CameraManager::class.java)?.setTorchMode(id, on)
        } catch (e: CameraAccessException) {
            val locked = PinLockRuntime.mode == LockMode.LOCKED
            Log.w(LOG_TAG, "The torch refused (reason ${e.reason}, locked $locked)")
            val refused = torchRefusedOnLock(refusedOnLock, e.reason == CameraAccessException.CAMERA_DISABLED, locked)
            if (refused != refusedOnLock) {
                refusedOnLock = refused
                changed()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't switch the torch: ${e.javaClass.simpleName}")
        }
    }

    private fun changed() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "A torch listener failed", e)
            }
        }
    }

    private fun autoOffIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        AUTO_OFF_REQUEST,
        Intent(app, TorchOffReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Exact and while idle (`USE_EXACT_ALARM`, as `TimeRuleAlarm`): a lit phone in a pocket is in Doze. */
    private fun armAutoOff(app: Context) {
        try {
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + TORCH_AUTO_OFF_MS
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, autoOffIntent(app))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, autoOffIntent(app))
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't arm the torch's auto-off: ${e.javaClass.simpleName}")
        }
    }

    private fun cancelAutoOff(app: Context) {
        try {
            app.getSystemService(AlarmManager::class.java)?.cancel(autoOffIntent(app))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the torch's auto-off: ${e.javaClass.simpleName}")
        }
    }
}

/** The torch's auto-off alarm (design 41 §1.4). Not exported: only our own alarm targets it. */
class TorchOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Torch.autoOff(context)
}
