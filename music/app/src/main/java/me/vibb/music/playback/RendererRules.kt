package me.vibb.music.playback

import me.vibb.music.logic.NetCaps
import me.vibb.music.logic.Playability
import me.vibb.music.logic.SPOTIFY_CONTEXT_KEY
import me.vibb.music.logic.firstPlayable
import me.vibb.music.logic.isContextItem
import me.vibb.music.logic.isSpotify
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.playability
import me.vibb.music.logic.spotifyPlayability
import me.vibb.music.model.Entry
import me.vibb.music.model.Source
import me.vibb.music.spotify.SPOTIFY_MODE_CONTEXT
import me.vibb.music.spotify.spotifyTarget
import me.vibb.music.spotify.uriOf

/*
 * The service's renderer rules (design 26c §5.10, step 5b), pure: which of the phone's renderers plays
 * an entry, whether an item can play now on the renderer that plays it, where a queue may start or step
 * to, the call rule's "paused" test and the natural advance's resume seek. `PlaybackService` asks these
 * at each site of §5.10's table (GuardsTest pins the sites); RendererRulesTest pins the rules.
 */

/**
 * Who plays [entry] on the phone (§5.10, "Which renderer"): Spotify's web player for a Spotify entry,
 * the phone's own player (ExoPlayer) for every other. A Sonos room keeps its renderer: its queue never
 * holds a Spotify item (`PlayBodies.speakerDropReason`, §4.2), so a Spotify entry never reaches it.
 */
fun phoneRendererFor(entry: Entry): RendererKind = if (isSpotify(entry)) RendererKind.SPOTIFY else RendererKind.LOCAL

/**
 * The renderer a new queue for [entry] is built for (design 26c §5.10; the merge of main 571f86f, qa-26c-merge-code
 * M1): on the phone, the phone's renderer for the entry ([phoneRendererFor]: `start` attaches it) - never the one
 * that happens to be attached, which is still Spotify's web player after a Spotify play, or the phone's own player
 * before one; on a room ([current] not `onPhone`), the room. `playEntry`, `playItem` and `restore` pass it to
 * `queueFor`, as `comeHome` passes [phoneRendererFor].
 */
fun queueKind(current: RendererKind, entry: Entry): RendererKind = if (current.onPhone) phoneRendererFor(entry) else current

/**
 * Whether a new queue claims its start item and the next and re-reads its rows (design 30 §1.6, qa-30 L4:
 * `PlayerHub.claim`, `CacheThrough.queueRows` - the stale-partial check): a queue for the phone's own player
 * ([kind] `LOCAL`, the queue's kind from [queueKind]) of any entry but own files. Never a Spotify queue (its web
 * player opens no cache) and never a room's.
 */
fun claimsQueue(kind: RendererKind, entry: Entry): Boolean = kind == RendererKind.LOCAL && entry.source != Source.OWN

/**
 * Whether an item of [entry] can play now (§5.10 `playable`, QA H1): on a room ([kind] not `onPhone`)
 * always (the speaker fetches by itself and its queue holds only what it can play); a Spotify item by the
 * network gate alone (`spotifyPlayability`: never `GONE`, it has no copy and no url by design); every other
 * item from its copy, else streamed through the gate. [streamMobileData] is design 30's streaming switch -
 * a stream's, Spotify's included; the downloads switch never decides whether something plays.
 */
fun itemPlayable(kind: RendererKind, entry: Entry, hasCopy: Boolean, hasUrl: Boolean, caps: NetCaps?, streamMobileData: Boolean): Boolean = when {
    !kind.onPhone -> true
    isSpotify(entry) -> spotifyPlayability(caps, streamMobileData) == Playability.STREAM
    else -> playability(hasCopy, hasUrl, caps, streamMobileData).let { it == Playability.LOCAL || it == Playability.STREAM }
}

/**
 * The first index of [entry]'s queue of [size] from [index] (forward or back, [wrap]ped or not) that can
 * play now (§5.10 `playableFrom`): for a Spotify entry all items or none - the gate is the network, not
 * the item - so [index] itself or null, never another item; for every other entry the first [playable].
 */
fun playableIndex(entry: Entry, size: Int, index: Int, forward: Boolean, wrap: Boolean, playable: (Int) -> Boolean): Int? =
    if (isSpotify(entry)) index.takeIf { it in 0 until size && playable(it) } else firstPlayable(size, index, forward, wrap, playable)

