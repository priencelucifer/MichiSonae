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
            ).takeIf { it.validationError() == null }
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

    fun backgroundSyncPolicy(): BackgroundSyncPolicy = BackgroundSyncPolicy(
        unmeteredOnly = preferences.getBoolean(KEY_SYNC_UNMETERED_ONLY, false),
        requireBatteryNotLow = preferences.getBoolean(KEY_SYNC_BATTERY_NOT_LOW, true),
    )

    fun saveBackgroundSyncPolicy(policy: BackgroundSyncPolicy) {
        check(
            preferences.edit()
                .putBoolean(KEY_SYNC_UNMETERED_ONLY, policy.unmeteredOnly)
                .putBoolean(KEY_SYNC_BATTERY_NOT_LOW, policy.requireBatteryNotLow)
                .commit(),
        ) {
            "Could not persist the background sync policy"
        }
    }

    fun shouldResumeMonitoring(): Boolean =
        preferences.getBoolean(KEY_RESUME_MONITORING, false)

    fun setShouldResumeMonitoring(shouldResume: Boolean) {
        check(preferences.edit().putBoolean(KEY_RESUME_MONITORING, shouldResume).commit()) {
            "Could not persist the monitoring recovery state"
        }
    }

    fun loadFavoriteDestinations(): List<Destination> =
        preferences.getStringSet(KEY_FAVORITE_DESTINATIONS, emptySet())
            .orEmpty()
            .mapNotNull {
                normalizeDestination(it, DestinationSource.FAVORITE)
            }
            .sortedBy { it.label.lowercase() }

    fun saveFavoriteDestination(destination: Destination) {
        val favorites = favoriteDestinations(loadFavoriteDestinations() + destination)
        check(
            preferences.edit()
                .putStringSet(
                    KEY_FAVORITE_DESTINATIONS,
                    favorites.map(Destination::label).toSet(),
                )
                .commit(),
        ) {
            "Could not persist the favorite destination"
        }
    }

    fun clearAll() {
        check(preferences.edit().clear().commit()) {
            "Could not delete local preferences"
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
        const val KEY_SYNC_UNMETERED_ONLY = "sync_unmetered_only"
        const val KEY_SYNC_BATTERY_NOT_LOW = "sync_battery_not_low"
        const val KEY_RESUME_MONITORING = "resume_monitoring"
        const val KEY_FAVORITE_DESTINATIONS = "favorite_destinations"
    }
}
