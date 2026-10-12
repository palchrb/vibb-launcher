package me.vibb.music.storytel

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import me.vibb.music.BuildConfig
import me.vibb.music.bridge.StorytelLogin
import me.vibb.music.media.MediaCache
import me.vibb.music.model.JSON
import java.io.File

/*
 * The phone's Storytel account, ported from palchrb/vibb `pi/vibb/storytel.py` (`credentials`,
 * `_cooldown_*` and the module-level session the sync and the Sonos body share; MIT License, Copyright
 * (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26b
 * §4.2 (qa-26b-step1-code, choice 1).
 *
 * Exactly one StorytelApi for the process: the login lock and the JWT live in the instance, so one per
 * worker run would log in every run. It is built over phase 1's sealed login (read per login), the
 * refused-login cooldown in noBackupFilesDir/storytel_refused.json, the monotonic and the wall clock,
 * and OkHttpStorytelHttp over MediaCache's guarded public-only client with the app's own honest
 * User-Agent - or, with the debug switch (design 26b §4.6, readers (a) and (b)), the fake's endpoints
 * over the LAN client. Every call that can log in or open the Keystore runs off the main thread: [mint]
 * is a suspend on IO, [state] is called from the sync's report (IO). Nothing here logs.
 */
class StorytelAccount private constructor(private val api: StorytelApi) {
    /**
     * A fresh signed CDN URL for book [cid] (`asset_url`), or why there is none (design 26b §4.1). On
     * IO: the login lock can block a thread up to the deadline. [timeoutMs] bounds the asset call,
     * [deadlineMs] (SystemClock.elapsedRealtime) the whole mint - downloads `(15 s, now + 60 s)`, the
     * Sonos path `(6 s, now + 10 s)`. Interruptible (the main session's review of step 3, Low 2): a
     * cancelled caller - a download job WorkManager stops - interrupts the blocked thread, so the wait for
     * the login lock ends at once and an OkHttp call at its next read or write (Okio checks the flag),
     * instead of the caller hanging on the call up to its deadline; the caller gets its
     * CancellationException, never the mint's answer. A room's actor never cancels a job: step 4b's
     * superseded play is dropped by its token, and a mint already running finishes (qa-26b-merge-code
     * Low 1); cancelling a job there would let the CancellationException end `SonosActor.run`'s loop.
     */
    suspend fun mint(cid: String, timeoutMs: Long, deadlineMs: Long): Mint =
        runInterruptible(Dispatchers.IO) { api.assetUrl(cid, timeoutMs, deadlineMs) }

    /** The report's `storytel`: `none`, `refused` or `ok` (design 26b §4.5). Off the main thread: it opens the sealed login. */
    fun state(): String = api.state()

    companion object {
        /** What the one instance was built for: the endpoints and the client kind (they change only with the debug switch). */
        private var builtFor: Pair<Endpoints, Boolean>? = null

        @Volatile private var instance: StorytelAccount? = null

        /**
         * The process's account. In a release build the debug switch is always null, so it is built once;
         * in a debug build a changed `storytel_base` builds it once more (the fake, a new session).
         */
        fun get(context: Context): StorytelAccount = synchronized(this) {
            val endpoints = StorytelEndpoints.endpoints()
            val lan = StorytelEndpoints.apiOverLan()
            val key = endpoints to lan
            instance?.takeIf { builtFor == key } ?: build(context.applicationContext, endpoints, lan).also {
                instance = it
                builtFor = key
            }
        }

        private fun build(app: Context, endpoints: Endpoints, lan: Boolean): StorytelAccount = StorytelAccount(
            StorytelApi(
                http = OkHttpStorytelHttp(MediaCache.httpClient(publicOnly = !lan), "VibbMusic/${BuildConfig.VERSION_NAME}"),
                login = { StorytelLogin.load(app) },
                cooldown = FileCooldownStore(File(app.noBackupFilesDir, "storytel_refused.json")),
                elapsedMs = SystemClock::elapsedRealtime,
                wallMs = System::currentTimeMillis,
                endpoints = endpoints,
            ),
        )
    }
}

/**
 * The refused-login cooldown on disk (vibb `storytel-login-refused.json`): one record `{generation,
 * at}` (design 26b §4.2, §7 10b) written tmp + rename. A stamp for another generation is no stamp: a
 * new login from the bridge clears the cooldown without a second writer. A file that can't be read is
 * none; a failed write throws, and the API swallows it (qa-26b-step1-code L1).
 */
class FileCooldownStore(private val file: File) : CooldownStore {
    @Serializable
    private data class Stamp(val generation: Long, val at: Long)

    override fun refusedAt(generation: Long): Long? {
        val stamp = runCatching { JSON.decodeFromString(Stamp.serializer(), file.readText()) }.getOrNull() ?: return null
        return stamp.at.takeIf { stamp.generation == generation }
    }

    override fun setRefused(generation: Long, wallMs: Long) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(Stamp.serializer(), Stamp(generation, wallMs)))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("the cooldown stamp wasn't stored")
        }
    }
}
