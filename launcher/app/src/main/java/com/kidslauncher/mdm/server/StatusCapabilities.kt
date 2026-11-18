package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.lock.ANDROID_KEYGUARD_CAPABILITY

/** What this launcher tells the server it can do (`StatusReportRequest.capabilities`). C1 emptied it but for the one
 * claim the server still reads: [ANDROID_KEYGUARD_CAPABILITY] (C3's own-lock code queues the Child PIN by it; it goes
 * with that code). The field stays for the first feature after v0.40.0. */
val STATUS_CAPABILITIES: List<String> = listOf(ANDROID_KEYGUARD_CAPABILITY)
