package io.github.xpertsavenue.ridevault.model

import java.time.Instant

/** Vom Nutzer vergebenes Etikett (z. B. "Rennrad", "Moped") mit eigener Farbe (ARGB). */
data class Tag(val id: Long, val name: String, val color: Int)

/**
 * Eine gespeicherte Fahrt mit Datenbank-ID, Import-Herkunft, optionalem Namen und Tags.
 * In Listen sind Spur und Messpunkte von [ride] leer.
 */
data class StoredRide(
    val id: Long,
    val ride: Ride,
    val importedAt: Instant,
    val deviceName: String?,
    val name: String? = null,
    val tags: List<Tag> = emptyList(),
    /** Archiviert: bleibt gespeichert, steht nicht in der Hauptliste und wird beim Import nicht erneut geladen. */
    val archived: Boolean = false,
)
