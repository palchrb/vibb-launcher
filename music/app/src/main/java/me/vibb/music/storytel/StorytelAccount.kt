package me.vibb.music.storytel

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import me.vibb.music.BuildConfig
import me.vibb.music.bridge.Bridge
import me.vibb.music.bridge.StorytelBridgeToken
import me.vibb.music.logic.debugSwitchJwt
import me.vibb.music.logic.storytelGeneration
import me.vibb.music.media.MediaCache

/*
 * The phone's Storytel account (design 26b §4.2, design 58 §5.1): the process's one StorytelSession (the
 * server's JWT in memory, the waits, the one ask in flight) and one StorytelApi (the asset call), so a
 * download run and a room's play share the JWT. The JWT comes through the launcher's bridge
 * (`getStorytelToken`, StorytelBridgeToken) for the managed config's `storytel_generation`; with the
 * debug switch (design 26b §4.6, readers (a), (b) and (e)) the API is the fake's over the LAN client, and
 * an unmanaged app asks the fake's `/fake/jwt` under generation `DEBUG_GENERATION` - while with the switch
 * set only a `FAKE-JWT-` goes on, whichever way it came (design 58 §5.6). Every call that asks runs off the
 * main thread: [mint] and [prefetchIfDue] are suspends on IO, [state] is called from the sync's report
 * (IO). Nothing here logs. The old login on disk is StorytelLegacy's (the sync's), never this class's.
 */
class StorytelAccount private constructor(
    private val api: StorytelApi,
    private val session: StorytelSession,
    private val scope: CoroutineScope,
) {
    /**
     * A fresh signed CDN URL for book [cid] (`asset_url`), or why there is none (design 26b §4.1, design 58
     * §5.1). [timeoutMs] bounds the asset call, [deadlineMs] (SystemClock.elapsedRealtime) the whole mint,
     * the wait for the JWT included - downloads `(15 s, now + 60 s)`, the Sonos path `(6 s, now + 10 s)`. A
     * cancelled caller (a download job WorkManager stops) gets its CancellationException at once: the asset
     * request is interruptible, and the ask runs on in the session's scope with its answer kept. A room's
     * actor never cancels a job (qa-26b-merge-code Low 1).
     */
    suspend fun mint(cid: String, timeoutMs: Long, deadlineMs: Long): Mint =
        if (!StorytelApi.validCid(cid)) Mint.NoAudio(0) else session.mint(timeoutMs, deadlineMs) { jwt -> api.assetUrl(cid, jwt, timeoutMs, deadlineMs) }

    /** The room's refresh ahead of an advance (design 58 §5.1): see StorytelSession.prefetchIfDue. Off main. */
    suspend fun prefetchIfDue() = session.prefetchIfDue()

    /** The report's `storytel`: `none`, `refused` or `ok` (design 58 §5.2). Off main: it reads the managed config. */
    fun state(): String = session.state()

    companion object {
        /** What the one instance was built for: the endpoints and the client kind (they change only with the debug switch). */
        private var builtFor: Pair<Endpoints, Boolean>? = null

        @Volatile private var instance: StorytelAccount? = null

        /**
         * The process's account. In a release build the debug switch is always null, so it is built once;
         * in a debug build a changed `storytel_base` builds it once more (the fake, a new session), and the
         * old one's asks end with its scope.
         */
        fun get(context: Context): StorytelAccount = synchronized(this) {
            val endpoints = StorytelEndpoints.endpoints()
            val lan = StorytelEndpoints.apiOverLan()
            val key = endpoints to lan
            instance?.takeIf { builtFor == key } ?: build(context.applicationContext, endpoints, lan).also {
                instance?.scope?.cancel()
                instance = it
                builtFor = key
            }
        }

        private fun build(app: Context, endpoints: Endpoints, lan: Boolean): StorytelAccount {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val api = StorytelApi(
                OkHttpStorytelHttp(MediaCache.httpClient(publicOnly = !lan), "VibbMusic/${BuildConfig.VERSION_NAME}"),
                SystemClock::elapsedRealtime,
                endpoints,
            )
            val fake = StorytelEndpoints.debugJwt()
            val session = StorytelSession(
                generation = { Bridge.config(app).let { storytelGeneration(it.managed, it.storytelGeneration, fake != null) } },
                ask = { stale ->
                    if (fake != null && !Bridge.config(app).managed) {
                        // the fake's route only for a run without a launcher (design 58 §5.6, Spotify's debugTokens())
                        runInterruptible(Dispatchers.IO) { api.debugJwt(fake, stale) }
                    } else {
                        val answer = StorytelBridgeToken.get(app, stale)
                        if (fake == null) answer else debugSwitchJwt(answer)
                    }
                },
                elapsedMs = SystemClock::elapsedRealtime,
                wallMs = System::currentTimeMillis,
                scope = scope,
            )
            return StorytelAccount(api, session, scope)
        }
    }
}
