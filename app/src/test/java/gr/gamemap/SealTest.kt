package gr.gamemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Encryption round trip, plus samples the web pages' decryption is checked against. */
@RunWith(RobolectricTestRunner::class)
class SealTest {
    @Test
    fun roundTrip() {
        val k = Seal.convoyKey("k7p2qx")
        val msg = """{"t":"pos","id":"abc","n":"Νίκος","s":42}"""
        val sealed = Seal.seal(k, msg)
        assertEquals(msg, Seal.open(k, sealed))
        assertNull(Seal.open(Seal.convoyKey("k7p2qy"), sealed))   // wrong code
        assertNull(Seal.open(k, msg))                              // old plain message
        assertNull(Seal.open(k, sealed.dropLast(2) + "AA"))        // tampered
        val lk = Seal.randomKey()
        assertEquals(msg, Seal.open(lk, Seal.seal(lk, msg)))

        System.getProperty("previewOut")?.let { dir ->
            File(dir).mkdirs()
            val live = Seal.unb64("AAECAwQFBgcICQoLDA0ODw")
            File(dir, "seal.txt").writeText(
                "topic " + Seal.convoyTopic("k7p2qx") + "\n" +
                "convoy " + sealed + "\n" +
                "live " + Seal.seal(live, msg) + "\n")
        }
    }
}
