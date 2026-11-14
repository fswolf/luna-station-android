package imbored.luna.station.station

import java.net.InetAddress
import java.net.URI

/**
 * Where to pair: the PC's address, its two ports and the one-time code.
 *
 * Luna's pairing QR holds the setup page link, `http://<pc-ip>:8794/?code=<code>`
 * (web/station.py `setup_url()`). One scan gives everything: the setup port serves
 * `luna-ca.crt`, and the station itself is on the port below it.
 */
data class PairLink(val host: String, val setupPort: Int, val code: String) {
    val stationPort: Int get() = setupPort - 1

    companion object {
        const val DEFAULT_SETUP_PORT = 8794

        /** The setup link (or the station's own /pair link), or null if it isn't Luna's. */
        fun parse(text: String): PairLink? {
            val uri = try { URI(text.trim()) } catch (e: Exception) { return null }
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            val code = query(uri.rawQuery)["code"] ?: return null
            val setupPort = when {
                uri.scheme == "http" && uri.path.orEmpty().trimEnd('/').isEmpty() ->
                    if (uri.port > 0) uri.port else return null
                uri.scheme == "https" && uri.path == "/pair" ->
                    if (uri.port > 0) uri.port + 1 else return null
                else -> return null
            }
            return of(host, setupPort, code)
        }

        /** Typed by hand: an address (optionally with the setup port) and the code. */
        fun typed(address: String, code: String): PairLink? {
            val a = address.trim()
            parse(a)?.let { return it }       // the whole link pasted into the address box
            val (host, port) = if (a.count { it == ':' } == 1) {
                val (h, p) = a.split(':')
                h to (p.toIntOrNull() ?: return null)
            } else a.removeSurrounding("[", "]") to DEFAULT_SETUP_PORT
            return of(host, port, code.trim())
        }

        private fun of(host: String, setupPort: Int, code: String): PairLink? {
            if (setupPort !in 2..65535) return null
            if (code.isEmpty() || code.length > 64 || !code.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
            if (!isHomeAddress(host)) return null
            return PairLink(host, setupPort, code)
        }

        /**
         * A literal private, link-local or loopback address, the same rule Luna's
         * station server applies. Names are refused: no DNS lookup decides where
         * a pairing code (and later the mic) goes.
         */
        fun isHomeAddress(host: String): Boolean {
            // Only real literals go to InetAddress, so it never falls back to a DNS lookup.
            // (Java would also read "192.168.1" as an address; only the four-part form counts.)
            val ipv4 = host.split('.').let { p -> p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) } }
            val literal = ipv4 || (host.contains(':') && host.all { it in "0123456789abcdefABCDEF:." })
            if (!literal) return false
            val ip = try { InetAddress.getByName(host) } catch (e: Exception) { return false }
            return ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isLoopbackAddress ||
                (ip.address.size == 16 && (ip.address[0].toInt() and 0xfe) == 0xfc)   // IPv6 unique local, fc00::/7
        }

        private fun query(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').mapNotNull {
                val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                if (k.isEmpty()) null else k to java.net.URLDecoder.decode(v, "UTF-8")
            }.toMap()
    }
}
