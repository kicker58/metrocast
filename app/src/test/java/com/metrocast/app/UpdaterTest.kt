package com.metrocast.app

import com.metrocast.app.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Updates from your PC: addresses and the version file. */
class UpdaterTest {
    @Test fun addresses() {
        assertEquals("http://192.168.1.20:8765/", Updater.base("192.168.1.20"))
        assertEquals("http://192.168.1.20:9000/", Updater.base(" 192.168.1.20:9000/ "))
        assertEquals("http://my-pc.local:8765/", Updater.base("http://my-pc.local"))
        assertNull(Updater.base("")); assertNull(Updater.base("not an address"))
    }

    @Test fun onlyOnTheHomeNetwork() {
        // Your PC at 10.1.1.20: the phone must be on 10.1.1.x.
        assertEquals(true, Updater.onHomeNetwork("10.1.1.20", listOf("10.1.1.77")))
        assertEquals(true, Updater.onHomeNetwork("10.1.1.20:9000", listOf("10.0.0.2", "10.1.1.3")))
        assertEquals(false, Updater.onHomeNetwork("10.1.1.20", listOf("192.168.1.77")))   // someone else's Wi-Fi
        assertEquals(false, Updater.onHomeNetwork("10.1.1.20", listOf("10.12.0.4")))       // work, café…
        assertEquals(false, Updater.onHomeNetwork("10.1.1.20", emptyList()))               // mobile data
        assertEquals(false, Updater.onHomeNetwork("my-pc.local", listOf("10.1.1.77")))     // needs the number
        assertEquals(false, Updater.onHomeNetwork("", listOf("10.1.1.77")))
        assertEquals("10.1.1", Updater.homeBlock("10.1.1.20"))
    }

    @Test fun versionFile() {
        val ok = Updater.parse("""{"versionCode":500000,"versionName":"1.0.500000","apk":"MetroCast-Updates.apk","sha256":"ABC","notes":"Fixes"}""")!!
        assertEquals(500000, ok.versionCode); assertEquals("abc", ok.sha256); assertEquals("MetroCast-Updates.apk", ok.apk)
        // Only a plain file name in the same folder.
        assertNull(Updater.parse("""{"versionCode":2,"apk":"../evil.apk","sha256":"a"}"""))
        assertNull(Updater.parse("""{"versionCode":2,"apk":"http://x/y.apk","sha256":"a"}"""))
        assertNull(Updater.parse("""{"versionCode":2,"apk":"notes.txt","sha256":"a"}"""))
        assertNull(Updater.parse("garbage"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256("abc".toByteArray()))
    }
}
