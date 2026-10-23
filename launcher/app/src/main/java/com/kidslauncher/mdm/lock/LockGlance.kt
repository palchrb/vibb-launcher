package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.badges.GlanceConversation
import com.kidslauncher.mdm.music.MusicSession
import com.kidslauncher.mdm.music.clockText
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The lock's glance page (design 25, docs/design/25-lock-glance.md at the monorepo root, mockup
 * LockGlance.dc.html): pure, LockGlanceTest. Replaces step 4's media view (lock/LockMedia.kt): the
 * PIN lock always comes up on the glance page - clock, date, the music tile while something plays,
 * up to three notification rows, "Nødsamtale" - and the swipe up (design 59d step J; the "Lås opp" button before it)
 * or a row leads to the keypad. lock/GlancePanel draws it inside PinLockActivity; lock/GlanceOpener opens a row's target
 * after the right code.
 *
 * It weakens nothing: the page's only actions are the music's play/pause, previous and next, the
 * stage switches and the lock's own emergency call. A row's target is opened only after the kid's
 * PIN or the parent code, from the unlock path (LockGlanceGuardTest).
 */

/** The status report's capability: this launcher has the glance page (the server's notes read it). */
const val LOCK_GLANCE_CAPABILITY = "lock_glance_v1"

/**
 * The lock's stages: the glance page, our keypad, or - as Android's face (design 59d step B, Q59d-6) - [ASK]: Android's
 * PIN pad is asked for from the glance page; our keypad is never visible or focusable there.
 */
enum class LockStage { GLANCE, KEYPAD, ASK }

/** Whether the glance page (its root, with its ground) is on screen: on the page and under Android's pad. */
fun glancePageShown(stage: LockStage): Boolean = stage != LockStage.KEYPAD

/**
 * Design 59d step B (the user's rc.2 recording: after the pad, the glance came to the front for ~330 ms and slid away
 * "the same as the old keypad page did"): the page's content - everything but the root's ground - goes once the
 * glance leaves ([leaving]), so the keyguard's going-away reveals only the glance's ground and Home follows unanimated.
 * Under Android's pad (ASK) the page stays as drawn: the user reads the pad as an overlay on our own lock. It changes
 * only what is drawn: when the glance leaves is still `faceStep`'s LEAVE alone.
 */
fun glanceContentShown(leaving: Boolean): Boolean = !leaving

/** Design 59d step B: the leave hides the page's content first - only as Android's face and with the glance in front
 * (`leaveInFront`); own mode's leave is unchanged. */
fun glanceLeaveHides(androidFace: Boolean, inFront: Boolean): Boolean = androidFace && inFront

/**
 * What a row opens after the code (memory only; redacted toString - a conversation key can be a
 * room ID). [Conversation]: the row's newest notification ([notificationKey], posted at
 * [postTimeMs]); [MissedCalls]: one contact's sheet ([number]), else the phone book.
 */
sealed interface GlanceTarget {
    data class Conversation(
        val conversation: GlanceConversation,
        val notificationKey: String,
        val postTimeMs: Long,
    ) : GlanceTarget {
        val packageName: String get() = conversation.packageName

        override fun toString() = "Conversation($packageName)"
    }

    data class MissedCalls(val number: String?) : GlanceTarget {
        override fun toString() = "MissedCalls"
    }

    /** The timer tile's tap (design 42 §1.4): the catalog's clock app, by its launch intent. */
    data object ClockApp : GlanceTarget {
        val packageName: String get() = com.kidslauncher.mdm.lock.ClockApp.PACKAGE
    }
}

/** The app a target opens; null for the missed calls (the phone book). */
val GlanceTarget.appPackage: String?
    get() = when (this) {
        is GlanceTarget.Conversation -> packageName
        is GlanceTarget.ClockApp -> packageName
        is GlanceTarget.MissedCalls -> null
    }

/** The stage and the target it carries. */
data class GlanceStageState(val stage: LockStage, val target: GlanceTarget?) {
    companion object {
        val GLANCE = GlanceStageState(LockStage.GLANCE, null)
    }
}

sealed interface GlanceStageEvent {
    /** The lock comes up with the screen on ([lockStageDue]): the first show, after a screen-off. */
    data object LockUp : GlanceStageEvent

    /** The swipe up, or TalkBack's "Lås opp" on the hint line (design 59d step J; the "Lås opp" button before it). */
    data object Unlock : GlanceStageEvent

    /** A row: its target, or `null` when none may be taken (a time rule leaves the app unusable). */
    data class Row(val target: GlanceTarget?) : GlanceStageEvent

    /** The keypad's back arrow; as the face also the pad's cancel, an error, the dead ask's recovery and STAY. */
    data object Back : GlanceStageEvent

    /** Design 59d step B: as the face with no Android credential at the tap (`FaceStep.OWN_KEYPAD`) - our keys, the
     * target kept. */
    data object OwnKeypad : GlanceStageEvent
}

