package com.kidslauncher.mdm.server

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kidslauncher.mdm.server.dto.CommandReceiptRequest
import com.kidslauncher.mdm.server.dto.CommandResultRequest
import com.kidslauncher.mdm.server.dto.CrashReportBatch
import com.kidslauncher.mdm.server.dto.DnsBlocklistCategory
import com.kidslauncher.mdm.server.dto.DnsEventReport
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.EnrollResponse
import com.kidslauncher.mdm.server.dto.InstallProgressReport
import com.kidslauncher.mdm.server.dto.ParentPinEventBatch
import com.kidslauncher.mdm.server.dto.PowerReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.TimeRequestBody
import com.kidslauncher.mdm.server.dto.TrackedAppUpdate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/**
 * Device-facing kid-phone-server REST endpoints. Enrollment is unauthenticated (the enrollment
 * code itself is the one-shot credential); policy/status require the bearer token issued at
 * enrollment, attached by [createMdmApi] via a header rather than threaded through every call -
 * only to the server's own origin ([bearerInterceptor], security review L8).
 */
interface MdmApi {

    @POST("api/devices/enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<EnrollResponse>

    /** Raw body: decoded with [decodeFresh] so a body the launcher can't read is told apart from
     * an unreachable server (see `MdmSyncWorker.fetchPolicy`). [ifNoneMatch] is the kept body's ETag (design 95
     * §3.5, [PolicyEtagStore]; `null` leaves the header out): a `304` - not "successful" to Retrofit - is that body. */
    @GET("api/devices/policy")
    suspend fun getPolicy(@Header("If-None-Match") ifNoneMatch: String?): Response<ResponseBody>

    /** The raw answer (design 87 §4.2, R2-L7): to a report claiming `app_logos_v1` a `200` with the logos the server
     * wants ([com.kidslauncher.mdm.appdisplay.wantedLogosOf]), else `204`. The caller closes the body; every 2xx is
     * "the server took the report". Streamed, so the caller's bound on what it reads is real. */
    @Streaming
    @POST("api/devices/status")
    suspend fun sendStatus(@Body report: StatusReportRequest): Response<ResponseBody>

    /** One wanted logo, the raw `image/png` (design 87 §4.3) - see [com.kidslauncher.mdm.appdisplay.AppLogos.upload]. */
    @POST("api/devices/app-logos/{hash}")
    suspend fun uploadAppLogo(@Path("hash") hash: String, @Body png: okhttp3.RequestBody): Response<Unit>

    @POST("api/devices/command-result")
    suspend fun sendCommandResult(@Body request: CommandResultRequest): Response<Unit>

    /** The explicit receipt (security review L12), before the command runs - see [CommandAnswers]. */
    @POST("api/devices/command-receipt")
    suspend fun sendCommandReceipt(@Body request: CommandReceiptRequest): Response<Unit>

    @GET("api/devices/apps")
    suspend fun getTrackedAppUpdates(): Response<List<TrackedAppUpdate>>

    /** Fire-and-forget from the caller's side (see [InstallProgressReport]'s own doc comment) -
     * called on a throttled schedule during [downloadTrackedApp], not on every chunk. */
    @POST("api/devices/apps/progress")
    suspend fun reportInstallProgress(@Body report: InstallProgressReport): Response<Unit>

    /** Only called when [PolicyResponse.dnsFilterVersion] differs from the last-fetched value -
     * see [DnsFilterEngine] - since this can be a large (~100k+ domain) payload. */
    @GET("api/devices/dns-blocklist")
    suspend fun getDnsBlocklist(): Response<List<DnsBlocklistCategory>>

    /** A contact photo by its hash (`call_policy` contact `photo`) - only for this device's own
     * contacts; see [com.kidslauncher.mdm.calls.ContactPhotos]. */
    @Streaming
    @GET("api/devices/contact-photos/{hash}")
    suspend fun getContactPhoto(@Path("hash") hash: String): Response<ResponseBody>

    /** A wallpaper image by its SHA-256 (`launcher_ui.wallpapers[].image`); 404 unless the
     * parent allowed it on this phone. See [com.kidslauncher.mdm.ui.wallpaper.WallpaperStore]. */
    @Streaming
    @GET("api/devices/wallpapers/{hash}")
    suspend fun getWallpaper(@Path("hash") hash: String): Response<ResponseBody>

    @POST("api/devices/dns-events")
    suspend fun sendDnsEvents(@Body events: List<DnsEventReport>): Response<Unit>

    /** [url] is [TrackedAppUpdate.downloadUrl] as sent by the server (e.g.
     * "/api/devices/apps/5/download") - a per-app path, since there can be many tracked apps
     * (including the launcher itself - it's just another tracked app server-side now). [Url]
     * resolves it against the same base URL/auth interceptor as every other call here.
     *
     * A blocking [Call] (design 13 §4), not a suspend function: [AppDownloads] cancels it with
     * `Call.cancel()` when the network stops qualifying - a blocking read ignores coroutine
     * cancellation. [range] (`bytes=<have>-`) and [ifMatch] (the first response's `ETag`) resume
     * a partial file; `null` leaves the header out. */
    @Streaming
    @GET
    fun downloadTrackedApp(
        @Url url: String,
        @Header("Range") range: String?,
        @Header("If-Match") ifMatch: String?,
    ): Call<ResponseBody>

    /** Launcher crashes (hash + short trace, no personal data) - see [com.kidslauncher.mdm.crash.CrashReports]. */
    @POST("api/devices/crashes")
    suspend fun sendCrashReports(@Body batch: CrashReportBatch): Response<Unit>

    /** Parent-PIN uses, wrong tries and lockouts (design 24) - see [ParentPinEvents]. */
    @POST("api/devices/pin-events")
    suspend fun sendPinEvents(@Body batch: ParentPinEventBatch): Response<Unit>

    /** The phone's shutdown and start (design 38) - see [PowerEvents]. */
    @POST("api/devices/power")
    suspend fun sendPower(@Body report: PowerReport): Response<Unit>

    /** The kid's request for more screen time (design r13 §2.5): the raw answer, read by
     * `timeRequestAnswer` (an old server's 404 included) - see `timerules/TimeRequests`. */
    @POST("api/devices/time-request")
    suspend fun sendTimeRequest(@Body request: TimeRequestBody): Response<ResponseBody>

    /** The kid's contact request from the phone book (design 77 §14): the raw answer, read by `contactRequestAnswer`
     * (an old server's 404 included) - see `calls/ContactRequests`. */
    @POST("api/devices/contact-request")
    suspend fun sendContactRequest(@Body request: com.kidslauncher.mdm.server.dto.ContactRequestBody): Response<ResponseBody>

    /** The messages held for the parent and the blocked ones (design 77 §7): who and when, never what - see
     * `sms/SmsRuntime.postHeld`. The raw answer: 200 `SmsHeldAnswer`, 400 `SmsHeldRefusal`. */
    @POST("api/devices/sms-held")
    suspend fun sendSmsHeld(@Body body: com.kidslauncher.mdm.server.dto.SmsHeldBody): Response<ResponseBody>

    /** A calendar link's set (design 72 §3): `ETag` = its rev, 304 for [ifNoneMatch], 404 unless this phone has the
     * link - see `calendar/CalendarFeeds`. */
    @GET("api/devices/calendar/feeds/{id}")
    suspend fun calendarFeed(@Path("id") id: String, @Header("If-None-Match") ifNoneMatch: String?): Response<ResponseBody>

    /** The kid's coming events for the marked CalDAV calendar (design 72b D1.7): the body as `day/KidCaldav.kt`'s
     * [com.kidslauncher.mdm.day.KidSnapshot.body] writes it; the raw answer (`{accepted, pending, back}`, a 409, 429,
     * 413 or 400) - see `calendar/KidCaldavSync`. */
    @POST("api/devices/calendar/kid-events")
    suspend fun kidEvents(@Body body: okhttp3.RequestBody): Response<ResponseBody>

    /** The lock glance's weather (design 75 §3.4): the raw answer, decoded by `weather/WeatherPull` into
     * [com.kidslauncher.mdm.server.dto.WeatherAnswer] (a 429 or a 5xx keeps the kept one). */
    @GET("api/devices/weather")
    suspend fun weather(): Response<ResponseBody>

    /** The card's nowcast line (design 75 D9), asked when the card opens. */
    @GET("api/devices/weather/now")
    suspend fun weatherNow(): Response<ResponseBody>
}

/** [callTimeoutMs] bounds a whole call (design 38's prepared shutdown client); `null` = none.
 * [token] is omitted for the enroll-only call (no token exists yet); pass it for every
 * subsequent authenticated call. The way to the server is [serverPathNow]'s (security review S-M1): https direct or
 * through [TsnetClient]'s embedded-tailnet SOCKS5 proxy while it runs; http to a tailnet IP only through that proxy;
 * any other address, or a tailnet one while tsnet is down, gives a client whose every call throws [ServerPathClosed]
 * before anything is sent. [path] is passed by the sync, which judges its answers by it (design 76: `tailnet` is
 * `path is ServerPath.Tailnet`, R-5). */
fun createMdmApi(
    baseUrl: String,
    token: String? = null,
    callTimeoutMs: Long? = null,
    path: ServerPath = serverPathNow(baseUrl),
): MdmApi {
    // Design 95 §3.5: a child of the shared base client (one pool, one TLS context). The base is [toServer]'s:
    // a closed path refuses before any other interceptor sees the request; on the direct path the origin fence and
    // the certificate check come first (design 76); the bearer below is added after them.
    val clientBuilder = ServerClients.base(path).newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
    // The whole call within this (design 38: the shutdown's POST holds the broadcast at most 3 s).
    callTimeoutMs?.let { clientBuilder.callTimeout(it, TimeUnit.MILLISECONDS) }

    val normalizedBaseUrl = path.baseUrl

    // Only to the server's own host (security review L8), never to an absolute URL elsewhere.
    if (token != null) clientBuilder.addInterceptor(bearerInterceptor(normalizedBaseUrl, token))

    return Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(clientBuilder.build())
        .addConverterFactory(ServerJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MdmApi::class.java)
}
