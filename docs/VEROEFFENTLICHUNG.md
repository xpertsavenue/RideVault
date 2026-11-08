# Veröffentlichung von RideVault 1.0.0

## Was schon vorbereitet ist
- Version 1.0.0 (`versionCode` 1) in `app/build.gradle.kts`
- Release-Build ohne Verkleinern; lokale Signatur über `keystore.properties` (nicht im Repository)
- `fastlane/metadata/android/{en-US,de-DE}`: Titel, Kurz- und Langtext, Änderungsliste (Quelle für den F-Droid-Eintrag)
- `README.md`, `PRIVACY.md`, Gradle-Wrapper, `.gitignore` (schließt `testdata/`, APKs, Schlüssel aus)
- Entwurf des F-Droid-Eintrags: `docs/fdroid/io.github.xpertsavenue.ridevault.yml`

## Festgelegt
- **Anwendungs-ID:** `io.github.xpertsavenue.ridevault` (nach der ersten Veröffentlichung endgültig; der Anzeigename „RideVault“ lässt sich ändern)
- **Lizenz:** MIT (`LICENSE`)
- **Quellcode:** https://github.com/xpertsavenue/RideVault
- **Satellitenkarte (Esri):** bleibt. Bei F-Droid erscheint dafür der Hinweis „NonFreeNet“ (steht im Entwurf des Eintrags).
  Die Kacheln von `server.arcgisonline.com` haben eigene Nutzungsbedingungen von Esri; bei starker Nutzung können sie gesperrt werden.

## Noch zu tun
1. **Repository auf GitHub anlegen** (`xpertsavenue/RideVault`, öffentlich, leer) und den lokalen Stand mit dem Tag `v1.0.0` hochladen:
   `git remote add origin https://github.com/xpertsavenue/RideVault.git`, `git push -u origin main`, `git push origin v1.0.0`.
2. **Namen und Marken.** BIKEVEE, BKV und iGPSPORT sind Marken der Hersteller. Die App nennt sie nur zur Kompatibilität und sagt,
   dass sie unabhängig ist. Die Protokollbeschreibungen in `docs/` stammen aus eigener Analyse der Geräte-Kommunikation;
   keine Originaldateien, Protokolldefinitionen oder Apps der Hersteller ins Repository legen. Bei Zweifeln rechtlich beraten lassen.

Screenshots liegen unter `fastlane/metadata/android/<Sprache>/images/phoneScreenshots/` (aktuell deutsch, in beiden Sprachordnern).

## F-Droid
1. Repository auf GitHub bereitstellen, Tag `v1.0.0` setzen. (F-Droid baut von jedem öffentlichen Git-Repository; der Eintrag selbst kommt ins GitLab-Projekt von F-Droid.)
2. Bei <https://gitlab.com/fdroid/fdroiddata> das Projekt forken, `docs/fdroid/io.github.xpertsavenue.ridevault.yml` nach `metadata/io.github.xpertsavenue.ridevault.yml`
   kopieren und einen Merge Request stellen. Die Prüfer bauen aus dem Quellcode, scannen auf proprietäre Bibliotheken und
   antworten im Merge Request. Dauer: meist einige Tage bis Wochen.
3. Aktualisierungen: neuen Tag setzen (`versionName`/`versionCode` erhöhen, Änderungsliste `changelogs/<versionCode>.txt` anlegen).
   F-Droid erkennt Tags selbst (`UpdateCheckMode: Tags`).
4. F-Droid signiert mit eigenem Schlüssel. Wer später auch in anderen Quellen mit demselben Schlüssel erscheinen will,
   kann reproduzierbare Builds nutzen (eigenes Thema).

## Play Store: Aufwand
Grob ein bis zwei Tage Vorbereitung plus Wartezeit, weitgehend einmalig.
- **Konto:** Einmalig 25 US-Dollar, Identitätsprüfung (Ausweis, ggf. Adresse). Bei neuen privaten Konten verlangt Google nach
  meinem Wissensstand einen geschlossenen Test mit mindestens 12 Testern über 14 Tage, bevor die App öffentlich werden darf
  (Regeln ändern sich: vor dem Start in der Play Console nachlesen).
- **Technik:** App-Bundle (`./gradlew bundleRelease`, mit Upload-Schlüssel signiert, Play App Signing), Ziel-API-Stufe passt
  (36). Anwendungs-ID und Upload-Schlüssel gut sichern.
- **Pflichtangaben:** Datenschutzerklärung als öffentliche Webadresse (z. B. `PRIVACY.md` im Repository), Formular „Datensicherheit“,
  Inhaltseinstufung, Zielgruppe, Anzeigenangabe (keine).
- **Health Connect:** Für jede angeforderte Berechtigung (Training, Route, Strecke, Puls, Geschwindigkeit, Leistung, Trittfrequenz)
  ist eine Erklärung in der Play Console nötig, mit Prüfung durch Google. Das kann einige Tage dauern.
- **Store-Eintrag:** Symbol 512×512, Grafik 1024×500, Screenshots, Texte (liegen als Entwurf in `fastlane/`), Kontakt-E-Mail.
- **Risiken:** Nennung der Herstellernamen im Eintrag (Markenrecht), Esri-Kacheln, Bluetooth-Berechtigungen (nur „in der Nähe“, kein Standort).
- **Ablauf:** interner Test → geschlossener Test (14 Tage) → Produktion beantragen → Prüfung durch Google (Stunden bis Tage).
- Alternativ lässt sich dieselbe signierte APK zusätzlich auf GitHub Releases anbieten.

## Bauen
```
./gradlew test assembleRelease   # APK: app/build/outputs/apk/release/
./gradlew bundleRelease          # AAB für Play
```
