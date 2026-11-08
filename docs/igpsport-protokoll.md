# iGPSPORT BLE-Protokoll (rekonstruiert)

Quelle: statische Analyse von `iGPSPORT_7.45.03_APKPure.apk` (89 MB, 7 DEX-Dateien).
Der Code ist **nicht verschleiert** (Klassen- und Methodennamen lesbar), die Protokollbeschreibungen liegen als
`.proto`-Dateien im Klartext im APK. REA konnte den APK nicht dekompilieren (JADX nicht eingerichtet), deshalb
wurde mit `dexdump` aus dem Android SDK disassembliert.

> Alles hier ist **statisch abgeleitet**, nichts an einem echten iGPSPORT-Gerät beobachtet.
> Es stand kein Gerät zur Verfügung. Die Abschnitte „Offen" müssen mit Gerät oder HCI-Mitschnitt geprüft werden.

## Geräte

BLE-Namen in der App (`BleConstants`): `BSC200`, `iGS10S_U`, `iGS320`, `iGS50S`, `iGS520`, `iGS520_U`, `iGS620`, `iGS630`.
Geräteklassen im SDK: IGS10S, IGS50S, IGS320, IGS520, IGS620, BB10 (Radar?), HR40, HR60 (Herzfrequenzgurte).

Es gibt **Generationen** (`IGPDevice.getGeneration()`, Werte 1, 2, 3):

| Generation | Befehle | Daten |
|---|---|---|
| 1 | FIT-codierte Befehle (`utils.Command`, Garmin FIT SDK `BufferEncoder`) | FIT |
| 2, 3 | Protobuf-Nachrichten mit 20-Byte-Kopf (siehe unten) | FIT-Datei als `file_content` |

## GATT

| Zweck | Service | Schreiben (RX des Geräts) | Benachrichtigung (TX des Geräts) |
|---|---|---|---|
| Hauptkanal (Daten) | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `6e400002-…ca9e` | `6e400003-…ca9e` |
| Steuerkanal | `6e400001-b5a3-f393-e0a9-e50e24dcca8e` | `…0002-…ca8e` | `…0003-…ca8e` |
| 3. Kanal | `6e400001-b5a3-f393-e0a9-e50e24dcca7e` | `…0002-…ca7e` | `…0003-…ca7e` |
| 4. Kanal | `6e400001-b5a3-f393-e0a9-e50e24dcca6e` | `…0002-…ca6e` | `…0003-…ca6e` |

(Nordic UART Service; die vier Kanäle erlauben parallele Übertragungen. Die App hat je Kanal eine eigene Warteschlange.)

Standarddienste: Geräteinfo `180A` (`2A29` Hersteller, `2A28` Software, `2A27` Hardware, `2A26` Firmware), Batterie `180F`/`2A19`,
Herzfrequenz `180D`/`2A37`, Geschwindigkeit/Trittfrequenz `1816`.

## Rahmen (Generation 2/3)

Jede Nachricht = **20-Byte-Kopf** + **Daten** (serialisierte Protobuf-Nachricht). Ein Rahmen kann auf mehrere BLE-Pakete verteilt sein.

**Kanalaufteilung (am BSC200 aus dem Verbindungsprotokoll und dem App-Code belegt):** Hat das Gerät den Steuerkanal (`…ca8e`),
gehen Daten und Kopf **getrennt**:

- App → Gerät: **zuerst die Nutzlast** auf den Datenkanal (`…ca9e/0002`, bei Bedarf in MTU-3-Stücke geteilt), **danach der 20-Byte-Kopf**
  (Typ 1) auf den Steuerkanal (`…ca8e/0002`). Umgekehrt (Kopf auf den Datenkanal) antwortet das Gerät **nicht**.
