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
 * tiles, bound while a session is live (the keypad stage has no music since 25b-G, user decision
 * 2026-10-12: it stays plain). No stage logic - the glance
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

    init {
        glance.lockMediaPlay.setOnClickListener { playPause() }
        glance.lockMediaPrevious.setOnClickListener { MusicSessions.previous(activity, MusicSessions.reading.controller) }
        glance.lockMediaNext.setOnClickListener { MusicSessions.next(activity, MusicSessions.reading.controller) }
        glance.lockMediaCompactPlay.setOnClickListener { playPause() }
    }

    /** onStart: watching the session. */
    fun start() {
        if (started) return
        started = true
        MusicSessions.addListener(activity, changed)
    }

    /** onStop (and onDestroy): nothing watched or ticking while the lock isn't shown. */
    fun stop() {
        handler.removeCallbacks(ticker)
        if (!started) return
        started = false
        MusicSessions.removeListener(changed)
    }

    /** The session the lock may show, looked up again (`shownSession`: none without listener access,
     * with the music app suspended or gone). */
    fun lookUp(): MusicSession? {
        val reading = MusicSessions.reading
        usable = MusicSessions.usable(activity, reading.session?.packageName)
        session = shownSession(reading.access, usable, reading.session)
        return session
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
        session = shownSession(MusicSessions.reading.access, usable, MusicSessions.reading.session)
        val s = session
        val shown = if (s == null) GlanceTileSize.NONE else tile
        glance.lockMediaTile.visibility = if (shown == GlanceTileSize.FULL) View.VISIBLE else View.GONE
        glance.lockMediaCompact.visibility = if (shown == GlanceTileSize.COMPACT) View.VISIBLE else View.GONE
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
        if (glanceTicks(shown != GlanceTileSize.NONE, pageShown, s)) handler.postDelayed(ticker, 1_000)
    }
}
