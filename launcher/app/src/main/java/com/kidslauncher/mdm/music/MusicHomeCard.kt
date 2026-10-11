package com.kidslauncher.mdm.music

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ViewHomeMusicCardBinding
import com.kidslauncher.mdm.lock.LockMode
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.ui.home.MusicCardModel
import com.kidslauncher.mdm.ui.home.musicCard
import com.kidslauncher.mdm.ui.home.musicCardTicks

/**
 * Home's "Spilles nå" card (design 21 §3, mockup HomeCard.dc.html v12; what it shows is the pure
 * [musicCard]). Live (the music app's session plays or is paused): the button is the session's
 * play/pause and a tap opens its session activity. Snapshot (the last `setNowPlaying`, paused - after
 * a restart at once): both open the player with `EXTRA_RESUME`. The clock ticks once a second only
 * while the card shows and plays. [HomeActivity] makes it active between onStart and onStop while
 * its content is shown - never while the PIN lock is LOCKED.
 */
class MusicHomeCard(private val activity: Activity, private val view: ViewHomeMusicCardBinding) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var shown: MusicCardModel? = null
    private var shownArt: Bitmap? = null
    private var artShown = false
    /** Whether the music app can be used, looked up on changes only (not at every tick). */
    private var usable = false
    private val ticker = Runnable { render(lookUp = false) }
    private val changed: () -> Unit = { render() }

    init {
        view.root.setOnClickListener { open() }
        view.musicCardPlay.setOnClickListener { playPause() }
    }

    /** Between Home's onStart and onStop, with its content on the window. */
    fun setActive(on: Boolean) {
        if (on == active) {
            if (on) render()
            return
        }
        active = on
        if (on) {
            MusicSessions.addListener(activity, changed)
            MusicNowPlaying.addListener(changed)
            render()
        } else {
            handler.removeCallbacks(ticker)
            MusicSessions.removeListener(changed)
            MusicNowPlaying.removeListener(changed)
        }
    }

    private fun render(lookUp: Boolean = true) {
        handler.removeCallbacks(ticker)
        if (!active) return
        val reading = MusicSessions.reading
        if (lookUp) usable = MusicSessions.usable(activity, reading.session?.packageName ?: MusicStore.installedPackage(activity))
        val card = musicCard(
            locked = PinLockRuntime.mode == LockMode.LOCKED,
            access = reading.access,
            usable = usable,
            session = reading.session,
            snapshot = MusicNowPlaying.snapshot(activity),
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        shown = card
        if (card == null) {
            view.root.visibility = View.GONE
            return
        }
        view.root.visibility = View.VISIBLE
        view.musicCardTitle.text = card.title
        view.musicCardLine.text = progressLine(card.item, card.positionMs, card.durationMs) { a, b -> activity.getString(R.string.music_time_of, a, b) }
        view.musicCardPlay.setImageResource(if (card.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        view.musicCardPlay.contentDescription = activity.getString(if (card.playing) R.string.music_pause else R.string.music_play)
        view.root.contentDescription = activity.getString(R.string.music_card_description, card.title, view.musicCardLine.text)
        bindArt(if (card.live) reading.art else MusicSessions.snapshotArt(activity))
        if (musicCardTicks(card)) handler.postDelayed(ticker, 1_000)
    }

    private fun bindArt(art: Bitmap?) {
        if (artShown && art === shownArt) return
        artShown = true
        shownArt = art
        view.musicCardArt.setImageBitmap(art)
        view.musicCardGlyph.visibility = if (art == null) View.VISIBLE else View.GONE
    }

    /** A tap on the card: live, the session's activity (else the player as it is); the snapshot,
     * the player continuing. */
    private fun open() {
        val card = shown ?: return
        val reading = MusicSessions.reading
        if (card.live && MusicSessions.openSessionActivity(activity, reading.controller)) return
        openPlayer(resume = !card.live)
    }

    private fun playPause() {
        val card = shown ?: return
        val reading = MusicSessions.reading
        if (card.live) MusicSessions.playPause(activity, reading.controller, reading.session) else openPlayer(resume = true)
    }

    private fun openPlayer(resume: Boolean) {
        val pkg = MusicSessions.reading.session?.packageName ?: MusicStore.installedPackage(activity) ?: return
        MusicSessions.openPlayer(activity, pkg, resume)
    }
}
