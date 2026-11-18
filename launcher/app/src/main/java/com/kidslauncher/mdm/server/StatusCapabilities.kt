package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.lock.ANDROID_KEYGUARD_CAPABILITY

/** What this launcher tells the server it can do (`StatusReportRequest.capabilities`). C1 emptied it but for the one
 * claim the server still reads: [ANDROID_KEYGUARD_CAPABILITY] (C3's own-lock code queues the Child PIN by it; it goes
 * with that code). The field stays for the first feature after v0.40.0. A server from before C1 reads this short list
 * as an old launcher's and leaves out `unified_push`, `server_tls`, the zones and `kid_caldav`: the C1 server ships
 * before this launcher reaches a real phone (C1 code QA M1). */
val STATUS_CAPABILITIES: List<String> = listOf(ANDROID_KEYGUARD_CAPABILITY)