/**
 * The stage machine (§1.1): every lock-up is the glance page, without a target; "Lås opp" and a
 * row lead to the keypad (a row with its target); the arrow back drops the target. Coming back
 * with the screen on (the emergency dialer, an alarm, a call) is no event: the stage and target stay.
 * Design 59d step B ([face]: the panel is Android's face, fixed for that glance): "Lås opp" and a row lead to
 * [LockStage.ASK] instead - a tap in ASK asks again (a row replaces the target); Back gives GLANCE, the target dropped;
 * [GlanceStageEvent.OwnKeypad] gives our keypad. Own mode never enters ASK.
 */
fun glanceStageStep(state: GlanceStageState, event: GlanceStageEvent, face: Boolean = false): GlanceStageState = when (event) {
    GlanceStageEvent.LockUp -> GlanceStageState.GLANCE
    GlanceStageEvent.Unlock -> GlanceStageState(if (face) LockStage.ASK else LockStage.KEYPAD, null)
    is GlanceStageEvent.Row -> GlanceStageState(if (face) LockStage.ASK else LockStage.KEYPAD, event.target)
    GlanceStageEvent.Back -> if (state.stage != LockStage.GLANCE) GlanceStageState.GLANCE else state
    GlanceStageEvent.OwnKeypad -> if (state.stage == LockStage.ASK) state.copy(stage = LockStage.KEYPAD) else state
}

// ---- the swipe up (design 59d step J) ------------------------------------------------------------

/** A fling counts from this many dp upwards (59d §8.7). */
const val GLANCE_SWIPE_FLING_DP = 48f

/** ... at this speed upwards at the lift, dp per second (a slow 48 dp drag is no fling). */
const val GLANCE_SWIPE_FLING_DPS = 500f

/** A drag counts once it passes this share of the page's height, lifted or not. */
const val GLANCE_SWIPE_DRAG_SHARE = 1f / 3f

/** What a touch on the glance page is to the swipe ([glanceSwipeStep]). */
enum class GlanceSwipe {
    /** Not (yet) a swipe: the touch goes on to the views. */
    NONE,

    /** The swipe up: "Lås opp" as the button was - the ASK stage as the face, our keypad in own mode. */
    UNLOCK,
}

/**
 * Whether a touch is never the glance's swipe: it started on a control that keeps the whole touch ([onControl]: the music
 * tile's buttons, the timer's Stopp, the flashlight, Nødsamtale) or in the rows' scroll while it can scroll
 * ([scrollTakes]: a drag there scrolls). Since design 59d §8.13 a swipe from the bottom edge - the handle, the gesture
 * inset - is the page's too: when lock task refuses the system's Home gesture the touches reach our window, and when the
 * system takes it (a desync, §8.9) its monitor pilfers the pointer and we get `ACTION_CANCEL`, which asks nothing.
 */
fun glanceSwipeBlocked(onControl: Boolean, scrollTakes: Boolean): Boolean = onControl || scrollTakes

// ---- the handle (design 59d §8.13) ----------------------------------------------------------------

/**
 * Android's gesture handle drawn on our glance (59d §8.13, the user): lock task hides SystemUI's navigation bar while we
 * are LOCKED, so there is only ever ours. A pill [widthDp] x [heightDp], fully rounded, white at [alpha], its bottom
 * [bottomDp] above the window's bottom edge - in the gesture inset, where SystemUI draws its own.
 */
data class GlanceHandle(val widthDp: Float, val heightDp: Float, val bottomDp: Float, val alpha: Float)

/**
 * Android's defaults for a phone without a profile (§8.13): 108 x 4 dp, rounded, white at 80 %, its bottom 8 dp above
 * the window's bottom - inside the bottom inset, as SystemUI's own (8 dp *above* the inset, read literally, would sit on
 * Nødsamtale's row: the page's content ends 8 dp above the inset).
 */
val DEFAULT_GLANCE_HANDLE = GlanceHandle(widthDp = 108f, heightDp = 4f, bottomDp = 8f, alpha = 0.8f)

/** The handle on this phone: its profile's (SystemUI's measured), else [DEFAULT_GLANCE_HANDLE]. */
fun glanceHandle(profile: ClockGeometry?): GlanceHandle = profile?.handle ?: DEFAULT_GLANCE_HANDLE

/**
 * The handle's view (dp): the pill's width, and as tall as the pill with [GlanceHandle.bottomDp] above and below it - the
 * view sits on the window's bottom edge with the pill centred in it, so TalkBack's node covers the inset and no more
 * (never Nødsamtale's row above it).
 */
fun glanceHandleViewHeightDp(handle: GlanceHandle): Float = handle.heightDp + 2 * handle.bottomDp

/**
 * Design 59d step J (the user, rc.2: "la vår låseskjerm bli swipet oppover så den er identisk med android sin egen"):
 * a vertical swipe up anywhere on the glance page asks as "Lås opp" did. [dy] is the travel upwards since the down
 * (dp, up positive), [dx] sideways (dp), [vy] the speed upwards at the lift (dp/s), [page] the page's height (dp),
 * [lifted] the finger is up. UNLOCK for a drag past a third of the page (also before the lift), or at the lift for a
 * fling of at least [GLANCE_SWIPE_FLING_DP] at [GLANCE_SWIPE_FLING_DPS]; always mostly vertical; never for a touch that
 * [startedOnTappable] ([glanceSwipeBlocked]). It only asks: what opens is still `faceStep`'s verdict (or our keypad).
 */
