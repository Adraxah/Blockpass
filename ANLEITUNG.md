# Blockpass 2.0.0 – Webseite direkt im Minecraft-Plugin

Die Webseite, die Minecraft-Pixelschrift, die Zugangscodes und die Freischaltung stecken in einer JAR. Kein separates Webhosting, keine externe Datenbank, keine API-Schlüssel und kein ChatGPT-Login nötig.

## Installation / Umstieg von Blockpass 1

1. Minecraft-Server stoppen.
2. Alte `Blockpass-1.0.0.jar` aus dem `plugins`-Ordner entfernen und durch `Blockpass-2.0.0.jar` ersetzen. Nicht beide Versionen gleichzeitig installieren.
3. Server starten. Das Plugin erstellt `plugins/Blockpass/config.yml` und startet den Webserver standardmäßig auf TCP-Port **8080**. Alte Einstellungen werden um die neuen ergänzt; alte Cloud-Schlüssel werden nicht mehr verwendet und aus der aktiven Konfiguration entfernt.
4. Im Hoster-Panel einen zusätzlichen TCP-Port zuweisen und öffnen. Bei einem eigenen Heimserver außerdem diesen Port im Router an den Minecraft-Rechner weiterleiten. Falls der Hoster einen anderen Port vorgibt, ihn in `web.port` eintragen und `/blockpass reload` ausführen. Der Web-Port darf nicht der Minecraft-Spielport sein.
5. Webseite im Browser öffnen: **http://DEINE-SERVER-IP:8080**. Auf demselben Rechner geht auch **http://localhost:8080**. `DEINE-SERVER-IP` ist ein Platzhalter für deine echte Serveradresse.
6. Optional in der Konfiguration `server.name`, `server.address` und `web.public-url` setzen. Nach Änderungen `/blockpass reload` ausführen. Unter `server.address` gehört die Minecraft-Spieladresse, unter `web.public-url` die Browser-Adresse.
7. Im Spiel als OP `/blockpass code` eingeben oder unter `/admin` auf „Code erstellen“ klicken. Den ausgegebenen Code manuell an einen Spieler weitergeben.
8. Der Spieler trägt Namen und Code auf der Webseite ein. „Freigeschaltet“ erscheint erst nach der Whitelist-Änderung auf dem Server.

**Voraussetzungen:** Minecraft Java, Paper/Spigot, `online-mode=true`. Das Plugin aktiviert standardmäßig die Whitelist; vorhandene Whitelist-Einträge bleiben erhalten. Gegen die Spigot-API 1.20.4 mit Java-17-Bytecode kompiliert. Der Server selbst benötigt weiterhin die für seine Minecraft-Version erforderliche Java-Version. Keine Unterstützung für Folia, Offline-/Cracked-Server, Offline-Proxy-Backends oder Bedrock-/Floodgate-Identitäten.

Die alte extern gehostete Blockpass-Seite ist für Version 2 nicht mehr nötig. Dort erstellte Codes werden nicht automatisch übertragen. Erstelle neue Codes mit dem Plugin. Bereits im Minecraft-Server gespeicherte Whitelist-Einträge bleiben bestehen.

## Befehle

| Befehl | Funktion |
| --- | --- |
| `/blockpass code` | Einmalcode ohne Namensbindung erstellen, sieben Tage gültig |
| `/blockpass code Spielername` | Persönlichen Einmalcode für diesen Namen erstellen, sieben Tage gültig |
| `/blockpass code Spielername 30` | Persönlichen Code für 30 Tage erstellen (1–90 möglich) |
| `/blockpass code *` | Einmalcode ohne Namensbindung; jeder mit dem Code kann ihn einlösen |
| `/blockpass list` | Die letzten 15 Einträge mit ID, Name und Status anzeigen |
| `/blockpass revoke ID` | Unbenutzten Code widerrufen; die ersten acht eindeutigen ID-Zeichen reichen |
| `/blockpass retry ID` | Fehlgeschlagene Freischaltung erneut versuchen |
| `/blockpass status` | Webadresse und Whiteliststatus anzeigen |
| `/blockpass reload` | Webserver mit der aktuellen Konfiguration neu starten |
| `/blockpass cleanup` | Nicht offene Einträge entfernen, deren Code seit mehr als 90 Tagen abgelaufen ist |
| `/whitelist remove Spielername` | Bereits freigeschalteten Spieler von der Whitelist entfernen |

Berechtigung: `blockpass.admin`, standardmäßig OP. In der Serverkonsole die Befehle ohne führenden `/` eingeben. Zusätzlich gibt es eine passwortgeschützte Übersicht unter `/admin`. Codes können auf der Adminseite ohne Spielernamen erstellt werden (einmal verwendbar, sieben Tage gültig). Widerruf und erneuter Versuch erfolgen weiterhin über die Minecraft-Befehle.

