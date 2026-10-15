package de.adraxah.blockoverview;

import com.google.gson.*;
import de.adraxah.blockpass.AccessStore;
import de.adraxah.blockpass.ModpackStore;
import de.adraxah.blockpass.WhitelistService;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class CombinedIntegrationTest {
    private static int passed;
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message);passed++;System.out.println("PASS "+message); }
    public static final class Pair implements AutoCloseable {
        final Path folder=Files.createTempDirectory("blockpass-combined-");
        final AccessStore codes=new AccessStore(folder.resolve("data.json"));
        final ModpackStore packs=new ModpackStore(folder);
        final AtomicInteger applied=new AtomicInteger();
        final WhitelistService service;
        final de.adraxah.blockpass.EmbeddedWebServer whitelist;
        final IntegrationTest.Fixture overview=new IntegrationTest.Fixture(folder.resolve("overview/content.json"));
        final String base;
        public Pair() throws Exception {
            Files.writeString(folder.resolve("modpacks/modrinth/test.mrpack"),"fixture-modpack");
            service=new WhitelistService(codes,new WhitelistService.Gateway(){
                public WhitelistService.Profile resolve(String name){return new WhitelistService.Profile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name);}
                public void whitelist(WhitelistService.Profile p){applied.incrementAndGet();}
            },s->{});
            whitelist=new de.adraxah.blockpass.EmbeddedWebServer(null,codes,service,"Nürnberger.lol","play.example.test","",null,new de.adraxah.blockpass.AdminAccess("admin",IntegrationTest.hash()),packs);
            overview.web.mountWhitelist(whitelist::handle);service.start();base=overview.base+"/whitelist";
        }
        public void close(){whitelist.close();service.close();overview.close();}
    }
    private static HttpResponse<String> call(HttpClient client,String base,String path,String body,String csrf) throws Exception {
        var req=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5));
        if(body!=null)req.header("Content-Type","application/json").header("Origin",URI.create(base).getScheme()+"://"+URI.create(base).getRawAuthority()).POST(HttpRequest.BodyPublishers.ofString(body));
        if(csrf!=null)req.header("X-CSRF-Token",csrf);
        return client.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static JsonObject json(HttpResponse<String> r){return JsonParser.parseString(r.body()).getAsJsonObject();}
    public static void main(String[] args) throws Exception {
        try(Pair p=new Pair()) {
            CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
            check(URI.create(p.base).getPort()==URI.create(p.overview.base).getPort() && p.whitelist.port()==0,"both websites share exactly one listener and port");
            check(call(client,p.base,"/",null,null).body().contains("Download Modpack") && call(client,p.overview.base,"/",null,null).body().contains("<h2>Termine</h2>"),"shared listener serves both homepages at their own routes");
            check(call(client,p.base,"/app.js",null,null).body().contains("api('claim'") && call(client,p.overview.base,"/app.js",null,null).body().contains("/api/overview"),"bundled JavaScript resources do not collide");
            String login="{\"username\":\"admin\",\"password\":\""+IntegrationTest.PASSWORD+"\"}";
            var a=call(client,p.base,"/api/admin/login",login,null);String csrfA=json(a).get("csrf").getAsString();
            check(a.statusCode()==200 && call(client,p.overview.base,"/api/admin/session",null,null).statusCode()==401,"whitelist cookie alone does not authenticate overview");
            var b=call(client,p.overview.base,"/api/admin/login",login,null);String csrfB=json(b).get("csrf").getAsString();
            check(b.statusCode()==200 && call(client,p.base,"/api/admin/session",null,null).statusCode()==200,"both admin sessions coexist on same host");
            var issued=call(client,p.base,"/api/admin/codes","{}",csrfA);String code=json(issued).get("code").getAsString();
            check(issued.statusCode()==201 && !json(issued).has("username"),"combined plugin still creates codes without username");
            String receipt=AccessStore.token(24);JsonObject claim=new JsonObject();claim.addProperty("username","Alex");claim.addProperty("code",code);claim.addProperty("receipt",receipt);
            check(call(client,p.base,"/api/claim",claim.toString(),null).statusCode()==200,"player submits name and code on whitelist website");
            long until=System.currentTimeMillis()+5000;while(!p.codes.status(receipt).status().equals("applied") && System.currentTimeMillis()<until)Thread.sleep(25);
            check(p.applied.get()==1 && p.codes.status(receipt).status().equals("applied"),"whitelist workflow completes while overview runs");
            String selection="{\"format\":\"modrinth\",\"filename\":\"test.mrpack\",\"version\":\"1.0\",\"enabled\":true}";
            check(call(client,p.base,"/api/admin/modpacks",selection,csrfA).statusCode()==200 && call(client,p.base,"/downloads/modrinth",null,null).body().equals("fixture-modpack"),"admin-selected modpacks download while overview runs");
            JsonObject edit=json(call(client,p.overview.base,"/api/admin/settings",null,null));edit.getAsJsonObject("settings").addProperty("title","Gemeinsames Plugin");
            check(call(client,p.overview.base,"/api/admin/settings",edit.toString(),csrfB).statusCode()==200 && call(client,p.overview.base,"/api/overview",null,null).body().contains("Gemeinsames Plugin"),"overview dashboard saves independently of whitelist dashboard");
            check(call(client,p.base,"/api/admin/codes",null,null).body().contains(code),"overview edits preserve existing invitation codes");
            check(call(client,p.overview.base,"/api/admin/codes",null,null).statusCode()==404 && call(client,p.base,"/api/admin/settings",null,null).statusCode()==404,"admin route namespaces remain separate");
            check(call(client,p.overview.base,"/api/admin/logout","{}",csrfB).statusCode()==200 && call(client,p.base,"/api/admin/session",null,null).statusCode()==200,"logging out of overview leaves whitelist session valid");
            check(call(client,p.base,"/overview/content.json",null,null).statusCode()==404 && call(client,p.overview.base,"/data.json",null,null).statusCode()==404,"neither website serves the other module's private data");
        }
        System.out.println("RESULT: "+passed+" combined website checks passed. Minecraft gateway simulated.");
    }
}
