package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleProfileTest {
    private val validProfile = VehicleProfile(
        nickname = "My car",
        vehicleClass = VehicleClass.COMPACT,
        fuelType = FuelType.PETROL,
        tankCapacityLitres = 35.0,
        efficiencyKmPerLitre = 16.0,
    )

    @Test
    fun sensibleVehicleProfileIsAccepted() {
        assertNull(validProfile.validationError())
    }

    @Test
    fun impossibleTankCapacityIsRejected() {
        assertEquals(
            "Tank capacity must be between 5 and 200 litres.",
            validProfile.copy(tankCapacityLitres = 0.0).validationError(),
        )
    }

    @Test
    fun smallerCarsUseALowerRoadImpactThreshold() {
        assertEquals(
            0.8,
            VehicleClass.COMPACT.roadImpactThresholdMultiplier,
            0.0,
        )
        assertEquals(
            1.25,
            VehicleClass.SUV.roadImpactThresholdMultiplier,
            0.0,
        )
    }
}
