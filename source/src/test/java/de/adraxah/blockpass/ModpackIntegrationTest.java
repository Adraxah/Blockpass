package de.adraxah.blockpass;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Exercises the actual HTTP listener, persisted selection, and disk streaming. */
public final class ModpackIntegrationTest {
    static final HttpClient HTTP=HttpClient.newHttpClient();static int passed;
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);passed++;System.out.println("PASS "+label);}
    static HttpResponse<String> call(String base,String path,String data,String cookie,String csrf,String origin)throws Exception{return AdminIntegrationTest.call(base,path,data,cookie,csrf,origin);}
    static String selection(String format,String filename,String version,boolean enabled){JsonObject j=new JsonObject();j.addProperty("format",format);j.addProperty("filename",filename);j.addProperty("version",version);j.addProperty("enabled",enabled);return j.toString();}
    static JsonArray packs(HttpResponse<String> r){return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("packs");}
    static JsonObject pack(JsonArray rows,String id){for(JsonElement e:rows)if(e.getAsJsonObject().get("id").getAsString().equals(id))return e.getAsJsonObject();throw new AssertionError(id);}
    public static void main(String[] args)throws Exception{
        Path data=Files.createTempDirectory("blockpass-modpack-test-");ModpackStore store=new ModpackStore(data);
        for(String folder:List.of("prism","curesforge","modrinth"))check(Files.isDirectory(data.resolve("modpacks/"+folder)),"created "+folder+" folder");
        String prism="Nürnberger Azubis 1.0.zip";Path prismFile=data.resolve("modpacks/prism/"+prism);byte[] bytes=new byte[3*1024*1024+17];new Random(42).nextBytes(bytes);Files.write(prismFile,bytes);
        Files.writeString(data.resolve("modpacks/prism/version-2.zip"),"version2");Files.writeString(data.resolve("modpacks/modrinth/pack.mrpack"),"modrinth");Files.writeString(data.resolve("modpacks/curesforge/pack.zip"),"curseforge");Files.writeString(data.resolve("modpacks/prism/not-an-archive.txt"),"private");Files.writeString(data.resolve("secret.txt"),"secret");
        Files.createSymbolicLink(data.resolve("modpacks/prism/linked.zip"),data.resolve("secret.txt"));
        byte[] salt=new byte[16];Arrays.fill(salt,(byte)9);String password="test-modpacks";
        String verifier="pbkdf2-sha256$210000$"+HexFormat.of().formatHex(salt)+"$"+HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(),salt,210000,256)).getEncoded());
        AccessStore codes=new AccessStore(data.resolve("data.json"));
        try(var web=new EmbeddedWebServer(new InetSocketAddress("127.0.0.1",0),codes,null,"Test","","",null,new AdminAccess("admin",verifier),store)){
            web.start();String base="http://127.0.0.1:"+web.port();
            check(packs(call(base,"/api/modpacks",null,null,null,null)).size()==3,"public metadata contains all formats");
            check(!pack(packs(call(base,"/api/modpacks",null,null,null,null)),"prism").get("available").getAsBoolean(),"files are not automatically published");
            check(call(base,"/downloads/prism",null,null,null,null).statusCode()==404,"unselected files cannot be downloaded");
            check(call(base,"/api/admin/modpacks",null,null,null,null).statusCode()==401,"file listing requires admin login");
            check(call(base,"/api/admin/modpacks",selection("prism",prism,"1.0",true),null,null,base).statusCode()==401,"selection requires admin login");
            var auth=call(base,"/api/admin/login","{\"username\":\"admin\",\"password\":\"test-modpacks\"}",null,null,base);
            String cookie=auth.headers().firstValue("set-cookie").orElseThrow().split(";")[0],csrf=JsonParser.parseString(auth.body()).getAsJsonObject().get("csrf").getAsString();
            var admin=call(base,"/api/admin/modpacks",null,cookie,null,base);
            check(admin.statusCode()==200&&admin.body().contains(prism)&&!admin.body().contains("linked.zip")&&!admin.body().contains("not-an-archive"),"scan lists valid regular archives and excludes symlinks");
            check(call(base,"/api/admin/modpacks",selection("prism",prism,"1.0",true),cookie,null,base).statusCode()==403,"save requires CSRF token");
            check(call(base,"/api/admin/modpacks",selection("prism",prism,"1.0",true),cookie,csrf,"http://evil.test").statusCode()==403,"cross-origin save rejected");
            check(call(base,"/api/admin/modpacks",selection("prism","../../secret.txt","",true),cookie,csrf,base).statusCode()==400,"path traversal filename rejected");
            check(call(base,"/api/admin/modpacks",selection("prism","C:\\secret.zip","",true),cookie,csrf,base).statusCode()==400,"Windows paths rejected");
            check(call(base,"/api/admin/modpacks",selection("modrinth","wrong.zip","",true),cookie,csrf,base).statusCode()==400,"wrong launcher extension rejected");
            check(call(base,"/api/admin/modpacks",selection("prism","missing.zip","",true),cookie,csrf,base).statusCode()==400,"missing archive cannot be enabled");
            check(call(base,"/api/admin/modpacks",selection("prism","linked.zip","",true),cookie,csrf,base).statusCode()==400,"symlink archive cannot be enabled");
            check(call(base,"/api/admin/modpacks",selection("../prism",prism,"",true),cookie,csrf,base).statusCode()==404,"unknown format rejected");
            check(call(base,"/api/admin/modpacks",selection("prism",prism,"1.0",true),cookie,csrf,base).statusCode()==200,"admin enables an existing version");
            var publicPack=pack(packs(call(base,"/api/modpacks",null,null,null,null)),"prism");
            check(publicPack.get("available").getAsBoolean()&&publicPack.get("version").getAsString().equals("1.0")&&publicPack.get("size").getAsLong()==bytes.length&&!publicPack.toString().contains("plugins/"),"public metadata reflects selection without local paths");
            Path downloaded=data.resolve("received.zip");var response=HTTP.send(HttpRequest.newBuilder(URI.create(base+"/downloads/prism")).build(),HttpResponse.BodyHandlers.ofFile(downloaded));
            check(response.statusCode()==200&&Files.mismatch(prismFile,downloaded)==-1,"multi-megabyte file streamed byte for byte");
            check(response.headers().firstValue("content-disposition").orElse("").contains("filename*=UTF-8''N%C3%BCrnberger%20Azubis%201.0.zip"),"attachment preserves Unicode and spaces in filename");
            var head=HTTP.send(HttpRequest.newBuilder(URI.create(base+"/downloads/prism")).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
            check(head.statusCode()==200&&head.body().length==0&&head.headers().firstValue("content-length").orElse("").equals(Integer.toString(bytes.length)),"HEAD reports size without sending archive bytes");
            check(call(base,"/downloads/prism","{}",null,null,null).statusCode()==405,"download accepts only GET and HEAD");
            check(call(base,"/downloads/prism/version-2.zip",null,null,null,null).statusCode()==404&&call(base,"/modpacks/prism/version-2.zip",null,null,null,null).statusCode()==404,"unselected archives and directory paths are private");
            check(call(base,"/modpacks.json",null,null,null,null).statusCode()==404,"selection settings cannot be downloaded");
            check(call(base,"/downloads/..%2F..%2Fsecret.txt",null,null,null,null).statusCode()==404,"encoded route traversal rejected");
            check(new ModpackStore(data).publicPacks().stream().anyMatch(p->p.id().equals("prism")&&p.available()&&p.filename().equals(prism)),"selected version survives restart");
            check(call(base,"/api/admin/modpacks",selection("prism","version-2.zip","2.0",true),cookie,csrf,base).statusCode()==200&&call(base,"/downloads/prism",null,null,null,null).body().equals("version2"),"switching versions takes effect without restart");
            for(String id:List.of("modrinth","curseforge")){String file=id.equals("modrinth")?"pack.mrpack":"pack.zip";check(call(base,"/api/admin/modpacks",selection(id,file,"",true),cookie,csrf,base).statusCode()==200&&call(base,"/downloads/"+id,null,null,null,null).body().equals(id),id+" serves from its own directory");}
            check(call(base,"/api/admin/modpacks",selection("prism","version-2.zip","2.0",false),cookie,csrf,base).statusCode()==200&&call(base,"/downloads/prism",null,null,null,null).statusCode()==404&&Files.exists(prismFile),"disabling blocks download without deleting files");
            store.select("prism","version-2.zip","2.0",true);Files.delete(data.resolve("modpacks/prism/version-2.zip"));
            check(call(base,"/downloads/prism",null,null,null,null).statusCode()==404&&!pack(packs(call(base,"/api/modpacks",null,null,null,null)),"prism").get("available").getAsBoolean(),"deleted selection becomes unavailable");
            store.select("prism",prism,"1.0",true);Path folder=data.resolve("modpacks/prism"),backup=data.resolve("prism-backup");Files.move(folder,backup);Files.createSymbolicLink(folder,backup);
            check(call(base,"/downloads/prism",null,null,null,null).statusCode()==404,"replaced symlink directory is not followed");Files.delete(folder);Files.move(backup,folder);
            check(call(base,"/api/info",null,null,null,null).statusCode()==200,"regular site API still responds after downloads");
        }
        Path bad=Files.createTempDirectory("blockpass-invalid-packs-");Files.writeString(bad.resolve("modpacks.json"),"broken");boolean safe=false;try{new ModpackStore(bad);}catch(java.io.IOException e){safe=true;}check(safe&&Files.readString(bad.resolve("modpacks.json")).equals("broken"),"corrupt settings fail closed without overwrite");
        System.out.println("RESULT: "+passed+" modpack checks passed.");
    }
}