fun glanceSwipeStep(dy: Float, vy: Float, startedOnTappable: Boolean, dx: Float = 0f, page: Float, lifted: Boolean): GlanceSwipe {
    if (startedOnTappable || dy <= kotlin.math.abs(dx)) return GlanceSwipe.NONE
    val drag = dy >= page * GLANCE_SWIPE_DRAG_SHARE
    val fling = lifted && dy >= GLANCE_SWIPE_FLING_DP && vy >= GLANCE_SWIPE_FLING_DPS
    return if (drag || fling) GlanceSwipe.UNLOCK else GlanceSwipe.NONE
}

/**
 * Whether the lock "comes up" now (its onStart/onResume) and decides its stage again: only with the
 * screen on ([interactive] - the lock is started at every screen-off and seen at the next
 * screen-on), the first time, and after every screen-off since the last decision ([screenOffs],
 * `PinLockRuntime.screenOffs`). Coming back from the emergency dialer or an alarm with the screen
 * on keeps the stage the kid was on.
 */
fun lockStageDue(interactive: Boolean, decidedAtScreenOffs: Int?, screenOffs: Int): Boolean =
    interactive && decidedAtScreenOffs != screenOffs

/**
 * The music tile (§1.1, user decision 2026-10-12, bug 25b-G): it shows whenever the music app has a
 * session ([session], `shownSession` - `null` without listener access, the app suspended or gone),
 * playing or paused - also at a lock-up - and goes when the session ends. Since design 28 the
 * session is the windowed one (`MusicLockPanel.shown`, `windowedSession`): a paused session only
 * within 10 minutes of the end of play or a fresh headset, as Home's card - never the snapshot.
 */
fun glanceTile(session: MusicSession?): Boolean = session != null

/** The page's progress ticks once a second only while it shows a tile ([tileShown]) of a playing
 * session - and only while the page itself shows ([pageShown]: not behind the keypad stage, which
 * has no music - code QA Low 7, 25b-G). */
fun glanceTicks(tileShown: Boolean, pageShown: Boolean, session: MusicSession?): Boolean =
    tileShown && pageShown && session?.playing == true

/** The minus of the time left (U+2212, as wide as a digit in Nunito). */
const val GLANCE_MINUS = "−"

/** The full tile's progress row (design r9 §3; the compact tile has none - the user, 2026-10-20): [elapsed] left of
 * the bar, [remaining] right of it ("1:26 ━━━━── −2:38"); `null` for a live stream or an unknown duration, which
 * show the elapsed time alone. The bar's content description keeps "1:26 av 4:04". */
data class GlanceTimes(val elapsed: String, val remaining: String?)

/** Both from whole seconds, so the two add up to the duration; past the end the time left is "−0:00". */
fun glanceTimes(positionMs: Long, durationMs: Long): GlanceTimes {
    if (durationMs <= 0) return GlanceTimes(clockText(positionMs), null)
    val left = (durationMs / 1000 - positionMs.coerceAtLeast(0) / 1000).coerceAtLeast(0)
    return GlanceTimes(clockText(positionMs), GLANCE_MINUS + clockText(left * 1000))
}

/**
 * The widest text of a time's shape, every digit "0" ("0:00", "00:00", "0:00:00"), for the times' fixed width (r9
 * §3): the duration's - or the position's once it passes a stale shorter duration (qa-r9-revision-check N6: "10:00"
 * against "9:59"); "0:00:00" without a duration (a live position past 10 h clips - accepted). The time left never
 * outgrows the duration's shape.
 */
fun glanceTimeWidest(durationMs: Long, positionMs: Long = 0): String =
    if (durationMs <= 0) "0:00:00" else clockText(maxOf(durationMs, positionMs)).replace(Regex("[0-9]"), "0")

/**
 * The refit rule (r6 §2, qa-r6 r6-4): a session callback renders the tile alone and refits the page
 * only when the tile came or went ([tileWasUp] -> [tileUp]) or the shown tile's measured height
 * ([measuredPx], with its margins; `null` before the page is laid out) differs from the one the last
 * fit was made with ([fittedPx]; `null` = no fit with a tile yet).
 */
fun glanceRefitDue(tileWasUp: Boolean, tileUp: Boolean, fittedPx: Int?, measuredPx: Int?): Boolean =
    tileWasUp != tileUp || (tileUp && (fittedPx == null || fittedPx != measuredPx))

/**
 * Callbacks within one frame draw once (r6 §2): [request] posts [work] through [post] (the glue's
 * `Choreographer.postFrameCallback`) only when none is pending, so the two or three session
 * messages of one tap render the tile once. Main thread only.
 */
