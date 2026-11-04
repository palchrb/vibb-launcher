package com.kidslauncher.mdm.music

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
 * (`PlaybackState.getLastPositionUpdateTime`, the pause moment - the bridge stamps only Vibb music) and the same headset
 * stamp - so a pause tapped on the lock keeps the tile for the window, as Vibb music's does.
 *
 * Of the ones left: a playing one before a paused one; among equals Vibb music first; then the platform's order of the
 * active sessions (most recently active first) - so two playing apps or two paused ones always give the same pick.
 */
fun lockTilePick(access: Boolean, candidates: List<LockCandidate>, listening: Listening, now: BootStamp): LockPick? =
    candidates.mapIndexedNotNull { index, c ->
        val window = if (c.vibb) listening else Listening(c.session.positionAtElapsedMs.takeIf { it > 0 }?.let { BootStamp(it, now.bootCount) }, listening.headsetAt)
        lockTileSession(access, c.usable, c.session, window, now).takeIf { it.session != null }?.let { LockPick(index, it) }
    }.minWithOrNull(compareBy<LockPick>({ !candidates[it.index].session.playing }, { !candidates[it.index].vibb }, { it.index }))

/**
 * Whether an app's session may show on the lock (and its controls act): never while [suspended] (a time rule, the used-up
 * budget, the camera lock - so the budget's exemption stays Vibb music's alone: no other app is left unsuspended by it).
 * Vibb music needs nothing more (its rule before 2026-11-04). Another app also must not be ours, nor hidden by the
 * device owner, and must be on the [allowlist] when the phone has one (null: no allowlist - an unmanaged phone; the
 * pause and the offline override lift the hiding, not this).
 */
fun lockAppUsable(pkg: String, vibb: Boolean, suspended: Boolean, hidden: Boolean, allowlist: Collection<String>?, ownPackage: String): Boolean = when {
    suspended -> false
    vibb -> true
    else -> pkg != ownPackage && !hidden && (allowlist == null || pkg in allowlist)
}

/** At most this many characters of another app's title or album on the lock (S-L8: its metadata is untrusted text). */
const val FOREIGN_TEXT_MAX = 120

/** Another app's times are kept within 0..this (100 hours), so a bogus position or duration can't widen the tile's times. */
const val FOREIGN_TIME_MAX_MS = 100 * 3_600_000L

/** Another app's art is scaled down to at most this many pixels on its long side before the lock draws it. */
const val FOREIGN_ART_MAX_PX = 512

/**
 * Another app's title or album as the lock shows it: ISO control characters (line breaks, tabs, NUL) as spaces, trimmed,
 * cut to [FOREIGN_TEXT_MAX] without splitting a surrogate pair; blank is null. The tile's lines are one line each,
 * ellipsized, as for Vibb music.
 */
fun foreignText(text: String?): String? {
    val clean = text?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")?.trim() ?: return null
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
