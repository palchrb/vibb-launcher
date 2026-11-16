package me.vibb.music.logic

/*
 * The media button's foreground hold (compat-fix-1, the Android 14-17 check of 2026-11-16; every version).
 *
 * `KidMediaButtonReceiver` starts the player as a foreground service when a stored queue exists (`Resumable`). Media3
 * goes into the foreground only once its notification has something to show, so a press whose playback resumption
 * gives no items (nothing to resume after all, the app suspended, a book not on the phone, a room or Spotify that never
 * starts) or a button that isn't play never called `startForeground`, and Android killed the app 30 s later
 * (`ForegroundServiceDidNotStartInTimeException`). Now the service goes into the foreground itself at once
 * (`PlaybackService.onStartCommand`, the hold), and lets go as these rules say: once the player plays, Media3's
 * notification takes over; otherwise, after the resumption settled, it stays silent and stops (vibb: nothing to
 * resume is no sound and no player). Pure; ForegroundHoldTest.
 */

/** Why playback resumption gave the session no items; [elsewhere]: another renderer is starting (wait for it). */
enum class Unresumable(val message: String, val elsewhere: Boolean) {
    SUSPENDED("suspended", false),
    NOT_ON_PHONE("not on the phone", false),
    NOTHING("nothing to resume", false),
    ON_ROOM("playing on the Sonos room", true),
    ON_SPOTIFY("playing on Spotify", true),
}

/** The failure a refused resumption hands Media3 (an UnsupportedOperationException: Media3 logs it as a warning). */
class ResumptionRefused(val why: Unresumable) : UnsupportedOperationException(why.message)

/** A button press with no item yet: a non-play button, or a resumption the service hasn't reached. */
const val HOLD_FIRST_LOOK_MS = 2_000L

/** After the session got items: the time for the player to start. */
const val HOLD_ITEMS_GRACE_MS = 3_000L

/** After a resumption handed to a room or Spotify: the time for it to start playing. */
const val HOLD_ELSEWHERE_GRACE_MS = 8_000L

/** The hold never outlives this, whatever is still pending. */
const val HOLD_MAX_MS = 60_000L

/** How often the hold is looked at while it waits. */
const val HOLD_CHECK_MS = 500L

/** [startedAtMs]/[waitUntilMs] on the elapsed clock; [pending]: resumptions asked and not settled. */
data class ForegroundHold(val startedAtMs: Long, val waitUntilMs: Long, val pending: Int = 0)

/** What the foreground notification is when the hold is looked at. */
enum class HoldShown {
    /** The hold's own placeholder. */
    PLACEHOLDER,

    /** Media3's notification (it was up before the press, or Media3 posted it since). */
    MEDIA,

    /** None (Media3 removed it). */
    NONE,
}

enum class HoldVerdict {
    /** Look again later. */
    WAIT,

    /** Media3's playing notification has the foreground: the hold is over, nothing to do. */
    DONE,

    /** Out of the foreground, keeping Media3's notification as it was; the service stays. */
    RELEASE_DETACH,

    /** Out of the foreground, the placeholder removed, and the service stopped: silent. */
    RELEASE_STOP,
}

fun holdStarted(nowMs: Long): ForegroundHold = ForegroundHold(nowMs, nowMs + HOLD_FIRST_LOOK_MS)

/** Another press during the hold: it looks again for at least the first look. */
fun holdPressedAgain(hold: ForegroundHold, nowMs: Long): ForegroundHold =
    hold.copy(waitUntilMs = maxOf(hold.waitUntilMs, nowMs + HOLD_FIRST_LOOK_MS))

fun holdResumptionAsked(hold: ForegroundHold): ForegroundHold = hold.copy(pending = hold.pending + 1)

/**
 * A resumption settled: [refused] null when the session got items. A silent refusal ends the wait at once (nothing
 * will play), items give the player [HOLD_ITEMS_GRACE_MS], another renderer [HOLD_ELSEWHERE_GRACE_MS].
 */
fun holdResumptionSettled(hold: ForegroundHold, refused: Unresumable?, nowMs: Long): ForegroundHold {
    val pending = maxOf(0, hold.pending - 1)
    val waitUntil = when {
        refused == null -> maxOf(hold.waitUntilMs, nowMs + HOLD_ITEMS_GRACE_MS)
        refused.elsewhere -> maxOf(hold.waitUntilMs, nowMs + HOLD_ELSEWHERE_GRACE_MS)
        else -> nowMs
    }
    return hold.copy(pending = pending, waitUntilMs = waitUntil)
}

/** The hold's next step: [playing] - the player plays (or buffers to play); [shown] - the foreground notification. */
fun holdVerdict(hold: ForegroundHold?, shown: HoldShown, playing: Boolean, nowMs: Long): HoldVerdict = when {
    hold == null -> HoldVerdict.DONE
    playing -> HoldVerdict.DONE
    nowMs - hold.startedAtMs < HOLD_MAX_MS && (hold.pending > 0 || nowMs < hold.waitUntilMs) -> HoldVerdict.WAIT
    shown == HoldShown.MEDIA -> HoldVerdict.RELEASE_DETACH
    else -> HoldVerdict.RELEASE_STOP
}
