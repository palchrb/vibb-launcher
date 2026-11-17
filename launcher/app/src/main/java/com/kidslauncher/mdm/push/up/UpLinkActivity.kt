package com.kidslauncher.mdm.push.up

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle

/**
 * The distributor's Link Activity (design 108 §5.2): `unifiedpush://link`, asked for a result by an app choosing its
 * distributor. No window (translucent, finished in [onCreate]), never in Recents, no history, in the caller's task. It
 * extends nothing of ours, so Home's, Dagen's and Kid Settings' rule redirects never run.
 *
 * `RESULT_OK` with `pi` - an immutable broadcast PendingIntent to the spec's dummy app, from which the connector reads
 * our package - only when the link is ours, the caller is known (`callingPackage`, set only for a request for a result)
 * and may register ([UpView.mayRegister]: UP on, allowed, not stopped); otherwise `RESULT_CANCELED`. Installed
 * disabled and switched with the receiver ([UpRuntime.setComponentsEnabled]); `AppEnforcer` always sets the
 * persistent preferred activity for its filter, so Android never shows a chooser of distributors (L3).
 */
class UpLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val ours = data?.scheme == UpSpec.LINK_SCHEME && data.host == UpSpec.LINK_HOST
        val caller = callingPackage
        if (ours && caller != null && UpRuntime.view().mayRegister(caller)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(UpSpec.DUMMY_APP), PendingIntent.FLAG_IMMUTABLE)
            setResult(RESULT_OK, Intent().putExtra(UpSpec.EXTRA_PI, pi))
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
