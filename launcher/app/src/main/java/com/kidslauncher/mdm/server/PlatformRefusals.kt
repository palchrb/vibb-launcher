package com.kidslauncher.mdm.server

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/*
 * Packages the platform refuses to suspend or hide for good (compat-fix-1, the Android 14-17 check of 2026-11-16).
 *
 * On Android 17 (API 37) `com.android.settings` hosts App lock and holds the app-op SYSTEM_EXEMPT_FROM_SUSPENSION, so
 * DevicePolicyManager answers `setPackagesSuspended` and `setApplicationHidden` for it with "ignoring ... policy-exempt
 * list": it stays visible and unsuspended whatever the plan says. Two things follow, both general (any package, not a
 * Settings special case):
 * - Home, the drawer and our Settings' system-page rows leave out what the enforcement plan wants hidden or suspended,
 *   read from the plan, not from the platform's state ([plannedOffLists]; before a process's first `apply` the last
 *   plan stored, [storePlan]) - a refused package is then never a dead tile, and a page in it never a row that opens
 *   nothing.
 * - `AppEnforcer.apply` stops asking again: a package refused because it is exempt ([exemptFromSuspension]) is
 *   recorded for this boot and this OS build ([refusalsNow]), logged once, and asked again after a reboot or an OS
 *   update. Any other refusal is still retried at every pass, as before.
 */

/** What the platform refused because the package is exempt, valid for one boot ([bootCount]) of one OS build
 * ([fingerprint]). */
data class PlatformRefusalRecord(
    val bootCount: Int,
    val fingerprint: String,
    val suspend: Set<String> = emptySet(),
    val hide: Set<String> = emptySet(),
)

/** The record in force now: [stored] when it was made on this boot of this OS build, else an empty one for now. An
 * unknown boot count (-1) never matches, so nothing is skipped when the boot can't be told. */
fun refusalsNow(stored: PlatformRefusalRecord?, bootCount: Int, fingerprint: String): PlatformRefusalRecord =
    if (stored != null && bootCount >= 0 && stored.bootCount == bootCount && stored.fingerprint == fingerprint) {
        stored
    } else {
        PlatformRefusalRecord(bootCount, fingerprint)
    }

/** Whether `apply` makes the call that would restrict [packageName]: it doesn't when the record says the platform
 * refused that call on this boot and build. A release (`want == false`) is always made. */
fun restrictCallDue(want: Boolean, current: Boolean, packageName: String, refused: Set<String>): Boolean =
    want != current && !(want && packageName in refused)

/**
 * Whether [packageName] stays off Home, the drawer and our system-page rows because the enforcement plan wants it
 * hidden or suspended - whatever the platform did with it. [planSuspend]/[planHide]: the last plan in this process
 * (`null` before the first `apply`); until then the last plan stored ([storedOff], QA M1: across a process start and a
 * reboot - `null` when none was ever stored) stands in.
 */
fun plannedOffLists(packageName: String, planSuspend: Set<String>?, planHide: Set<String>?, storedOff: Set<String>?): Boolean =
    if (planSuspend == null && planHide == null) {
        packageName in storedOff.orEmpty()
    } else {
        packageName in planSuspend.orEmpty() || packageName in planHide.orEmpty()
    }

object PlatformRefusals {
    private const val LOG_TAG = "PlatformRefusals"
    private const val PREFS = "platform_refusals"
    private const val KEY_BOOT = "boot_count"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_SUSPEND = "suspend"
    private const val KEY_HIDE = "hide"
    private const val KEY_PLAN_OFF = "plan_off"

    /** The app-op's name (`AppOpsManager.OPSTR_SYSTEM_EXEMPT_FROM_SUSPENSION`, a system API since Android 14). */
    const val OP_EXEMPT_FROM_SUSPENSION = "android:system_exempt_from_suspension"

    enum class Call { SUSPEND, HIDE }

    @Volatile
    private var cached: PlatformRefusalRecord? = null

    /** The last plan's off-set as stored ([storePlan]); read once per process. */
    @Volatile
    private var planOff: Set<String>? = null

