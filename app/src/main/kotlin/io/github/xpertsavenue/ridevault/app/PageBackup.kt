package io.github.xpertsavenue.ridevault.app

import android.content.Context
import io.github.xpertsavenue.ridevault.device.DevicePage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sicherung der Seiten eines Geräts, bevor die App sie zum ersten Mal ändert (je Gerät und Fahrmodus). Damit lässt sich der Stand
 * vor den Änderungen jederzeit wiederherstellen. Liegt nur lokal auf dem Handy.
 */
class PageBackupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("page_backups", Context.MODE_PRIVATE)

    private fun key(address: String, mode: Int?) = "$address|${mode ?: -1}"

    fun has(address: String, mode: Int?): Boolean = prefs.contains(key(address, mode))

    /** Speichert die Sicherung nur, wenn es noch keine gibt: die älteste Sicherung ist der Stand vor allen Änderungen. */
    fun saveIfMissing(address: String, mode: Int?, pages: List<DevicePage>) {
        if (has(address, mode)) return
        val arr = JSONArray()
        pages.forEach { p ->
            arr.put(
                JSONObject()
                    .put("index", p.index).put("enabled", p.enabled).put("type", p.type)
                    .put("data", JSONArray(p.data)).put("layout", p.layout).put("lineWidth", p.lineWidth)
                    .put("name", p.name ?: JSONObject.NULL).put("dataSite", p.dataSite ?: JSONObject.NULL)
                    .put("mainPage", p.mainPage).put("graphic", p.graphicDisplay ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(key(address, mode), arr.toString()).apply()
    }

    fun load(address: String, mode: Int?): List<DevicePage>? = runCatching {
        val arr = JSONArray(prefs.getString(key(address, mode), null) ?: return null)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DevicePage(
                index = o.getInt("index"), enabled = o.getBoolean("enabled"), type = o.getInt("type"),
                data = o.getJSONArray("data").let { d -> (0 until d.length()).map { d.getInt(it) } },
                layout = o.getInt("layout"), lineWidth = o.getInt("lineWidth"),
                name = if (o.isNull("name")) null else o.getString("name"),
                dataSite = if (o.isNull("dataSite")) null else o.getInt("dataSite"),
                mainPage = o.getBoolean("mainPage"),
                graphicDisplay = if (o.isNull("graphic")) null else o.getInt("graphic"),
            )
        }
    }.getOrNull()
}