Neu erstellte Codes werden bei ihrer Erstellung und in der geschützten Adminübersicht angezeigt. Bei einer Erstellung über die Konsole enthält deren Ausgabe den Code. Spieler benötigen ausschließlich diesen Einmalcode, niemals ihr Microsoft-/Minecraft-Passwort.

## Konfiguration

```yaml
web:
  bind-address: "0.0.0.0"
  port: 8080
  public-url: ""
  https:
    enabled: false
    keystore-file: "web.p12"
    password-env: "BLOCKPASS_KEYSTORE_PASSWORD"
server:
  name: "Nürnberger.lol"
  address: ""
enable-whitelist: true
```

- `0.0.0.0` erlaubt Verbindungen auf allen Netzwerkinterfaces. Für rein lokalen Zugriff oder einen lokalen Reverse-Proxy kann `127.0.0.1` genutzt werden.
- Die im Status ausgegebene Beispieladresse `SERVER-IP` wird erst durch einen Eintrag in `web.public-url` ersetzt; der Plugin-Webserver kann die öffentliche Adresse hinter einem Router nicht automatisch wissen.
- Die Webseite ist nur erreichbar, solange Minecraft mit dem Plugin läuft. Nach einem Neustart werden bereits angenommene offene Freischaltungen aus `data.json` weiterverarbeitet.
- Der Minecraft-Server braucht ausgehenden Internetzugriff zur offiziellen Minecraft-Namensprüfung. Die Spielerseite einschließlich Schrift und JavaScript lädt vollständig vom Plugin.

## Optional: HTTPS

Standardmäßig verwendet Port 8080 **HTTP**. Dabei werden Namen und Einmalcodes nicht transportverschlüsselt. Für öffentliche Nutzung kann das Plugin selbst HTTPS mit einem gültigen Zertifikat anbieten:

1. Zertifikat und privaten Schlüssel als PKCS#12-Keystore `web.p12` im Blockpass-Ordner bereitstellen.
2. Das Keystore-Passwort über die Server-Umgebungsvariable `BLOCKPASS_KEYSTORE_PASSWORD` setzen.
3. `web.https.enabled: true` und eine passende `https://`-Adresse in `web.public-url` setzen, anschließend neu laden.

Alternativ kann ein vorhandener HTTPS-Reverse-Proxy auf den lokalen Plugin-Port weiterleiten. Ein weiteres Hostingkonto ist dafür nicht Bestandteil dieser Lösung. Bei einem Proxy wird für die Ratenbegrenzung dessen direkte IP verwendet; mehrere Spieler hinter demselben Proxy teilen dieses Limit. Für kleine private Server ist der direkte Plugin-Port der einfachste Betrieb.

## Daten und Fehlerbehebung

- Codes werden zur Prüfung als Hash und für die Adminanzeige zusätzlich mit AES-256-GCM verschlüsselt in `plugins/Blockpass/data.json` gespeichert. Der lokale Schlüssel liegt in `plugins/Blockpass/codes.key`. Beide Dateien gemeinsam sichern, vor fremdem Zugriff schützen und nicht bei laufendem Server ändern. Wer beide Dateien besitzt, kann die Codes lesen. Alte Einträge ohne verschlüsselten Code bleiben gültig, ihr ursprünglicher Text kann aber nicht wiederhergestellt werden.
- Codes sind einmal verwendbar; parallele Anfragen verbrauchen denselben Code höchstens einmal. Eine wiederholte Anfrage mit derselben geheimen Anfrage-ID kann eine verlorene HTTP-Antwort wiederherstellen.
- Bei einem vorübergehenden Ausfall der Minecraft-Namensprüfung bleibt der Code unbenutzt. Eine bereits angenommene Anfrage bleibt bei einem Serverneustart erhalten.
- Wenn die Whitelist-Änderung bereits stattgefunden hat, aber der Prozess vor dem Speichern des Erfolgs endet, setzt der nächste Versuch denselben Whitelist-Eintrag erneut. Das ist ungefährlich und erzeugt keinen zweiten Spieler.
- Für die Netzwerkarbeit werden eigene Threads verwendet. Die eigentliche Whitelist-Änderung erfolgt im Minecraft-Hauptthread. Bans werden nicht aufgehoben; solche Fälle erscheinen als fehlgeschlagen und können nach Behebung über `/blockpass retry ID` wiederholt werden.
- **Seite nicht erreichbar:** `/blockpass status`, Serverlog, Hoster-Portzuweisung, Firewall und Router prüfen. Ein Spielport erlaubt nicht automatisch Zugriff auf Port 8080.
- **Port bereits belegt:** freien Port zuweisen, `web.port` ändern und neu laden.
- **Offline-Modus:** `online-mode=true` setzen und den Server neu starten. Hinter einem Proxy im Offline-Modus arbeitet diese Version nicht.
- **Zu viele Versuche:** nach acht Code-Versuchen je IP innerhalb von zehn Minuten warten. Gemeinsame IPs teilen dieses Limit.
- **Datenfehler:** Das Plugin überschreibt eine beschädigte `data.json` nicht. Server stoppen und eine geprüfte Sicherung wiederherstellen.
- Ein Minecraft-Einmalcode beweist nicht, dass der Browsernutzer dieses Minecraft-Konto besitzt. Der Online-Modus prüft den Kontobesitz beim tatsächlichen Beitritt.

