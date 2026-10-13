package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.music.BootStamp
import com.kidslauncher.mdm.music.Listening
import com.kidslauncher.mdm.music.MusicSession
import com.kidslauncher.mdm.music.NowPlayingSnapshot
import com.kidslauncher.mdm.music.musicShownUntil
import com.kidslauncher.mdm.music.positionNow
import com.kidslauncher.mdm.music.shownSession

/*
 * Home's "Spilles nå" card (design 21 §3, mockup HomeCard.dc.html v12): pure, MusicCardTest. The
 * music package's MusicHomeCard draws it between the contacts row (or the call card) and the grid.
 */

/**
 * What the card shows: [title] is the library entry (the item when the entry is unknown), [item]
 * the episode or chapter in the line under it, the times, and whether the button is pause
 * ([playing]). [live]: from the music app's session - transport controls, a tap opens its session
 * activity; otherwise from the last `setNowPlaying` snapshot, shown paused - both open the player
 * with `EXTRA_RESUME`. [hideAtElapsedMs]: the listening window's end (design 28), null while playing.
 */
data class MusicCardModel(
    val title: String,
    val item: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val live: Boolean,
    val hideAtElapsedMs: Long?,
)

/**
 * The card now: never while the PIN lock is [locked] (Home shows the night ground then anyway),
 * without notification-listener [access] (no media surfaces) or while the music app isn't
 * [usable]; only while the session plays or inside the [listening] window (design 28: 10 minutes
 * after the end of play or a fresh headset, `musicShownUntil` in [bootCount]'s boot) - outside it
 * nothing, a paused session never falls back to the snapshot. Then the live session while it plays
 * or is paused; else the [snapshot], paused (back at once after a restart inside the window);
 * nothing when neither names anything. A live session without a known duration borrows the
 * snapshot's for the same item.
 */
fun musicCard(
    locked: Boolean,
    access: Boolean,
    usable: Boolean,
    session: MusicSession?,
    snapshot: NowPlayingSnapshot?,
    nowElapsedMs: Long,
    listening: Listening,
    bootCount: Int,
): MusicCardModel? {
    if (locked || !access || !usable) return null
    val live = shownSession(access, usable, session)
    val until = musicShownUntil(live?.playing == true, listening, BootStamp(nowElapsedMs, bootCount)) ?: return null
    val hideAt = until.takeIf { it != Long.MAX_VALUE }
    if (live != null) {
        val title = text(live.entry) ?: text(live.item) ?: text(snapshot?.entry) ?: return null
        val duration = live.durationMs.takeIf { it > 0 } ?: snapshot?.takeIf { it.item == live.item }?.durationMs ?: 0
        return MusicCardModel(
            title = title,
            item = text(live.item).takeIf { it != title },
            positionMs = positionNow(live.copy(durationMs = duration), nowElapsedMs),
            durationMs = duration,
            playing = live.playing,
            live = true,
            hideAtElapsedMs = hideAt,
        )
    }
    snapshot ?: return null
    val title = text(snapshot.entry) ?: text(snapshot.item) ?: return null
    return MusicCardModel(
        title = title,
        item = text(snapshot.item).takeIf { it != title },
        positionMs = snapshot.positionMs.coerceAtLeast(0).let { if (snapshot.durationMs > 0) it.coerceAtMost(snapshot.durationMs) else it },
        durationMs = snapshot.durationMs.coerceAtLeast(0),
        playing = false,
        live = false,
        hideAtElapsedMs = hideAt,
    )
}

/** The card's clock ticks once a second only while it is shown and playing. */
fun musicCardTicks(card: MusicCardModel?): Boolean = card?.playing == true

private fun text(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }
