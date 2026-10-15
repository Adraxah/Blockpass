package de.adraxah.blockoverview;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/** Validated immutable content, published only after the atomic save succeeds. */
public final class ContentStore {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public record Settings(String brand, String title, String address,
        String mapUrl, String whitelistUrl, String discordUrl,
        String projectStart, String netherOpen, String endOpen, String announcementTitle,
        String announcementText, String announcementKind, boolean maintenance,
        boolean showPlayers, boolean showTps, boolean showUptime, boolean showTimeline) {}
    public record Document(long revision, Settings settings) {}
    private final Path file;
    private volatile Document current;

    public ContentStore(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        Files.createDirectories(this.file.getParent());
        if (Files.isSymbolicLink(this.file)) throw new IOException("content.json darf kein symbolischer Link sein.");
        if (Files.exists(this.file)) {
            try {
                if (Files.size(this.file) > 65536) throw new IOException("content.json ist zu groß.");
                JsonObject root = JsonParser.parseString(Files.readString(this.file)).getAsJsonObject();
                long revision = root.get("revision").getAsLong();
                if (revision < 1) throw new IllegalArgumentException();
                current = new Document(revision, validate(root.getAsJsonObject("settings")));
            } catch (Exception e) { throw new IOException("content.json ist ungültig; Original wurde nicht verändert.", e); }
        } else {
            current = new Document(1, defaults());
            persist(current);
        }
    }
    public Document get() { return current; }
    public synchronized Document update(long revision, JsonObject settings) throws HttpError, IOException {
        if (revision != current.revision()) throw new HttpError(409, "Inzwischen wurden Änderungen gespeichert. Bitte aktuellen Stand laden und erneut bearbeiten.");
        Document next = new Document(current.revision() + 1, validate(settings));
        persist(next); current = next; return next;
    }
    private static Settings defaults() {
        return new Settings("Nürnberger.lol", "Azubi 2026 Server", "",
            "https://map.xn--nrnberger-q9a.lol/", "/whitelist/", "",
            "", "", "", "", "", "info", false, true, true, true, true);
    }
    private static String string(JsonObject o, String key, int max, boolean required) throws HttpError {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new HttpError(400, "Feld fehlt: " + key);
        String value = e.getAsString().trim();
        if (value.length() > max || (required && value.isEmpty()) || value.indexOf('\0') >= 0) throw new HttpError(400, "Ungültige Eingabe: " + key);
        return value;
    }
    private static boolean bool(JsonObject o, String key) throws HttpError {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new HttpError(400, "Schalter fehlt: " + key);
        return e.getAsBoolean();
    }
    private static String url(JsonObject o, String key, boolean required) throws HttpError {
        String s = string(o, key, 500, required);
        if (s.isEmpty()) return s;
        try {
            URI u = URI.create(s);
            if (!Set.of("https", "http").contains(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null) throw new IllegalArgumentException();
        } catch (Exception e) { throw new HttpError(400, "Bitte eine vollständige http(s)-Adresse eintragen: " + key); }
        return s;
    }
    public static Long epoch(String value) throws HttpError {
        if (value.isEmpty()) return null;
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")) throw new IllegalArgumentException();
            LocalDateTime dt = LocalDateTime.parse(value);
            if (dt.getYear() < 2000 || dt.getYear() > 2199 || ZONE.getRules().getValidOffsets(dt).isEmpty()) throw new IllegalArgumentException();
            return dt.atZone(ZONE).toInstant().toEpochMilli();
        } catch (Exception e) { throw new HttpError(400, "Datum oder Uhrzeit ungültig (Zeitzone Europe/Berlin; Zeitumstellung beachten)."); }
    }
    private static String date(JsonObject o, String key) throws HttpError { String s = string(o, key, 16, false); epoch(s); return s; }
    public static Settings validate(JsonObject o) throws HttpError {
        if (o == null) throw new HttpError(400, "Einstellungen fehlen.");
        String kind = string(o, "announcementKind", 10, true);
        if (!Set.of("info", "event", "warning").contains(kind)) throw new HttpError(400, "Unbekannter Ankündigungstyp.");
        String start = date(o, "projectStart"), nether = date(o, "netherOpen"), end = date(o, "endOpen");
        Long a = epoch(start), b = epoch(nether), c = epoch(end);
        if (a != null && ((b != null && b < a) || (c != null && c < a))) throw new HttpError(400, "Nether und End dürfen nicht vor dem Projektstart liegen.");
        return new Settings(string(o,"brand",60,true), string(o,"title",80,true), string(o,"address",120,false),
            url(o,"mapUrl",true), "/whitelist/", url(o,"discordUrl",false), start, nether, end,
            string(o,"announcementTitle",100,false), string(o,"announcementText",1200,false), kind,
            bool(o,"maintenance"), bool(o,"showPlayers"), bool(o,"showTps"), bool(o,"showUptime"), bool(o,"showTimeline"));
    }
    private void persist(Document document) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".content-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
            byte[] bytes = JSON.toJson(document).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
