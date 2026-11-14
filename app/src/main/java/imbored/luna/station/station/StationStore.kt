package imbored.luna.station.station

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A paired PC: where it is, the authority to trust, and this device's token. */
data class Pairing(val host: String, val port: Int, val ca: X509Certificate, val token: String)

/**
 * Keeps the pairing on the device. The token is the device's key to Luna, so it's
 * encrypted with an AES key that lives in the Android Keystore and can't be read
 * out. (androidx security-crypto would do the same, but it's deprecated.)
 * Backups are off in the manifest, so none of this leaves the device.
 */
class StationStore(context: Context) {
    private val prefs = context.getSharedPreferences("station", Context.MODE_PRIVATE)

    fun load(): Pairing? = try {
        val host = prefs.getString("host", null)
        val ca = prefs.getString("ca", null)
        val token = prefs.getString("token", null)
        if (host == null || ca == null || token == null) null
        else Pairing(host, prefs.getInt("port", 8793), LunaCa.parse(b64(ca), checkDates = false), decrypt(b64(token)))
    } catch (e: Exception) {
        null    // the Keystore key is gone (device reset, restored data): pair again
    }

    fun save(p: Pairing) {
        prefs.edit()
            .putString("host", p.host)
            .putInt("port", p.port)
            .putString("ca", Base64.getEncoder().encodeToString(p.ca.encoded))
            .putString("token", Base64.getEncoder().encodeToString(encrypt(p.token)))
            .apply()
    }

    /** Forget the PC on this device. Luna still lists it until it's unpaired on the PC. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun b64(s: String) = Base64.getDecoder().decode(s)

    private fun encrypt(text: String): ByteArray {
        val cipher = Cipher.getInstance(AES)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(text.toByteArray())
    }

    private fun decrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance(AES)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        return String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES))
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "luna_station_token"
        const val AES = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