class GlanceCoalescer(private val post: (() -> Unit) -> Unit, private val work: () -> Unit) {
    private var pending = false

    fun request() {
        if (pending) return
        pending = true
        post {
            pending = false
            work()
        }
    }
}

// ---- the clock as Android's small clock (design 59d step L) -------------------------------------

/**
 * Where Android's small single-line keyguard clock sits on one kind of phone (59d §8.8, step L: the glance takes its
 * geometry - the same left edge, baseline and size, a thin weight - with the short date in Android's date's place), in
 * dp from the window's top left at [GLANCE_CLOCK_PROFILE_DENSITY]: the clock's first digit's ink starts at
 * [clockInkStartDp] (Nunito's side bearings vary from 0.04 to 0.11 em by digit, so the box moves with the first digit -
 * Android's ink sits within 2 px of this for every digit), its baseline at [clockBaselineDp], its size [clockDp] (dp:
 * Android's small clock doesn't follow the font scale); the date's box at [dateStartDp] (a capital's bearing is a pixel
 * or two), its baseline at [dateBaselineDp], [dateSp]. Measured from frames of SystemUI's face, so the ink's left edge,
 * the baseline and the digits' height match - not the glyphs (Nunito is wider). The alarm line goes under the date.
 */
data class ClockGeometry(
    val name: String,
    val clockInkStartDp: Float,
    val clockBaselineDp: Float,
    val clockDp: Float,
    val dateStartDp: Float,
    val dateBaselineDp: Float,
    val dateSp: Float,
    /** Design 59d §8.13: SystemUI's gesture handle on that phone. */
    val handle: GlanceHandle = DEFAULT_GLANCE_HANDLE,
)

/** The density the profiles were measured at (the Jelly Star's 240 dpi; the emulator at `wm density 240`). */
const val GLANCE_CLOCK_PROFILE_DENSITY = 1.5f

/** ... and the screen width in dp (480 px at 240 dpi): the two phones measured so far. */
const val GLANCE_CLOCK_PROFILE_WIDTH_DP = 320

/**
 * The emulator's Pixel SystemUI (Android 16, `handy36` at `wm density 240`, 480x854 px), measured from SystemUI's face
 * with the small clock (`docs/testing/screens/59d-jl/clock-twin.png`): the digits' ink 92 px high from y 180 to the
 * baseline at 273 px (the last ink row 272), the first digit's ink at x 49 ("1") to 53 ("5") - 51 px, 34 dp, is within
 * 2 px of both; the date's capitals 17 px high on a baseline at 329 px, its ink at x 50. In Nunito SemiBold (digits
 * 0.712 em high, an "F" 0.082 em from the box's left edge): 86 dp; 16 sp, the box at 32 dp. Since the code QA's JL-2 the
 * clock is Nunito Light (300; its digits are 0.712 em high too). The handle (§8.13): SystemUI's, measured on the same
 * frames while it showed - 162 x 6 px (108 x 4 dp), rows 833-838 (its bottom 10 dp above the window's, centred in the
 * 24 dp gesture inset), x 159-320, luma 235 on the night (white at about 92 %).
 */
val EMULATOR_PIXEL_CLOCK = ClockGeometry(
    name = "emulator Pixel",
    clockInkStartDp = 34f,
    clockBaselineDp = 182f,
    clockDp = 86f,
    dateStartDp = 32f,
    dateBaselineDp = 219f,
    dateSp = 16f,
    handle = GlanceHandle(widthDp = 108f, heightDp = 4f, bottomDp = 10f, alpha = 0.92f),
)

/**
 * AOSP emulator, confirm on the phone (`docs/testing/2026-10-23-aosp-lock-look.md`): pure AOSP SystemUI (Android 16,
 * the `default` x86_64 image, AVD `aosp36` at 480x854 and 240 dpi), measured from its face over our ground with the
 * single-line clock (`lockscreen_use_double_line_clock 0`; AOSP's default is the double-line clock, which this model
 * can't express - see the doc): the digits' ink from y 104 to the last row 196 (93 rows, as Pixel's 180-272: 86 dp
 * in Nunito), a baseline at 197 px, the first digit's ink at x 56 for a "1" (37.3 dp) - the box starts at 42 px (28
 * dp), left-aligned, so the ink follows the digit's bearing as the Pixel's does. AOSP's face has no date line here;
 * ours keeps the Pixel profile's 56 px from the clock's baseline to the date's (253 px) and its box 2 dp left of the
 * clock's ink. The handle is SystemUI's, pixel for pixel the Pixel image's: 162 x 6 px, rows 833-838, x 159-320,
 * luma 236. Not matched: AOSP's digits are wider and widely tracked ("12:17" 362 px against Nunito Light's ~280) and
 * its stems 11 px (Nunito Light's 8).
 */
val AOSP_EMULATOR_CLOCK = ClockGeometry(
    name = "AOSP emulator",
    clockInkStartDp = 37.3f,
    clockBaselineDp = 131.3f,
    clockDp = 86f,
    dateStartDp = 35.3f,
    dateBaselineDp = 168.7f,
    dateSp = 16f,
    handle = GlanceHandle(widthDp = 108f, heightDp = 4f, bottomDp = 10f, alpha = 0.92f),
)

