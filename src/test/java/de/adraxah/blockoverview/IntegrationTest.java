package de.adraxah.blockoverview;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Actual HTTP and disk; only the live Minecraft snapshot is supplied by the fixture. */
public final class IntegrationTest {
    private static final Gson JSON=new Gson();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static LiveStatus.Player player(String name) { return new LiveStatus.Player(name,UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    static final String PASSWORD="TestOverview2026!";
    private static int passed;
    static String hash() throws Exception {
        byte[] salt=new byte[16]; Arrays.fill(salt,(byte)42);
        PBEKeySpec spec=new PBEKeySpec(PASSWORD.toCharArray(),salt,210000,256);
        try { return "pbkdf2-sha256$210000$"+HexFormat.of().formatHex(salt)+"$"+HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        finally { spec.clearPassword(); }
    }
    static final class Fixture implements AutoCloseable {
        final Path file; final ContentStore content; final LiveStatus live=new LiveStatus(); final EmbeddedWebServer web; final String base;
        Fixture(Path file) throws Exception {
            this.file=file; content=new ContentStore(file); publish(System.currentTimeMillis());
            web=new EmbeddedWebServer(new InetSocketAddress("127.0.0.1",0),content,live,new AdminAccess("admin",hash()),"",null); web.start(); base="http://127.0.0.1:"+web.port();
        }
        void publish(long time) { live.publish(new LiveStatus.Snapshot(time,time-3600000,2,20,3,"1.20.4",List.of(player("Alex"),player("Steve")),19.95)); }
        public void close() { web.close(); }
    }
    private record Reply(int status,String raw,HttpHeaders headers) {
        JsonObject json() { return JsonParser.parseString(raw).getAsJsonObject(); }
    }
    private static Reply request(Fixture f,String path,String method,String raw,String cookie,String csrf,String origin) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(f.base+path)).timeout(Duration.ofSeconds(5));
        if(raw!=null)b.header("Content-Type","application/json");
        b.method(method,raw==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(raw));
        if(cookie!=null)b.header("Cookie",cookie); if(csrf!=null)b.header("X-CSRF-Token",csrf); if(origin!=null)b.header("Origin",origin);
        HttpResponse<String> r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString()); return new Reply(r.statusCode(),r.body(),r.headers());
    }
    private static Reply get(Fixture f,String path) throws Exception { return request(f,path,"GET",null,null,null,null); }
    private static void check(boolean ok,String name) { if(!ok)throw new AssertionError(name); passed++; System.out.println("PASS "+name); }
    private static Path fresh() throws Exception { return Files.createTempDirectory("blockoverview-test-").resolve("content.json"); }
    private static JsonObject settings(ContentStore content) { return JSON.toJsonTree(content.get().settings()).getAsJsonObject(); }
    private static JsonObject saveBody(ContentStore content) { return JSON.toJsonTree(content.get()).getAsJsonObject(); }
    public static void main(String[] args) throws Exception {
        Path file=fresh();
        try(Fixture f=new Fixture(file); Fixture other=new Fixture(fresh())) {
            check(!f.base.equals(other.base) && get(other,"/").status()==200,"independent plugins/listeners can run side by side");
            Reply page=get(f,"/"); check(page.status()==200 && page.raw().contains("White liste dich jetzt") && page.raw().contains("map.xn--nrnberger-q9a.lol"),"embedded overview and requested buttons served");
            check(get(f,"/admin").status()==200 && get(f,"/admin/").status()==200,"admin HTML available at both routes");
            check(get(f,"/font.ttf").headers().firstValue("content-type").orElse("").equals("font/ttf"),"local pixel font served");
            check(get(f,"/app.js").headers().firstValue("content-type").orElse("").contains("javascript"),"JavaScript served with correct type");
            check(request(f,"/","HEAD",null,null,null,null).raw().isEmpty(),"HEAD has no body");
            check(get(f,"/config.yml").status()==404 && get(f,"/content.json").status()==404 && get(f,"/%2e%2e/config.yml").status()==404,"private files and traversal unavailable");
            check(page.headers().firstValue("content-security-policy").orElse("").contains("frame-ancestors 'none'"),"CSP and frame protection enabled");
            JsonObject overview=get(f,"/api/overview").json();
            check(overview.getAsJsonObject("live").get("online").getAsInt()==2 && overview.getAsJsonObject("live").getAsJsonArray("players").size()==2,"real snapshot feeds player count and names");
            check(overview.getAsJsonObject("live").get("tps").getAsDouble()==19.95,"measured TPS is delivered");
            check(overview.getAsJsonObject("milestones").get("netherOpen").isJsonNull(),"unset dates remain unset");
            check(!overview.toString().contains("password") && !overview.toString().contains("csrf"),"public response has no admin credentials");
            check(get(f,"/api/admin/settings").status()==401,"settings require authentication");
            check(request(f,"/api/admin/login","POST","{\"username\":\"admin\",\"password\":\"wrong\"}",null,null,f.base).status()==401,"wrong password rejected");
            String login="{\"username\":\"admin\",\"password\":\""+PASSWORD+"\"}";
            check(request(f,"/api/admin/login","POST",login,null,null,"https://evil.test").status()==403,"cross-origin login blocked");
            Reply auth=request(f,"/api/admin/login","POST",login,null,null,f.base);
            String setCookie=auth.headers().firstValue("set-cookie").orElseThrow(), cookie=setCookie.split(";",2)[0], csrf=auth.json().get("csrf").getAsString();
            check(auth.status()==200 && setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && cookie.startsWith("blockoverview_admin="),"session cookie is distinct from Blockpass and HttpOnly");
            check(request(f,"/api/admin/settings","GET",null,cookie.replace("blockoverview_admin=","blockpass_admin="),null,null).status()==401,"Blockpass cookie cannot authenticate overview");
            Reply saved=request(f,"/api/admin/settings","GET",null,cookie,null,null);
            check(saved.status()==200 && saved.json().get("revision").getAsLong()==1,"authenticated administrator can read settings");
            JsonObject edit=saved.json(), s=edit.getAsJsonObject("settings");
            s.addProperty("title","Nürnberg & Freunde"); s.addProperty("address","play.example.test");
            s.addProperty("playerPauseEnabled",false);
            s.addProperty("projectStart","2026-10-15T18:00"); s.addProperty("netherOpen","2026-10-16T18:00"); s.addProperty("endOpen","2026-10-20T18:00");
            s.addProperty("announcementText","<script>alert(1)</script>"); s.addProperty("maintenance",true);
            check(request(f,"/api/admin/settings","POST",edit.toString(),cookie,null,f.base).status()==403,"saving requires CSRF token");
            check(request(f,"/api/admin/settings","POST",edit.toString(),cookie,csrf,"https://evil.test").status()==403,"cross-origin save rejected despite session");
            Reply updated=request(f,"/api/admin/settings","POST",edit.toString(),cookie,csrf,f.base);
            check(updated.status()==200 && updated.json().get("revision").getAsLong()==2,"valid edit persists and increments revision");
            check(request(f,"/api/admin/settings","POST",edit.toString(),cookie,csrf,f.base).status()==409,"stale tab cannot overwrite newer content");
            check(get(f,"/api/overview").json().getAsJsonObject("content").get("title").getAsString().equals("Nürnberg & Freunde"),"public page receives saved edits immediately");
            check(get(f,"/api/overview").raw().contains("\\u003cscript\\u003e"),"HTML-shaped announcement safely JSON-escaped");
            check(ContentStore.epoch("2026-10-15T18:00")==Instant.parse("2026-10-15T16:00:00Z").toEpochMilli(),"dates converted using Berlin summer time");
            check(ContentStore.epoch("2026-12-01T18:00")==Instant.parse("2026-12-01T17:00:00Z").toEpochMilli(),"dates converted using Berlin winter time");
            JsonObject invalid=saveBody(f.content); invalid.getAsJsonObject("settings").addProperty("mapUrl","javascript:alert(1)");
            check(request(f,"/api/admin/settings","POST",invalid.toString(),cookie,csrf,f.base).status()==400,"script URL rejected");
            invalid=saveBody(f.content); invalid.getAsJsonObject("settings").addProperty("netherOpen","2026-03-29T02:30");
            check(request(f,"/api/admin/settings","POST",invalid.toString(),cookie,csrf,f.base).status()==400,"nonexistent DST time rejected");
            invalid=saveBody(f.content); invalid.getAsJsonObject("settings").addProperty("netherOpen","2026-10-01T10:00");
            check(request(f,"/api/admin/settings","POST",invalid.toString(),cookie,csrf,f.base).status()==400,"opening before launch rejected");
            check(request(f,"/api/admin/settings","POST","{}",cookie,csrf,f.base).status()==400,"partial/malformed settings rejected");
            check(request(f,"/api/admin/settings","POST","a".repeat(32769),cookie,csrf,f.base).status()==413,"oversized body rejected");
            invalid=saveBody(f.content); invalid.getAsJsonObject("settings").addProperty("showPlayers",false); invalid.getAsJsonObject("settings").addProperty("showTps",false); invalid.getAsJsonObject("settings").addProperty("showUptime",false);
            check(request(f,"/api/admin/settings","POST",invalid.toString(),cookie,csrf,f.base).status()==200,"visibility settings saved");
            JsonObject redacted=get(f,"/api/overview").json().getAsJsonObject("live");
            check(redacted.getAsJsonArray("players").isEmpty() && redacted.get("tps").isJsonNull() && redacted.get("processStartedAt").isJsonNull(),"hidden player names, TPS and uptime also removed from public API");
            f.publish(System.currentTimeMillis()-20000); JsonObject stale=get(f,"/api/overview").json().getAsJsonObject("live");
            check(!stale.get("available").getAsBoolean() && stale.get("online").isJsonNull(),"stale Minecraft snapshot never shown as current");
            check(request(f,"/api/admin/logout","POST","{}",cookie,csrf,f.base).status()==200,"logout succeeds");
            check(request(f,"/api/admin/session","GET",null,cookie,null,null).status()==401,"logged-out session cannot be reused");
            check(request(f,"/api/overview","POST","{}",null,null,null).status()==405,"wrong HTTP method rejected");
            check(new ContentStore(file).get().settings().title().equals("Nürnberg & Freunde"),"content survives restart");
        }
        try(Fixture f=new Fixture(fresh())) {
            for(int i=0;i<5;i++)request(f,"/api/admin/login","POST","{\"username\":\"admin\",\"password\":\"bad\"}",null,null,f.base);
            check(request(f,"/api/admin/login","POST","{}",null,null,f.base).status()==429,"repeated password guesses rate limited");
        }
        Path corrupt=fresh(); Files.writeString(corrupt,"broken"); boolean rejected=false;
        try { new ContentStore(corrupt); } catch(java.io.IOException e) { rejected=true; }
        check(rejected && Files.readString(corrupt).equals("broken"),"corrupt data preserved without silent reset");
        LiveStatus.TickRate t=new LiveStatus.TickRate(); t.tick(0); check(t.value()==null,"TPS starts unknown during warm-up");
        for(int i=1;i<=1200;i++)t.tick(i*50_000_000L); check(t.value()==20.0,"20 ticks per second measured correctly");
        for(int i=1;i<=600;i++)t.tick(60_000_000_000L+i*100_000_000L); check(t.value()==10.0,"rolling window detects 10 TPS slowdown");
        System.out.println("RESULT: "+passed+" checks passed. Real HTTP + disk; Minecraft snapshot simulated.");
    }
}
