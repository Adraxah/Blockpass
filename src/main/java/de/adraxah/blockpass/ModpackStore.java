package de.adraxah.blockpass;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Only explicit, administrator-selected archives in the three dedicated directories are public. */
public final class ModpackStore {
    public record Format(String id,String label,String folder,String extension) {}
    public static final List<Format> FORMATS=List.of(new Format("modrinth","Modrinth","modrinth",".mrpack"),new Format("prism","Prism / ZIP","prism",".zip"),new Format("curseforge","CurseForge","curesforge",".zip"));
    public record Selection(String filename,String version,boolean enabled) {}
    public record FileOption(String filename,long size) {}
    public record AdminPack(String id,String label,String folder,String extension,Selection selection,boolean available,List<FileOption> files) {}
    public record PublicPack(String id,String label,boolean available,String version,String filename,long size,String url) {}
    public record Download(String filename,long size,FileChannel channel) implements AutoCloseable{public void close()throws IOException{channel.close();}}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private record Snapshot(int version,Map<String,Selection> selections) {}
    private final Path root,settings;
    private Map<String,Selection> selections=new LinkedHashMap<>();

    public ModpackStore(Path dataFolder)throws IOException{
        Files.createDirectories(dataFolder);Path base=dataFolder.toRealPath();root=base.resolve("modpacks");settings=base.resolve("modpacks.json");
        makeDirectory(root);for(Format f:FORMATS)makeDirectory(root.resolve(f.folder()));
        for(Format f:FORMATS)selections.put(f.id(),new Selection("","",false));
        if(Files.exists(settings,LinkOption.NOFOLLOW_LINKS)){
            if(Files.isSymbolicLink(settings)||Files.size(settings)>32768)throw new IOException("modpacks.json ist ungültig.");
            try{Snapshot snapshot=JSON.fromJson(Files.readString(settings),Snapshot.class);
                if(snapshot==null||snapshot.version()!=1||snapshot.selections()==null)throw new IllegalArgumentException();
                for(var item:snapshot.selections().entrySet()){
                    Format f=format(item.getKey());Selection s=item.getValue();
                    if(s==null||s.filename()==null||s.version()==null||!validVersion(s.version())||(!s.filename().isEmpty()&&!validName(f,s.filename()))||(s.enabled()&&s.filename().isEmpty()))throw new IllegalArgumentException();
                    selections.put(f.id(),s);
                }
            }catch(RuntimeException|AccessStore.Rejected e){throw new IOException("modpacks.json ist ungültig. Keine Einstellungen wurden überschrieben.",e);}
        }
    }
    private static void makeDirectory(Path path)throws IOException{
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Modpack-Ordner darf keine Verknüpfung sein: "+path.getFileName());
    }
    private Path directory(Format f)throws IOException{
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.isDirectory(root.resolve(f.folder()),LinkOption.NOFOLLOW_LINKS))throw new IOException("Modpack-Ordner fehlt oder ist eine Verknüpfung.");
        return root.resolve(f.folder());
    }
    private static Format format(String id)throws AccessStore.Rejected{return FORMATS.stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new AccessStore.Rejected(404,"Modpack-Format nicht gefunden."));}
    private static boolean validVersion(String value){return value.length()<=60&&value.codePoints().noneMatch(Character::isISOControl);}
    private static boolean validName(Format f,String name){return !name.isEmpty()&&name.length()<=200&&!name.startsWith(".")&&!name.contains("/")&&!name.contains("\\")&&!name.contains(":")&&name.codePoints().noneMatch(Character::isISOControl)&&name.toLowerCase(Locale.ROOT).endsWith(f.extension());}
    private Path resolve(Format f,String name)throws IOException,AccessStore.Rejected{
        if(!validName(f,name))throw new AccessStore.Rejected(400,"Ungültiger Dateiname oder falsches Dateiformat.");
        Path path=directory(f).resolve(name);BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.size()==0)throw new AccessStore.Rejected(400,"Datei fehlt, ist leer oder ist eine Verknüpfung.");return path;
    }
    private List<FileOption> files(Format f)throws IOException{
        List<FileOption> result=new ArrayList<>();int scanned=0;
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(directory(f))){for(Path path:paths){
            if(++scanned>10000)throw new IOException("Zu viele Dateien im Modpack-Ordner.");
            String name=path.getFileName().toString();if(!validName(f,name))continue;
            try{BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.isRegularFile()&&a.size()>0)result.add(new FileOption(name,a.size()));}catch(NoSuchFileException ignored){}
        }}result.sort(Comparator.comparing(FileOption::filename,String.CASE_INSENSITIVE_ORDER));return result;
    }
    public synchronized List<AdminPack> adminPacks()throws IOException{
        List<AdminPack> result=new ArrayList<>();for(Format f:FORMATS){List<FileOption> files=files(f);Selection s=selections.get(f.id());boolean available=s.enabled()&&files.stream().anyMatch(o->o.filename().equals(s.filename()));result.add(new AdminPack(f.id(),f.label(),"plugins/Blockpass/modpacks/"+f.folder()+"/",f.extension(),s,available,files));}return result;
    }
    public synchronized List<PublicPack> publicPacks(){
        List<PublicPack> result=new ArrayList<>();for(Format f:FORMATS){Selection s=selections.get(f.id());long size=0;
            if(s.enabled())try{size=Files.size(resolve(f,s.filename()));}catch(IOException|AccessStore.Rejected ignored){}
            boolean available=size>0;result.add(new PublicPack(f.id(),f.label(),available,available?s.version():"",available?s.filename():"",size,available?"/downloads/"+f.id():""));
        }return result;
    }
    public synchronized void select(String id,String filename,String version,boolean enabled)throws IOException,AccessStore.Rejected{
        Format f=format(id);if(!validVersion(version)||(!filename.isEmpty()&&!validName(f,filename)))throw new AccessStore.Rejected(400,"Dateiname oder Versionsbezeichnung ist ungültig.");
        if(enabled)try{resolve(f,filename);}catch(IOException e){throw new AccessStore.Rejected(400,"Datei nicht gefunden. Bitte Ordner neu einlesen.");}
        var next=new LinkedHashMap<>(selections);next.put(id,new Selection(filename,version,enabled));
        Path temp=Files.createTempFile(settings.getParent(),"blockpass-modpacks-",".tmp");
        try{try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            byte[] bytes=JSON.toJson(new Snapshot(1,next)).getBytes(StandardCharsets.UTF_8);
            try(FileChannel c=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())c.write(buffer);c.force(true);}
            Files.move(temp,settings,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);selections=next;
        }finally{Files.deleteIfExists(temp);}
    }
    public synchronized Download open(String id)throws IOException,AccessStore.Rejected{
        Format f=format(id);Selection s=selections.get(id);if(!s.enabled())throw new AccessStore.Rejected(404,"Dieser Download ist noch nicht verfügbar.");
        try{Path path=resolve(f,s.filename());FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);try{return new Download(s.filename(),channel.size(),channel);}catch(IOException|RuntimeException e){channel.close();throw e;}}
        catch(IOException|AccessStore.Rejected e){throw new AccessStore.Rejected(404,"Modpack-Datei nicht verfügbar. Bitte das Server-Team kontaktieren.");}
    }
}
