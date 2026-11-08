# BIKEVEE BLE-Protokoll (rekonstruiert)

Quelle: statische Analyse von `BIKEVEE_2.0.66_APKPure.xapk` (Paket `uni.UNID86F0E9`, versionCode 30).
Die App ist eine DCloud-uni-app; die gesamte Logik steckt in
`assets/apps/__UNI__D86F0E9/www/app-service.js` (Webpack-Bundle, Vue 2).
REA-Evidence: `ev_312cff3f2ab8c35b884f0cf420fae39c9005a2601eced0443c14b866e455b6d5`.

> Alles hier ist **statisch abgeleitet** (nichts am echten Gerät beobachtet).
> Punkte unter „Offen“ müssen mit einem echten Gerät (HCI-Snoop-Log) verifiziert werden.

## Geräte / Modelle

Suche per BLE-Name, der den Modellnamen enthält (`name.includes(searchBleName)`), z. B. `BKV500-XXXX`.
Geräte-ID der Original-App = Namensteile nach dem ersten `-` (`parts[1] + parts[2]`).

| Marke | Modelle (Anzeige → Typ) |
|---|---|
| BIKEVEE | BKV100, BKV120, BKV500, BKV510, BKV520, BKV530 |
| BKVTOP | BKVTOP100…530 → gleiche Typen wie BKV |
| WEST BIKING | west_biking → BKV300 |
| KROSS | KRC-439GPS |

BKV100 / BKV120 / KRC-439GPS bekommen zusätzlich das Nutzerprofil (0x1B) und eigene Diagramm-Darstellung.

## GATT

| Zweck | Service | Characteristics | Quelle |
|---|---|---|---|
| **Hauptprotokoll** (Settings, Fahrten) | UUID enthält `0201` | `…0202` Notify (read), `…0203` Write, `…0204` Write „A“ | `util/ble.js` |
| Firmware-Update „DG“ | UUID enthält `0401` | `…0402` Notify, `…0403` Write, `…0404` Write A | `util/bleA.js`, `Update.vue` |
| Firmware-Update Nordic DFU | `FE59` | `8EC90001/2/3-F315-4F60-9FB8-838830DAEA50` | `util/bleB.js`, Gerätename `BKVDFU` |
| Externe Sensoren (direkt am Handy) | `1816` CSC, `180D` HR, `1818` Power | Standard | `util/bleC.js` |

MTU: 512 angefordert (Hauptprotokoll), 247 beim Firmware-Update.

## Rahmenformat (Hauptprotokoll)

Immer **20 Bytes**, in beide Richtungen:

```
55 AA <CMD> <16 Byte Payload> <CHK>
CHK = XOR über die Bytes 0..18 (inkl. 55 AA)
```

Antworten mit anderer Länge als 20 Byte ignoriert die Original-App.
Datums-/Zeitfelder sind **BCD** (`"25"` → `0x25`), sonst Little-Endian.

Hinweis: Die Original-Checksummenfunktion hat einen Bug (bei XOR-Werten 0x0A–0x0F entsteht ein
3-stelliger Hex-String). Eine eigene Implementierung muss einfach 1 Byte XOR senden.

## Befehle – Einstellungen

