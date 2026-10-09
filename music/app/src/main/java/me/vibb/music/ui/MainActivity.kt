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
    }

    private val vm: MusicViewModel by viewModels()
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) open(intent)
        setContent { MusicRoot(vm, actions) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_NEWS -> vm.screen = Screen.NEWS
            OPEN_PLAYER -> vm.screen = Screen.PLAYER
        }
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            pending.forEach { it(c) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
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
        override fun next() = withController { if (PlayerHub.state.value.live) it.seekToNext() }
        override fun previous() = withController { if (PlayerHub.state.value.live) it.seekToPrevious() }
        override fun seekTo(ms: Long) = withController { if (PlayerHub.state.value.live) it.seekTo(ms) }
        override fun toggleShuffle() = command(Commands.SHUFFLE)
        override fun sleep(minutes: Int) = command(Commands.SLEEP, bundleOf(Commands.ARG_MINUTES to minutes))
        override fun output(id: Int) = command(Commands.OUTPUT, bundleOf(Commands.ARG_OUTPUT to id))
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
    fun output(id: Int)
    fun backHome()
}
