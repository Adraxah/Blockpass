# Blockpass 3.1.0 – beide Webseiten in einem Plugin

**Eine JAR, ein eingebauter Webserver, ein TCP-Port.** Die Serverübersicht und die Whitelist inklusive Codeverwaltung und Modpack-Downloads laufen gemeinsam auf Port **8080**. Ein bereits individuell eingestellter Blockpass-Port wird beibehalten.

Version 3.1.0: „Spieler online“ zeigt aktuelle Spieler / gemessenen Höchststand statt maximaler Slots. Daneben steht „Gerade in der Welt“. Server-Performance und Laufzeit stehen unter den Terminen. Deutsche Texte und Fehlermeldungen verwenden echte Umlaute. Die Spieler-Aktualisierung pausiert standardmäßig täglich von 06:00 bis 16:00 Uhr; Zeitraum und Schalter sind im Übersichts-Admin einstellbar. Die bisherigen Animationen bleiben erhalten und berücksichtigen reduzierte Bewegung.

**Update von 3.0.x:** Server stoppen, bisherige Blockpass-JAR durch **Blockpass-3.1.0.jar** ersetzen, Datenordner behalten und Server starten. Die neuen Pauseneinstellungen werden auch bei bestehenden Übersichtsdaten automatisch mit 06:00–16:00 Uhr vorbelegt.

## Von Blockpass 2 und BlockOverview umsteigen

1. Minecraft-Server stoppen und die bisherigen Plugin-Daten sichern.
2. Die alten **Blockpass-2.0.0.jar** und **BlockOverview-1.0.0.jar** aus dem `plugins`-Ordner entfernen.
3. Die Ordner **plugins/Blockpass/** und **plugins/BlockOverview/** behalten. Insbesondere `data.json`, `codes.key`, `modpacks.json` und die Modpack-Dateien nicht löschen.
4. Nur die neue **Blockpass-3.1.0.jar** in `plugins` kopieren.
5. Server starten. Der bisherige Blockpass-Port bleibt aktiv; standardmäßig **8080**. Für die neue Version ist kein zweiter Web-Port nötig.

Das Plugin verwendet die bestehenden Codes, deren Verschlüsselungsschlüssel und die Modpacks direkt weiter. Bisherige Übersichtsinhalte werden beim ersten Start nach `plugins/Blockpass/overview/content.json` kopiert. Die ursprünglichen BlockOverview-Dateien bleiben erhalten. Die bisherige Blockpass-Konfiguration wird vor einem Import unter `plugins/Blockpass/migration-backup/config.before-3.yml` gesichert. Ein erneuter Start überschreibt keine bereits importierten und danach bearbeiteten Übersichtsinhalte.

Die Netzwerkeinstellungen des alten BlockOverview-Webservers, einschließlich Port 8082, werden nicht als zweiter Webserver übernommen. Für beide Seiten gelten jetzt ausschließlich die gemeinsamen `web`-Einstellungen in `plugins/Blockpass/config.yml`.

Falls die alte BlockOverview-JAR noch installiert ist, meldet Blockpass 3 dies im Serverlog und startet nicht. Entferne dann die alte JAR und starte neu; die Datenordner bleiben liegen.

## Adressen

Ersetze `SERVER-IP` durch deine Serveradresse und gegebenenfalls `8080` durch deinen vorhandenen Blockpass-Port.

| Bereich | Adresse |
|---|---|
| Serverübersicht | `http://SERVER-IP:8080/` |
| Whitelist und Downloads | `http://SERVER-IP:8080/whitelist/` |
| Übersicht verwalten | `http://SERVER-IP:8080/admin` |
| Codes und Modpacks verwalten | `http://SERVER-IP:8080/whitelist/admin` |

`/whitelist` leitet auf `/whitelist/` weiter. Der grüne **White liste dich jetzt**-Button öffnet diese interne Seite über denselben Port. Der blaue **Map**-Button verlinkt weiterhin `https://map.xn--nrnberger-q9a.lol/`.

Die Adminseiten verlinken aufeinander. Der öffentliche Admin-Link unten rechts ist entfernt; die Verwaltung bleibt direkt über ihre Adresse erreichbar. Die Übersichtsseite enthält keinen Untertitel „Dein Überblick. Unsere Welt.“, keinen „Unsere Welt“-Bereich und keine Spielregeln mehr. Die entsprechenden Eingabefelder sind ebenfalls entfernt, damit diese Bereiche auch nach dem Import alter Einstellungen nicht wieder erscheinen.

## Login

Bei einer neuen Installation: Benutzername **admin**, dasselbe von dir festgelegte Startpasswort wie bei deinem bisherigen Blockpass.

Bestehende Blockpass-Anmeldedaten aus `admin` bleiben erhalten. Ein bisher separat geänderter BlockOverview-Login wird nach `overview.admin` übernommen und gilt weiterhin für die Übersicht. Ohne diesen gesonderten Eintrag verwenden beide Verwaltungsbereiche die Anmeldedaten aus `admin`. Die Sitzungen sind getrennt und können gleichzeitig im selben Browser benutzt werden.

## Skin-Köpfe und Live-Daten

Die Spielerliste zeigt den Kopf des Spieler-Skins einschließlich Hut-/Helmebene statt Initialen. Das Plugin liest die UUID und die Skin-Texturadresse aus dem Profil des angemeldeten Minecraft-Spielers. Es lädt die Textur im Hintergrund vom offiziellen Minecraft-Texturserver, erstellt das Kopfbild und liefert es über dieselbe Webseite aus. Der Browser muss keinen fremden Avatar-Dienst aufrufen.

Während des Ladens oder bei einer nicht verfügbaren Textur erscheint ein lokaler Pixelkopf als Ersatz. Funktionierende Köpfe werden zwischengespeichert. Skin-Änderungen im Spielerprofil werden beim nächsten Snapshot erkannt. Öffentliche Bild-Endpunkte sind auf die angezeigte Spielerliste beschränkt. Während einer Aktualisierungspause bleiben die zu diesem Stand gehörenden Köpfe verfügbar. Bei ausgeblendeter Spielerliste sind die Bild-Endpunkte gesperrt.

Erhalten bleiben: Spielerzahl und gemessener Höchststand, Spielerliste, TPS-Messung, Minecraft-Version, Laufzeit seit Serverstart, Projektstart, Nether-/End-Countdowns, Ankündigungen, Wartungshinweis, kopierbare Serveradresse und optionaler Discord-Link. Zeiten gelten in **Europe/Berlin**. Nether-/End-Termine sind Ankündigungen; sie verändern keine Portale. Der Wartungshinweis sperrt keine Spieler aus.

## Spieler-Aktualisierung pausieren

Unter **/admin → Spieler-Aktualisierung**:

- **Aktualisierung täglich pausieren** schaltet die Pause ein oder aus.
- **Pause ab** und **Weiter ab** legen Beginn und Ende fest. Standard: **06:00–16:00 Uhr**, täglich in **Europe/Berlin** einschließlich Sommerzeit.
- Auch Zeiträume über Mitternacht funktionieren, zum Beispiel 22:00–08:00 Uhr. Beginn und Ende müssen unterschiedlich sein.

Während der Pause bleiben Spielerzahl, angezeigter Höchststand und Spielerliste einschließlich Skin-Köpfen auf dem letzten Stand. Die Webseite kennzeichnet den Zeitpunkt und das Ende der Pause. Neuladen oder ein anderer Browser erhält denselben eingefrorenen Stand. TPS, Laufzeit, Status und Termine laufen weiter. Ab dem Ende kommen wieder aktuelle Spielerdaten; eine bereits offene Webseite übernimmt Änderungen normalerweise innerhalb von zehn Sekunden.

Die Pause wird im Plugin umgesetzt, auch ohne geöffnete Webseite. Der tatsächliche Spieler-Höchststand wird währenddessen weiter erfasst und nach der Pause wieder angezeigt. Seine bisherige Zählweise bleibt unverändert: Er wird beim Start bzw. Neuladen des Moduls neu erfasst; es handelt sich nicht um ein dauerhaft gespeichertes Allzeitmaximum. Der gewünschte Anzeigetext lautet weiterhin „Höchststand seit Serverstart“.

Die Einstellungen bleiben in `plugins/Blockpass/overview/content.json` erhalten. Änderungen im Adminbereich benötigen keinen Neustart. Bei einem Server- oder Plugin-Neustart während der Pause wird einmal der erste verfügbare Minecraft-Stand aufgenommen und bis zum Ende der Pause beibehalten; der eingefrorene Spielerstand selbst wird nicht auf Festplatte gespeichert. Die Pause beeinflusst weder Minecraft-Spieler noch die Whitelist.

## Whitelist und Modpacks

Ein Administrator erstellt einen Einmalcode **ohne Spielernamen**. Der Spieler trägt seinen Minecraft-Java-Namen und den Code auf `/whitelist/` ein. Nach erfolgreicher Namensprüfung und Bestätigung durch Minecraft wird die Freischaltung angezeigt. Der Admin sieht anschließend, welcher Spieler den Code verwendet hat. Die bisherigen optional namensgebundenen Codes bleiben lesbar.

Die Modpack-Dateien liegen unverändert hier:

| Download | Ordner | Dateityp |
|---|---|---|
| Prism / ZIP | `plugins/Blockpass/modpacks/prism/` | `.zip` |
| CurseForge | `plugins/Blockpass/modpacks/curesforge/` | `.zip` |
| Modrinth | `plugins/Blockpass/modpacks/modrinth/` | `.mrpack` |

Die Schreibweise **curesforge** wird aus deinem bisherigen Plugin übernommen. Im Adminbereich `/whitelist/admin` Dateien neu einlesen, Datei und Versionsnamen wählen und den Download aktivieren. Der Download läuft unter `/whitelist/downloads/…` ebenfalls über den gemeinsamen Web-Port. Modpack-Dateien sind nicht in diesem Installationspaket enthalten.

## Gemeinsame Konfiguration

`plugins/Blockpass/config.yml`:

```yaml
web:
  bind-address: "0.0.0.0"
  port: 8080
  public-url: ""
```

Bei direktem Zugriff diesen TCP-Port beim Hoster bzw. in der Firewall freigeben. Bei einer Domain mit HTTPS-Reverse-Proxy alle Pfade unverändert an diesen einen Webserver weiterleiten; also auch `/whitelist/`, `/api/`, `/whitelist/api/` und `/heads/`. In `web.public-url` die gemeinsame öffentliche `https://`-Adresse ohne Unterpfad eintragen. Eine frühere Whitelist-Subdomain zeigt am Wurzelpfad jetzt ebenfalls die Übersicht, wenn sie weiter auf denselben Port zeigt; die Whitelist liegt unter `/whitelist/`.

Für öffentliche Admin-Anmeldungen HTTPS verwenden. Bestehende Blockpass-HTTPS-Einstellungen unter `web.https` gelten für den gesamten Webserver. Ein gültiges PKCS12-Zertifikat lässt sich wie bisher direkt im Plugin verwenden. `web.https.password-env` benennt die Umgebungsvariable für das Keystore-Passwort. Domain und Zertifikat werden nicht automatisch eingerichtet.

Nach Konfigurationsänderungen: **/blockpass reload**. Beide Seiten werden zusammen neu geladen und bestehende Websitzungen beendet. **/blockpass status** zeigt beide Adressen. **/blockoverview** ist als Befehlsalias enthalten. Standardmäßig ist die Berechtigung `blockpass.admin` für OPs aktiviert.

## Quellcode und eigene Änderungen

Den Ordner **source** als Maven-Projekt in IntelliJ öffnen und `mvn clean package` ausführen. Die fertige JAR liegt unter `source/target/Blockpass-3.1.0.jar`.

- Whitelist-Webseite: `source/src/main/resources/web/`
- Übersicht und deren Adminseite: `source/src/main/resources/overview-web/`
- Java-Code: `source/src/main/java/de/adraxah/`

Die Dateien beider Webseiten sind in der JAR enthalten. Nach Änderungen neu bauen, Server stoppen, JAR austauschen und Server starten. Für einen vollständigen Funktionstest ist das Plugin nötig; eine rein statische HTML-Vorschau besitzt kein Login-, Live-Daten- oder Whitelist-Backend.

Ein neues Admin-Passwort kann mit dem enthaltenen Terminalwerkzeug gehasht werden:

```powershell
java -cp .\Blockpass-3.1.0.jar de.adraxah.blockoverview.PasswordHash
```

Den ausgegebenen Prüfwert bei `admin.password-hash` und gegebenenfalls `overview.admin.password-hash` eintragen, anschließend neu laden. Das Passwort wird verdeckt abgefragt und nicht als Befehlsargument übergeben.

## Voraussetzungen und Prüfungen

Java-Plugin für klassische Paper-/Spigot-Server, kompiliert für Java 17 gegen die Spigot-API 1.20.4. Verwende die Java-Version, die deine Minecraft-Version benötigt. `online-mode=true` ist für die sichere Whitelist-Namensprüfung erforderlich. Kein Vanilla-, Forge-, Fabric-, Proxy- oder Folia-Plugin.

221 Backend-Prüfungen und 33 Browserprüfungen bestanden. Die Tests verwenden echte HTTP-Anfragen, Dateien und PNG-Verarbeitung sowie Chromium für die Browserabläufe. Minecraft-Spielerdaten, Skin-Downloads und die eigentliche Minecraft-Whitelist-Schnittstelle werden im Test simuliert. Ein Test auf einem tatsächlich laufenden Minecraft-Server steht noch aus. Testquellcode liegt unter `source/src/test/`; die Java-Testprogramme lassen sich in IntelliJ mit dem Testklassenpfad ausführen und sind keine automatisch erkannten JUnit-Tests. Die Browserprüfung verwendet Playwright und den enthaltenen `PreviewServer` mit Testdaten.

Schrift: Silkscreen, SIL Open Font License; Lizenzdateien liegen bei den jeweiligen Webressourcen.