/**
 * The Jelly Star (Unihertz, AOSP SystemUI on Android 16): the AOSP emulator's numbers - AOSP emulator, confirm on the
 * phone (a frame of its face with the single-line clock; Unihertz's build may move or restyle it).
 */
val JELLY_STAR_CLOCK = AOSP_EMULATOR_CLOCK.copy(name = "Jelly Star")

/**
 * The clock profile for this phone ([model] and [manufacturer] as `Build` reports them, [density] the display's,
 * [widthDp] the screen's width in dp): the emulator's Pixel image (`sdk_gphone*` by Google), the pure AOSP emulator
 * image ("Android SDK built for ..." by "unknown") or the Jelly Star, only at
 * 240 dpi **and** 320 dp wide; anything else is `null`, and the glance keeps its own centred clock (56/40 sp, the long
 * date). No PWA switch.
 *
 * Why both keys (the code QA's JL-6): SystemUI lays its clock out in dp, so the width in dp is what makes the numbers
 * fit (34 dp plus Nunito's widest "00:00" at 86 dp); the density pins the place the frames were measured at, so a
 * "Display size" change or another density falls back cleanly instead of guessing. Both known phones are 480 px at 240
 * dpi = 320 dp; a second density joins once it is measured. The Jelly Star's model string isn't verified yet
 * (`getprop ro.product.model` on the phone), so spaces, underscores and case are ignored ("Jelly Star", "JellyStar",
 * "Jelly_Star"); a phone that matches nothing is logged once (`GlancePanel`).
 */
fun glanceClockProfile(model: String?, manufacturer: String?, density: Float, widthDp: Int): ClockGeometry? {
    if (abs(density - GLANCE_CLOCK_PROFILE_DENSITY) > 0.01f || widthDp != GLANCE_CLOCK_PROFILE_WIDTH_DP) return null
    val name = model.orEmpty().trim()
    val bare = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    val maker = manufacturer.orEmpty().trim()
    return when {
        maker.equals("Google", ignoreCase = true) && name.startsWith("sdk_gphone", ignoreCase = true) -> EMULATOR_PIXEL_CLOCK
        maker.equals("Unihertz", ignoreCase = true) && "jellystar" in bare -> JELLY_STAR_CLOCK
        // The pure AOSP emulator image (`default`): "Android SDK built for x86_64" by "unknown".
        maker.equals("unknown", ignoreCase = true) && name.startsWith("Android SDK built for", ignoreCase = true) -> AOSP_EMULATOR_CLOCK
        else -> null
    }
}

/** The date's skeleton: Android's short date as the profile's ("Fre. 23. okt.", "Fri, Oct 23"), else the long one. */
fun glanceDateSkeleton(profile: Boolean): String = if (profile) "EEEdMMM" else "EEEEdMMMM"

/** The date as Android's face writes it: the first letter capitalised ("fre. 23. okt." -> "Fre. 23. okt."). */
fun glanceDateCapitalised(text: String, locale: Locale): String =
    text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

/**
 * The top margin (px) that puts a view's baseline at [targetPx] from the window's top: with no margin the view would
 * start at [topPx], its baseline [baselineInViewPx] below its own top; never below [floorPx] (the date never climbs above
 * the clock's baseline - its floor is minus the clock's descent).
 */
fun baselineMarginPx(targetPx: Float, topPx: Float, baselineInViewPx: Float, floorPx: Float = 0f): Int =
    maxOf(floorPx, targetPx - topPx - baselineInViewPx).roundToInt()

/** The start margin (px) that puts a view's box at [targetPx] from the window's left when its parent's content starts at
 * [contentStartPx]; never negative. */
fun startMarginPx(targetPx: Float, contentStartPx: Float): Int = maxOf(0f, targetPx - contentStartPx).roundToInt()

// ---- the flashlight button (design 41 §1.3) ------------------------------------------------------

/**
 * What the lock needs of the torch (design 41 §1.1; the glue's `Torch` behind it): whether the phone
 * has a flash unit, whether it is lit, whether turning it on failed with `CAMERA_DISABLED` while
 * the PIN lock was LOCKED (§1.5: the lock's button goes, Home's tile stays), the toggle and the
 * change listener. The lock's button toggles the torch and nothing else - it opens nothing.
 */
interface TorchPort {
    val hasFlash: Boolean
    val lit: Boolean
    val refusedWhileLocked: Boolean
    fun toggle()
    fun addListener(listener: () -> Unit)
    fun removeListener(listener: () -> Unit)
}

enum class GlanceTorchButton { HIDDEN, OFF, LIT }

/** The lock's round button (§1.3): none without a flash unit or after a `CAMERA_DISABLED` refusal
 * on the lock (§1.5); else off or lit (the lit state a filled peach disc). */
