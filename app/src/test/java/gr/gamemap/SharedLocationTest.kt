package gr.gamemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Locations as other apps send them. */
class SharedLocationTest {
    private fun pos(s: String): LatLng = assertNotNull(s, SharedLocation.parse(s)?.pos).let { SharedLocation.parse(s)!!.pos!! }

    @Test
    fun links() {
        pos("geo:37.9838,23.7275?q=37.9838,23.7275(Omonoia)").let { assertEquals(37.9838, it.lat, 1e-6) }
        assertEquals("Omonoia", SharedLocation.parse("geo:37.9838,23.7275?q=37.9838,23.7275(Omonoia)")?.label)
        assertEquals("Πατησίων 44, Αθήνα", SharedLocation.parse("geo:0,0?q=Πατησίων 44, Αθήνα")?.query)
        pos("https://maps.google.com/?q=37.97,23.72").let { assertEquals(23.72, it.lon, 1e-6) }
        val place = SharedLocation.parse("https://www.google.com/maps/place/Mikel+Coffee/@37.97,23.72,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d37.9755!4d23.7348")
        assertEquals(37.9755, place?.pos?.lat ?: 0.0, 1e-6)
        assertEquals("Mikel Coffee", place?.label)
        pos("https://waze.com/ul?ll=37.97,23.72&navigate=yes")
        pos("https://www.openstreetmap.org/?mlat=37.97&mlon=23.72#map=17/37.97/23.72")
        assertEquals("Syntagma Square", SharedLocation.parse("https://maps.google.com/maps?daddr=Syntagma+Square")?.query)
        assertEquals(37.9838, SharedLocation.find("Δες εδώ: 37.9838, 23.7275")?.pos?.lat ?: 0.0, 1e-6)
        assertEquals(37.97, SharedLocation.find("Η τοποθεσία μου https://maps.google.com/?q=37.97,23.72 έλα")?.pos?.lat ?: 0.0, 1e-6)
    }
}