## Quellcode / Nachbauen

`source/` enthält den vollständigen Maven-Quellcode, alle Webseiten-Dateien, die Schrift mit OFL-Lizenz und einen Integrationstest. Mit Java 17+ und Maven im Quellordner `mvn package` ausführen; Ergebnis: `target/Blockpass-2.0.0.jar`. Keine zusätzlichen Bibliotheks-Plugins nötig; Gson wird vom Minecraft-Server bereitgestellt.

Die Webseite ist eine statische HTML/CSS/JavaScript-Oberfläche im Ressourcenordner `src/main/resources/web`. Webseiten-Dateien kommen weiterhin aus der JAR. Für Modpack-Downloads werden ausschließlich ausdrücklich aktivierte Archive aus den unten genannten Ordnern ausgeliefert.

## Geprüft

95 erfolgreiche Prüfungen mit einem echten eingebetteten HTTP-Server, echten Dateischreibvorgängen und simulierten Minecraft-/Mojang-Aufrufen: Auslieferung der Seite/Schrift, gesperrte Dateipfade, Ursprungsprüfung, Namensbindung, Hash-Speicherung, doppelte und parallele Einlösung, Wiederherstellung verlorener Antworten, Neustarts, Bestätigungsstatus, Widerruf, Ablauf, Wiederholung nach Fehlern, Ratenlimit und beschädigte Daten. Zusätzlich geprüft: Login, falsche Passwörter, unberechtigter Zugriff, Sitzungs-Cookie, Abmeldung, CSRF, Ratenlimit, verschlüsselte Codes und Migration alter Datensätze. Die JAR wurde gegen die echte Spigot-API kompiliert. Der tatsächliche Beitritt auf deinem Minecraft-Server ist noch zu testen.

Offizielle Grundlagen:
- https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html
- https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/OfflinePlayer.html

## Angepasste Webseite

Kopfzeile: „Nürnberger Azubi 2026 Server“. Servername: „Nürnberger.lol“. Beim Start ersetzt diese JAR den bisherigen Standardnamen „Mein Minecraft-Server“ automatisch; individuell gesetzte Namen bleiben erhalten. Bei einem Update Server stoppen, vorhandene JAR ersetzen und neu starten. Anschließend die Webseite mit Strg+F5 neu laden.

## Web-Adminseite

- Adresse: `http://DEINE-SERVER-IP:8080/admin` (denselben Web-Port wie auf der Freischaltungsseite verwenden).
- Benutzername: `admin`. Das vom Serverinhaber vorgegebene Passwort ist eingerichtet; es wird nur als gesalzener PBKDF2-SHA256-Hash in `admin.password-hash` gespeichert, nicht im Browsercode.
- Die Tabelle zeigt alle gespeicherten Codes, Namensbindungen, einlösende Spieler, Verwendungsstatus und Ablaufdatum. Suche, Seitenwechsel und automatische Aktualisierung alle 15 Sekunden sind vorhanden.
- Neu erzeugte Codes bleiben auch nach Verwendung und Neustart sichtbar. Alte Codes erscheinen als „Alter Code: Text nicht verfügbar“ mit Name und Status; ihre bisherigen Hashes bleiben gültig.
- „Verwendet: Ja“ bedeutet, dass der Code eingelöst wurde. Ob die Whitelist bereits geschrieben wurde, zeigt der separate Status „Freigeschaltet“. Offene oder fehlgeschlagene Anfragen sind entsprechend markiert.
- Eine Sitzung gilt eine Stunde und endet außerdem bei Abmeldung oder Plugin-/Serverneustart. Fünf Loginversuche pro IP innerhalb von zehn Minuten; keine öffentliche Registrierung.
- Der Login verwendet ein HttpOnly-/SameSite-Cookie. Bei HTTPS zusätzlich Secure. HTTP verschlüsselt auch das Administratorpasswort nicht. Für Zugriff über das Internet HTTPS wie oben beschrieben aktivieren.
- Update: Server stoppen, vorhandene `Blockpass-2.0.0.jar` ersetzen, Server starten. `config.yml`, `data.json` und `codes.key` behalten. Fehlende Admin-Standardeinstellungen werden ergänzt. Webseite mit Strg+F5 neu laden.

