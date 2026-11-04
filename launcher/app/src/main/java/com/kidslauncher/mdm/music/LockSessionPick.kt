package com.kidslauncher.mdm.music

import android.content.pm.ApplicationInfo
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.kidslauncher.mdm.appdisplay.isValidPackageName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * The PIN lock's media tile for any allowed app (the user, 2026-11-04: "mediekontroller for andre apper, men kun for
 * lock screen"): which live session the lock shows when several apps have one, whether an app may show there at all,
 * and how another app's session text is cut. Pure rules, LockSessionPickTest; the glue is MusicSessions (every active
 * session, watched while a surface shows) and MusicLockPanel. Home's "Spilles nå" card is unchanged: Vibb music only
 * (MusicSessions.Reading's session/controller/art).
 */

/**
 * One live session the lock may show: [vibb] - Vibb music by its name and our signer (`MusicTrust`, S-L8; a squatter
 * on the name is another app); [usable] - [lockAppUsable] at the panel's last look-up.
 */
data class LockCandidate(val session: MusicSession, val vibb: Boolean, val usable: Boolean)

/** The lock's pick: the candidate's [index] in the list it was picked from, and its tile with the window's end. */
data class LockPick(val index: Int, val tile: WindowedSession)

/**
 * The session the lock's tile shows, or null. Each candidate goes through [lockTileSession] (listener [access], usable,
 * live, inside the listening window): Vibb music with the window's stamps as they are ([listening]: the bridge's end of
 * play, a fresh headset); another app with its session's own last state change as the end of play
 * (`PlaybackState.getLastPositionUpdateTime`, the pause moment - the bridge stamps only Vibb music) and **no** headset
 * window (code QA L5: earbuds plugged in by a finder must not bring an old paused session of any app back on the lock) -
 * so a pause tapped on the lock keeps the tile for the window, as Vibb music's does. The app sets that stamp itself
 * (code QA L4): one that keeps re-stamping a paused state, or claims to play, keeps its tile - with only its own
 * transport controls.
 *
 * Of the ones left: a playing one before a paused one; among equals Vibb music first; then the platform's order of the
 * active sessions (most recently active first) - so two playing apps or two paused ones always give the same pick.
 */
fun lockTilePick(access: Boolean, candidates: List<LockCandidate>, listening: Listening, now: BootStamp): LockPick? =
    candidates.mapIndexedNotNull { index, c ->
        val window = if (c.vibb) listening else Listening(c.session.positionAtElapsedMs.takeIf { it > 0 }?.let { BootStamp(it, now.bootCount) }, headsetAt = null)
        lockTileSession(access, c.usable, c.session, window, now).takeIf { it.session != null }?.let { LockPick(index, it) }
    }.minWithOrNull(compareBy<LockPick>({ !candidates[it.index].session.playing }, { !candidates[it.index].vibb }, { it.index }))

/** A tap on the lock tile's transport (or Home's card's play/pause). */
enum class TileAction { PLAY, PAUSE, NEXT, PREVIOUS }

/**
 * How a tile tap reaches the session (E9 M1): Vibb music by its transport controls, as before (null); another app as a
 * media button on its own session (`MediaController.dispatchMediaButtonEvent`, down and up) - the key code here. A
 * Media3 player (Fossify Music Player 1.8.5 on the emulator) ignored the launcher's `TransportControls.pause()` and
 * `skipToNext()` but obeys a media key at once: Media3 runs a media button as its own notification controller, and
 * every player handles them for headsets. PLAY and PAUSE are their own keys, never PLAY_PAUSE (Media3 holds that one
 * for a double tap, and a toggle could cross a state change the tile hasn't drawn yet).
 *
 * A skip goes as a key only when the session's `PlaybackState` [advertised] it (`ACTION_SKIP_TO_NEXT` /
 * `_PREVIOUS`; QA L-a): the platform's default media-button handling - an older player that doesn't override it -
 * acts on a skip key only then, while the transport control reaches its `onSkipToNext` regardless, and a skip has no
 * second try. No actions at all (0, or no state) count as not advertised: Media3 always lists what it can do, so the
 * transport control is the one that reaches such a player. Play and pause keep the key: their 1 s check catches a
 * player that ignored it ([tileFallback]).
 */
fun tileMediaKey(vibb: Boolean, action: TileAction, advertised: Long): Int? = if (vibb) null else when (action) {
    TileAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
    TileAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
    TileAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT.takeIf { advertised and PlaybackState.ACTION_SKIP_TO_NEXT != 0L }
    TileAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS.takeIf { advertised and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L }
}

/** After [TILE_FALLBACK_MS] the media button's play or pause is checked: still not where it was asked to go
 * ([playingNow]), it goes again as the transport control (a session that handles no media button) - but only while
 * it is still the [newest] tap (QA M1: a pause-then-play within the second must not have the pause's check stop the
 * music again). A pause or a play twice is harmless; a skip is never repeated (it can't be told from the state, and
 * twice skips two). */
fun tileFallback(action: TileAction, playingNow: Boolean, newest: Boolean): Boolean = newest && when (action) {
    TileAction.PAUSE -> playingNow
    TileAction.PLAY -> !playingNow
    TileAction.NEXT, TileAction.PREVIOUS -> false
}

/** How long a media button's play or pause has before [tileFallback] looks. */
const val TILE_FALLBACK_MS = 1_000L

/**
 * Whether an app's session may show on the lock (and its controls act): never while [suspended] (a time rule, the used-up
 * budget, the camera lock - so the budget's exemption stays Vibb music's alone: no other app is left unsuspended by it).
 * Vibb music needs nothing more (its rule before 2026-11-04). Another app also must not be ours, nor hidden by the
 * device owner, and must be on the [allowlist] when the phone has one (null: no allowlist - an unmanaged phone; the
 * pause and the offline override lift the hiding, not this), and must be a [mediaApp] ([lockMediaApp]: the user,
 * 2026-11-04 - "bare for musikk apper og evt videoer og musikk som spilles av i browser"; a messenger's voice message
 * never shows on the lock; or one the parent switched on, 2026-11-04).
 */
fun lockAppUsable(
    pkg: String,
    vibb: Boolean,
    suspended: Boolean,
    hidden: Boolean,
    allowlist: Collection<String>?,
    ownPackage: String,
    mediaApp: Boolean,
): Boolean = when {
    suspended -> false
    vibb -> true
    else -> mediaApp && pkg != ownPackage && !hidden && (allowlist == null || pkg in allowlist)
}

/**
 * Another app is a media app for the lock: its manifest declares `android:appCategory` audio or video
 * (`ApplicationInfo.category`), or it is a web browser ([browser]: it opens a plain https link no app claims - music or
 * a video playing in the browser), or the parent switched it on ([chosen]: the policy's `lock_media_apps`,
 * [lockMediaAppsOf] - "Show as a media player on the lock screen", 2026-11-04, for a podcast app that declares no
 * category, AntennaPod in E9's M2). Every other category - social (messengers), games, productivity, maps, news, image,
 * accessibility - and an undefined one never shows unless chosen.
 */
fun lockMediaApp(category: Int, browser: Boolean, chosen: Boolean): Boolean =
    chosen || browser || category == ApplicationInfo.CATEGORY_AUDIO || category == ApplicationInfo.CATEGORY_VIDEO

/**
 * The policy's `lock_media_apps` (2026-11-04): the packages the parent switched on as media players on the lock. Only
 * valid package names; anything else - not a list, a wrong type, null - is none (a parent's "on" can't widen anything
 * but the tile).
 */
fun lockMediaAppsOf(element: JsonElement?): Set<String> =
    (element as? JsonArray).orEmpty().mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { isValidPackageName(it) }
    }.toSet()

