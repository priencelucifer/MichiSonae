package io.github.priencelucifer.michisonae

import android.content.Context
import java.util.UUID

internal class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("michisonae", Context.MODE_PRIVATE)

    fun hasAcceptedPrivacy(): Boolean = preferences.getBoolean(KEY_PRIVACY_ACCEPTED, false)

    fun acceptPrivacy() {
        check(preferences.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).commit()) {
            "Could not persist privacy consent"
        }
        installationId()
    }

    fun installationId(): String {
        preferences.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { id ->
            check(preferences.edit().putString(KEY_INSTALLATION_ID, id).commit()) {
                "Could not persist the local installation identity"
            }
        }
    }

    fun loadVehicleProfile(): VehicleProfile? {
        if (!preferences.contains(KEY_VEHICLE_NAME)) return null
        return runCatching {
            VehicleProfile(
                nickname = checkNotNull(preferences.getString(KEY_VEHICLE_NAME, null)),
                vehicleClass = VehicleClass.valueOf(
                    checkNotNull(preferences.getString(KEY_VEHICLE_CLASS, null)),
                ),
                fuelType = FuelType.valueOf(
                    checkNotNull(preferences.getString(KEY_FUEL_TYPE, null)),
                ),
                tankCapacityLitres = Double.fromBits(
                    preferences.getLong(KEY_TANK_CAPACITY, 0L),
                ),
                efficiencyKmPerLitre = Double.fromBits(
                    preferences.getLong(KEY_FUEL_EFFICIENCY, 0L),
                ),
            )
        }.getOrNull()
    }

    fun saveVehicleProfile(profile: VehicleProfile) {
        require(profile.validationError() == null)
        check(
            preferences.edit()
                .putString(KEY_VEHICLE_NAME, profile.nickname)
                .putString(KEY_VEHICLE_CLASS, profile.vehicleClass.name)
                .putString(KEY_FUEL_TYPE, profile.fuelType.name)
                .putLong(KEY_TANK_CAPACITY, profile.tankCapacityLitres.toBits())
                .putLong(KEY_FUEL_EFFICIENCY, profile.efficiencyKmPerLitre.toBits())
                .commit(),
        ) {
            "Could not persist the vehicle profile"
        }
    }

    private companion object {
        const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_VEHICLE_NAME = "vehicle_name"
        const val KEY_VEHICLE_CLASS = "vehicle_class"
        const val KEY_FUEL_TYPE = "fuel_type"
        const val KEY_TANK_CAPACITY = "tank_capacity"
        const val KEY_FUEL_EFFICIENCY = "fuel_efficiency"
    }
}
