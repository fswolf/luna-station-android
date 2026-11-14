package imbored.luna.station.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LunaCaTest {
    @Test
    fun readsLunasAuthority() {
        val ca = LunaCa.parse(Fixtures.CA_PEM.toByteArray())
        assertEquals(Fixtures.CA_FINGERPRINT, LunaCa.fingerprint(ca))
    }

    @Test
    fun refusesAServerCertificate() {
        assertThrows(StationError::class.java) { LunaCa.parse(Fixtures.LEAF_PEM.toByteArray()) }
    }

    @Test
    fun refusesJunk() {
        assertThrows(StationError::class.java) { LunaCa.parse("<html>not a cert</html>".toByteArray()) }
    }

    @Test
    fun buildsATrustStoreFromIt() {
        LunaCa.socketFactory(LunaCa.parse(Fixtures.CA_PEM.toByteArray()))
    }
}

class PortraitTrustTest {
    private val ca = LunaCa.parse(Fixtures.CA_PEM.toByteArray())
    private val leaf = java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(Fixtures.LEAF_PEM.toByteArray().inputStream()) as java.security.cert.X509Certificate

    @Test
    fun herServerAtHerAddress() = org.junit.Assert.assertTrue(LunaCa.vouchesFor(ca, leaf, "192.168.1.20"))

    @Test
    fun notAnotherAddress() = org.junit.Assert.assertFalse(LunaCa.vouchesFor(ca, leaf, "192.168.1.21"))

    @Test
    fun notSignedByAnotherAuthority() = org.junit.Assert.assertFalse(LunaCa.vouchesFor(leaf, leaf, "192.168.1.20"))
}