/**
 * Whether the full tile shows its previous or next button ([next]) for the picked session: always for Vibb music (as
 * before); for another app hidden only when its `PlaybackState` [advertised] actions are known (non-zero) and lack that
 * skip (`ACTION_SKIP_TO_PREVIOUS` / `_NEXT`; E9b: Fennec's session, which can't skip, showed both and a tap did
 * nothing). No actions (0, or no state) shows both: the tap then goes as the transport control ([tileMediaKey]), which
 * reaches a player's `onSkipToNext` whatever it lists (QA L2 of lock-media-switch). The hidden button keeps its place,
 * so play/pause stays in the middle.
 */
fun lockSkipShown(vibb: Boolean, advertised: Long, next: Boolean): Boolean =
    vibb || advertised == 0L ||
        advertised and (if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L

/**
 * The web browsers (QA M2 of lock-media-apps-2): the apps that open a plain https link no app claims ([https]) and
 * a plain http one too ([http]) - a browser declares both; an app faking a browser with one host-less https filter
 * drops out.
 */
fun lockBrowsers(https: Set<String>, http: Set<String>): Set<String> = https intersect http

/** At most this many characters of another app's title or album on the lock (S-L8: its metadata is untrusted text). */
const val FOREIGN_TEXT_MAX = 120

/** Another app's times are kept within 0..this (100 hours) - its position and duration ([foreignSession]) and the
 * position carried forward while it plays ([lockPositionNow]) -, so bogus times can't widen the tile's times. */
const val FOREIGN_TIME_MAX_MS = 100 * 3_600_000L

/** Another app's art is scaled down to at most this many pixels on its long side before the lock draws it. */
const val FOREIGN_ART_MAX_PX = 512

/** At most this many combining marks in a row are kept on another app's text (code QA L2: a pile of them draws over the
 * lines above and below). */
const val FOREIGN_MARKS_MAX = 3

private fun isMark(codePoint: Int) = Character.getType(codePoint).let {
    it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
}

/** Line breaks, tabs, NUL; the bidi embeddings, overrides and isolates (U+202A-202E, U+2066-2069), zero-width and the
 * other format characters; U+2028/2029. */
private fun isBlanked(codePoint: Int) = Character.getType(codePoint).let {
    it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() ||
        it == Character.LINE_SEPARATOR.toInt() || it == Character.PARAGRAPH_SEPARATOR.toInt()
}

/**
 * Another app's title or album as the lock shows it: control, format (bidi controls among them) and line/paragraph
 * separator characters as spaces, at most [FOREIGN_MARKS_MAX] combining marks in a row, trimmed, cut to
 * [FOREIGN_TEXT_MAX] characters without splitting a surrogate pair; blank is null. The tile's lines are one line each,
 * ellipsized, as for Vibb music.
 */
fun foreignText(text: String?): String? {
    text ?: return null
    val out = StringBuilder()
    var marks = 0
    var i = 0
    // Read a little past the cut only (the trim may drop leading spaces).
    while (i < text.length && out.length < FOREIGN_TEXT_MAX * 4) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        marks = if (isMark(cp)) marks + 1 else 0
        if (marks > FOREIGN_MARKS_MAX) continue
        if (isBlanked(cp)) out.append(' ') else out.appendCodePoint(cp)
    }
    val clean = out.toString().trim()
    if (clean.isEmpty()) return null
    if (clean.length <= FOREIGN_TEXT_MAX) return clean
    val end = if (clean[FOREIGN_TEXT_MAX - 1].isHighSurrogate()) FOREIGN_TEXT_MAX - 1 else FOREIGN_TEXT_MAX
    return clean.substring(0, end).trimEnd()
}

/** Another app's session as the lock may show it: [foreignText] for its texts, its times within [FOREIGN_TIME_MAX_MS]. */
fun foreignSession(session: MusicSession): MusicSession = session.copy(
    entry = foreignText(session.entry),
    item = foreignText(session.item),
    positionMs = session.positionMs.coerceIn(0, FOREIGN_TIME_MAX_MS),
    durationMs = session.durationMs.coerceIn(0, FOREIGN_TIME_MAX_MS),
)

/** The lock's position now ([positionNow]) within [FOREIGN_TIME_MAX_MS] (code QA L3: a playing session without a
 * duration is carried forward without an end; Vibb music never gets near 100 hours). Home's card uses [positionNow]. */
fun lockPositionNow(session: MusicSession, nowElapsedMs: Long): Long = positionNow(session, nowElapsedMs).coerceAtMost(FOREIGN_TIME_MAX_MS)
