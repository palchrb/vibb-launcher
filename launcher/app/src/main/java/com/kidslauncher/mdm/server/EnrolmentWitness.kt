package com.kidslauncher.mdm.server

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import java.io.File

private const val LOG_TAG = "EnrolmentWitness"

/** The witness's record: the phone has been managed, and the server it was enrolled with. */
@Serializable
data class WitnessRecord(val managed: Boolean = true, val serverUrl: String? = null)

/** Whether a stored witness says managed: none is a new phone; one that is there but doesn't read counts as managed
 * (fail closed - a phone that was managed must never be read as new). */
fun witnessManaged(raw: String?): Boolean {
    if (raw == null) return false
    return try {
        ServerJson.decodeFromString(WitnessRecord.serializer(), raw).managed
    } catch (e: Exception) {
        true
    }
}

/** The server address a stored witness names, or `null`. */
fun witnessServerUrl(raw: String?): String? = try {
    raw?.let { ServerJson.decodeFromString(WitnessRecord.serializer(), it).serverUrl?.takeIf { url -> url.isNotBlank() } }
} catch (e: Exception) {
    null
}

/** What a phone counts as for "a policy was applied": the main preferences' flag, or the witness. */
fun everManaged(policyEverApplied: Boolean, witnessManaged: Boolean): Boolean = policyEverApplied || witnessManaged

/**
 * The enrolment's witness: one small file in device-protected storage (`enrolment_witness`, written atomically, as
 * the boot cover's record), apart from the main preferences. Written at every enrolment and every applied policy;
 * never removed by the app (a wipe removes it). When the main preferences are lost or cut short, the witness still
 * says the phone was managed: Settings stays behind the parent code, the sync enforces the last plan or nothing (never
 * "no restrictions"), no first-launch reset runs, and Koble til - with its emergency button - is the way back.
 * Never throws.
 */
object EnrolmentWitness {
    private const val FILE = "enrolment_witness"

    @Volatile
    private var memo: String? = null

    private fun file(context: Context) =
        AtomicFile(File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, FILE))

    /** The base file itself (it is only ever replaced by a rename, so whole); `null` when there is none. */
    private fun raw(context: Context): String? = try {
        val base = file(context).baseFile
        if (!base.exists()) null else String(base.readBytes(), Charsets.UTF_8)
    } catch (e: Exception) {
        // There but not readable: managed.
        "?"
    }

    /** Once managed, managed for the process (the file is never removed while the app runs). */
    @Volatile
    private var managedSeen = false

    fun managed(context: Context): Boolean {
        if (managedSeen) return true
        return witnessManaged(raw(context)).also { if (it) managedSeen = true }
    }

    /** [managed] from the application's context; `false` only when there is no application yet (never in practice). */
    fun managedNow(): Boolean = DeviceCredentials.appContext()?.let(::managed) ?: false

    fun serverUrl(context: Context): String? = witnessServerUrl(raw(context))

    /** Records the phone as managed with [serverUrl]; written only when it changes. */
    fun record(context: Context, serverUrl: String?) {
        val next = ServerJson.encodeToString(WitnessRecord.serializer(), WitnessRecord(true, serverUrl?.trim()))
        if (memo == next) return
        if (raw(context) == next) {
            memo = next
            return
        }
        val atomic = file(context)
        val out = try {
            atomic.startWrite()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't write the witness: ${e.javaClass.simpleName}")
            return
        }
        try {
            out.write(next.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
            memo = next
            managedSeen = true
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.w(LOG_TAG, "Couldn't write the witness: ${e.javaClass.simpleName}")
        }
    }
}
