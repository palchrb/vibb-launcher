package com.kidslauncher.mdm.music

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ViewLockGlanceBinding
import com.kidslauncher.mdm.lock.GlanceTileSize
import com.kidslauncher.mdm.lock.glanceTicks

/**
 * Vibb music on the PIN lock (design 21 step 4, design 25): the glance page's full and compact
 * tiles, bound while a session is live and inside the listening window (design 28: playing, or 10
 * minutes after the end of play or a fresh headset - Home's card's rule, [shown]; never the
 * snapshot, which has no controls without opening the player) (the keypad stage has no music since
 * 25b-G, user decision 2026-10-12: it stays plain). No stage logic - the glance
 * panel (lock/GlancePanel) decides whether a tile shows and which ([show], from its fit); this binds
 * the session's art, item, entry, progress and play/pause into them, watches the session between the
 * lock's onStart and onStop and tells the panel when it changed ([onChange]).
 *
 * It weakens nothing (LockMediaGuardTest): the session's play/pause, previous and next are all it
 * does - it never opens the music app, its session activity, a setting or a notification.
 */
class MusicLockPanel(
    private val activity: Activity,
    private val glance: ViewLockGlanceBinding,
    private val onChange: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var session: MusicSession? = null
    private var usable = false
    private var tile = GlanceTileSize.NONE
    private var pageShown = false
    private var shownArt: Bitmap? = null
    private var artShown = false
    private val ticker = Runnable { render() }
    private val changed: () -> Unit = {
        lookUp()
        onChange()
    }
    /** The listening window's end while a paused session shows: the panel refits without the tile (design 28 §2.4). */
    private val expiry = Runnable { changed() }

    init {
        glance.lockMediaPlay.setOnClickListener { playPause() }
        glance.lockMediaPrevious.setOnClickListener { MusicSessions.previous(activity, MusicSessions.reading.controller) }
        glance.lockMediaNext.setOnClickListener { MusicSessions.next(activity, MusicSessions.reading.controller) }
        glance.lockMediaCompactPlay.setOnClickListener { playPause() }
    }

    /** onStart: watching the session and the listening window. */
    fun start() {
        if (started) return
        started = true
        MusicSessions.addListener(activity, changed)
        MusicListening.addListener(changed)
    }

    /** onStop (and onDestroy): nothing watched or ticking while the lock isn't shown. */
    fun stop() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(expiry)
        if (!started) return
        started = false
        MusicSessions.removeListener(changed)
        MusicListening.removeListener(changed)
    }

    /** The session the lock may show, looked up again ([shown]); a paused one's window end is
     * rechecked once (at least 1 ms later, so the end is never re-rendered in a loop). */
    fun lookUp(): MusicSession? {
        handler.removeCallbacks(expiry)
        val reading = MusicSessions.reading
        usable = MusicSessions.usable(activity, reading.session?.packageName)
        val now = SystemClock.elapsedRealtime()
        val tileNow = shown(now)
        session = tileNow.session
        // Only while started: stop() removes the expiry, and a post after it would outlive the lock.
        if (started) musicRecheckInMs(tileNow.hideAtElapsedMs, now)?.let { handler.postDelayed(expiry, it) }
        return session
    }

    /**
     * The one read of what the lock shows, for [lookUp] and [render] alike: the pure
     * [lockTileSession] (none without listener access, with the music app suspended or gone; kept only
     * while it plays or inside the listening window - design 28, QA L7 - a live session only, never
     * the snapshot), with the window's end for [lookUp]'s recheck (null while playing).
     */
    private fun shown(nowElapsedMs: Long = SystemClock.elapsedRealtime()): WindowedSession {
        val reading = MusicSessions.reading
        val now = BootStamp(nowElapsedMs, MusicListening.bootCount())
        return lockTileSession(reading.access, usable, reading.session, MusicListening.listening(), now)
    }

    /** The glance panel's choice: the full tile, the compact one or none; [page]: the glance page
     * shows (not the keypad stage) - the progress ticks only then. */
    fun show(size: GlanceTileSize, page: Boolean) {
        tile = size
        pageShown = page
        render()
    }

    private fun playPause() = MusicSessions.playPause(activity, MusicSessions.reading.controller, session)

    private fun render() {
        handler.removeCallbacks(ticker)
        session = shown().session
        val s = session
        val size = if (s == null) GlanceTileSize.NONE else tile
        glance.lockMediaTile.visibility = if (size == GlanceTileSize.FULL) View.VISIBLE else View.GONE
        glance.lockMediaCompact.visibility = if (size == GlanceTileSize.COMPACT) View.VISIBLE else View.GONE
        if (s == null) return
        val playIcon = if (s.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play
        val playLabel = activity.getString(if (s.playing) R.string.music_pause else R.string.music_play)
        val art = MusicSessions.reading.art
        if (!artShown || art !== shownArt) {
            artShown = true
            shownArt = art
            glance.lockMediaArt.setImageBitmap(art)
            glance.lockMediaCompactArt.setImageBitmap(art)
            val glyph = if (art == null) View.VISIBLE else View.GONE
            glance.lockMediaGlyph.visibility = glyph
            glance.lockMediaCompactGlyph.visibility = glyph
        }
        val item = s.item ?: s.entry
        val entry = if (s.item != null) s.entry else null
        glance.lockMediaItem.text = item
        glance.lockMediaEntry.text = entry
        glance.lockMediaCompactItem.text = item
        glance.lockMediaCompactEntry.text = entry
        val position = positionNow(s, SystemClock.elapsedRealtime())
        val progress = progressPermille(position, s.durationMs)
        val progressText = if (s.durationMs > 0) {
            activity.getString(R.string.music_time_of, clockText(position), clockText(s.durationMs))
        } else {
            clockText(position)
        }
        for (bar in listOf(glance.lockMediaProgress, glance.lockMediaCompactProgress)) {
            bar.progress = progress
            bar.contentDescription = progressText
        }
        for (button in listOf(glance.lockMediaPlay, glance.lockMediaCompactPlay)) {
            button.setImageResource(playIcon)
            button.contentDescription = playLabel
        }
        if (glanceTicks(size != GlanceTileSize.NONE, pageShown, s)) handler.postDelayed(ticker, 1_000)
    }
}