    @Volatile
    private var planOffRead = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The record in force on this boot and build ([refusalsNow]). Never throws. */
    fun now(context: Context): PlatformRefusalRecord {
        val boot = BootClock.bootCount()
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val stored = cached ?: try {
            val p = prefs(context)
            if (p.contains(KEY_BOOT)) {
                PlatformRefusalRecord(
                    bootCount = p.getInt(KEY_BOOT, -1),
                    fingerprint = p.getString(KEY_FINGERPRINT, "").orEmpty(),
                    suspend = p.getStringSet(KEY_SUSPEND, null).orEmpty().toSet(),
                    hide = p.getStringSet(KEY_HIDE, null).orEmpty().toSet(),
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        return refusalsNow(stored, boot, fingerprint).also { cached = it }
    }

    /** Called after the platform refused [call] for [packageName]: recorded, with its one log line, only when the
     * package is exempt from suspension ([exemptFromSuspension]) - then true; any other refusal is left to the next pass
     * (false: the caller logs it). */
    fun refused(context: Context, packageName: String, call: Call): Boolean {
        if (!exemptFromSuspension(context, packageName)) return false
        val record = now(context)
        val next = when (call) {
            Call.SUSPEND -> record.copy(suspend = record.suspend + packageName)
            Call.HIDE -> record.copy(hide = record.hide + packageName)
        }
        if (next == record) return true
        cached = next
        try {
            prefs(context).edit()
                .putInt(KEY_BOOT, next.bootCount)
                .putString(KEY_FINGERPRINT, next.fingerprint)
                .putStringSet(KEY_SUSPEND, next.suspend)
                .putStringSet(KEY_HIDE, next.hide)
                .apply()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't store the refusal of $packageName", e)
        }
        Log.w(
            LOG_TAG,
            "Android refuses to ${call.name.lowercase()} $packageName (exempt from suspension); not asked again until a reboot or an OS update",
        )
        return true
    }

    /** Whether Android exempts [packageName] from suspension (the app-op, e.g. Android 17's Settings with App lock);
     * false when it can't be read. The by-name read is deprecated in API 36 (as `AppAccess.states`), still fine on 34-37. */
    @Suppress("DEPRECATION")
    fun exemptFromSuspension(context: Context, packageName: String): Boolean = try {
        val uid = context.packageManager.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
        context.getSystemService(AppOpsManager::class.java)
            ?.unsafeCheckOpNoThrow(OP_EXEMPT_FROM_SUSPENSION, uid, packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /** QA M1: every `apply` stores its plan's hidden and suspended packages (written only when they changed), so the
     * lists are right before the first `apply` of a process - after a reboot or a process death in a pause too. */
    fun storePlan(context: Context, suspend: Set<String>, hide: Set<String>) {
        val off = suspend + hide
        if (storedPlan(context) == off) return
        planOff = off
        try {
            prefs(context).edit().putStringSet(KEY_PLAN_OFF, off).apply()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't store the plan's off-set: ${e.javaClass.simpleName}")
        }
    }

    private fun storedPlan(context: Context): Set<String>? {
        if (!planOffRead) {
            planOff = try {
                prefs(context).getStringSet(KEY_PLAN_OFF, null)?.toSet()
            } catch (e: Exception) {
                null
            }
            planOffRead = true
        }
        return planOff
    }

    /** QA L1: the recorded refusals whose package still holds the app-op - an op revoked during the boot makes the call
     * again. One app-op read per recorded package (a handful of system-uid ones). */
    fun stillExempt(context: Context, record: PlatformRefusalRecord): PlatformRefusalRecord {
        if (record.suspend.isEmpty() && record.hide.isEmpty()) return record
        val exempt = (record.suspend + record.hide).filterTo(mutableSetOf()) { exemptFromSuspension(context, it) }
        return record.copy(suspend = record.suspend intersect exempt, hide = record.hide intersect exempt)
    }

    /** [plannedOffLists] with the last plan of this process, else the last plan stored. */
    fun plannedOff(context: Context, packageName: String): Boolean {
        val suspend = AppEnforcer.lastPlanSuspend
        val hide = AppEnforcer.lastPlanHide
        val stored = if (suspend == null && hide == null) storedPlan(context) else null
        return plannedOffLists(packageName, suspend, hide, stored)
    }
}
