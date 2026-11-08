package io.github.xpertsavenue.ridevault.app

import android.app.Application
import io.github.xpertsavenue.ridevault.data.RideVaultDatabase
import io.github.xpertsavenue.ridevault.data.RideRepository

class RideVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleHelper.init()
    }

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val database: RideVaultDatabase by lazy { RideVaultDatabase.build(this) }
    val rides: RideRepository by lazy { RideRepository(database.rideDao()) }
    val health: HealthExporter by lazy { HealthExporter(this) }
}
