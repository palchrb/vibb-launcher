package com.kidslauncher.mdm.push.up

import android.content.Context
import android.content.SharedPreferences
import com.kidslauncher.mdm.server.Sealed
import com.kidslauncher.mdm.server.SealedPrefs
import com.kidslauncher.mdm.server.Secrets
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.Serializable

/** A registration's state: written `pending` before the server call, `confirmed` once the server answered its endpoint
 * (design 108 §2.1). */
const val UP_PENDING = "pending"
const val UP_CONFIRMED = "confirmed"

/**
 * One registration on this phone (design 108 §5.3): the phone's key [reg], the app [pkg], the connector's [token], the
 * endpoint [secret] the phone chose and the [endpoint] the server answered (`null` while [state] is [UP_PENDING]).
 * [confirmedAtMs] is the wall time the server's answer arrived - the `regs` reconcile's race rule (§2.3). [lastAppAckMs]
 * the app's last `MESSAGE_ACK` (the 30-day rule, R2), [pingId]/[pingSentMs] that rule's ping. [message] is the app's own
 * description (Element X's MXID), cut to 100 bytes, **never sent anywhere** - shown only in the PIN-gated Settings'
 * diagnostics row (§6.5). [lastDeliveredMs] the last MESSAGE broadcast sent. Design 108b: [refreshMs] the last
 * `NEW_ENDPOINT` sent to ask the app to re-check its address (§3.3, at most a day apart); [vapidPending] a new VAPID key
 * the app registered with that the server hasn't taken yet and [keyTryMs] the last call that posted one (at most one an
 * hour, M4; §4.6); [pingLastSendMs] and [pingBoot] the last send of the outstanding ping and the boot it went out in (Q1:
 * it is re-sent once per boot, and "dead" needs an hour since the last send). Tokens, secrets and endpoints are
 * capabilities: never logged ([toString]).
 */
@Serializable
data class UpRow(
    val reg: String,
    val pkg: String,
    val token: String,
    val secret: String,
    val endpoint: String? = null,
    val state: String = UP_PENDING,
    val createdAtMs: Long = 0,
    val confirmedAtMs: Long? = null,
    val vapid: String? = null,
    val lastAppAckMs: Long? = null,
    val pingId: String? = null,
    val pingSentMs: Long? = null,
    val message: String? = null,
    val lastDeliveredMs: Long? = null,
    val refreshMs: Long? = null,
    val vapidPending: String? = null,
    val keyTryMs: Long? = null,
    val pingLastSendMs: Long? = null,
    val pingBoot: Int? = null,
) {
    val confirmed: Boolean get() = state == UP_CONFIRMED && endpoint != null

    override fun toString() = "UpRow(reg=$reg, package=$pkg, state=$state)"
}

/** The blob under [UpStore.REGS]: [v] its format, [rows] the registrations. Unknown keys of a newer build are dropped
 * by an older one's decode, never a reason to fail. */
@Serializable
data class UpRegsBlob(val v: Int = 1, val rows: List<UpRow> = emptyList())

/** A note for the diagnostics row, kept once per package and kind (§5.1 L4, §5.4): [kind] [NOTE_OLD_CONNECTOR] (a
 * REGISTER without a shared identity, an AND_2-only connector) or [NOTE_NO_RAISE] (no RAISE_TO_FOREGROUND service). */
@Serializable
data class UpNote(val pkg: String, val kind: String, val atMs: Long)

const val NOTE_OLD_CONNECTOR = "old_connector"
const val NOTE_NO_RAISE = "no_raise"

/** Design 108b: an app that didn't answer for 30 days and was unregistered ([NOTE_UNANSWERED]); a key update the server
 * refused as not a P-256 point, the old key kept ([NOTE_BAD_KEY]). */
const val NOTE_UNANSWERED = "unanswered"
const val NOTE_BAD_KEY = "bad_key"

/** What a read of the registrations found: [Rows], or [Unreadable] (a blob that doesn't open or decode - kept as it
 * is, and nothing that would delete a server row runs on it). */
sealed interface UpRead {
    data class Rows(val rows: List<UpRow>) : UpRead

    data object Unreadable : UpRead
}

/**
 * The phone's UnifiedPush store (design 108 §5.3), in credential-encrypted prefs of its own:
 * - [REGS] (`up_regs_v1`): one JSON blob of [UpRow]s, sealed under the secrets key ([SealedPrefs], design r10) -
 *   tokens and endpoints are capabilities, like the device token in the same store - written with `commit()`.
 * - [DELETES] (`up_deletes_v1`): the `reg`s whose `DELETE` hasn't reached the server yet, retried at every sync (a
 *   `reg` alone is nothing without the device's bearer token, so plain).
 * - [CHURN] (`up_churn_v1`): the new registrations of the last hour per app (the churn limit, L10), plain.
 * - [NOTES] (`up_notes_v1`): the diagnostics row's notes, plain.
 *
 * Touched only by [UpEngine] on [UpRuntime]'s executor, and only after the first unlock ([UpEngine]'s `unlocked`, L6).
 */
