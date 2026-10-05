package com.kidslauncher.mdm.apps

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.play.playPackageLaunchable
import com.kidslauncher.mdm.server.systemDialerPackage
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.util.Locale

class AppFilter(
    var context: Context,
    var hiddenVisibility: AppSetVisibility = AppSetVisibility.HIDDEN,
    var pinnedVisibility: AppSetVisibility = AppSetVisibility.VISIBLE
) {

    operator fun invoke(apps: List<AbstractDetailedAppInfo>): List<AbstractDetailedAppInfo> {
        var apps =
            apps.sortedBy { app -> app.getCustomLabel(context).lowercase(Locale.ROOT) }

        val hidden = LauncherPreferences.apps().hidden() ?: setOf()
        val pinned = LauncherPreferences.minimalist().apps() ?: setOf()

        val blockedDialer = blockedSystemDialer()
        // Play services/GSF/the Play Store are never on Home or in the drawer - the store only
        // during the parent's install mode (handy step 7).
        val installMode = try {
            PlayRuntime.installModeActive(context)
        } catch (e: Exception) {
            false
        }
        apps = apps.filter { info ->
            val packageName = (info.getRawInfo() as? AppInfo)?.packageName
            hiddenVisibility.predicate(hidden, info)
                    && pinnedVisibility.predicate(pinned, info)
                    && !isMdmSuspended(info)
                    && packageName != blockedDialer
                    && (packageName == null || playPackageLaunchable(packageName, installMode))
        }

        return apps
    }

    /**
     * Belt-and-suspenders check alongside [android.app.admin.DevicePolicyManager.setApplicationHidden]
     * (which should already make MDM-blocked apps disappear from the underlying app enumeration):
     * excludes anything currently OS-suspended, so a live-updating drawer/home list never shows an
     * app the parent has blocked even if hiding doesn't fully take effect on a given Android version.
     */
    private fun isMdmSuspended(info: AbstractDetailedAppInfo): Boolean {
        val packageName = (info.getRawInfo() as? AppInfo)?.packageName ?: return false
        return try {
            context.packageManager.isPackageSuspended(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * The system dialer is never suspended (it's the in-call UI for emergency calls - see
     * `EnforcementPlan`), so [isMdmSuspended] doesn't hide it. While calls are managed (our phone
     * book is the way to call) or outgoing calls are restricted it's left off Home and the app
     * list anyway: its keypad can only place emergency calls then, which the lock screen also
     * offers.
     */
    private fun blockedSystemDialer(): String? {
        val restricted = try {
            context.getSystemService(UserManager::class.java)
                ?.hasUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS) == true
        } catch (e: Exception) {
            false
        }
        val hide = restricted || CallPolicyStore.state.managed
        return if (hide) systemDialerPackage(context) else null
    }

    companion object {
        enum class AppSetVisibility(
            val predicate: (set: Set<AbstractAppInfo>, AbstractDetailedAppInfo) -> Boolean
        ) {
            VISIBLE({ _, _ -> true }),
            HIDDEN({ set, appInfo -> !set.contains(appInfo.getRawInfo()) }),
            EXCLUSIVE({ set, appInfo -> set.contains(appInfo.getRawInfo()) }),
            ;
        }
    }
}