- Gerät → App: die Nutzlast als Benachrichtigungen auf dem Datenkanal (`…ca9e/0003`), danach ein Kopf (Typ 3, „Übertragung vollständig")
  auf dem Steuerkanal (`…ca8e/0003`). Typ-2-Köpfe auf dem Steuerkanal sind Bestätigungen des Geräts.
- Bestätigungen der App (Typ 2) gehen auf den Steuerkanal.

Ohne Steuerkanal (ältere Geräte) läuft alles als ein Strom über den Datenkanal: Kopf, dann Daten (so beschreibt es `ReceivedDeviceDataCommand`).

| Byte | Inhalt |
|---|---|
| 0 | Befehlstyp (beim Senden `0x01`) |
| 1 | Dienst (`service_type_index`, Cycling-Data = **6**) |
| 2 | zweiter Dienst, Standard `0xFF` |
| 3 | Dateimarke („sendFileTag"), Standard `0xFF` |
| 4 | Operation (z. B. `CYCLING_DATA_OPERATE_TYPE`) |
| 5 | zweite Operation, Standard `0xFF` |
| 6 | `0xFF` |
| 7–8 | Länge der Daten, **Big-Endian** uint16 |
| 9 | CRC8 der Daten |
| 10 | Endetyp (Protobuf = `1`) |
| 11–18 | reserviert, `0xFF` |
| 19 | CRC8 über Byte 0..18 |

**CRC8** = Dallas/Maxim (Polynom `0x31`, gespiegelt `0x8C`, Startwert 0). Die Tabelle im APK beginnt `00 5E BC E2 61 3F`.
Berechnung: `crc = tab[(crc xor b) and 0xFF]` je Byte.

**Empfang:** Die App sammelt Benachrichtigungen in einem Puffer. Sind ≥ 20 Byte da, ist `puffer[0..19]` der Kopf, die Länge
steht in Byte 7–8. Fertig, wenn `puffer.size == 20 + länge`.

## Fahrtenübertragung (Dienst 6, „Cycling Data")

Protobuf-Schema `cycling_data.proto` (proto2; Java-Paket `com.igpsport.blelib.bean`):

```
CYCLING_DATA_OPERATE_TYPE: LIST_GET=1 (App: Liste holen)  LIST_SEND=2 (Gerät: Liste)  FILE_GET=3 (App: Datei holen)
                           FILE_SEND=4 (Gerät: Datei, "4K")  FILE_DEL=5  ALL_DEL=6  AUTO_UPLOAD=7  LIST_NUM_GET=8
cycling_data_file_flag_message { uint32 timestamp=1; uint32 file_size=2; string user_id=3; string device_id=4 }
cycling_data_msg { service_type=1 (=6); operate_type=2; repeated file_flag_message=3; bytes file_content=4;
                   auto_upload=5; file_list_get_message list_msg=6 }
file_list_get_message { file_num=1; file_list_support_num_max=2; file_index_start=3; file_index_end=4 }
```

Ablauf (aus `IGPDeviceManager.readActivityList`, `readActivityFitFile`, `deleteActivityFitFile`):

1. App → `cycling_data_msg{operate=LIST_GET}`; Kopf: Byte 1 = 6, Byte 4 = 1.
2. Gerät → `LIST_SEND` mit je Fahrt einer `file_flag_message` (`timestamp`, `file_size`).
3. App → `FILE_GET` mit einer `file_flag_message{timestamp}`.
4. Gerät → `FILE_SEND` mit `file_content` (die FIT-Datei, in 4-KB-Blöcken je Nachricht, zusammenzusetzen).
5. App → `FILE_DEL` mit `timestamp`(s) bzw. `ALL_DEL`.

Das Gerät löscht Fahrten vermutlich nicht selbst; die App löscht ausdrücklich (`deleteActivityFitFile`, `deleteAllActivityFitFile`).
Das ist anders als beim BIKEVEE (dort verschwindet die Fahrt nach der Bestätigung).

**Generation 1:** Befehle sind **FIT-Nachrichten** (mit dem Garmin-FIT-Encoder erzeugt): Typ `1` = Fahrtenliste lesen,
`2` = FIT-Datei mit ID lesen, `4` = Fahrten mit IDs löschen. Das genaue Byte-Layout wurde nicht ausgelesen.

## Fahrtdaten = FIT

Die App dekodiert die empfangene Datei mit dem Garmin-FIT-SDK (`FitDecoder`, nativ). Aus den FIT-Nachrichten
(`file_id`, `record`, `session`, `activity`) entstehen Strecke, Zeit, Höhe, Puls usw. Dafür braucht RideVault einen eigenen
FIT-Parser (Kotlin). `FitUtil.convertFitTimeToString` deutet darauf hin, dass `timestamp` in FIT-Zeit
(Sekunden seit 1989-12-31 00:00 UTC) vorliegt – **nicht verifiziert**.

## Weitere Funktionen (für Geräte-Einstellungen)

Die App kennt u. a. `readDeviceName`/`writeDeviceName`, `readUnit`/`writeUnit`, `readRTC`, `readBackgroundLightSetting`/`write…`,
`readUserInformation`/`write…`, `readBattery`, `readFirmwareVersion`, `readLanguagePack`, WLAN-, Sensor-, Strecken-,
Karten- und Firmware-Funktionen. Anders als der BKV **liefert** iGPSPORT Lesebefehle, die Einstellungen lassen sich also
auslesen. Die zugehörigen Protobuf-Schemas (`config.proto` 16 KB, `device_information.proto`, `dev_status.proto`,
`user_config.proto`, …) liegen im APK.

## Bestätigung (`ConfirmCommand`, 20 Byte, aus dem Code belegt)

Die Bestätigung geht über den **Steuerkanal** (`…ca8e`, Schreib-Characteristic `…0002`):

| Byte | Inhalt |
|---|---|
| 0 | `0x02` (Bestätigung) |
| 1 | Dienst der bestätigten Nachricht (6) |
| 2 | `0xFF` |
| 3 | `0xFF` |
| 4 | Operation der bestätigten Nachricht (4 = `FILE_SEND`) |
| 5 | `0xFF` |
| 6 | `0xFF` |
| 7 | Status: **0 = in Ordnung**, 1 = Fehler (Gerät soll den 4-KB-Block erneut senden) |
| 8–18 | `0xFF` |
| 19 | CRC8 über Byte 0..18 |

Beim Empfang eines `FILE_SEND`-Blocks prüft die App: `CRC8(Daten) == Kopf[9]` und `Datenlänge == Kopf[7..8]`, dazu dass der `timestamp` der
`file_flag_message` zur angeforderten Datei passt. Bei Fehler wird mit Status 1 bestätigt („4K-Paket fehlerhaft, erneut holen"), sonst
wird `file_content` an den FIT-Puffer angehängt.

## Stand der Umsetzung in RideVault (`:driver:igpsport`)

Implementiert und mit Unit-Tests (gegen unabhängig berechnete Referenzwerte und ein simuliertes Gerät) abgesichert: CRC8, 20-Byte-Kopf,
Bestätigung, Protobuf-Nachrichten (`cycling_data_msg`), Nachrichten-Zusammensetzung, FIT-Parser (`:fit`) und der Ablauf
Liste → Datei holen (je Block bestätigen) → FIT → Fahrt. **Noch nicht an einem echten Gerät erprobt.**
Das Gerät wird beim Abruf nicht geleert (kein `FILE_DEL`).

## Offen / zu verifizieren

- Ob jeder 4-KB-Block bestätigt werden muss (die App bestätigt jeden gültigen Block; ob das Gerät sonst stehen bleibt, ist offen).
- Block- und Paketgrößen, MTU (die App fordert eine MTU an: `sendAfterRequestMtu`).
- Kopplung/Bonding (`requestBonding`, `removeBond`) und ob für den Fahrten-Abruf nötig.
- Einheit und Epoche von `timestamp`.
- Welche Geräte welche Generation haben (Zuordnung Name → Generation).
- Aufbau der Generation-1-Befehle und -Antworten.
- Verhalten ohne Bestätigung / bei Abbruch.