class UpStore(
    private val prefs: SharedPreferences,
    private val sealed: SealedPrefs = Secrets.prefs,
    private val migrate: () -> Boolean = { false },
) {
    companion object {
        const val PREFS_NAME = "unified_push"
        const val REGS = "up_regs_v1"
        const val DELETES = "up_deletes_v1"
        const val CHURN = "up_churn_v1"
        const val NOTES = "up_notes_v1"
        const val UNREADABLE_SINCE = "up_unreadable_since_v1"
        const val AAD = "$PREFS_NAME/$REGS"

        /** At most this many notes are kept (the oldest goes), and of them at most [OLD_CONNECTOR_MAX] unverified
         * old-connector hints (code QA L1). */
        const val NOTES_MAX = 10
        const val OLD_CONNECTOR_MAX = 3

        fun of(context: Context): UpStore =
            UpStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    fun read(): UpRead = when (val state = sealed.read(prefs, AAD, REGS, migrate())) {
        Sealed.Absent -> UpRead.Rows(emptyList())
        Sealed.Unreadable -> UpRead.Unreadable
        is Sealed.Value -> try {
            UpRead.Rows(ServerJson.decodeFromString(UpRegsBlob.serializer(), state.plain).rows)
        } catch (e: Exception) {
            UpRead.Unreadable
        }
    }

    /** When the registrations were first found unreadable (marked now if not yet) - code QA L4's grace. */
    fun unreadableSince(nowMs: Long): Long {
        val since = prefs.getLong(UNREADABLE_SINCE, -1L)
        if (since >= 0) return since
        prefs.edit().putLong(UNREADABLE_SINCE, nowMs).commit()
        return nowMs
    }

    fun clearUnreadable() {
        if (prefs.contains(UNREADABLE_SINCE)) prefs.edit().remove(UNREADABLE_SINCE).commit()
    }

    /** Drops the registrations' blob (no seal needed, so it works with a broken key too) and the mark: the next read is
     * empty. */
    fun reset(): Boolean {
        val removed = sealed.edit(prefs) { it.remove(AAD, REGS) }
        if (removed) clearUnreadable()
        return removed
    }

    /** The rows, sealed, in one `commit()`; false when the seal or the commit failed (nothing written then). */
    fun write(rows: List<UpRow>): Boolean =
        sealed.edit(prefs) { it.putSealed(AAD, REGS, ServerJson.encodeToString(UpRegsBlob.serializer(), UpRegsBlob(rows = rows))) }

    fun deletes(): List<String> = list(DELETES)

    fun setDeletes(regs: List<String>): Boolean = putList(DELETES, regs.distinct())

    /** The churn record: package -> the wall times of its new registrations (only the last hour is ever kept). */
    fun churn(): Map<String, List<Long>> = try {
        prefs.getString(CHURN, null)?.let { ServerJson.decodeFromString(ChurnBlob.serializer(), it).apps } ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    fun setChurn(apps: Map<String, List<Long>>): Boolean =
        prefs.edit().putString(CHURN, ServerJson.encodeToString(ChurnBlob.serializer(), ChurnBlob(apps))).commit()

    fun notes(): List<UpNote> = try {
        prefs.getString(NOTES, null)?.let { ServerJson.decodeFromString(NotesBlob.serializer(), it).notes } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Adds [note] once per package and kind; true when it was new. */
    fun note(note: UpNote): Boolean {
        val notes = notes()
        if (notes.any { it.pkg == note.pkg && it.kind == note.kind }) return false
        if (note.kind == NOTE_OLD_CONNECTOR && notes.count { it.kind == NOTE_OLD_CONNECTOR } >= OLD_CONNECTOR_MAX) return false
        val kept = (notes + note).takeLast(NOTES_MAX)
        return prefs.edit().putString(NOTES, ServerJson.encodeToString(NotesBlob.serializer(), NotesBlob(kept))).commit()
    }

    private fun list(key: String): List<String> = try {
        prefs.getString(key, null)?.let { ServerJson.decodeFromString(ListBlob.serializer(), it).items } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun putList(key: String, items: List<String>): Boolean =
        prefs.edit().putString(key, ServerJson.encodeToString(ListBlob.serializer(), ListBlob(items))).commit()

    @Serializable
    private data class ListBlob(val items: List<String> = emptyList())

    @Serializable
    private data class ChurnBlob(val apps: Map<String, List<Long>> = emptyMap())

    @Serializable
    private data class NotesBlob(val notes: List<UpNote> = emptyList())
}
