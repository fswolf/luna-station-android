package imbored.luna.station.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLinkTest {
    @Test
    fun setupLinkFromTheQr() {
        val link = PairLink.parse("http://192.168.1.20:8794/?code=Ab3_x-9QzP1k")!!
        assertEquals("192.168.1.20", link.host)
        assertEquals(8794, link.setupPort)
        assertEquals(8793, link.stationPort)
        assertEquals("Ab3_x-9QzP1k", link.code)
    }

    @Test
    fun stationPairLinkWorksToo() {
        val link = PairLink.parse("https://10.0.0.5:9000/pair?code=abc")!!
        assertEquals(9001, link.setupPort)
        assertEquals(9000, link.stationPort)
    }

    @Test
    fun customPortAndIpv6() {
        assertEquals(8800, PairLink.parse("http://192.168.0.2:8800/?code=x")!!.setupPort)
        assertEquals("fd00::5", PairLink.parse("http://[fd00::5]:8794/?code=x")!!.host)
    }

    @Test
    fun refusesWhatIsntLunas() {
        assertNull(PairLink.parse("http://8.8.8.8:8794/?code=abc"))          // not a home address
        assertNull(PairLink.parse("http://luna.example.com:8794/?code=abc")) // names are never looked up
        assertNull(PairLink.parse("http://192.168.1.20:8794/?nope=abc"))     // no code
        assertNull(PairLink.parse("http://192.168.1.20:8794/other?code=abc"))
        assertNull(PairLink.parse("http://192.168.1.20/?code=abc"))          // no port to work from
        assertNull(PairLink.parse("http://192.168.1.20:8794/?code=a%3Bb"))   // ; isn't in Luna's codes
        assertNull(PairLink.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(PairLink.parse(""))
    }

    @Test
    fun typedByHand() {
        assertEquals(PairLink("192.168.1.20", 8794, "abc"), PairLink.typed(" 192.168.1.20 ", " abc "))
        assertEquals(PairLink("192.168.1.20", 9001, "abc"), PairLink.typed("192.168.1.20:9001", "abc"))
        assertEquals(PairLink("192.168.1.20", 8794, "xyz"), PairLink.typed("http://192.168.1.20:8794/?code=xyz", ""))
        assertNull(PairLink.typed("192.168.1.20", ""))
        assertNull(PairLink.typed("1.1.1.1", "abc"))
    }

    @Test
    fun homeAddresses() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.50.1", "169.254.1.1", "127.0.0.1", "fd12::1", "fe80::1")
            .forEach { assertTrue(it, PairLink.isHomeAddress(it)) }
        listOf("8.8.8.8", "172.32.0.1", "2001:db8::1", "luna.local", "", "192.168.1")
            .forEach { assertFalse(it, PairLink.isHomeAddress(it)) }
    }
}
