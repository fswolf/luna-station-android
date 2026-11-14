package imbored.luna.station.station

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Luna's own certificate authority, made on the PC (web/station.py).
 *
 * The station connection trusts this one certificate and nothing else: not the
 * system's, never "trust all". The authority can only vouch for home-network
 * addresses (its NameConstraints), and Android's verifier enforces that.
 */
object LunaCa {
    /**
     * [checkDates] is for pairing. A stored certificate is read without it: a phone whose
     * clock is off shouldn't silently forget its pairing; the TLS handshake checks dates anyway.
     */
    fun parse(bytes: ByteArray, checkDates: Boolean = true): X509Certificate {
        val cert = try {
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        } catch (e: Exception) {
            throw StationError("What the PC sent isn't a certificate.")
        }
        if (cert.basicConstraints < 0) throw StationError("The PC's certificate isn't a certificate authority.")
        if (checkDates) try {
            cert.checkValidity()
        } catch (e: Exception) {
            throw StationError("Luna's certificate has expired or isn't valid yet. Check the date on both devices.")
        }
        return cert
    }

    /** SHA-256 of the certificate, AB:CD:... like `openssl x509 -fingerprint -sha256` prints it. */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /**
     * For her portrait's WebView, which only hands over the server's own certificate (all
     * Luna's chain has below her authority): it's current, signed by [ca], and names [host].
     */
    fun vouchesFor(ca: X509Certificate, leaf: X509Certificate, host: String): Boolean = try {
        leaf.checkValidity()
        leaf.verify(ca.publicKey)
        leaf.issuerX500Principal == ca.subjectX500Principal &&
            (leaf.subjectAlternativeNames ?: emptyList()).any { san ->
                val value = san[1].toString()
                when (san[0] as Int) {
                    7 -> sameAddress(value, host)                     // an IP address
                    2 -> value.equals(host, ignoreCase = true)        // a name
                    else -> false
                }
            }
    } catch (e: Exception) {
        false
    }

    // Both are literals (checked at pairing), so this never looks anything up.
    private fun sameAddress(a: String, b: String): Boolean = try {
        java.net.InetAddress.getByName(a) == java.net.InetAddress.getByName(b)
    } catch (e: Exception) {
        false
    }

    fun socketFactory(cert: X509Certificate): SSLSocketFactory {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("luna-ca", cert)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}

/** Something went wrong in a way the person can act on; the message says how. */
open class StationError(message: String) : Exception(message)

/** Luna no longer knows this device's token: it was unpaired on the PC. */
class NotPairedError : StationError("This device was unpaired on the PC. Forget it here and pair again.")