fun glanceTorchButton(hasFlash: Boolean, refusedWhileLocked: Boolean, lit: Boolean): GlanceTorchButton = when {
    !hasFlash || refusedWhileLocked -> GlanceTorchButton.HIDDEN
    lit -> GlanceTorchButton.LIT
    else -> GlanceTorchButton.OFF
}

// ---- the fit -------------------------------------------------------------------------------------

/** The page shows at most this many rows; the rest is the "more" line's count. */
const val GLANCE_MAX_ROWS = 3

/**
 * Each part's height as measured (any unit, the same for all and for the available height):
 * [fixed] = the swipe hint line (design 59d step J; "Lås opp" before it) and the bottom row ("Nødsamtale" and the
 * flashlight, design 41) with their margins; [date] with its margin; the tiles with their top margin (the full one with its progress
 * row, r9 §3); [clockFull] the timer tile with its margin (design 42); [rowsTop] the rows' scroll's
 * margin below a tile, [rowsTopNoTile] below the date; [row] one row (the tallest); [rowGap] between
 * rows; [more] the "more" line (nothing above it); [fade] what the scroll's bottom fade covers past a row's own
 * bottom padding, kept clear under the one visible row while more scrolls below it (qa-r9-code R9C-1).
 */
data class GlanceHeights(
    val fixed: Float,
    val clockBig: Float,
    val clockSmall: Float,
    val date: Float,
    val tileFull: Float,
    val tileCompact: Float,
    val clockFull: Float,
    val rowsTop: Float,
    val rowsTopNoTile: Float,
    val row: Float,
    val rowGap: Float,
    val more: Float,
    val fade: Float,
)

enum class GlanceTileSize { NONE, FULL, COMPACT }

/** What the page shows: [candidate] is the fit's number (1-7), for the logs and tests; [rows] the rows
 * bound (min(n, 3) - none only in candidate 7), [visibleRows] those the fit counted on screen (all of
 * them, or one with the rest below it in the scroll), [more] the "more" line's count; [clock]: the
 * timer tile (design 42). */
data class GlanceFit(
    val candidate: Int,
    val bigClock: Boolean,
    val date: Boolean,
    val tile: GlanceTileSize,
    val rows: Int,
    val visibleRows: Int,
    val more: Int,
    val height: Float,
    val clock: Boolean = false,
)

/** The rows' scroll shows whenever there is a row at all - in candidate 7 with the "more" line alone
 * (qa-r9-revision-check N1). */
val GlanceFit.scrollShown: Boolean get() = rows > 0 || more > 0

/**
 * The fit (design 25 §1.2, r9 §2): the clock's, date's and tiles' sizes; the bottom block always
 * stays whole (the layout keeps it), and the rows scroll in what is left. A candidate counts every
 * row (with the "more" line when n > 3) or one row (the rest, and the line, below it in the scroll);
 * rows are never folded into "+ N varsler til" to make room. The first whose height fits [available]
 * wins:
 * 1. big clock, date, full tile, every row; 2. small clock, the same;
 * 3. small clock, date, full tile, one row visible;
 * 4. small clock, date, compact tile, every row; 5. the same, one row visible;
 * 6. small clock, no date, compact tile, one row visible;
 * 7. small clock, no date, compact tile, no row - only the "more" line, counting all n (the last
 *    resort is a line, never a faded sliver of a row).
 * Without a tile 4 = 2 and 5 = 3. Nothing fits: the last candidate.
 *
 * With a running timer ([clock], design 42 §1.6 as changed by r9 §2) the seven with the timer tile
 * come first, then the seven without it: a running timer outlives the music tile's full size, a
 * second visible row and the date.
 */
fun glanceFit(available: Float, heights: GlanceHeights, tile: Boolean, rowCount: Int, clock: Boolean = false): GlanceFit {
    val n = rowCount.coerceAtLeast(0)
    val most = minOf(n, GLANCE_MAX_ROWS)
    val one = minOf(most, 1)
    val full = if (tile) GlanceTileSize.FULL else GlanceTileSize.NONE
    val compact = if (tile) GlanceTileSize.COMPACT else GlanceTileSize.NONE
    fun fit(candidate: Int, big: Boolean, date: Boolean, size: GlanceTileSize, rows: Int, visible: Int, withClock: Boolean): GlanceFit {
        val more = n - rows
        val tileHeight = when (size) {
            GlanceTileSize.NONE -> 0f
            GlanceTileSize.FULL -> heights.tileFull
            GlanceTileSize.COMPACT -> heights.tileCompact
        }
        // The "more" line sits after row 3: on screen only when every bound row is. With more below the
        // visible row, the scroll's fade is counted so that row stays clear of it.
        val list = if (n == 0) {
            0f
        } else {
            (if (size == GlanceTileSize.NONE && !withClock) heights.rowsTopNoTile else heights.rowsTop) +
                visible * heights.row + (visible - 1).coerceAtLeast(0) * heights.rowGap +
                (if (visible == rows && more > 0) heights.more else 0f) +
                (if (visible < rows) heights.fade else 0f)
        }
        val height = heights.fixed + (if (big) heights.clockBig else heights.clockSmall) +
            (if (date) heights.date else 0f) + tileHeight + (if (withClock) heights.clockFull else 0f) + list
        return GlanceFit(candidate, big, date, size, rows, visible, more, height, withClock)
    }
    val candidates = (if (clock) listOf(true, false) else listOf(false)).flatMap { withClock ->
        listOf(
            fit(1, big = true, date = true, size = full, rows = most, visible = most, withClock = withClock),
            fit(2, big = false, date = true, size = full, rows = most, visible = most, withClock = withClock),
            fit(3, big = false, date = true, size = full, rows = most, visible = one, withClock = withClock),
            fit(4, big = false, date = true, size = compact, rows = most, visible = most, withClock = withClock),
            fit(5, big = false, date = true, size = compact, rows = most, visible = one, withClock = withClock),
            fit(6, big = false, date = false, size = compact, rows = most, visible = one, withClock = withClock),
            fit(7, big = false, date = false, size = compact, rows = 0, visible = 0, withClock = withClock),
        )
    }
    return candidates.firstOrNull { it.height <= available } ?: candidates.last()
}