| CMD | Richtung | Payload | Bedeutung |
|---|---|---|---|
| `01` | → | `YYMMDDhhmmss`(Lokal, BCD) `TT`(Zeitformat) `YYMMDDhhmmss`(UTC, BCD) `000000` | Uhrzeit setzen; Antwort CMD `01` = OK |
| `0D` | → leer / ← `BB` | BB × 20 = Akku in % | Akku lesen |
| `0F` | → leer / ← `a b c d YY MM DD` | Firmware `a.b.c.d`, Datum `20YY-MM-DD` | Version lesen |
| `10` | → `000000` aus / `010000` immer an / `02` + `SSSS`(BCD Sekunden: 0030, 0060, 0300, 0600) | Hintergrundbeleuchtung |
| `11` | → `00` aus / `01 hhmm hhmm` (BCD Start/Ende) | Zeitgesteuerte Beleuchtung |
| `12` | → `hhmm` (BCD) | Auto-Standby-Zeit („outTime“) |
| `13` | → `HH`(Stunden, BCD) `45`='E' / `57`='W' | Zeitzone |
| `14` | → `LL` (1-basiert, BCD): 01 EN, 02 DE, 03 ZH, 04 CS, 05 PL, 06 FR, 07 IT, 08 ES, 09 NL, 10 KO, 11 PT, 12 JA | Sprache |
| `15` | → `00` km/h, `01` mph | Einheiten |
| `1B` | → `SS`(00=m,01=w) `HH`(cm) `WW`(kg) `AA`(Alter) – alles binär | Nutzerprofil (BKV100/120, KRC-439GPS) |
| `20` | → leer / ← `NN` | `NN == 1` → neue Fahrten vorhanden |

`TT` in 0x01: Wert aus dem Feld `timeType` (vermutlich 12/24-h).

## Fahrten-Synchronisation

1. App → `55AA16 00…` (Fahrt anfordern)
2. Gerät ← `16`: `?? YYMMDDhhmmss(BCD) u32LE nGps u32LE nSamples NN`
   `NN` = Anzahl gespeicherter Fahrten (0 → nichts da). Fortschritt = empfangen / (nGps + nSamples).
3. Gerät streamt Datensätze; **jeder wird mit `55AA<CMD>00…<CHK>` quittiert** (ACK):

| CMD | Inhalt |
|---|---|
| `0E` | GPS-Punkt: `YYMMDDhhmmss`(BCD, Bytes 3–8), Byte 9 ?, `float32LE lat` (Bytes 10–13), Byte 14 ?, `float32LE lon` (Bytes 15–18) |
| `18` | Messpunkt: `i16LE temp/10`, `u16LE speed/10`, `i16LE gradient/10`, `i16LE altitude`, `u16LE distance/10`, `u8 HR`, `u8 cadence`, `u16LE power` |
| `02` | Fahrzeit `h m s` (binär) |
| `03` / `04` / `05` | Distanz / Ø-Speed / Max-Speed: `u32LE /10`, Byte 7 Einheit (00 metrisch, sonst imperial) |
| `06` | Temperatur `i16LE`, Einheit-Byte |
| `07` / `08` | Höhenmeter (u32LE) / Höhe (i16LE), Einheit-Byte |
| `09` | Ø-Steigung %, Max-Steigung % |
| `0A` / `0B` / `0C` | Gesamt-Distanz, Gesamt-Zeit, Gesamt-Anstieg/-Abstieg (Odometer) |
| `19` | Ø-HF, Max-HF |
| `1A` | Ø-Trittfrequenz, Max-Trittfrequenz |
| `1C` | Kalorien `u16LE` |
| `1D` | Ø-Leistung, Max-Leistung `u16LE` |
| `17` | Ende der Fahrt (ACK `17`) → bei `NN > 1` erneut mit `16` die nächste anfordern |

Die Original-App fragt vor dem Download **die Cloud** (`getlatest`), ob die Fahrt neu ist, und sendet
nur dann ACKs. → In der eigenen App ersetzt durch lokale Prüfung (Gerät + Startzeit in der Datenbank).

## Offen / zu verifizieren

- Löscht oder markiert das Gerät eine Fahrt nach ACK auf `17`? (Ablauf mit `NN` deutet darauf hin.)
  → Fahrt erst vollständig lokal speichern, dann `17` quittieren.
- Was passiert ohne ACK (Timeout / Wiederholung)?
- Bedeutung von Byte 3 in `16` sowie Byte 9 und 14 in `0E`.
- Werte von `TT` (Zeitformat) in `01`.
- Gibt es Lesebefehle für die aktuellen Einstellungen? (In der Original-App nicht gefunden – sie
  kommen dort aus der Cloud.)
- Firmware-Update (0401 / Nordic DFU) bewusst ausgeklammert: Firmware kommt nur aus der Cloud.
