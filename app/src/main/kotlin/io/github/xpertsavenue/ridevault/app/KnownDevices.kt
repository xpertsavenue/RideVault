package io.github.xpertsavenue.ridevault.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ein Fahrradcomputer, mit dem die App schon verbunden war. Gespeichert wird nur lokal auf dem Handy. */
data class KnownDevice(
    val address: String,
    val name: String,
    /** Kennung des Treibers ([io.github.xpertsavenue.ridevault.device.BikeComputerDriver.id]), damit ohne Suche wieder verbunden werden kann. */
    val driverId: String,
    val vendor: String,
    val model: String,
    /** Zeitpunkt der letzten erfolgreichen Verbindung (epoch millis). */
    val lastConnected: Long,
)

/** Merkt sich verbundene Geräte (neueste zuerst) in den App-Einstellungen. */
class KnownDevicesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("known_devices", Context.MODE_PRIVATE)

    fun load(): List<KnownDevice> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KnownDevice(
                o.getString("address"), o.getString("name"), o.getString("driverId"),
                o.optString("vendor"), o.optString("model"), o.optLong("lastConnected"),
            )
        }
    }.getOrDefault(emptyList()).sortedByDescending { it.lastConnected }

    /** Fügt das Gerät hinzu oder aktualisiert es (Name, Verbindungszeit). */
    fun remember(device: KnownDevice): List<KnownDevice> = save(load().filterNot { it.address == device.address } + device)

    fun forget(address: String): List<KnownDevice> = save(load().filterNot { it.address == address })

    private fun save(list: List<KnownDevice>): List<KnownDevice> {
        val sorted = list.sortedByDescending { it.lastConnected }
        val arr = JSONArray()
        sorted.forEach {
            arr.put(
                JSONObject()
                    .put("address", it.address).put("name", it.name).put("driverId", it.driverId)
                    .put("vendor", it.vendor).put("model", it.model).put("lastConnected", it.lastConnected),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
        return sorted
    }
}