// ---- the keypad's prompt -------------------------------------------------------------------------

/** A label in the keypad's hint is cut to this many characters (QA Low 5: the parent's label can be
 * 100 characters; the prompt keeps to two lines). */
const val GLANCE_PROMPT_LABEL_MAX = 20

/** [label] as the hint shows it: at most [GLANCE_PROMPT_LABEL_MAX] characters, a cut one ends in "…". */
fun glancePromptLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.codePointCount(0, trimmed.length) <= GLANCE_PROMPT_LABEL_MAX) return trimmed
    val end = trimmed.offsetByCodePoints(0, GLANCE_PROMPT_LABEL_MAX - 1)
    return trimmed.substring(0, end).trimEnd() + "…"
}

/** Which prompt the keypad shows (§1.1): the usual wait/wrong/checking/unusable texts first; else the
 * target's hint; else the plain prompt. */
enum class GlancePrompt { UNUSABLE, WAIT, CHECKING, WRONG, OPEN_APP, SEE_MISSED, PLAIN }

fun glancePrompt(usable: Boolean, waiting: Boolean, checking: Boolean, wrong: Boolean, target: GlanceTarget?): GlancePrompt = when {
    !usable -> GlancePrompt.UNUSABLE
    waiting -> GlancePrompt.WAIT
    checking -> GlancePrompt.CHECKING
    wrong -> GlancePrompt.WRONG
    target is GlanceTarget.Conversation || target is GlanceTarget.ClockApp -> GlancePrompt.OPEN_APP
    target is GlanceTarget.MissedCalls -> GlancePrompt.SEE_MISSED
    else -> GlancePrompt.PLAIN
}

// ---- taking a target and opening it ------------------------------------------------------------

/**
 * Whether a row's tap takes its target (§1.4 with the decision on QA Q1): always without a time
 * rule; under one ([timeRule]) only when the rule leaves it usable - the row's app among the time
 * lock's usable apps ([usableUnderRule]), a missed-call row when the rule allows calls and calls are
 * managed (the time-rule screen's phone book). Otherwise the row just opens the keypad.
 */
fun glanceTargetTaken(timeRule: Boolean, usableUnderRule: Boolean): Boolean = !timeRule || usableUnderRule

/**
 * Whether the time lock [lock] leaves [target] usable (QA Q1, for the tap and the hand-over): no time
 * lock - yes; a conversation - its app among the lock's usable apps (the budget's messaging and music
 * apps, a rule's exempt apps); missed calls - calls managed ([callsManaged]) and allowed by the rule
 * (the time-rule screen's own condition for its phone book).
 */
fun glanceUsableUnderLock(lock: com.kidslauncher.mdm.timerules.TimeLock, target: GlanceTarget, callsManaged: Boolean): Boolean = when {
    !lock.locked -> true
    else -> target.appPackage?.let { it in lock.usableApps } ?: (lock.callsAllowed && callsManaged)
}

/** Where the open after the code runs from. */
enum class GlanceOpenFrom {
    /** The lock's unlock path ([PinLockActivity]'s leave step). */
    LOCK,

    /** The time-rule screen, after the lock handed the target over (QA Q1). */
    TIME_RULE_SCREEN,
}

/** What happens to a target after the code ([glanceOpenStep]). */
enum class GlanceOpen {
    /** A time rule locks the phone: the time-rule screen comes up first and opens it over itself. */
    HAND_TO_TIME_RULE_SCREEN,

    /** The missed calls: one contact's sheet, else the phone book. */
    MISSED_CALLS,

    /** The notification's own content intent, sent from our visible window. */
    SEND,

    /** The app's launch intent. */
    LAUNCH,

    NOTHING,
}

