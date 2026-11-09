package kg.stancontrol

/**
 * Domain models for production shift records.
 *
 * Keep these models independent from Android UI and persistence APIs.
 */
data class Pack(
    val weight: Double,
    val rows: Int,
    val pieces: Int,
    val coefficient: Double,
    val width: Int = 5,
    val length: Double = 6.0
)

data class Defect(
    val type: String,
    val weight: Double
)

data class Pallet(
    val type: String,
    val gross: Double,
    val tare: Double
) {
    val net: Double
        get() = gross - tare
}

/**
 * A production-control record stored in the production calendar and archive.
 *
 * Values are expressed in tonnes, matching the existing persistence format.
 */
internal data class ProductionEntry(
    val date: String,
    val clean: Double,
    val scrap: Double,
    val note: String
)

/** A direct clean-weight record for long defects, separate from pallet weights. */
data class LongDefect(
    val type: String,
    val weight: Double
)
