package com.kidslauncher.mdm

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import androidx.core.app.CoreComponentFactory
import com.kidslauncher.mdm.calls.DirectBootComponents

/**
 * Runs the deferred unlocked setup (Application.ensureUnlockedInit) before any component that isn't
 * direct-boot-aware is created (QA direct-boot #1). When the process was started before the first
 * unlock (BootCallReceiver, Telecom), it is often still alive at unlock, and the system may then
 * start KidVpnService, CommandListenerService or a receiver in it before ACTION_USER_UNLOCKED
 * arrives - those read LauncherPreferences, which isn't initialised yet. Such a component only
 * exists once CE storage is unlocked, even while UserManager.isUserUnlocked() still says false
 * ("unlocking"), so that's the gate here, not isUserUnlocked(). Runs on the main thread.
 * Extends androidx's factory, which the merged manifest used before.
 */
class KidAppComponentFactory : CoreComponentFactory() {

    private fun beforeCreating(className: String) {
        if (DirectBootComponents.needsUnlockedSetup(className)) Application.instance?.ensureUnlockedInit()
    }

    override fun instantiateActivity(cl: ClassLoader, className: String, intent: Intent?): Activity {
        beforeCreating(className)
        return super.instantiateActivity(cl, className, intent)
    }

    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service {
        beforeCreating(className)
        return super.instantiateService(cl, className, intent)
    }

    override fun instantiateReceiver(cl: ClassLoader, className: String, intent: Intent?): BroadcastReceiver {
        beforeCreating(className)
        return super.instantiateReceiver(cl, className, intent)
    }

    /** Providers are normally created before Application.onCreate (instance still null: the
     * normal start handles setup); one installed later in a process started before unlock counts
     * like any other component. */
    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider {
        beforeCreating(className)
        return super.instantiateProvider(cl, className)
    }
}
