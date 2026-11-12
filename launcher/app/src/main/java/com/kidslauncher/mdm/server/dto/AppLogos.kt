package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Design 87: what a launcher that reports its apps' logos ([InstalledApp.logo]) and uploads the wanted ones claims
 * in the status report's `capabilities`. The shared contract is `app_logos.json` (byte-identical with
 * `server/testdata/`). */
const val APP_LOGOS_CAPABILITY = "app_logos_v1"

/** A logo is exactly this many pixels a side. */
const val APP_LOGO_SIZE_PX = 128

/** The largest logo the server takes (`POST /api/devices/app-logos/{hash}`'s body limit); a drawn PNG over it is
 * never reported. */
const val APP_LOGO_MAX_BYTES = 80 * 1024

/** The most hashes one answer asks for; the phone uploads at most these. */
const val APP_LOGOS_MAX_WANTED = 40

/** The status report's answer to a launcher claiming [APP_LOGOS_CAPABILITY] (design 87 §4.2): `200` with the logo
 * hashes the server wants uploaded. A `204` (any older server, or a report without the capability), an empty or
 * unreadable body is nothing wanted. */
@Serializable
data class WantedLogos(
    val wantedLogos: List<String> = emptyList(),
)
