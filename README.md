# RideVault

Android-App (Kotlin, Jetpack Compose), die Fahrten von Fahrradcomputern per Bluetooth importiert und lokal verwaltet.
Keine Cloud, kein Konto, kein Tracking. Version 1.0.0.

*English: RideVault imports rides from bike computers (BIKEVEE/BKV, iGPSPORT) over Bluetooth and keeps them on your phone. No cloud, no account, no tracking.*

## Funktionen
- Fahrten per BLE importieren (BIKEVEE/BKV; iGPSPORT, getestet mit dem BSC200)
- Karte, Diagramme, Statistik, Tags, Archiv, Sortieren und Gruppieren
- Export: GPX, TCX, ZIP, Bild zum Teilen; optional nach Health Connect (nur auf Wunsch, wieder entfernbar)
- Sicherung und Wiederherstellung der Datenbank
- iGPSPORT: Einstellungen, Datenseiten, Strecken senden/aktivieren/umbenennen/löschen, Akku/Speicher, Livewerte
- 9 Sprachen: de, en, fr, es, it, nl, pl, cs, pt

## Bauen
Voraussetzung: JDK 17 bis 21 (z. B. das JBR von Android Studio) und das Android SDK.

```
./gradlew test assembleDebug      # Debug-APK: app/build/outputs/apk/debug/
./gradlew assembleRelease         # unsigniert, wenn keystore.properties fehlt
```

Zum Signieren lokal `keystore.properties` neben `settings.gradle.kts` anlegen (Schlüssel `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). Die Datei gehört nicht ins Repository.

## Aufbau
`app` (Oberfläche) · `core:model`, `core:device-api`, `core:ble` · `driver:bikevee`, `driver:igpsport` · `data` (Room) · `export` (GPX/TCX) · `fit`
Das Protokoll der Geräte ist in `docs/` beschrieben.

## Datenschutz
Siehe [PRIVACY.md](PRIVACY.md).

## Hinweise
RideVault ist ein unabhängiges Projekt und nicht mit BIKEVEE/BKV oder iGPSPORT verbunden. Die Namen dienen nur der Kompatibilitätsangabe.
Kartendaten © OpenStreetMap-Mitwirkende; optionale Satellitenkacheln © Esri (eigene Nutzungsbedingungen des Dienstes).

## Verwendete Bibliotheken
AndroidX/Jetpack Compose, Room, Health Connect Client (Apache-2.0), osmdroid (Apache-2.0), material-kolor (MIT).

## Lizenz
[MIT](LICENSE)
