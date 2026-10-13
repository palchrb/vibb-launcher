package me.vibb.music.bridge

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.bridge.IMusicBridge
import me.vibb.bridge.IStorytelCallback
import me.vibb.music.data.Store
import me.vibb.music.logic.WantedArt
import me.vibb.music.logic.heldOwnFiles
import me.vibb.music.logic.keepOwnCopy
import me.vibb.music.logic.ownFetchPlan
import me.vibb.music.model.OwnFile
import me.vibb.music.model.Source
import me.vibb.music.sync.ArtWants
import me.vibb.music.sync.Inbox

private const val LOG_TAG = "BridgeSync"

/**
 * The bridge part of the sync (design 21 §2/§4.5, 21b §5-§6): the managed config (language, mobile
 * data), then from the launcher the library, the listings whose version changed, covers, own files
 * (copied, checked, then released so the launcher deletes its copy) and the Storytel login. The
 * report goes back at the end of the sync ([push]). Everything lands through [Inbox]'s checks.
 */
object BridgeSync {
    /** Before the sync's own work. */
    suspend fun pull(context: Context) {
        val store = Store.get(context)
        val config = Bridge.config(context)
        if (!config.managed) return
        Bridge.withBridge(context) { bridge ->
            pullLibrary(store, bridge, config)
            pullListings(store, bridge)
            pullCovers(context, store, bridge)
            pullFiles(store, bridge)
            pullStorytel(context, bridge, config)
        }
    }

    private fun pullLibrary(store: Store, bridge: IMusicBridge, config: BridgeConfig) {
        val have = store.library()?.version
        if (nothingTicked(config)) {
            if (have != "") Inbox.putLibrary(store, EMPTY_LIBRARY)
            return
        }
        bridge.openLibrary(have)?.let { pfd ->
            val text = read(pfd).toString(Charsets.UTF_8)
            if (!Inbox.putLibrary(store, text)) Log.w(LOG_TAG, "The launcher's library isn't a v1 library")
        }
    }

    private suspend fun pullListings(store: Store, bridge: IMusicBridge) {
        val lib = store.library() ?: return
        val applied = store.db.listings().associate { it.entryId to it.version }
        val launcher = lib.entries.filter { it.source != Source.OWN }.associate { it.id to bridge.listingVersion(it.id) }
        for (id in listingsWanted(launcher, applied)) {
            bridge.openListing(id)?.let { Inbox.putListing(store, id, read(it).toString(Charsets.UTF_8)) }
        }
    }

    /** The library's covers, then every wanted episode picture the launcher has (design 27 §2.2). */
    private suspend fun pullCovers(context: Context, store: Store, bridge: IMusicBridge) {
        val lib = store.library() ?: return
        val want = ArtWants.compute(context)
        for (hash in lib.covers + want.now + want.later) {
            if (store.coverFile(hash).isFile) continue
            bridge.openCover(hash)?.let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd).use { Inbox.putCover(store, hash, it) } }
        }
    }

    /**
     * Own files (21 §2): copy, check size and SHA-256, close, release. The report at the end of the
     * sync names the files held, so the launcher fetches the missing ones (and only those). A held
     * copy whose recorded SHA-256 isn't the library's (a file id reused after a server restore or on a
     * new server, design 29 step 4) is deleted and fetched again.
     */
    private fun pullFiles(store: Store, bridge: IMusicBridge) {
        val lib = store.library() ?: return
        val plan = ownFetchPlan(lib.files, store.ownCopies()) { keptCopy(store, it) }
        for (id in plan.refetch) {
            Log.w(LOG_TAG, "Own file $id's copy is another file (a reused id): fetched again")
            store.deleteOwnCopy(id)
        }
        for (file in plan.fetch) {
            val pfd = bridge.openFile(file.id) ?: continue
            val ok = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { Inbox.putOwnFile(store, file, it) }
            if (ok) bridge.releaseFile(file.id, file.sha256) else Log.w(LOG_TAG, "Own file ${file.id} didn't match its size or SHA-256")
        }
    }

    /** The Storytel login when the launcher's generation isn't ours (`!=`, QA #7). */
    private suspend fun pullStorytel(context: Context, bridge: IMusicBridge, config: BridgeConfig) {
        val have = StorytelLogin.generation(context)
        if (config.storytelGeneration == have) return
        val answer = CompletableDeferred<Triple<Long, String?, String?>>()
        bridge.getStorytelLogin(have, object : IStorytelCallback.Stub() {
            override fun onLogin(generation: Long, email: String?, password: String?) {
                answer.complete(Triple(generation, email, password))
            }
        })
        val (generation, email, password) = withTimeoutOrNull(20_000) { answer.await() } ?: return
        runCatching { StorytelLogin.store(context, generation, email, password) }
            .onFailure { Log.w(LOG_TAG, "Couldn't store the Storytel login (${it.javaClass.simpleName})") }
    }

    /** Is [file]'s held copy the library's file (its record, written by hashing it once when there is none)? */
    private fun keptCopy(store: Store, file: OwnFile): Boolean =
        keepOwnCopy(file.sha256, store.ownSha(file.id), { Inbox.sha256(store.ownFile(file.id)) }, { store.recordOwnSha(file.id, it) })

    /**
     * At the end of the sync: the report (21b §4.3) and the own files held (so the launcher never fetches
     * them again) - not a copy known to be another file (design 29 step 4), so the launcher fetches a
     * reused id's new file; a copy not judged yet still counts (qa-29-code L3).
     */
    suspend fun push(context: Context, report: String) {
        val store = Store.get(context)
        val lib = store.library()
        val held = heldOwnFiles(lib?.files.orEmpty(), store.ownCopies()) { store.ownSha(it) }
        val state = Bundle().apply {
            putString(REPORT_KEY, report)
            putLongArray(HELD_FILES_KEY, held.map { it.id }.toLongArray())
            putStringArray(HELD_SHA256_KEY, held.map { it.sha256 }.toTypedArray())
        }
        Bridge.withBridge(context) { it.reportState(state) }
    }

    /**
     * At the end of the sync, next to [push]: the pictures wanted (design 27 §2.2). An older launcher
     * ignores the call (an unknown oneway transaction), and the app shows series covers, as before.
     */
    suspend fun wantArt(context: Context, want: WantedArt) {
        if (!Bridge.config(context).managed) return
        Bridge.withBridge(context) { it.wantArt(want.now.toTypedArray(), want.later.toTypedArray()) }
    }

    private fun read(pfd: ParcelFileDescriptor): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
}
