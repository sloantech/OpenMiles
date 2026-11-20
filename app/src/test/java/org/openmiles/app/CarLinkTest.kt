package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.CarLink
import org.openmiles.app.domain.Speech

class CarLinkTest {
    @Test
    fun shortDropDoesNotEndTheDrive() {
        assertFalse(CarLink.shouldEnd(null, 1_000_000))
        assertFalse(CarLink.shouldEnd(1_000_000, 1_000_000 + 59_999))
        assertTrue(CarLink.shouldEnd(1_000_000, 1_000_000 + 60_000))
    }

    @Test
    fun addressesAreChecked() {
        assertTrue(CarLink.isAddress("00:1A:7D:DA:71:13"))
        assertFalse(CarLink.isAddress("00:1A:7D:DA:71"))
        assertFalse(CarLink.isAddress("00:1A:7D:DA:71:1G"))
        assertFalse(CarLink.isAddress("00:1a:7d:da:71:13")) // stored upper case only
        assertFalse(CarLink.isAddress(""))
    }

    @Test
    fun savedCarsRoundTrip() {
        val cars = listOf(CarLink.Car("00:1A:7D:DA:71:13", "My Honda"), CarLink.Car("AA:BB:CC:DD:EE:FF", "Work van"))
        assertEquals(cars, CarLink.decode(CarLink.encode(cars)))
    }

    @Test
    fun lowerCaseAddressIsSavedUpperCase() {
        val back = CarLink.decode(CarLink.encode(listOf(CarLink.Car("aa:bb:cc:dd:ee:ff", "Car"))))
        assertEquals("AA:BB:CC:DD:EE:FF", back.single().address)
    }

    @Test
    fun damagedAndRepeatedLinesAreSkipped() {
        val text = "nonsense\nAA:BB:CC:DD:EE:FF\tCar one\nAA:BB:CC:DD:EE:FF\tSame car\n\n11:22:33:44:55:66"
        val cars = CarLink.decode(text)
        assertEquals(2, cars.size)
        assertEquals("Car one", cars[0].name)
        assertEquals("Car", cars[1].name) // no name saved
        assertTrue(CarLink.decode(null).isEmpty())
        assertTrue(CarLink.decode("").isEmpty())
    }

    @Test
    fun atMostFiveCars() {
        val many = (0 until 9).map { CarLink.Car(String.format("AA:BB:CC:DD:EE:%02X", it), "Car $it") }
        assertEquals(CarLink.MAX_CARS, CarLink.decode(CarLink.encode(many)).size)
    }

    @Test
    fun deviceNamesCannotBreakTheFile() {
        val cars = listOf(CarLink.Car("AA:BB:CC:DD:EE:FF", "Evil\nBB:BB:BB:BB:BB:BB\tfake"))
        val back = CarLink.decode(CarLink.encode(cars))
        assertEquals(1, back.size)
        assertFalse(back[0].name.contains("\n"))
        assertEquals(40, CarLink.clean("x".repeat(100)).length)
    }

    @Test
    fun spokenMiles() {
        assertEquals("12.4 miles", Speech.miles(12.4 * 1609.344))
        assertEquals("1 mile", Speech.miles(1609.344))
        assertEquals("0.3 miles", Speech.miles(0.3 * 1609.344))
        assertEquals("5 miles", Speech.miles(5.0 * 1609.344))
    }

    @Test
    fun spokenSaved() {
        val m = 5.0 * 1609.344
        assertEquals("Drive saved. 5 miles.", Speech.saved(m, null, asking = false))
        assertEquals("Drive saved. 5 miles. Marked Business.", Speech.saved(m, "Business", asking = true))
        assertTrue(Speech.saved(m, null, asking = true).contains("notification"))
        assertFalse(Speech.saved(m, "A\nB", asking = false).contains("\n"))
        assertEquals("Drive started.", Speech.started())
    }
}
