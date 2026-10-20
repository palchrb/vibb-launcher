package com.kidslauncher.mdm.torch

/*
 * The flashlight's rules (design 41 §1.4) - pure, no Android imports, `TorchRulesTest`. [Torch] is
 * the glue: it feeds these and acts.
 */

/** Off by itself this long after the last on (design 41 §1.4). */
const val TORCH_AUTO_OFF_MS = 10 * 60_000L

/** Home's tile and the lock's button: `null` everywhere when the phone has no flash unit. */
data class TorchState(val lit: Boolean)

/** One camera as `CameraCharacteristics` describe it. */
data class TorchCamera(val id: String, val back: Boolean, val flash: Boolean)

/** The back camera with a flash unit (`FLASH_INFO_AVAILABLE`); none (the emulator): no tile, no button. */
fun torchCamera(cameras: List<TorchCamera>): String? = cameras.firstOrNull { it.back && it.flash }?.id

/** What happened to the torch. */
enum class TorchEvent {
    /** The platform says it is lit (our tap, or anything else turning it on). */
    LIT,

    /** The platform says it is off, or the camera took the flash ("unavailable"). */
    DARK,

    /** A call began ([com.kidslauncher.mdm.music.CallEdge.BEGAN]). */
    CALL_BEGAN,

    /** That call ended: nothing - the torch is never lit again by us. */
    CALL_ENDED,

    /** The 10-minute alarm fired. */
    AUTO_OFF,
}

enum class TorchAlarm { ARM, CANCEL, KEEP }

/** [switchOff]: turn the torch off now; [alarm]: the auto-off alarm. */
data class TorchStep(val switchOff: Boolean, val alarm: TorchAlarm)

/**
 * Every on (re)arms the auto-off alarm at [TORCH_AUTO_OFF_MS] from now; every off path cancels it.
 * A call's beginning and the alarm turn a lit torch off. Nothing turns it on again, and the screen
 * going off is no event at all (as on an iPhone).
 */
fun torchStep(lit: Boolean, event: TorchEvent): TorchStep = when (event) {
    TorchEvent.LIT -> TorchStep(switchOff = false, alarm = TorchAlarm.ARM)
    TorchEvent.DARK -> TorchStep(switchOff = false, alarm = TorchAlarm.CANCEL)
    TorchEvent.CALL_BEGAN, TorchEvent.AUTO_OFF -> TorchStep(switchOff = lit, alarm = TorchAlarm.CANCEL)
    TorchEvent.CALL_ENDED -> TorchStep(switchOff = false, alarm = TorchAlarm.KEEP)
}

/**
 * The lock's button after a tap's result (design 41 §1.5, no camera-policy lift): a refusal with
 * `CAMERA_DISABLED` while the PIN lock is LOCKED hides it for the process; the next unlock clears
 * that, so the next lock tries again. Home's tile is never hidden by it.
 */
fun torchRefusedOnLock(refused: Boolean, cameraDisabled: Boolean, locked: Boolean): Boolean =
    refused || (cameraDisabled && locked)

/** Whether the lock shows its button: a flash unit, and no refusal under the lock in this process. */
fun torchLockButton(state: TorchState?, refusedOnLock: Boolean): Boolean = state != null && !refusedOnLock
