package com.kidslauncher.mdm.badges

import com.kidslauncher.mdm.appdisplay.isValidPackageName
import com.kidslauncher.mdm.calls.MessagePackages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The lock's glance page (design 25, docs/design/25-lock-glance.md at the monorepo root): the pure
 * rules behind its rows - the parent's policy, what is read of a notification and at which level,
 * which notifications are candidates, and how they become rows. JVM-tested (GlanceRowsTest,
 * PolicyResponseCompatTest). Names and texts live only in memory ([GlanceStore]); every type here
 * that can carry one has a redacted toString().
 */

/**
 * The parent's choice (`PolicyResponse.lock_glance`): the packages whose messages show on the lock
 * ([apps]) and whether with their text ([showText]). Missed calls always show; they aren't in it.
 */
data class GlancePolicy(val apps: Set<String>, val showText: Boolean) {
    companion object {
        /** No usable policy (a corrupt or missing cache, the last-enforced fallback, anything the
         * server sent that isn't an object): missed calls only - the parent may have unticked
         * something. */
        val UNKNOWN = GlancePolicy(emptySet(), showText = false)

        /** What an older server without the key means (its own default for every phone). */
        val SERVER_DEFAULT = GlancePolicy(setOf(MessagePackages.ELEMENT_X), showText = false)
    }
}

/**
 * `PolicyResponse.lockGlance` read field by field: nothing in it can fail the policy (design 25
 * §2.1, like `app_display` and `music`).
 * - an object: `apps` = its valid package names (anything else in the list is dropped, so is a
 *   non-list), `show_text` true only for JSON `true`;
 * - missing or JSON `null`: from a fresh response ([fresh]) an older server - its own default,
 *   Element X with the sender only; from the cache it is a cache an older build wrote (it never
 *   kept the key), so unknown (QA Low 11);
 * - anything else: unknown.
 */
fun lockGlancePolicy(element: JsonElement?, fresh: Boolean): GlancePolicy = when (element) {
    null, JsonNull -> if (fresh) GlancePolicy.SERVER_DEFAULT else GlancePolicy.UNKNOWN
    is JsonObject -> GlancePolicy(
        apps = (element["apps"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter(::isValidPackageName)
            .toSet(),
        showText = (element["show_text"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
    )
    else -> GlancePolicy.UNKNOWN
}
