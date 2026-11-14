package imbored.luna.station.station

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.X509Certificate
import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Talks to Luna's station server (web/station.py). Blocking: call it off the main thread.
 *
 * Plain HttpURLConnection for now; the turn loop in the next phase may move to OkHttp.
 */
object StationClient {
    private const val COOKIE = "luna_station"
    private const val MAX_CA_BYTES = 64 * 1024
    private const val MAX_CLIP_BYTES = 16 * 1024 * 1024

    /** Step 1: the authority's certificate, from the plain-HTTP setup port. */
    fun fetchCa(link: PairLink): X509Certificate {
        val conn = URL("http://${urlHost(link.host)}:${link.setupPort}/luna-ca.crt").openConnection() as HttpURLConnection
        return network(link.host) {
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = false
            try {
                if (conn.responseCode != 200) throw StationError("Luna's setup page answered ${conn.responseCode}. Is \"Relay stations\" switched on?")
                val bytes = conn.inputStream.use { readCapped(it, MAX_CA_BYTES + 1) }
                if (bytes.size > MAX_CA_BYTES) throw StationError("That isn't Luna's certificate (far too big).")
                LunaCa.parse(bytes)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Step 2: trade the one-time code for this device's token. */
    fun pair(link: PairLink, ca: X509Certificate): String {
        val conn = open(link.host, link.stationPort, ca, "/pair?code=${java.net.URLEncoder.encode(link.code, "UTF-8")}", null)
        return network(link.host) {
            try {
                when (conn.responseCode) {
                    303 -> tokenFrom(conn) ?: throw StationError("Luna paired, but didn't send a token back.")
                    403 -> throw StationError("That pairing code is used up or too old. Make a new one on the PC.")
                    else -> throw StationError("Pairing failed: Luna answered ${conn.responseCode}.")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    sealed interface Check {
        data object Connected : Check
        data object Unpaired : Check
        data class Unreachable(val why: String) : Check
    }

    /**
     * Is this device still paired, and can it reach her? Asks for a turn that can't
     * exist: a paired device gets 404 (no such turn), an unpaired one 401. Cheap, and
     * no turn is started. A /info endpoint on Luna's side would make this cleaner.
     */
    fun check(p: Pairing): Check = try {
        val conn = open(p.host, p.port, p.ca, "/turn/check", p.token)
        network(p.host) {
            try {
                when (conn.responseCode) {
                    404 -> Check.Connected
                    401 -> Check.Unpaired
                    else -> Check.Unreachable("Luna answered ${conn.responseCode}.")
                }
            } finally {
                conn.disconnect()
            }
        }
    } catch (e: StationError) {
        Check.Unreachable(e.message ?: "Can't reach Luna.")
    }

    // --- turns ---------------------------------------------------------------------------

    /** A permission question she's waiting on (write a file, run a command...). */
    data class Ask(val title: String, val body: String, val warn: Boolean)

    /** One look at a running turn: GET /turn/<id> (web/station.py Turn.view()). */
    data class Turn(
        val state: String,           // hearing, thinking, speaking, done
        val heard: String?,          // what Whisper heard (or the typed text); null until known
        val reply: String,           // her words so far
        val clips: Int,              // reply audio clips ready to fetch
        val ask: Ask?,
        val error: String,
        val stopped: Boolean,
    )

    /** Typed words. Returns the turn id. */
    fun say(p: Pairing, text: String): String =
        start(p, "/say", "application/json", JSONObject().put("text", text).toString().toByteArray())

    /** A recording (a RIFF WAV). Returns the turn id. */
    fun talk(p: Pairing, wav: ByteArray): String = start(p, "/talk", "audio/wav", wav)

    private fun start(p: Pairing, path: String, type: String, body: ByteArray): String {
        val conn = post(p, path, type, body)
        return network(p.host) {
            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val json = json(conn)
                when (code) {
                    200 -> json.optString("turn").ifEmpty { throw StationError("Luna didn't start a turn.") }
                    401 -> throw NotPairedError()
                    409 -> throw StationError("She's still answering the last one.")
                    503 -> throw StationError("Relay stations are switched off on the PC.")
                    else -> throw StationError(json.optString("error").ifEmpty { "Luna answered $code." })
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** The turn so far, or null once Luna has forgotten it (finished turns go after 5 minutes). */
    fun poll(p: Pairing, id: String): Turn? {
        val conn = open(p.host, p.port, p.ca, "/turn/$id", p.token)
        return network(p.host) {
            try {
                when (val code = conn.responseCode) {
                    200 -> json(conn).let { j ->
                        Turn(
                            state = j.optString("state"),
                            heard = if (j.isNull("heard")) null else j.optString("heard"),
                            reply = j.optString("reply"),
                            clips = j.optInt("clips"),
                            ask = j.optJSONObject("ask")?.let { a ->
                                Ask(a.optString("title"), a.optString("body"), a.optBoolean("warn"))
                            },
                            error = if (j.isNull("error")) "" else j.optString("error"),
                            stopped = j.optBoolean("stopped"),
                        )
                    }
                    404 -> null
                    401 -> throw NotPairedError()
                    else -> throw StationError("Luna answered $code.")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Reply clip n as WAV bytes, or null if it isn't there. */
    fun clip(p: Pairing, id: String, n: Int): ByteArray? {
        val conn = open(p.host, p.port, p.ca, "/clip/$id/$n", p.token)
        return network(p.host) {
            try {
                if (conn.responseCode != 200) null
                else conn.inputStream.use { readCapped(it, MAX_CLIP_BYTES) }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Clip n of a turn is starting to play here: her portrait's mouth moves with it. */
    fun playing(p: Pairing, id: String, n: Int) = fire(p, "/playing/$id/$n", "application/json", ByteArray(0))

    /** The Stop button: she stops writing and talking (and her portrait's mouth closes). */
    fun stop(p: Pairing, id: String) = fire(p, "/stop/$id", "application/json", ByteArray(0))

    /** Allow or deny the question she asked on this device. */
    fun answer(p: Pairing, id: String, allow: Boolean) =
        fire(p, "/answer/$id", "application/json", JSONObject().put("allow", allow).toString().toByteArray())

    private fun fire(p: Pairing, path: String, type: String, body: ByteArray) {
        val conn = post(p, path, type, body)
        network(p.host) {
            try {
                conn.outputStream.use { it.write(body) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun post(p: Pairing, path: String, type: String, body: ByteArray) =
        open(p.host, p.port, p.ca, path, p.token).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", type)
            setFixedLengthStreamingMode(body.size)
            readTimeout = 30000          // a recording upload over weak Wi-Fi
        }

    private fun json(conn: HttpURLConnection): JSONObject {
        val stream = (if (conn.responseCode >= 400) conn.errorStream else conn.inputStream) ?: return JSONObject()
        val text = stream.use { String(readCapped(it, 64 * 1024)) }
        return try { JSONObject(text) } catch (e: Exception) { JSONObject() }
    }

    // --- plumbing --------------------------------------------------------------------

    private fun open(host: String, port: Int, ca: X509Certificate, path: String, token: String?): HttpsURLConnection =
        (URL("https://${urlHost(host)}:$port$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = LunaCa.socketFactory(ca)
            connectTimeout = 5000
            readTimeout = 10000
            instanceFollowRedirects = false
            useCaches = false
            if (token != null) setRequestProperty("Cookie", "$COOKIE=$token")
        }

    private fun tokenFrom(conn: HttpURLConnection): String? =
        conn.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .firstNotNullOfOrNull { header ->
                header.split(';').first().trim().split('=', limit = 2)
                    .takeIf { it.size == 2 && it[0] == COOKIE && it[1].isNotEmpty() }?.get(1)
            }

    /** Turn network failures into something to say, the same way every time. */
    private inline fun <T> network(host: String, block: () -> T): T = try {
        block()
    } catch (e: StationError) {
        throw e
    } catch (e: SSLException) {
        throw StationError("Luna's certificate didn't check out. Check the date and time on this device; if the PC made a new certificate, forget this PC and pair again.")
    } catch (e: SocketTimeoutException) {
        throw StationError("Luna at $host isn't answering. Is the PC awake?")
    } catch (e: ConnectException) {
        throw StationError("Can't reach Luna at $host. Is she running, with Relay stations on, and the firewall open?")
    } catch (e: NoRouteToHostException) {
        throw StationError("Can't reach $host from here. Is this device on the same Wi-Fi as the PC?")
    } catch (e: IOException) {
        throw StationError("Couldn't talk to Luna at $host: ${e.message ?: e.javaClass.simpleName}")
    }

    /** Up to [limit] bytes (InputStream.readNBytes needs API 33; this app starts at 30). */
    private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun urlHost(host: String) = if (host.contains(':')) "[$host]" else host
}
