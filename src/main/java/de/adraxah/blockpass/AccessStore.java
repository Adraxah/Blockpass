package de.adraxah.blockpass;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Immutable records and atomic, forced writes: a code is spent before a job is applied. */
public final class AccessStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public record Entry(String id, String codeHash, String boundName, long created, long expires,
            String status, String username, String uuid, String receiptHash, String error, String sealedCode) {}
    public record Issued(String id, String code, String username, long expires) {}
    public record Claim(String receipt, String username, String status) {}
    public static final class Rejected extends Exception {
        public final int httpStatus;
        public Rejected(int status, String message) { super(message); httpStatus=status; }
    }
    private static final class Snapshot {
        int version=2;
        List<Entry> entries;
        Snapshot(Collection<Entry> entries) { this.entries=new ArrayList<>(entries); }
    }
    private final Path file;
    private final SecretKeySpec codeKey;
    private LinkedHashMap<String,Entry> entries = new LinkedHashMap<>();
    public AccessStore(Path file) throws IOException {
        this.file=file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (Files.exists(file)) {
        if (Files.size(file)>16*1024*1024) throw new IOException("data.json ist zu gross. Datei prüfen.");
        try {
            Snapshot data=JSON.fromJson(Files.readString(file),Snapshot.class);
            if(data==null||data.version!=2||data.entries==null)throw new IllegalArgumentException();
            Set<String> hashes=new HashSet<>(),receipts=new HashSet<>();
            for(Entry e:data.entries){
                if(e==null||e.id()==null||!e.id().matches("[a-f0-9]{16}")||e.codeHash()==null||!e.codeHash().matches("[a-f0-9]{64}")
                    ||e.status()==null||!Set.of("unused","pending","applied","failed","revoked").contains(e.status())
                    ||e.created()<0||e.expires()<e.created()||entries.containsKey(e.id())||!hashes.add(e.codeHash())
                    ||(e.boundName()!=null&&!validName(e.boundName())))throw new IllegalArgumentException();
                if(Set.of("pending","applied","failed").contains(e.status())){
                    if(!validName(e.username())||e.uuid()==null||e.receiptHash()==null||!e.receiptHash().matches("[a-f0-9]{64}")||!receipts.add(e.receiptHash()))throw new IllegalArgumentException();
                    UUID.fromString(e.uuid());
                }
                entries.put(e.id(),e);
            }
        }catch(RuntimeException e){throw new IOException("data.json ist ungültig. Keine Daten wurden überschrieben.",e);}
        }
        Path keyFile=file.resolveSibling("codes.key");
        if(!Files.exists(keyFile)){
            if(entries.values().stream().anyMatch(e->e.sealedCode()!=null))throw new IOException("codes.key fehlt. Datensicherung wiederherstellen.");
            byte[] key=new byte[32];RANDOM.nextBytes(key);
            try{Files.createFile(keyFile,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
            catch(UnsupportedOperationException e){Files.createFile(keyFile);}
            Files.write(keyFile,key,StandardOpenOption.WRITE);
        }
        byte[] key=Files.readAllBytes(keyFile);if(key.length!=32)throw new IOException("codes.key ist ungültig.");
        codeKey=new SecretKeySpec(key,"AES");
        for(Entry e:entries.values())if(e.sealedCode()!=null)openCode(e);
    }
    private String sealCode(String code,String id)throws IOException{
        try{byte[] iv=new byte[12];RANDOM.nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,codeKey,new GCMParameterSpec(128,iv));c.updateAAD(id.getBytes(StandardCharsets.UTF_8));byte[] encrypted=c.doFinal(code.getBytes(StandardCharsets.UTF_8));byte[] packed=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(packed);}
        catch(GeneralSecurityException e){throw new IOException("Code konnte nicht verschlüsselt werden.",e);}
    }
    private String openCode(Entry e)throws IOException{
        if(e.sealedCode()==null)return null;
        try{byte[] packed=Base64.getDecoder().decode(e.sealedCode());if(packed.length<29)throw new IllegalArgumentException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,codeKey,new GCMParameterSpec(128,packed,0,12));c.updateAAD(e.id().getBytes(StandardCharsets.UTF_8));String code=new String(c.doFinal(packed,12,packed.length-12),StandardCharsets.UTF_8);if(!hash(normalize(code)).equals(e.codeHash()))throw new IllegalArgumentException();return code;}
        catch(GeneralSecurityException|IllegalArgumentException ex){throw new IOException("Gespeicherter Code konnte nicht entschlüsselt werden. codes.key und data.json prüfen.",ex);}
    }
    public record AdminRow(String id,String code,String boundName,String username,String status,boolean used,long created,long expires){}
    public synchronized List<AdminRow> adminRows()throws IOException{
        List<AdminRow> rows=new ArrayList<>();long now=System.currentTimeMillis()/1000;
        for(Entry e:entries.values())rows.add(new AdminRow(e.id(),openCode(e),e.boundName(),e.username(),e.status().equals("unused")&&e.expires()<=now?"expired":e.status(),Set.of("pending","applied","failed").contains(e.status()),e.created(),e.expires()));
        Collections.reverse(rows);return rows;
    }
    public static boolean validName(String s){return s!=null&&s.matches("[A-Za-z0-9_]{3,16}");}
    public static String normalize(String code){return code==null?"":code.replaceAll("[\\s-]","").toUpperCase(Locale.ROOT);}
    public static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String token(int bytes){byte[] b=new byte[bytes];RANDOM.nextBytes(b);return HexFormat.of().formatHex(b);}
    private void commit(LinkedHashMap<String,Entry> next) throws IOException {
        byte[] bytes=JSON.toJson(new Snapshot(next.values())).getBytes(StandardCharsets.UTF_8);
        Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"blockpass-",".tmp");
        try {
            try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            // Fail closed if the filesystem cannot safely replace the complete snapshot.
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            entries=next;
        }finally{Files.deleteIfExists(temp);}
    }
    private LinkedHashMap<String,Entry> copy(){return new LinkedHashMap<>(entries);}
    private void put(Entry e)throws IOException{var next=copy();next.put(e.id(),e);commit(next);}
    public synchronized Issued issue(String name,int days)throws IOException,Rejected{
        if(name!=null&&!validName(name))throw new Rejected(400,"Minecraft-Name: 3 bis 16 Buchstaben, Ziffern oder Unterstriche.");
        if(days<1||days>90)throw new Rejected(400,"Gültigkeit: 1 bis 90 Tage.");
        if(entries.size()>=10000)throw new Rejected(503,"10.000 Einträge erreicht. Bitte /blockpass cleanup ausführen.");
        StringBuilder raw=new StringBuilder();for(int i=0;i<20;i++)raw.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        String code=raw.substring(0,5)+"-"+raw.substring(5,10)+"-"+raw.substring(10,15)+"-"+raw.substring(15);
        String id=token(8);long now=System.currentTimeMillis()/1000,expires=now+days*86400L;
        put(new Entry(id,hash(raw.toString()),name==null?null:name.toLowerCase(Locale.ROOT),now,expires,"unused",null,null,null,null,sealCode(code,id)));
        return new Issued(id,code,name,expires);
    }
    private Entry eligible(String code,String name)throws Rejected{
        if(!validName(name)||!normalize(code).matches("[A-Z2-9]{20}"))throw invalid();
        String digest=hash(normalize(code));long now=System.currentTimeMillis()/1000;
        for(Entry e:entries.values())if(e.codeHash().equals(digest)&&e.status().equals("unused")&&e.expires()>now&&(e.boundName()==null||e.boundName().equalsIgnoreCase(name)))return e;
        throw invalid();
    }
    private static Rejected invalid(){return new Rejected(400,"Code ungültig, abgelaufen, bereits benutzt oder für einen anderen Spielernamen.");}
    public synchronized void check(String code,String name)throws Rejected{eligible(code,name);}
    public synchronized Claim resume(String code,String name,String receipt)throws Rejected{
        if(receipt==null||!receipt.matches("[a-f0-9]{48}"))throw new Rejected(400,"Ungültige Anfrage-ID.");
        String digest=hash(normalize(code)),receiptDigest=hash(receipt);
        for(Entry e:entries.values())if(receiptDigest.equals(e.receiptHash())){
            if(e.codeHash().equals(digest)&&e.username().equalsIgnoreCase(name))return new Claim(receipt,e.username(),e.status());
            throw new Rejected(409,"Anfrage-ID bereits vergeben.");
        }
        return null;
    }
    public synchronized Claim claim(String code,String name,UUID uuid,String receipt)throws IOException,Rejected{
        Claim previous=resume(code,name,receipt);if(previous!=null)return previous;
        Entry e=eligible(code,name);
        put(new Entry(e.id(),e.codeHash(),e.boundName(),e.created(),e.expires(),"pending",name,uuid.toString(),hash(receipt),null,e.sealedCode()));
        return new Claim(receipt,name,"pending");
    }
    public synchronized Entry status(String receipt)throws Rejected{
        if(receipt==null||!receipt.matches("[a-f0-9]{48}"))throw new Rejected(404,"Anfrage nicht gefunden.");
        String digest=hash(receipt);return entries.values().stream().filter(e->digest.equals(e.receiptHash())).findFirst().orElseThrow(()->new Rejected(404,"Anfrage nicht gefunden."));
    }
    public synchronized List<Entry> pending(){return entries.values().stream().filter(e->e.status().equals("pending")).limit(20).toList();}
    public synchronized void finish(String id,String error)throws IOException{
        Entry e=entries.get(id);if(e==null||!e.status().equals("pending"))return;
        put(new Entry(e.id(),e.codeHash(),e.boundName(),e.created(),e.expires(),error==null?"applied":"failed",e.username(),e.uuid(),e.receiptHash(),error,e.sealedCode()));
    }
    private Entry byId(String id)throws Rejected{
        List<Entry> found=entries.values().stream().filter(e->e.id().startsWith(id.toLowerCase(Locale.ROOT))).toList();
        if(id.length()<8||found.size()!=1)throw new Rejected(404,"Bitte eine eindeutige ID (mindestens 8 Zeichen) aus /blockpass list angeben.");return found.get(0);
    }
    public synchronized void revoke(String id)throws IOException,Rejected{
        Entry e=byId(id);if(!e.status().equals("unused"))throw new Rejected(409,"Nur unbenutzte Codes können widerrufen werden.");
        put(new Entry(e.id(),e.codeHash(),e.boundName(),e.created(),e.expires(),"revoked",null,null,null,null,e.sealedCode()));
    }
    public synchronized void retry(String id)throws IOException,Rejected{
        Entry e=byId(id);if(!e.status().equals("failed"))throw new Rejected(409,"Nur fehlgeschlagene Anfragen können neu gestartet werden.");
        put(new Entry(e.id(),e.codeHash(),e.boundName(),e.created(),e.expires(),"pending",e.username(),e.uuid(),e.receiptHash(),null,e.sealedCode()));
    }
    public synchronized List<Entry> recent(){List<Entry> rows=new ArrayList<>(entries.values());Collections.reverse(rows);return rows.stream().limit(15).toList();}
    public synchronized int cleanup()throws IOException{
        long cutoff=System.currentTimeMillis()/1000-90*86400L;var next=copy();next.values().removeIf(e->!e.status().equals("pending")&&e.expires()<cutoff);int count=entries.size()-next.size();if(count>0)commit(next);return count;
    }
}
