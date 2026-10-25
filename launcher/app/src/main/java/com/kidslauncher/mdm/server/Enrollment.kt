package com.kidslauncher.mdm.server

import android.content.Context
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.EnrollRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one enrolment (design r10 §5): `POST /api/devices/enroll` with a one-shot code, the new device token sealed
 * and committed ([DeviceToken.set], on IO - revision check N7), `enrolled`, and the old enrolment's state cleared.
 * Used by the QR's [applyProvisioningExtras], Settings' "Enroll now" and the lock's "Ny oppsettkode"; the callers
 * that want the first policy at once ask for the sync themselves.
 */
object Enrollment {
    suspend fun enroll(context: Context, serverUrl: String, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = createMdmApi(serverUrl).enroll(EnrollRequest(code))
            val body = response.body()
            if (!response.isSuccessful || body == null) return@withContext Result.failure(Exception("HTTP ${response.code()}"))
            val mdm = LauncherPreferences.mdm()
            if (!DeviceToken.set(body.deviceToken)) return@withContext Result.failure(Exception("the token couldn't be stored"))
            mdm.enrolled(true)
            // A new enrollment: the queued parent-PIN events were about the old one (design 24).
            ParentPin.clearEvents(context)
            // And the commands it ran and the answers it queued (QA of the launcher wave, Medium 1).
            CommandAnswers.clear(context)
            // And the old enrollment's zone watch: fences, queue, kept fix, beat, mock count (design 64 step 2, Q-8).
            com.kidslauncher.mdm.zones.ZoneWatch.forget(context)
            // And a running Install Now (design 37): its command was the old enrollment's.
            InstallNow.clear(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * "Ny oppsettkode" in the parent-code dialogs (design r10 §5, QA R10-5): only in the deadlock - the device token is
 * [Sealed.Unreadable] **and** the parent PIN can't be checked until a sync (or there is none), with a server URL
 * stored. Not kid-controllable: a key goes only by an OEM fault, root or our uid.
 */
fun showReEnrol(token: Sealed, parent: ParentPinStatus, serverUrl: String?): Boolean =
    token == Sealed.Unreadable && (parent.untilSync || !parent.configured) && !serverUrl.isNullOrBlank()
