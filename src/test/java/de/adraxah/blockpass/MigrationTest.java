package de.adraxah.blockpass;

import com.google.gson.*;
import de.adraxah.blockoverview.ContentStore;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;

public final class MigrationTest {
    private static int passed;
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); passed++;System.out.println("PASS "+message); }
    public static void main(String[] args) throws Exception {
        Path plugins=Files.createTempDirectory("combined-migration-"),base=plugins.resolve("Blockpass"),old=plugins.resolve("BlockOverview");Files.createDirectories(base);Files.createDirectories(old);
        String previous="web:\n  port: 8090\nadmin:\n  username: owner\n  password-hash: original-blockpass-hash\n";Files.writeString(base.resolve("config.yml"),previous);
        AccessStore codes=new AccessStore(base.resolve("data.json"));var code=codes.issue(null,7);
        byte[] data=Files.readAllBytes(base.resolve("data.json")),key=Files.readAllBytes(base.resolve("codes.key"));
        ModpackStore packs=new ModpackStore(base);Files.writeString(base.resolve("modpacks/modrinth/server.mrpack"),"archive");packs.select("modrinth","server.mrpack","1.0",true);
        byte[] selection=Files.readAllBytes(base.resolve("modpacks.json"));
        ContentStore before=new ContentStore(old.resolve("content.json"));
        JsonObject legacy=new Gson().toJsonTree(before.get()).getAsJsonObject(),settings=legacy.getAsJsonObject("settings");
        settings.addProperty("title","Bestehende Welt");settings.addProperty("projectStart","2026-10-15T12:00");settings.addProperty("subtitle","Dein Überblick. Unsere Welt.");settings.addProperty("about","Alter Beschreibungstext");settings.add("rules",new Gson().toJsonTree(List.of("Alte Regel")));
        Files.writeString(old.resolve("content.json"),legacy.toString());
        String oldConfig="web:\n  port: 8092\n  bind: 127.0.0.1\n  public-url: https://overview.example.test\n  https:\n    enabled: true\n    keystore-file: custom.p12\n    password-env: OLD_TLS_PASSWORD\nadmin:\n  username: overview-owner\n  password-hash: original-overview-hash\n";
        Files.writeString(old.resolve("config.yml"),oldConfig);Files.writeString(old.resolve("custom.p12"),"certificate");
        check(LegacyMigration.run(base),"legacy overview imported");
        YamlConfiguration config=YamlConfiguration.loadConfiguration(base.resolve("config.yml").toFile());
        check(config.getInt("web.port")==8090 && !config.contains("overview.web.port"),"one shared custom web port; old second listener not imported");
        check(config.getString("admin.password-hash").equals("original-blockpass-hash") && config.getString("overview.admin.password-hash").equals("original-overview-hash"),"both existing admin credentials preserved independently");
        check(!config.contains("overview.web"),"no obsolete second webserver configuration imported");
        check(Files.readString(old.resolve("custom.p12")).equals("certificate"),"old certificate retained unchanged in original folder");
        check(Arrays.equals(data,Files.readAllBytes(base.resolve("data.json"))) && Arrays.equals(key,Files.readAllBytes(base.resolve("codes.key"))),"invitation database and encryption key untouched");
        check(new AccessStore(base.resolve("data.json")).adminRows().stream().anyMatch(r->r.code().equals(code.code())),"old invitation remains decryptable after merge");
        check(Arrays.equals(selection,Files.readAllBytes(base.resolve("modpacks.json"))) && new ModpackStore(base).publicPacks().stream().anyMatch(p->p.id().equals("modrinth") && p.available()),"selected modpacks still work from original folder");
        check(Files.readString(base.resolve("migration-backup/config.before-3.yml")).equals(previous),"pre-import config backed up");
        check(Files.readString(old.resolve("content.json")).equals(legacy.toString()) && Files.readString(old.resolve("config.yml")).equals(oldConfig),"original overview files retained unchanged");
        ContentStore imported=new ContentStore(base.resolve("overview/content.json"));
        check(imported.get().settings().title().equals("Bestehende Welt") && imported.get().settings().projectStart().equals("2026-10-15T12:00"),"existing title and schedule survive migration");
        String published=new Gson().toJson(imported.get().settings());
        check(!published.contains("subtitle") && !published.contains("about") && !published.contains("rules"),"removed sections absent even for imported legacy content");
        JsonObject updated=new Gson().toJsonTree(imported.get().settings()).getAsJsonObject();updated.addProperty("title","Nach Import bearbeitet");imported.update(imported.get().revision(),updated);
        check(!LegacyMigration.run(base) && new ContentStore(base.resolve("overview/content.json")).get().settings().title().equals("Nach Import bearbeitet"),"second startup never overwrites newer content");
        Path fresh=Files.createTempDirectory("no-legacy-").resolve("Blockpass");check(!LegacyMigration.run(fresh),"fresh install needs no old plugin");
        Path broken=Files.createTempDirectory("broken-legacy-");Files.createDirectories(broken.resolve("BlockOverview"));Files.writeString(broken.resolve("BlockOverview/content.json"),"broken");boolean failed=false;
        try{LegacyMigration.run(broken.resolve("Blockpass"));}catch(java.io.IOException e){failed=true;}
        check(failed && !Files.exists(broken.resolve("Blockpass/overview/content.json")) && Files.readString(broken.resolve("BlockOverview/content.json")).equals("broken"),"corrupt legacy data preserved and not silently imported");
        System.out.println("RESULT: "+passed+" migration checks passed.");
    }
}
