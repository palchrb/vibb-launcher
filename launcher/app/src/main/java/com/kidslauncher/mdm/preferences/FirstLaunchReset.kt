package com.kidslauncher.mdm.preferences

/**
 * `Application.initRest`'s first-launch reset (PLAN "Enrolment before Home's first start",
 * qa-glance-topinset-code Q-3; design 59d §8.17): `resetPreferences` clears the whole default
 * preferences file - the device token, the server URL, the Tailscale key, the cached policy and
 * `policy_ever_applied` included - and only Home's first interactive start sets
 * `internal.started_before`. A phone enrolled before that start (the QR's enrolment in
 * `onProfileProvisioningComplete`, or Settings opened without Home) lost its enrolment at its next
 * process start. So the reset runs only on a phone that has neither started Home nor enrolled:
 * both enrolment paths write `mdm.enrolled` and `mdm.device_token` together, and a fresh install
 * has neither. [tokenStored]: a token is stored, sealed or not (`DeviceToken.present`, design r10 - an unreadable one
 * still marks an enrolled phone). `started_before` itself stays Home's to set, with its first-launch work (the HOME
 * role request, the notification permission).
 */
fun firstLaunchResetDue(startedBefore: Boolean, enrolled: Boolean, tokenStored: Boolean): Boolean =
    !startedBefore && !enrolled && !tokenStored