/** The facts [glanceOpenStep] decides on; the Android glue (GlanceOpener) reads them. */
data class GlanceOpenFacts(
    val from: GlanceOpenFrom,
    val missedCalls: Boolean,
    /** A time rule locks the phone now (`lockReason != NONE`). */
    val timeRule: Boolean,
    /** From the time-rule screen: the rule leaves the target usable (the app is among its usable
     * apps; for missed calls, calls are managed and the rule allows them). */
    val usableUnderRule: Boolean,
    /** Kiosk on and the lock was the lock-task root (the 1 s fallback) - read before its stop; it
     * travels with a hand-over (code QA Low 1). */
    val lockWasRoot: Boolean,
    /** Kiosk on but no lock task running at the open (code QA Low 1): the app would run outside it. */
    val kioskWithoutLockTask: Boolean,
    val installed: Boolean,
    val suspended: Boolean,
    val hidden: Boolean,
    /** The conversation's newest content intent (else the one captured at the tap) exists. */
    val hasIntent: Boolean,
    val intentIsActivity: Boolean,
    /** The intent's creator is the notification's own app (QA Low 10). */
    val intentFromApp: Boolean,
    /** The send threw, or was refused (code QA Low 2: our own window was back in front after it):
     * the launch intent instead. */
    val sendFailed: Boolean,
    val hasLaunchIntent: Boolean,
)

/** The decision with its reason (the log names the package and the reason only). */
data class GlanceOpenStep(val open: GlanceOpen, val reason: String)

/**
 * §1.4's table, with QA Q1 (a time rule hands the target to the time-rule screen), QA Medium 2 (no
 * launch intent: nothing), QA Low 10 (only the app's own intent is sent) and code QA Low 1 (the
 * lock-task checks hold for the hand-over too):
 * | kiosk on and the lock was the lock-task root   | nothing (the app would cover Home)            |
 * | a time rule locks the phone (from the lock)    | the time-rule screen opens it over itself    |
 * | from the time-rule screen, not usable under it | nothing                                      |
 * | kiosk on and no lock task running now          | nothing (the app would run outside it)        |
 * | missed calls                                   | one contact's sheet, else the phone book      |
 * | not installed, suspended or hidden             | nothing                                      |
 * | an activity intent created by the app itself   | sent from our visible window                 |
 * | otherwise, or the send failed or was refused   | the app's launch intent; none: nothing       |
 */
fun glanceOpenStep(f: GlanceOpenFacts): GlanceOpenStep = when {
    f.lockWasRoot -> GlanceOpenStep(GlanceOpen.NOTHING, "the lock was the lock-task root")
    f.from == GlanceOpenFrom.LOCK && f.timeRule -> GlanceOpenStep(GlanceOpen.HAND_TO_TIME_RULE_SCREEN, "time rule")
    f.from == GlanceOpenFrom.TIME_RULE_SCREEN && !f.usableUnderRule -> GlanceOpenStep(GlanceOpen.NOTHING, "not usable under the time rule")
    f.kioskWithoutLockTask -> GlanceOpenStep(GlanceOpen.NOTHING, "lock task not running")
    f.missedCalls -> GlanceOpenStep(GlanceOpen.MISSED_CALLS, "missed calls")
    !f.installed -> GlanceOpenStep(GlanceOpen.NOTHING, "not installed")
    f.suspended -> GlanceOpenStep(GlanceOpen.NOTHING, "suspended")
    f.hidden -> GlanceOpenStep(GlanceOpen.NOTHING, "hidden")
    f.hasIntent && f.intentIsActivity && f.intentFromApp && !f.sendFailed -> GlanceOpenStep(GlanceOpen.SEND, "intent")
    f.hasLaunchIntent -> GlanceOpenStep(GlanceOpen.LAUNCH, "app")
    else -> GlanceOpenStep(GlanceOpen.NOTHING, "no launch intent")
}

/** How long after a send the opener looks whether it opened anything (code QA Low 2). */
const val GLANCE_SEND_CHECK_MS = 1_500L

/** What a send turned out to be, [GLANCE_SEND_CHECK_MS] after it. */
enum class GlanceSendCheck {
    /** One of our own windows is in front again (Home, the time-rule screen): the start was refused
     * (a positive result code - `PendingIntent.send` throws only for a negative one) - the launch
     * intent instead. */
    REFUSED,

    /** Something else is in front, and below the lock was Home or the time-rule screen: the app
     * opened - the row is marked opened. */
    OPENED,

    /** Something else is in front, but the lock didn't leave through Home (kiosk off with a call live,
     * design 32): that may be what the kid had open before the lock - nothing is concluded (the app
     * clears its notification when the room opens). */
    UNKNOWN,
}

/** [ourWindowInFront]: an activity of ours other than the lock is resumed now; [confirmable]: the
 * lock left through Home (kiosk on, or design 32 with no call live) or the send came from the
 * time-rule screen. */
fun glanceSendCheck(ourWindowInFront: Boolean, confirmable: Boolean): GlanceSendCheck = when {
    ourWindowInFront -> GlanceSendCheck.REFUSED
    confirmable -> GlanceSendCheck.OPENED
    else -> GlanceSendCheck.UNKNOWN
}
