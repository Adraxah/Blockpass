package de.adraxah.blockpass;

import de.adraxah.blockoverview.ContentStore;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Non-destructive import. Blockpass invitations, encryption key and modpacks stay in place. */
public final class LegacyMigration {
    public static boolean run(Path blockpass) throws Exception {
        blockpass=blockpass.toAbsolutePath();
        Path old=blockpass.resolveSibling("BlockOverview"), target=blockpass.resolve("overview");
        if(!Files.isDirectory(old))return false;
        if(Files.isSymbolicLink(old) || Files.isSymbolicLink(target))throw new IOException("Migrationsordner darf kein symbolischer Link sein.");
        Path oldContent=old.resolve("content.json"), newContent=target.resolve("content.json");
        if(Files.exists(oldContent) && !Files.exists(newContent))new ContentStore(oldContent); // validate without changing the original
        Path configFile=blockpass.resolve("config.yml");
        YamlConfiguration config=new YamlConfiguration();
        if(Files.exists(configFile))config.load(configFile.toFile());
        boolean importConfig=!config.contains("overview"), changed=false;
        YamlConfiguration previous=new YamlConfiguration();
        Path oldConfig=old.resolve("config.yml"); if(Files.exists(oldConfig))previous.load(oldConfig.toFile());
        Files.createDirectories(target);
        if(Files.exists(oldContent) && !Files.exists(newContent)) { copyOnce(oldContent,newContent); changed=true; }
        if(importConfig) {
            for(String key:List.of("username","password-hash")) {
                Object value=previous.get("admin."+key); if(value!=null)config.set("overview.admin."+key,value);
            }
            config.set("overview.imported-from-blockoverview",true);
            if(Files.exists(configFile))copyOnce(configFile,blockpass.resolve("migration-backup/config.before-3.yml"));
            write(configFile,config.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8),true); changed=true;
        }
        return changed;
    }
    private static void copyOnce(Path from,Path to) throws IOException {
        if(Files.exists(to))return;
        if(Files.isSymbolicLink(from))throw new IOException("Migrationsdatei darf kein symbolischer Link sein.");
        write(to,Files.readAllBytes(from),false);
    }
    private static void write(Path to,byte[] data,boolean replace) throws IOException {
        Files.createDirectories(to.getParent()); Path temp=Files.createTempFile(to.getParent(),".import-",".tmp");
        try {
            try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            Files.write(temp,data);
            // Never overwrite pre-existing destination content or certificate files.
            if(!replace)Files.move(temp,to);
            else try{Files.move(temp,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,to,StandardCopyOption.REPLACE_EXISTING);}
        } finally { Files.deleteIfExists(temp); }
    }
}