### Codes im Browser erstellen

Unter `/admin` anmelden und auf „Code erstellen“ klicken. Dafür ist kein Spielername nötig. Der neue Code ist sieben Tage gültig und kann genau einmal eingelöst werden. Er wird direkt angezeigt, kann kopiert werden und erscheint in der Übersicht. Der Spieler gibt auf der Startseite seinen eigenen Minecraft-Namen und diesen Code ein; bei der Einlösung wird sein Name zum Code gespeichert. Danach ist der Code für weitere Spieler gesperrt. Bisherige Codes behalten ihren Zustand und gegebenenfalls ihre alte Namensbindung. Bei einer unterbrochenen Antwort zuerst die Übersicht prüfen, bevor ein weiterer Code erstellt wird. Erstellung erfordert eine gültige Admin-Sitzung und CSRF-Prüfung.

### Modpack-Auswahl

Auf der Startseite öffnet der grüne Button „Download Modpack“ die Auswahl Modrinth, Prism / ZIP und CurseForge. Die bereitgestellten Logos sind lokal eingebunden. Die Downloads werden im Admin-Dashboard konfiguriert.

Die Ordner werden beim ersten Start dieser JAR automatisch angelegt:

| Auswahl | Serverordner | Dateityp |
| --- | --- | --- |
| Prism / ZIP | `plugins/Blockpass/modpacks/prism/` | `.zip` |
| CurseForge | `plugins/Blockpass/modpacks/curesforge/` | `.zip` |
| Modrinth | `plugins/Blockpass/modpacks/modrinth/` | `.mrpack` |

Der Ordner `curesforge` ist entsprechend der gewünschten Schreibweise benannt.

1. Die passenden Modpack-Dateien direkt in den jeweiligen Ordner auf dem Minecraft-Server legen (z. B. über das Hoster-Dateipanel oder SFTP). Nicht entpacken. Mehrere Versionen dürfen als unterschiedliche Dateien im selben Ordner liegen; Unterordner werden nicht eingelesen.
2. Auf der Webseite `/admin` anmelden und unter „Modpack-Downloads“ auf „Ordner neu einlesen“ klicken.
3. Pro Format die gewünschte Datei auswählen, optional einen Versionsnamen eingeben und „Download aktivieren“ anhaken.
4. Bei diesem Format auf „Speichern“ klicken. Der Download gilt sofort ohne Serverneustart. Die öffentliche Auswahl aktualisiert sich beim Laden oder spätestens nach 15 Sekunden.
5. Zum Versionswechsel eine andere Datei auswählen und speichern. Zum Sperren den Haken entfernen und speichern; die Dateien werden nicht gelöscht.

Die Einstellungen liegen in `plugins/Blockpass/modpacks.json` und überstehen Neustarts. Diese Datei zusammen mit dem Modpack-Ordner sichern. Manuelle Änderungen daran nur bei gestopptem Server durchführen.

Nur das ausgewählte, aktivierte Archiv wird über `/downloads/prism`, `/downloads/curseforge` bzw. `/downloads/modrinth` öffentlich ausgeliefert. Andere Dateien, Unterordner, Verknüpfungen und Plugin-Einstellungen sind darüber nicht erreichbar. Fehlt die ausgewählte Datei oder ist sie leer, zeigt die Webseite den Download als nicht verfügbar an. Dateinamen dürfen Leerzeichen und Umlaute enthalten; versteckte Dateien und Pfadangaben sind nicht erlaubt.

Downloads laufen direkt über den Plugin-Web-Port und benötigen kein zusätzliches Hosting. Große Dateien werden mit einem kleinen Puffer aus der Datei übertragen. Maximal zwei parallele Downloads, jeweils höchstens 30 Minuten; bei ausgelasteten Downloadplätzen kurz warten und erneut versuchen. Unterbrochene Downloads werden vollständig neu gestartet. Dateien möglichst unter einem neuen Versionsnamen hochladen und erst nach vollständiger Übertragung auswählen, statt aktive Archive während eines Downloads zu überschreiben.

Die Archive sind noch nicht enthalten. Du kannst sie selbst in die passenden Serverordner legen und im Dashboard freigeben. Es gibt keinen Datei-Upload im Browser; das Dashboard wählt Dateien aus, die bereits auf dem Server liegen.

Die zusätzlichen 35 HTTP-/Dateisystemprüfungen decken Dateiauswahl, Speicherung, Versionswechsel, exakte Download-Bytes, Umlaute, HEAD-Anfragen, gesperrte Pfade, fehlende Dateien, deaktivierte Downloads, Sitzungsprüfung und CSRF ab.

