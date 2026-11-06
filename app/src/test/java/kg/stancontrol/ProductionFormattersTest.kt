package kg.stancontrol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ProductionFormattersTest {
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun wholeNumberFormatterRoundsToNoDecimalPlaces() {
        assertEquals("13", fmtWhole(12.6))
    }

    @Test
    fun weightFormatterOmitsUnnecessaryDecimalPart() {
        assertEquals("12", fmtWeight(12.0))
    }

    @Test
    fun weightFormatterKeepsOneDecimalPlaceWhenNeeded() {
        assertEquals("12.5", fmtWeight(12.5))
    }

    @Test
    fun coefficientFormatterUsesOneDecimalPlace() {
        assertEquals("1.3", fmtCoefficient(1.26))
    }

    @Test
    fun todayUsesTheProductionDateFormat() {
        assertTrue(today().matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}")))
    }
}
