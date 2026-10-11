package me.vibb.music.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import me.vibb.music.bridge.LiveBridge
import me.vibb.music.logic.resumeRequested
import me.vibb.music.playback.Commands
import me.vibb.music.playback.PlaybackService
import me.vibb.music.playback.PlayerHub

/**
 * The kid's screens (design 21 §4.2). Talks to the player through a [MediaController] (which also
 * binds the service; playback, and with it the foreground service, starts only on a tap).
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN = "me.vibb.music.extra.OPEN"
        const val OPEN_NEWS = "news"
        const val OPEN_PLAYER = "player"
        /** The launcher's Home card on its snapshot (design 21 §3 step 4): continue, as the play button would. */
        const val EXTRA_RESUME = "me.vibb.music.extra.RESUME"
    }

    private val vm: MusicViewModel by viewModels()
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        open(intent, restored = savedInstanceState != null)
        setContent { MusicRoot(vm, actions) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent, restored = false)
    }

    /** A new intent ([restored]: a recreated instance - its screen is already restored). */
    private fun open(intent: Intent?, restored: Boolean) {
        intent ?: return
        if (!restored) {
            when (intent.getStringExtra(EXTRA_OPEN)) {
                OPEN_NEWS -> vm.screen = Screen.NEWS
                OPEN_PLAYER -> vm.screen = Screen.PLAYER
            }
        }
        // Home's snapshot card: the last item where it stopped, or play on - once, as delivered;
        // never from Recents or a restored instance (resumeRequested).
        if (resumeRequested(intent.getBooleanExtra(EXTRA_RESUME, false), intent.flags, restored)) {
            intent.removeExtra(EXTRA_RESUME)
            command(Commands.RESUME)
        }
    }

    override fun onStart() {
        super.onStart()
        LiveBridge.acquire(this)
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            pending.forEach { it(c) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    /** The player in front counts as the music app's time, not screen time (21 §3, QA #5): only
     * as the top resumed activity, never in multi-window. */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        LiveBridge.setForeground(isTopResumedActivity && !isInMultiWindowMode)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) LiveBridge.setForeground(false)
    }

    override fun onStop() {
        LiveBridge.setForeground(false)
        LiveBridge.release(this)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        pending.clear()
        super.onStop()
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    private fun command(action: String, args: Bundle = Bundle.EMPTY) =
        withController { it.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }

    private fun restoreThen(then: String, positionMs: Long = 0) =
        command(Commands.RESTORE, bundleOf(Commands.ARG_THEN to then, Commands.ARG_POSITION to positionMs))

    private val actions = object : Actions {
        override fun playEntry(entryId: Long) = command(Commands.PLAY_ENTRY, bundleOf(Commands.ARG_ENTRY to entryId))
        override fun playItem(entryId: Long, key: String) = command(Commands.PLAY_ITEM, bundleOf(Commands.ARG_ENTRY to entryId, Commands.ARG_KEY to key))
        override fun togglePlay() {
            val hub = PlayerHub.state.value
            when {
                !hub.live -> command(Commands.RESUME)
                hub.playing -> withController { it.pause() }
                else -> withController { it.play() }
            }
        }
        // Before play after a restart the queue isn't loaded: these load it paused first (M6).
        override fun next() = if (PlayerHub.state.value.live) withController { it.seekToNext() } else restoreThen(Commands.THEN_NEXT)
        override fun previous() = if (PlayerHub.state.value.live) withController { it.seekToPrevious() } else restoreThen(Commands.THEN_PREVIOUS)
        override fun seekTo(ms: Long) = if (PlayerHub.state.value.live) withController { it.seekTo(ms) } else restoreThen(Commands.THEN_SEEK, ms)
        override fun toggleShuffle() = if (PlayerHub.state.value.live) command(Commands.SHUFFLE) else restoreThen(Commands.THEN_SHUFFLE)
        override fun sleep(minutes: Int) = command(Commands.SLEEP, bundleOf(Commands.ARG_MINUTES to minutes))
        override fun output(key: String) = command(Commands.OUTPUT, bundleOf(Commands.ARG_OUTPUT to key))
        override fun backHome() = finish()
    }
}

/** What the screens ask of the player. */
interface Actions {
    fun playEntry(entryId: Long)
    fun playItem(entryId: Long, key: String)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun toggleShuffle()
    fun sleep(minutes: Int)
    /** An `Output.key`. */
    fun output(key: String)
    fun backHome()
}
