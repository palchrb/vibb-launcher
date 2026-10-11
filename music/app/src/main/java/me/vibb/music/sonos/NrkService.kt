package me.vibb.music.sonos

import org.w3c.dom.Element
import java.util.concurrent.ConcurrentHashMap

/*
 * The NRK Radio Sonos service recipe, ported from palchrb/vibb `pi/sonosd.py` (`NRK_SID`,
 * `NRK_SVCTYPE`, `_nrk_serial`, `nrk_program_uri`, `nrk_desc`; MIT License, Copyright (c) 2026
 * palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26a §2.3.
 * Android-free; DidlTest, SonosFixturesTest.
 *
 * Only NRK *series* go this way (HLS, which a speaker plays through the service); NRK podcasts and
 * RSS are plain mp3 URLs the speaker fetches itself. Precondition: the household's Sonos has the NRK
 * Radio service linked (it has: `sn=14` is the owner's known-working value). The path is necessary:
 * a Sonos doesn't play an NRK series manifest handed over as a plain url (design 26a §10 item 7, tested
 * on a speaker 2026-10-11).
 */
class NrkService(
    /** `GET http://<ip>:<port>/status/accounts` (Soap.get). */
    private val accounts: (ip: String, port: Int) -> String,
    private val log: (String) -> Unit = {},
) {
    private val serials = ConcurrentHashMap<String, String>()

    /**
     * `_nrk_serial`: the household's account serial for the service, looked up once per speaker ip
     * (a hardcoded serial breaks silently if the service is ever re-linked); [FALLBACK_SERIAL] when the
     * lookup fails, not cached.
     */
    fun serial(ip: String, port: Int): String {
        serials[ip]?.let { return it }
        try {
            parseSerial(accounts(ip, port))?.let {
                serials[ip] = it
                return it
            }
        } catch (e: Exception) {
            log("nrk serial lookup failed (${e.javaClass.simpleName}) - falling back to sn=$FALLBACK_SERIAL")
        }
        return FALLBACK_SERIAL
    }

    /** `nrk_program_uri`: the service uri of one program of [series] (the slug), at the speaker [ip]. */
    fun programUri(ip: String, port: Int, series: String, programId: String): String =
        programUri(series, programId, serial(ip, port))

    companion object {
        const val SID = 277
        const val SVC_TYPE = SID * 256 + 7
        const val FALLBACK_SERIAL = "14"
        const val UPNP_CLASS = "object.item.audioItem.show"
        const val PROTOCOL = "sonos.com-http:*:audio/mpeg:*"

        /** `nrk_desc`: the `cdudn` descriptor a service item needs. */
        const val DESC = "SA_RINCON${SVC_TYPE}_X_#Svc$SVC_TYPE-0-Token"

        /** The uri, `quote` as Python's (a `/` stays). */
        fun programUri(series: String, programId: String, serial: String): String =
            "x-sonos-http:series%3a${pyQuote(series)}%3a1%3a$programId.unknown?sid=$SID&flags=0&sn=$serial"

        /** The `SerialNum` of the `<Account Type="70919">` in `/status/accounts`, or null. */
        fun parseSerial(xml: String): String? {
            val root = runCatching { parseXml(xml).documentElement }.getOrNull() ?: return null
            val accounts = root.getElementsByTagName("Account")
            for (i in 0 until accounts.length) {
                val a = accounts.item(i) as? Element ?: continue
                if (a.getAttribute("Type") == SVC_TYPE.toString() && a.hasAttribute("SerialNum")) return a.getAttribute("SerialNum")
            }
            return null
        }
    }
}
