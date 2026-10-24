package me.vibb.music.playback

import androidx.media3.common.Player
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.logic.Repeat
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 49 §1.3-§1.4: only the app's own commands change shuffle and repeat (`KidPlayer` refuses the Player's set-mode
 * commands from the launcher, Bluetooth and the notification, so Room stays the truth), every queue hands its modes to
 * the renderer, and the phone's ExoPlayer repeats by its own repeat mode. `KidPlayer` is the service's inner class: its
 * source is read, as GuardsTest reads it.
 */
class KidPlayerTest {
    private fun code(path: String) = moduleFile(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val service by lazy { code("src/main/java/me/vibb/music/playback/PlaybackService.kt") }

    @Test
    fun `both Player set-mode commands are refused - not offered, and a call does nothing`() {
        val kid = service.substringAfter("private inner class KidPlayer").substringBefore("private val callback")
        assertTrue(kid.contains(".removeAll(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)"))
        assertTrue(kid.contains("private val refused = listOf(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)"))
        assertTrue(kid.contains("override fun isCommandAvailable(command: Int): Boolean = command !in refused &&"))
        assertTrue(kid.contains("override fun setRepeatMode(repeatMode: Int) = Unit"))
        assertTrue(kid.contains("override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit"))
    }

    @Test
    fun `every queue hands its shuffle and repeat to the renderer, and the toggles are the app's commands`() {
        val loads = Regex("\\bactive = a\\n").findAll(service).count()
        assertEquals(3, loads)
        assertEquals("each one followed by applyModes", loads, Regex("\\bactive = a\\n\\s*applyModes\\(a\\)").findAll(service).count())
        assertTrue(service.contains("Commands.REPEAT -> toggleRepeat()"))
        assertTrue(service.contains("Commands.THEN_REPEAT -> toggleRepeat()"))
        assertTrue(service.contains("store.db.upsertRepeat(RepeatRow(entryKey, next.word))"))
        assertTrue("the move-on wraps only through moveOnIndex", service.split("moveOnFrom(a, index)").size == 3)
        // qa-music-a-code 1: a run of failures counts its lap across onError's calls
        val onError = service.substringAfter("private fun onError(").substringBefore("\n    }\n")
        assertTrue(onError.contains("if (a.failRunFrom == null) a.failRunFrom = index"))
        assertTrue(service.contains("moveOnIndex(a.items.size, from, a.repeat, a.failRunFrom)"))
        assertTrue("cleared by real play", service.substringAfter("private val saveTick").substringBefore("\n    }\n").contains("active?.failRunFrom = null"))
    }

    @Test
    fun `the phone's player repeats by ExoPlayer's repeat mode, set from the entry`() {
        assertEquals(Player.REPEAT_MODE_OFF, exoRepeatMode(Repeat.OFF))
        assertEquals(Player.REPEAT_MODE_ALL, exoRepeatMode(Repeat.ALL))
        assertEquals(Player.REPEAT_MODE_ONE, exoRepeatMode(Repeat.ONE))
        val local = code("src/main/java/me/vibb/music/playback/LocalRenderer.kt")
        assertTrue(local.contains("exo.repeatMode = exoRepeatMode(repeat)"))
        assertTrue("ExoPlayer's shuffle is never ours", !local.contains("shuffleModeEnabled"))
        // the entry's mode comes from Room through the snapshot, off where the entry has no repeat to offer
        val own = entry(1, Source.OWN, resume = false)
        val podcast = entry(2, Source.RSS)
        val snap = snapshot(library(listOf(own, podcast))).copy(repeat = mapOf(own.key to Repeat.ONE, podcast.key to Repeat.ALL))
        assertEquals(Repeat.ONE, snap.repeatOf(own))
        assertEquals("a podcast has the skip pair, never repeat", Repeat.OFF, snap.repeatOf(podcast))
    }

    @Test
    fun `r12 E-1 - controllers hear KidPlayer's commands, never the wrapped player's - a run's last item keeps next`() {
        // ExoPlayer drops seek-to-next on a queue's last item (a favourites run of one); the session forwards the
        // listener's argument to every controller (the app's buttons, the lock tile, the notification)
        val ours = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build()
        val heard = mutableListOf<Player.Commands>()
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                heard += availableCommands
            }
        }
        val listeners = KidListeners { ours }
        val wrapped = listeners.wrap(listener)
        wrapped.onAvailableCommandsChanged(Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build())
        assertEquals(listOf(ours), heard)
        assertTrue(heard.single().contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue("one wrapper per listener", listeners.wrap(listener) === wrapped)
        assertTrue("a removal finds the wrapper it added", listeners.unwrap(listener) === wrapped)
        assertTrue(listeners.unwrap(listener) === listener)
        val kid = service.substringAfter("private inner class KidPlayer").substringBefore("private val callback")
        assertTrue(kid.contains("private val listeners = KidListeners { availableCommands }"))
        assertTrue(kid.contains("override fun addListener(listener: Player.Listener) = super.addListener(listeners.wrap(listener))"))
        assertTrue(kid.contains("override fun removeListener(listener: Player.Listener) = super.removeListener(listeners.unwrap(listener))"))
    }

    @Test
    fun `r12 E-1-Q-1 - the wrapper forwards every Player Listener method, the commands alone replaced`() {
        // Kotlin's `by` forwards no Java default method: the session's listeners went deaf (play state, items, timeline,
        // metadata, positions, errors). Every method the interface has now - a Media3 bump's new one too - must arrive.
        val ours = Player.Commands.Builder().add(Player.COMMAND_SEEK_TO_NEXT).build()
        val arrived = mutableListOf<Pair<java.lang.reflect.Method, Array<Any?>?>>()
        lateinit var inner: Player.Listener
        inner = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player.Listener::class.java)) { _, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(inner)
                "equals" -> args!![0] === inner
                "toString" -> "inner"
                else -> {
                    arrived += m to args
                    null
                }
            }
        } as Player.Listener
        val wrapper = KidListeners { ours }.wrap(inner)
        val methods = Player.Listener::class.java.methods.filter { it.declaringClass == Player.Listener::class.java }
        assertTrue("Media3 1.7.1's listener has 37 methods", methods.size >= 37)
        for (m in methods) {
            val args = m.parameterTypes.map { t ->
                when (t) {
                    java.lang.Boolean.TYPE -> true
                    Integer.TYPE -> 7
                    java.lang.Long.TYPE -> 7L
                    java.lang.Float.TYPE -> 0.5f
                    else -> null
                }
            }.toTypedArray<Any?>()
            arrived.clear()
            m.invoke(wrapper, *args)
            assertEquals("${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) reaches the inner listener", listOf(m), arrived.map { it.first })
            val got = arrived.single().second?.toList().orEmpty()
            if (m.name == "onAvailableCommandsChanged") assertEquals(listOf<Any?>(ours), got) else assertEquals(args.toList(), got)
        }
    }
}
