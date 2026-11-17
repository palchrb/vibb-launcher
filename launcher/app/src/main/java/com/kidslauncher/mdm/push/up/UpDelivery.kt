package com.kidslauncher.mdm.push.up

/** What the phone knows of a message's target app (design 108 §5.4). */
enum class UpAppState {
    /** Installed and not hidden - suspended included: a suspended app still receives broadcasts and runs (§5.7). */
    INSTALLED,

    /** Hidden by the allowlist (`isApplicationHidden`): it gets no broadcast. */
    HIDDEN,

    UNINSTALLED,
}

/** A message's way, from [UpDelivery.plan]. */
sealed interface UpPlan {
    /** Raise and send to [row]'s app; acknowledged only once the broadcast was sent (H1). */
    data class Deliver(val row: UpRow) : UpPlan

    /** Not delivered, acknowledged at once (the server would only send it again). [removeRow]: the app is gone, its
     * registration too (§2.3). */
    data class Drop(val removeRow: UpRow?) : UpPlan
}

/**
 * Design 108 §5.4's delivery plan, pure (`UpDeliveryPlanTest`, `up_rules.json`'s `delivery`):
 *
 * | the app is | the message is |
 * |---|---|
 * | installed and visible, suspended included (a time rule, the SMS rule; §5.7, Q5 "deliver") | delivered |
 * | hidden | dropped and acknowledged (the server deletes the registration through the allowlist anyway) |
 * | uninstalled | dropped and acknowledged, the registration removed |
 * | an unknown `reg` (removed meanwhile), or one not confirmed | dropped and acknowledged |
 */
object UpDelivery {
    fun plan(message: UpWireMessage, rows: List<UpRow>, state: (String) -> UpAppState): UpPlan {
        val row = rows.firstOrNull { it.reg == message.reg }?.takeIf { it.confirmed } ?: return UpPlan.Drop(null)
        return when (state(row.pkg)) {
            UpAppState.INSTALLED -> UpPlan.Deliver(row)
            UpAppState.HIDDEN -> UpPlan.Drop(null)
            UpAppState.UNINSTALLED -> UpPlan.Drop(row)
        }
    }
}
