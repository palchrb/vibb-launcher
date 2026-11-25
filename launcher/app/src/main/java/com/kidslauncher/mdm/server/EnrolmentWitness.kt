package com.kidslauncher.mdm.server

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import java.io.File

private const val LOG_TAG = "EnrolmentWitness"

/** The witness's record: the phone enrolled, a policy was applied, and the server it was enrolled with. */
@Serializable
data class WitnessRecord(val enrolled: Boolean = false, val policyApplied: Boolean = false, val serverUrl: String? = null)

/** A stored witness read: `null` for none; one that is there but doesn't read is both facts (fail closed - a phone that
 * was managed must never be read as new). */
fun witnessRecord(raw: String?): WitnessRecord? {
    if (raw == null) return null
    return try {
        ServerJson.decodeFromString(WitnessRecord.serializer(), raw)
    } catch (e: Exception) {
        WitnessRecord(enrolled = true, policyApplied = true)
    }
}

/** Whether a policy was ever applied by the witness: the fact that decides what to enforce ([everManaged]). */
fun witnessPolicyApplied(raw: String?): Boolean = witnessRecord(raw)?.policyApplied == true

/** Whether the phone was enrolled by the witness - either fact: Koble til and the first-launch reset. */
fun witnessEnrolled(raw: String?): Boolean = witnessRecord(raw)?.let { it.enrolled || it.policyApplied } == true

/** The server address a stored witness names, or `null`. */
fun witnessServerUrl(raw: String?): String? = try {
    raw?.let { ServerJson.decodeFromString(WitnessRecord.serializer(), it).serverUrl?.takeIf { url -> url.isNotBlank() } }
} catch (e: Exception) {
    null
}

/** What a phone counts as for "a policy was applied": the main preferences' flag, or the witness's own. An enrolment
 * without a first policy yet is not enough - a new phone still sets up. */
fun everManaged(policyEverApplied: Boolean, witnessPolicyApplied: Boolean): Boolean = policyEverApplied || witnessPolicyApplied

/**
 * The enrolment's witness: one small file in device-protected storage (`enrolment_witness`, written atomically, as
 * the boot cover's record), apart from the main preferences. Two facts: `enrolled` (written at every enrolment) and
 * `policy_applied` (written with every accepted policy), and the server address; never removed by the app (a wipe
 * removes it). When the main preferences are lost or cut short, the witness still knows: with a policy applied, Settings
 * stays behind the parent code and the sync enforces the last plan or nothing (never "no restrictions"); enrolled or
 * applied, no first-launch reset runs and Koble til - with its emergency button, only for the witness's server - is the
 * way back. Never throws.
 */
object EnrolmentWitness {
    private const val FILE = "enrolment_witness"

    @Volatile
    private var memo: String? = null

    /** Once a policy was applied, so for the process (the file is never removed while the app runs). */
    @Volatile
    private var appliedSeen = false

    private fun file(context: Context) =
        AtomicFile(File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, FILE))

    /** The base file itself (it is only ever replaced by a rename, so whole); `null` when there is none. */
    private fun raw(context: Context): String? = try {
        val base = file(context).baseFile
        if (!base.exists()) null else String(base.readBytes(), Charsets.UTF_8)
    } catch (e: Exception) {
        // There but not readable: both facts.
        "?"
    }

    fun policyApplied(context: Context): Boolean {
        if (appliedSeen) return true
        return witnessPolicyApplied(raw(context)).also { if (it) appliedSeen = true }
    }

    /** [policyApplied] from the application's context; `false` only when there is no application yet. */
    fun policyAppliedNow(): Boolean = DeviceCredentials.appContext()?.let(::policyApplied) ?: false

    fun enrolled(context: Context): Boolean = appliedSeen || witnessEnrolled(raw(context))

    fun serverUrl(context: Context): String? = witnessServerUrl(raw(context))

    /** At an enrolment: enrolled with [serverUrl]; an earlier applied policy stays recorded. */
    fun recordEnrolled(context: Context, serverUrl: String?) = write(context) { it.copy(enrolled = true, serverUrl = serverUrl?.trim()) }

    /** With an accepted policy: applied, with [serverUrl]. */
    fun recordPolicyApplied(context: Context, serverUrl: String?) =
        write(context) { it.copy(enrolled = true, policyApplied = true, serverUrl = serverUrl?.trim()) }

    @Synchronized
    private fun write(context: Context, change: (WitnessRecord) -> WitnessRecord) {
        val before = raw(context)
        val next = ServerJson.encodeToString(WitnessRecord.serializer(), change(witnessRecord(before) ?: WitnessRecord()))
        if (memo == next || before == next) {
            memo = next
            if (witnessPolicyApplied(next)) appliedSeen = true
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
            if (witnessPolicyApplied(next)) appliedSeen = true
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.w(LOG_TAG, "Couldn't write the witness: ${e.javaClass.simpleName}")
        }
    }
}
