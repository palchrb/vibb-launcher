package com.kidslauncher.mdm.server

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kidslauncher.mdm.server.dto.BrowserHistoryUpload
import com.kidslauncher.mdm.server.dto.CommandResultRequest
import com.kidslauncher.mdm.server.dto.DnsBlocklistCategory
import com.kidslauncher.mdm.server.dto.DnsEventReport
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.EnrollResponse
import com.kidslauncher.mdm.server.dto.InstallProgressReport
import com.kidslauncher.mdm.server.dto.JournalEntryUpload
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.TrackedAppUpdate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/**
 * Device-facing kid-phone-server REST endpoints. Enrollment is unauthenticated (the enrollment
 * code itself is the one-shot credential); policy/status require the bearer token issued at
 * enrollment, attached by [createMdmApi] via a header rather than threaded through every call.
 */
interface MdmApi {

    @POST("api/devices/enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<EnrollResponse>

    /** Raw body: decoded with [decodeFresh] so a body the launcher can't read is told apart from
     * an unreachable server (see `MdmSyncWorker.fetchPolicy`). */
    @GET("api/devices/policy")
    suspend fun getPolicy(): Response<ResponseBody>

    @POST("api/devices/status")
    suspend fun sendStatus(@Body report: StatusReportRequest): Response<Unit>

    @POST("api/devices/command-result")
    suspend fun sendCommandResult(@Body request: CommandResultRequest): Response<Unit>

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

    @POST("api/devices/dns-events")
    suspend fun sendDnsEvents(@Body events: List<DnsEventReport>): Response<Unit>

    /** [url] is [TrackedAppUpdate.downloadUrl] as sent by the server (e.g.
     * "/api/devices/apps/5/download") - a per-app path, since there can be many tracked apps
     * (including the launcher itself - it's just another tracked app server-side now). [Url]
     * resolves it against the same base URL/auth interceptor as every other call here. */
    @Streaming
    @GET
    suspend fun downloadTrackedApp(@Url url: String): Response<ResponseBody>

    /** Runs as one server-side transaction, all-or-nothing - see
     * [com.kidslauncher.mdm.server.JournalSync] for why the sync cursor only advances after this
     * actually succeeds. */
    @POST("api/devices/journal")
    suspend fun uploadJournalEntries(@Body entries: List<JournalEntryUpload>): Response<Unit>

    /** [remoteId] must already exist server-side (i.e. [uploadJournalEntries] ran first for it) -
     * the server uses it purely to scope this upload to a row this device already reported. */
    @POST("api/devices/journal/media/{remoteId}")
    suspend fun uploadJournalMedia(
        @Path("remoteId") remoteId: Long,
        @Body body: RequestBody,
    ): Response<Unit>

    /** Same all-or-nothing transaction semantics as [uploadJournalEntries] - see
     * [com.kidslauncher.mdm.server.BrowserHistorySync]. */
    @POST("api/devices/browser-history")
    suspend fun uploadBrowserHistory(@Body entries: List<BrowserHistoryUpload>): Response<Unit>
}

/** [token] is omitted for the enroll-only call (no token exists yet); pass it for every
 * subsequent authenticated call. Routes through [TsnetClient]'s embedded-tailnet SOCKS5 proxy
 * whenever one is running, so calls reach the server over the tailnet without needing the
 * standalone Tailscale app - falls back to the device's normal network path (plain internet
 * routing, whatever that resolves to) if tsnet hasn't connected yet, same as before this existed. */
fun createMdmApi(baseUrl: String, token: String? = null): MdmApi {
    val clientBuilder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)

    TsnetClient.proxy()?.let { clientBuilder.proxy(it) }

    if (token != null) {
        clientBuilder.addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            )
        }
    }

    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    return Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(clientBuilder.build())
        .addConverterFactory(ServerJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MdmApi::class.java)
}
