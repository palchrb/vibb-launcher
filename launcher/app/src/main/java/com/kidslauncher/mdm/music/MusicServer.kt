package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.server.TsnetClient
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

/**
 * The music routes of the device API (design 21 §1.3, 21b §4.2), behind one small interface so
 * design 22 L1 can swap the transport in one place. Every call is the phone's own (bearer), over the
 * tailnet when it is up.
 */
interface MusicServer {
    /** `GET /api/devices/music/library` (ETag = version, 304, 404 = nothing ticked). */
    suspend fun library(ifNoneMatch: String?): Response<ResponseBody>

    /** `GET /api/devices/music/entries/{id}/items` (ETag = version, 304, 404 = never listed). */
    suspend fun listing(entryId: Long, ifNoneMatch: String?): Response<ResponseBody>

    /** `GET /api/devices/music/covers/{hash}`. */
    suspend fun cover(hash: String): Response<ResponseBody>

    /** `GET /api/devices/music/storytel` (404 = none or switched off, 503 = no usable key). */
    suspend fun storytel(): Response<ResponseBody>

    /** `GET /api/devices/music/files/{id}`: a blocking call, so the runner can cancel it (design 13 §4). */
    fun file(id: Long, range: String?, ifMatch: String?): Call<ResponseBody>
}

private interface MusicApi {
    // @Streaming: the size caps are applied while reading, so a huge body never lands in the heap (L8).
    @Streaming
    @GET("api/devices/music/library")
    suspend fun library(@Header("If-None-Match") ifNoneMatch: String?): Response<ResponseBody>

    @Streaming
    @GET("api/devices/music/entries/{id}/items")
    suspend fun listing(@Path("id") entryId: Long, @Header("If-None-Match") ifNoneMatch: String?): Response<ResponseBody>

    @Streaming
    @GET("api/devices/music/covers/{hash}")
    suspend fun cover(@Path("hash") hash: String): Response<ResponseBody>

    @GET("api/devices/music/storytel")
    suspend fun storytel(): Response<ResponseBody>

    @Streaming
    @GET("api/devices/music/files/{id}")
    fun file(@Path("id") id: Long, @Header("Range") range: String?, @Header("If-Match") ifMatch: String?): Call<ResponseBody>
}

/** Like `createMdmApi`: the bearer (to the server's own host only, review L8), the tailnet proxy when tsnet runs. */
fun createMusicServer(baseUrl: String, token: String): MusicServer {
    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { TsnetClient.proxy()?.let { proxy(it) } }
        .addInterceptor(com.kidslauncher.mdm.server.bearerInterceptor(normalizedBaseUrl, token))
        .build()
    val api = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(client)
        .build()
        .create(MusicApi::class.java)
    return object : MusicServer {
        override suspend fun library(ifNoneMatch: String?) = api.library(ifNoneMatch?.let { "\"$it\"" })
        override suspend fun listing(entryId: Long, ifNoneMatch: String?) = api.listing(entryId, ifNoneMatch?.let { "\"$it\"" })
        override suspend fun cover(hash: String) = api.cover(hash)
        override suspend fun storytel() = api.storytel()
        override fun file(id: Long, range: String?, ifMatch: String?) = api.file(id, range, ifMatch)
    }
}