/**
 * The uri a Spotify item's MediaItem carries (§5.10 `mediaItem`): `uriOf(kind, key)` - `spotify:track:<key>`,
 * or `spotify:episode:<key>` for a show or an episode entry -, and for a context-mode entry's synthetic item
 * (key `context`, §5.9, step 6) the entry's target; null for a target the grammar refuses (the library hides
 * such an entry).
 */
fun spotifyItemUri(entry: Entry, key: String): String? {
    val ref = spotifyTarget(entry.target) ?: return null
    return if (entry.spotifyMode == SPOTIFY_MODE_CONTEXT && key == SPOTIFY_CONTEXT_KEY) ref.target else uriOf(ref.kind, key)
}

/**
 * The call rule's "paused" (§5.8, `callStep`): a renderer with a queue that doesn't show playing and has no
 * remote state - a Sonos room's four lines mean the speaker isn't ours to count; a Spotify line
 * ([RendererState.remote] false) is a paused web player, as the phone's own.
 */
fun pausedForCall(showsPlaying: Boolean, itemCount: Int, state: RendererState?): Boolean =
    !showsPlaying && itemCount > 0 && state?.remote != true

/**
 * The call rule's "a call is on" for the renderer [kind] (26a §6.7, `callStep`): the launcher's word for every renderer;
 * the audio mode (a ringing or active system call) too on a Sonos room - the backstop without the launcher's listener
 * access - and on Spotify's web player (emulator round 2026-10-14 N3): Chromium's own focus loss pauses the page before
 * the launcher looks at the session, so the launcher often sends nothing; the phone's own player has its audio focus.
 */
fun callOn(launcherInCall: Boolean, modeInCall: Boolean, kind: RendererKind): Boolean =
    launcherInCall || (modeInCall && (kind == RendererKind.SONOS || kind == RendererKind.SPOTIFY))

/**
 * A cover tap on the Spotify entry whose queue is [loaded] goes on with it only while the library still lists it the same
 * way ([now], the library's entry of that id now; null = gone, nothing to compare): the same key, target and mode. An
 * entry flipped from list to context mode (a 403 on the playlist, design 26c §5.9) - or back - while its play was alive
 * starts its new queue instead, so the old list's queue adds stop (emulator round 2026-10-14 N5). Every other source
 * resumes as ever (vibb's tile press).
 */
fun loadedPlayStale(loaded: Entry, now: Entry?): Boolean =
    isSpotify(loaded) && now != null && (now.key != loaded.key || now.target != loaded.target || now.spotifyMode != loaded.spotifyMode)

/**
 * A natural advance resumes an item heard before (vibb: in-session jumps resume too) on the phone's own
 * player only (§5.10, QA M4): on a room and on Spotify the next track starts where the renderer starts it,
 * at 0 - nothing is sent.
 */
fun resumesOnAdvance(auto: Boolean, kind: RendererKind): Boolean = auto && kind == RendererKind.LOCAL

/**
 * Whether the player's duration of item [key] of [entry] is kept (`Active.durations`, the done mark at a queue's end,
 * the stored queue's duration): never for a Spotify context's synthetic item (design 26c §5.9, step 6) - the web
 * player's duration is the live track's, and a track's end is not the context's, so the item is never done.
 */
fun keepsDuration(entry: Entry, key: String): Boolean = !isContextItem(entry, key)

/**
 * Whether next and previous on [entry] are the web player's own (design 26c §5.9, step 6): a Spotify context's -
 * Spotify's order, `vibb.next()`/`vibb.previous()` - never the service's skip rules over its one synthetic item.
 */
fun stepsOnWebPlayer(entry: Entry): Boolean = isSpotifyContext(entry)

/**
 * Whether a failed item that is neither an own file nor a copy (those have their own rules in `onError`: nothing,
 * or the broken copy dropped) goes into `item_errors` (21b §6; §5.10 `onError`): a listed url's stream, and a
 * Spotify track the account can't play (`unplayable`, §5.8) though it has no url; never a withdrawn item.
 */
fun recordsItemError(entry: Entry, hasUrl: Boolean): Boolean = hasUrl || isSpotify(entry)
