package com.kidslauncher.mdm.server

import android.content.Context
import android.os.SystemClock

/**
 * Design 129 §2.1 (H4): what a server call authenticates with. [Bearer] - the device token alone; [Key] - the adopted
 * hardware key, signing every request (§4.2), with the bearer beside it while the server only observes (S1) or holds
 * the key (`held`), and without it once the key is `bound`. [ownerId] keys the policy's ETag cache and the heartbeat's
 * base (`PolicyOwner`, `HeartbeatAckStore`): binding changes the owner, so the phone fetches the policy once without an
 * ETag and sends one full report - intended.
 */
sealed interface DeviceCredential {
    val bearer: String?
    val ownerId: String

    class Bearer(override val bearer: String) : DeviceCredential {
        override val ownerId: String get() = hexOf(sha256(bearer.toByteArray(Charsets.UTF_8)))

        override fun equals(other: Any?): Boolean = other is Bearer && other.bearer == bearer

        override fun hashCode(): Int = bearer.hashCode()

        /** Never the token. */
        override fun toString(): String = "Bearer(…)"
    }

    class Key(val keyId: String, val alias: String, override val bearer: String?) : DeviceCredential {
        override val ownerId: String get() = "key:$keyId"

        override fun equals(other: Any?): Boolean =
            other is Key && other.keyId == keyId && other.alias == alias && other.bearer == bearer

        override fun hashCode(): Int = (keyId.hashCode() * 31 + alias.hashCode()) * 31 + (bearer?.hashCode() ?: 0)

        override fun toString(): String = "Key($keyId, ${if (bearer != null) "with" else "without"} bearer)"
    }
}

/**
 * The only reader of the sealed device token (design 129 §2.1; `SealedPrefsTest` pins it): every server call takes its
 * credential from [current]. The token's writers stay `Enrollment` and `FirstLaunchReset`; the one removal here is a
 * `bound` key's (§5.5).
 */
object DeviceCredentials {
    @Volatile
    private var app: Context? = null

    /** The app context (the interceptor's `unknown_key` wakes the flow with it). */
    fun appContext(): Context? = app ?: com.kidslauncher.mdm.Application.instance

    /** [current] where no context is at hand (the process's own). */
    fun current(): DeviceCredential? = appContext()?.let { current(it) }

    /** The credential now, or `null`: not enrolled, the token unreadable with no key, or a lost key (§5.4: the phone
     * goes silent until a new setup code). Off the main thread (a Keystore read). */
    fun current(context: Context): DeviceCredential? {
        val appContext = context.applicationContext ?: context
        app = appContext
        val book = KeyBook.read(appContext)
        if (book.lost) return null
        val token = DeviceToken.get()
        val adopted = book.adopted
        return when {
            adopted != null -> DeviceCredential.Key(adopted.keyId, adopted.alias, token)
            token != null -> DeviceCredential.Bearer(token)
            else -> null
        }
    }

    /** Something is stored - a token (sealed or not, readable or not) or an adopted key - with no Keystore call: the
     * first-launch reset's "enrolled" half and Settings' presence checks. */
    fun stored(context: Context): Boolean = DeviceToken.present() || KeyBook.adoptedStored(context)

    /** Whether the token is present at all (no Keystore call). */
    fun tokenStored(): Boolean = DeviceToken.present()

    /** The sealed token's state, for "Ny oppsettkode" ([showReEnrol]). */
    fun tokenState(): Sealed = DeviceToken.state()

    /** §5.5: the key is `bound` - the bearer no longer authenticates, so it leaves the phone. */
    fun dropTokenForBoundKey() {
        if (!DeviceToken.clear()) android.util.Log.w("DeviceCredentials", "The bearer of a bound key didn't go")
    }

    /** "Ny oppsettkode" (§5.4, §6.2): the server no longer knows the key and no bearer is left, or the key stopped
     * signing with no bearer beside it. */
    fun keyUnusable(context: Context): Boolean {
        val book = KeyBook.read(context)
        return book.lost || (book.adopted != null && SignatureClock.signFailing && !DeviceToken.present())
    }
}

/**
 * §4.3: the time a signature carries, on the server's signature clock - elapsed realtime plus the offset the last
 * answer's `Vibb-Time` gave. Before any answer in this process: [TrustedClock]'s time (a `stale` refusal corrects it).
 */
object SignatureClock {
    @Volatile
    private var offset: Long? = null

    /** The last sign failed (the key unusable): "Ny oppsettkode" while no bearer goes along. */
    @Volatile
    var signFailing: Boolean = false

    fun now(): Long = offset?.let { signatureTime(it, SystemClock.elapsedRealtime()) } ?: TrustedClock.nowMs()

    fun observe(vibbTime: Long, sentElapsedMs: Long, receivedElapsedMs: Long) {
        offset = signatureOffset(vibbTime, sentElapsedMs, receivedElapsedMs)
    }

    fun elapsed(): Long = SystemClock.elapsedRealtime()
}
