package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.ui.HomeActivity

private const val LOG_TAG = "HomePin"

/**
 * Design 59d step M: the unlocked HOME is HomeActivity, with the boot cover enabled too (android mode).
 *
 * The system picks the first of our HOME PPAs whose component is in the query (`ComputerEngine
 * .findPersistentPreferredActivity`). With the cover enabled both of ours are, and their order is the resolver's - after
 * a reboot the order PMS read `package-restrictions.xml` in, which it wrote from an `ArraySet` of identity-hashed
 * objects: arbitrary per boot (16b QA #8). A single PPA can't be removed or moved through DPM, so [check] resolves the
 * HOME intent as the system does (`QUERY_ALL_PACKAGES`), and when that isn't HomeActivity - the cover first, or a PPA the
 * policy engine dropped (six-inch F6) - clears our package's PPAs and adds them back in order: HomeActivity's HOME, the
 * Play link blocker, the call-log pin ([AppEnforcer.reseatPreferredActivities]), the cover's last. At most
 * [HOME_PIN_MAX_RESEATS] per process ([homePinReseatDue]), never before the unlock. Runs at the take-over (main thread,
 * before the boot's Home start) and after every apply. Never throws.
 */
object HomePin {
    private val lock = Any()

    @Volatile
    private var reseats = 0

    /** Our PPA writes that must not land inside a re-seat's clear and re-add (the cover's add). */
    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    /** [block] with the device owner's DPM and admin; nothing when we aren't device owner. Never throws. */
    fun asOwner(context: Context, block: (DevicePolicyManager, ComponentName) -> Unit) {
        try {
            val app = context.applicationContext
            val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(app.packageName)) return
            block(dpm, ComponentName(app, MdmDeviceAdminReceiver::class.java))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Home pin: device owner call failed: ${e.javaClass.simpleName}")
        }
    }

    /** What the system's HOME resolves to now, as `package/class` (`null` = nothing or unreadable). */
    fun resolvedHome(context: Context): ComponentName? = try {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    } catch (e: Exception) {
        null
    }

    /** The check (and re-seat when due). [log]: the resolution is logged even when it is HomeActivity (the take-over). */
    fun check(context: Context, why: String, log: Boolean = false) {
        val app = context.applicationContext
        try {
            synchronized(lock) {
                val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked == true
                if (!unlocked) return
                val home = ComponentName(app, HomeActivity::class.java)
                val resolved = resolvedHome(app)
                val isHome = resolved == home
                if (log || !isHome) Log.i(LOG_TAG, "HOME resolves to ${resolved?.flattenToShortString()} ($why)")
                if (!homePinReseatDue(unlocked, isHome, reseats)) return
                reseats++
                asOwner(app) { dpm, admin ->
                    AppEnforcer.reseatPreferredActivities(app, dpm, admin)
                    if (BootCover.pinWanted(app)) BootCover.addCoverPin(app, dpm, admin)
                }
                Log.w(
                    LOG_TAG,
                    "Re-seated our persistent preferred activities ($why, $reseats of $HOME_PIN_MAX_RESEATS): " +
                        "HOME resolves to ${resolvedHome(app)?.flattenToShortString()}",
                )
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Home pin check failed ($why): ${e.javaClass.simpleName}")
        }
    }
}
