package io.github.xpertsavenue.ridevault.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.xpertsavenue.ridevault.model.RideSummary
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

@Entity(tableName = "rides", indices = [Index(value = ["deviceKey", "startTime"], unique = true)])
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceKey: String,
    val startTime: LocalDateTime,
    @Embedded(prefix = "s_") val summary: RideSummary,
    /** Zeitpunkt des Imports (epoch millis) und Name des Geräts, von dem importiert wurde. */
    val importedAt: Long = 0L,
    val deviceName: String? = null,
    /** Vom Nutzer vergebener Name (optional). */
    val name: String? = null,
    /** Archivierte Fahrten bleiben in der Datenbank (und zählen als bekannt), erscheinen aber nur im Archiv. */
    val archived: Boolean = false,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
)

/** Zuordnung Fahrt <-> Tag (n:m). Wird eine Fahrt oder ein Tag gelöscht, verschwindet die Zuordnung mit. */
@Entity(
    tableName = "ride_tags",
    primaryKeys = ["rideId", "tagId"],
    foreignKeys = [
        ForeignKey(RideEntity::class, ["id"], ["rideId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class RideTagEntity(val rideId: Long, val tagId: Long)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(RideEntity::class, ["id"], ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rideId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val time: LocalDateTime,
    val latitude: Double,
    val longitude: Double,
)

@Entity(
    tableName = "samples",
    foreignKeys = [ForeignKey(RideEntity::class, ["id"], ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rideId")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val temperatureC: Double?,
    val speedKmh: Double?,
    val gradientPercent: Double?,
    val altitudeM: Int?,
    val distanceKm: Double?,
    val heartRate: Int?,
    val cadenceRpm: Int?,
    val powerW: Int?,
)

@Dao
abstract class RideDao {
    @Insert abstract suspend fun insertRide(ride: RideEntity): Long
    @Insert abstract suspend fun insertTrack(points: List<TrackPointEntity>)
    @Insert abstract suspend fun insertSamples(samples: List<SampleEntity>)

    @Query("SELECT COUNT(*) FROM rides WHERE deviceKey = :deviceKey AND startTime = :startTime")
    abstract suspend fun count(deviceKey: String, startTime: LocalDateTime): Int

    @Query("SELECT * FROM rides ORDER BY startTime DESC")
    abstract fun observeAll(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    abstract suspend fun ride(id: Long): RideEntity?

    @Query("SELECT * FROM track_points WHERE rideId = :id ORDER BY id")
    abstract suspend fun track(id: Long): List<TrackPointEntity>

    @Query("SELECT * FROM samples WHERE rideId = :id ORDER BY id")
    abstract suspend fun samples(id: Long): List<SampleEntity>

    @Query("DELETE FROM rides WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT id FROM rides")
    abstract suspend fun allIds(): List<Long>

    @Query("UPDATE rides SET archived = :archived WHERE id IN (:ids)")
    abstract suspend fun setArchived(ids: List<Long>, archived: Boolean)

    // ---- Name und Tags ----
    @Query("UPDATE rides SET name = :name WHERE id = :id")
    abstract suspend fun setName(id: Long, name: String?)

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    abstract fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM ride_tags")
    abstract fun observeRideTags(): Flow<List<RideTagEntity>>

    @Query("SELECT tags.* FROM tags INNER JOIN ride_tags ON tags.id = ride_tags.tagId WHERE ride_tags.rideId = :rideId ORDER BY tags.name COLLATE NOCASE")
    abstract suspend fun tagsOf(rideId: Long): List<TagEntity>

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun tagByName(name: String): TagEntity?

    @Insert abstract suspend fun insertTag(tag: TagEntity): Long

    @Query("UPDATE tags SET name = :name, color = :color WHERE id = :id")
    abstract suspend fun updateTag(id: Long, name: String, color: Int)

    @Query("DELETE FROM tags WHERE id = :id")
    abstract suspend fun deleteTag(id: Long)

    @Query("DELETE FROM ride_tags WHERE rideId = :rideId")
    abstract suspend fun clearRideTags(rideId: Long)

    @Insert abstract suspend fun insertRideTags(items: List<RideTagEntity>)

    @Query("DELETE FROM ride_tags WHERE tagId = :tagId AND rideId IN (:rideIds)")
    abstract suspend fun removeTagFromRides(tagId: Long, rideIds: List<Long>)

    /** Fügt Tags zu mehreren Fahrten hinzu bzw. entfernt sie, ohne andere Tags anzutasten. */
    @Transaction
    open suspend fun changeTags(rideIds: List<Long>, add: Collection<Long>, remove: Collection<Long>) {
        remove.forEach { removeTagFromRides(it, rideIds) }
        add.forEach { tagId ->
            removeTagFromRides(tagId, rideIds) // verhindert doppelte Zuordnungen
            insertRideTags(rideIds.map { RideTagEntity(it, tagId) })
        }
    }

    @Transaction
    open suspend fun setRideInfo(rideId: Long, name: String?, tagIds: Collection<Long>) {
        setName(rideId, name)
        clearRideTags(rideId)
        insertRideTags(tagIds.map { RideTagEntity(rideId, it) })
    }

    /** Ersetzt eine Fahrt atomar durch mehrere (zuerst löschen, damit Gerät+Startzeit eindeutig bleibt). */
    @Transaction
    open suspend fun replace(oldId: Long, items: List<Triple<RideEntity, List<TrackPointEntity>, List<SampleEntity>>>) {
        delete(oldId)
        items.forEach { (ride, track, samples) -> save(ride, track, samples) }
    }

    /** Alles oder nichts: bei einem Fehler wird keine halbe Fahrt gespeichert. */
    @Transaction
    open suspend fun save(ride: RideEntity, track: List<TrackPointEntity>, samples: List<SampleEntity>): Long {
        val id = insertRide(ride)
        insertTrack(track.map { it.copy(rideId = id) })
        insertSamples(samples.map { it.copy(rideId = id) })
        return id
    }
}

class Converters {
    @TypeConverter fun toText(t: LocalDateTime?): String? = t?.toString()
    @TypeConverter fun fromText(s: String?): LocalDateTime? = s?.let(LocalDateTime::parse)
}

/** Aktuelle Datenbankversion; Backups dieser oder einer älteren Version lassen sich wiederherstellen. */
const val DB_VERSION = 4

@Database(
    entities = [RideEntity::class, TrackPointEntity::class, SampleEntity::class, TagEntity::class, RideTagEntity::class],
    version = DB_VERSION,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class RideVaultDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        /** v1 -> v2: Importdatum und Importgerät. Bereits vorhandene Fahrten erhalten das Migrationsdatum. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN importedAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
                db.execSQL("ALTER TABLE rides ADD COLUMN deviceName TEXT")
            }
        }

        /** v2 -> v3: Name je Fahrt, Tags und Zuordnung. Das SQL entspricht dem von Room erwarteten Schema. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN name TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ride_tags` (`rideId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`rideId`, `tagId`), " +
                        "FOREIGN KEY(`rideId`) REFERENCES `rides`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ride_tags_tagId` ON `ride_tags` (`tagId`)")
            }
        }

        /** v3 -> v4: Archiv-Kennzeichen je Fahrt (bestehende Fahrten sind nicht archiviert). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): RideVaultDatabase =
            Room.databaseBuilder(context.applicationContext, RideVaultDatabase::class.java, "ridevault.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
