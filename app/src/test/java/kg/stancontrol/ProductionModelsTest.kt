package kg.stancontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionModelsTest {
    @Test
    fun palletNetWeightIsGrossMinusTare() {
        val pallet = Pallet(
            type = "Европаллет",
            gross = 1_000.0,
            tare = 37.5
        )

        assertEquals(962.5, pallet.net, 0.000001)
    }

    @Test
    fun packKeepsExistingDefaultGeometry() {
        val pack = Pack(
            weight = 120.0,
            rows = 10,
            pieces = 80,
            coefficient = 1.0
        )

        assertEquals(5, pack.width)
        assertEquals(6.0, pack.length, 0.000001)
    }

    @Test
    fun defectStoresItsTypeAndWeightWithoutExtraCalculation() {
        val defect = Defect(type = "Резка", weight = 12.75)

        assertEquals("Резка", defect.type)
        assertEquals(12.75, defect.weight, 0.000001)
    }

    @Test
    fun longDefectStoresSelectedProductionTypeAndCleanWeight() {
        val defect = LongDefect(type = "Резка", weight = 2_345.0)
        assertEquals("Резка", defect.type)
        assertEquals(2_345.0, defect.weight, 0.000001)
    }

    @Test
    fun productionEntryRetainsItsPersistedValues() {
        val entry = ProductionEntry(
            date = "2026-11-07",
            clean = 12.5,
            scrap = 0.75,
            note = "Контрольная запись"
        )

        assertEquals("2026-11-07", entry.date)
        assertEquals(12.5, entry.clean, 0.000001)
        assertEquals(0.75, entry.scrap, 0.000001)
        assertEquals("Контрольная запись", entry.note)
    }
}
