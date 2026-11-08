package io.github.xpertsavenue.ridevault.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.github.xpertsavenue.ridevault.export.Exporters
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Export (GPX/TCX/ZIP) und Backup/Wiederherstellung über den Android-Dateiauswahldialog (kein Speicherzugriff nötig). */
object DataTransfer {
    private const val DB_NAME = "ridevault.db"
    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    /** Höchste Datenbankversion, die diese App-Version kennt (Migrationen heben ältere Backups an). */
    private const val MAX_DB_VERSION = io.github.xpertsavenue.ridevault.data.DB_VERSION

    suspend fun write(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Datei konnte nicht geöffnet werden")
    }

    /** Alle Fahrten als GPX-Dateien in einem ZIP. */
    suspend fun exportAllZip(app: RideVaultApp, uri: Uri) = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val files = app.rides.allRideIds().mapNotNull { app.rides.load(it) }.map { s ->
            "${Exporters.fileBaseName(s.ride, s.name)}.gpx" to Exporters.gpx(s.ride, s.name, zone).toByteArray()
        }
        app.contentResolver.openOutputStream(uri, "wt")?.use { Exporters.zip(files, it) } ?: error("Datei konnte nicht geöffnet werden")
    }

    /** Ausgewählte Fahrten als GPX-Dateien in einem ZIP. */
    suspend fun exportRidesZip(context: Context, uri: Uri, rides: List<io.github.xpertsavenue.ridevault.model.StoredRide>, zone: ZoneId) = withContext(Dispatchers.IO) {
        val files = rides.map { s -> "${Exporters.fileBaseName(s.ride, s.name)}.gpx" to Exporters.gpx(s.ride, s.name, zone).toByteArray() }
        context.contentResolver.openOutputStream(uri, "wt")?.use { Exporters.zip(files, it) } ?: error("Datei konnte nicht geöffnet werden")
    }

    /** Ausgewählte Fahrten in **einer** GPX-Datei (eine Spur je Fahrt). */
    suspend fun exportRidesCombinedGpx(context: Context, uri: Uri, rides: List<io.github.xpertsavenue.ridevault.model.StoredRide>, zone: ZoneId) = withContext(Dispatchers.IO) {
        val xml = Exporters.gpxCombined(rides.map { it.ride to it.name }, zone).toByteArray()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(xml) } ?: error("Datei konnte nicht geöffnet werden")
    }

    /** Kopiert die komplette Datenbank (Fahrten, Namen, Tags) in die gewählte Datei. */
    suspend fun backup(app: RideVaultApp, uri: Uri) = withContext(Dispatchers.IO) {
        // Schreibprotokoll (WAL) in die Hauptdatei übernehmen, damit die kopierte Datei vollständig ist
        app.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val file = app.getDatabasePath(DB_NAME)
        app.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Datei konnte nicht geöffnet werden")
    }

    /**
     * Ersetzt die Datenbank durch ein Backup. Prüft vorher, dass es eine SQLite-Datei mit Fahrten-Tabelle und bekannter Version ist.
     * @return false, wenn die Datei kein gültiges Backup ist (dann bleibt alles unverändert).
     */
    suspend fun restore(app: RideVaultApp, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(app.cacheDir, "restore.db")
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@withContext false
            val header = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
            if (String(header, Charsets.ISO_8859_1) != SQLITE_MAGIC) return@withContext false
            val valid = runCatching {
                SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val hasRides = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='rides'", null).use { it.moveToFirst() }
                    hasRides && db.version in 1..MAX_DB_VERSION
                }
            }.getOrDefault(false)
            if (!valid) return@withContext false

            val db = app.getDatabasePath(DB_NAME)
            app.database.close()
            File(db.path + "-wal").delete()
            File(db.path + "-shm").delete()
            tmp.copyTo(db, overwrite = true)
            true
        } finally {
            tmp.delete()
        }
    }
}
