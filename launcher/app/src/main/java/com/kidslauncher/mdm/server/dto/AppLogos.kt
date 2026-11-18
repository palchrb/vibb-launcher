package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** A logo is exactly this many pixels a side. */
const val APP_LOGO_SIZE_PX = 128

/** The largest logo the server takes (`POST /api/devices/app-logos/{hash}`'s body limit); a drawn PNG over it is
 * never reported. */
const val APP_LOGO_MAX_BYTES = 80 * 1024

/** The most hashes one answer asks for; the phone uploads at most these. */
const val APP_LOGOS_MAX_WANTED = 40

/** The status report's answer (design 87 §4.2): `200` with the logo hashes the server wants uploaded. A `204`, an
 * empty or unreadable body is nothing wanted. */
@Serializable
data class WantedLogos(
    val wantedLogos: List<String> = emptyList(),
)
