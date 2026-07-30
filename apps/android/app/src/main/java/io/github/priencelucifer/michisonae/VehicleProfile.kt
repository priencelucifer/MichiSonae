package io.github.priencelucifer.michisonae

internal data class VehicleProfile(
    val nickname: String,
    val vehicleClass: VehicleClass,
    val fuelType: FuelType,
    val tankCapacityLitres: Double,
    val efficiencyKmPerLitre: Double,
) {
    fun validationError(): String? = when {
        nickname.isBlank() -> "Enter a name for the car."
        nickname.length > 40 -> "Car name must be 40 characters or fewer."
        tankCapacityLitres !in 5.0..200.0 -> "Tank capacity must be between 5 and 200 litres."
        efficiencyKmPerLitre !in 1.0..50.0 -> "Efficiency must be between 1 and 50 km/litre."
        else -> null
    }
}

internal enum class VehicleClass(
    val displayName: String,
    val roadImpactThresholdMultiplier: Double,
) {
    COMPACT("Small", 0.8),
    STANDARD("Medium", 1.0),
    SUV("SUV", 1.25),
}

internal enum class FuelType(val displayName: String) {
    PETROL("Petrol"),
    DIESEL("Diesel"),
}
