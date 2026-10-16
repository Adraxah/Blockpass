package de.adraxah.blockoverview;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bundled assets and JSON only. No directory listing, Bukkit access, or external runtime. */
public final class EmbeddedWebServer implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Map<String,String> ROUTES = Map.ofEntries(
        Map.entry("/","index.html"), Map.entry("/index.html","index.html"), Map.entry("/admin","admin.html"),
        Map.entry("/admin/","admin.html"), Map.entry("/style.css","style.css"), Map.entry("/app.js","app.js"),
        Map.entry("/admin.js","admin.js"), Map.entry("/font.ttf","font.ttf"), Map.entry("/favicon.svg","favicon.svg"));
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, r -> daemon(r,"BlockOverview-timeout"));
    private final Map<String,byte[]> assets = new HashMap<>();
    private final ContentStore content;
    private final LiveStatus live;
    private final SkinHeads heads;
    private volatile com.sun.net.httpserver.HttpHandler whitelist;
    private final AdminAccess admin;
    private final String publicOrigin;
    private final RateLimiter limiter = new RateLimiter();
    private volatile boolean active = true;
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r,name); t.setDaemon(true); return t; }
    private static final class Bucket { long expires; int count; Bucket(long expires) { this.expires=expires; } }
    private static final class RateLimiter {
        final Map<String,Bucket> buckets = new HashMap<>();
        synchronized boolean allow(String key, int limit, long period) {
            long now=System.currentTimeMillis();
            if (buckets.size()>1024) buckets.values().removeIf(b -> b.expires<now);
            Bucket b=buckets.get(key);
            if(b==null || b.expires<now) { if(buckets.size()>=8192)return false; b=new Bucket(now+period); buckets.put(key,b); }
            return ++b.count<=limit;
        }
    }
    public EmbeddedWebServer(InetSocketAddress bind, ContentStore content, LiveStatus live, AdminAccess admin,
        String publicOrigin, SSLContext tls) throws IOException {
        this(bind,content,live,admin,publicOrigin,tls,null);
    }
    public EmbeddedWebServer(InetSocketAddress bind, ContentStore content, LiveStatus live, AdminAccess admin,
        String publicOrigin, SSLContext tls, SkinHeads heads) throws IOException {
        this.content=content; this.live=live; this.admin=admin; this.publicOrigin=publicOrigin;
        live.configurePlayers(() -> content.get().settings());
        this.heads=heads;
        for(String name:new HashSet<>(ROUTES.values())) try(InputStream in=getClass().getResourceAsStream("/overview-web/"+name)) {
            if(in==null)throw new IOException("Web-Datei fehlt: "+name); assets.put(name,in.readAllBytes());
        }
        if(tls==null) server=HttpServer.create(bind,32);
        else { HttpsServer https=HttpsServer.create(bind,32); https.setHttpsConfigurator(new HttpsConfigurator(tls)); server=https; }
        workers=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r -> daemon(r,"BlockOverview-http"),new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true); watchdog.setRemoveOnCancelPolicy(true);
        server.setExecutor(workers); server.createContext("/",this::handle);
    }
    public void start() { server.start(); }
    public void mountWhitelist(com.sun.net.httpserver.HttpHandler handler) { whitelist=handler; }
    public int port() { return server.getAddress().getPort(); }
    private void headers(HttpExchange x) {
        Headers h=x.getResponseHeaders();
        h.set("X-Content-Type-Options","nosniff"); h.set("X-Frame-Options","DENY"); h.set("Referrer-Policy","no-referrer");
        h.set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
        h.set("Cache-Control","no-store"); h.set("Connection","close");
    }
    private void send(HttpExchange x,int status,String type,byte[] bytes) throws IOException {
        x.getResponseHeaders().set("Content-Type",type);
        if(x.getRequestMethod().equals("HEAD")) { x.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length)); x.sendResponseHeaders(status,-1); return; }
        x.sendResponseHeaders(status,bytes.length); x.getResponseBody().write(bytes);
    }
    private void json(HttpExchange x,int status,Object data) throws IOException { send(x,status,"application/json; charset=utf-8",JSON.toJson(data).getBytes(StandardCharsets.UTF_8)); }
    private JsonObject body(HttpExchange x) throws IOException,HttpError {
        String type=x.getRequestHeaders().getFirst("Content-Type");
        if(type==null || !type.toLowerCase(Locale.ROOT).split(";",2)[0].trim().equals("application/json")) throw new HttpError(415,"JSON erwartet.");
        byte[] bytes=x.getRequestBody().readNBytes(32769);
        if(bytes.length>32768)throw new HttpError(413,"Anfrage zu groß.");
        try { return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch(RuntimeException e) { throw new HttpError(400,"Ungültige Anfrage."); }
    }
    private static String credential(JsonObject o,String key,int max) throws HttpError {
        JsonElement v=o.get(key);
        if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().length()>max)throw new HttpError(400,"Anmeldedaten fehlen oder sind zu lang.");
        return v.getAsString();
    }
    private void origin(HttpExchange x) throws HttpError {
        if("cross-site".equals(x.getRequestHeaders().getFirst("Sec-Fetch-Site")))throw new HttpError(403,"Fremder Ursprung.");
        String origin=x.getRequestHeaders().getFirst("Origin"); if(origin==null)return;
        String host=x.getRequestHeaders().getFirst("Host"), scheme=x instanceof HttpsExchange?"https":"http";
        if(host==null || (!origin.equals(scheme+"://"+host) && !origin.equals(publicOrigin)))throw new HttpError(403,"Fremder Ursprung.");
    }
    private static void method(String actual,String expected) throws HttpError { if(!actual.equals(expected))throw new HttpError(405,"Methode nicht erlaubt."); }
    private Object publicData() throws HttpError {
        long now=System.currentTimeMillis();
        ContentStore.Settings s=content.get().settings();
        LiveStatus.Snapshot snap=live.get(); boolean fresh=PlayerUpdates.fresh(snap,now);
        PlayerUpdates.View view=live.players(s,now); LiveStatus.Snapshot players=view.snapshot();
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("available",fresh); values.put("observedAt",snap==null?null:snap.observedAt());
        values.put("playersAvailable",view.available()); values.put("playersPaused",view.paused());
        values.put("playersResumeAt",view.resumesAt()); values.put("playersObservedAt",view.available()?players.observedAt():null);
        values.put("online",view.available()?players.online():null); values.put("maxPlayers",fresh?snap.maxPlayers():null);
        values.put("peakPlayers",view.available()?players.peakPlayers():null); values.put("version",fresh?snap.version():null);
        values.put("players",view.available() && s.showPlayers()?players.players():List.of());
        values.put("tps",fresh && s.showTps()?snap.tps():null);
        values.put("processStartedAt",fresh && s.showUptime()?snap.processStartedAt():null);
        Map<String,Object> dates=new LinkedHashMap<>();
        dates.put("projectStart",ContentStore.epoch(s.projectStart())); dates.put("netherOpen",ContentStore.epoch(s.netherOpen())); dates.put("endOpen",ContentStore.epoch(s.endOpen()));
        return Map.of("serverNow",now,"timezone",ContentStore.ZONE.getId(),"content",s,"live",values,"milestones",dates);
    }
    private void handle(HttpExchange x) throws IOException {
        String rawPath=x.getRequestURI().getRawPath();
        if(whitelist!=null && rawPath.equals("/whitelist")){
            headers(x);x.getResponseHeaders().set("Location","/whitelist/");x.sendResponseHeaders(308,-1);x.close();return;
        }
        if(whitelist!=null && rawPath.startsWith("/whitelist/")){whitelist.handle(x);return;}
        headers(x); ScheduledFuture<?> timeout=watchdog.schedule(x::close,15,TimeUnit.SECONDS);
        try {
            if(!active)throw new HttpError(503,"Übersicht wird neu gestartet.");
            String path=x.getRequestURI().getRawPath(), method=x.getRequestMethod(), ip=x.getRemoteAddress().getAddress().getHostAddress();
            if(!limiter.allow("all:"+ip,600,60000))throw new HttpError(429,"Zu viele Anfragen. Bitte kurz warten.");
            if(ROUTES.containsKey(path)) {
                if(!Set.of("GET","HEAD").contains(method))throw new HttpError(405,"Methode nicht erlaubt.");
                String name=ROUTES.get(path), type=name.endsWith(".css")?"text/css; charset=utf-8":name.endsWith(".js")?"text/javascript; charset=utf-8":name.endsWith(".svg")?"image/svg+xml":name.endsWith(".ttf")?"font/ttf":"text/html; charset=utf-8";
                send(x,200,type,assets.get(name)); return;
            }
            if(path.equals("/api/overview")) { method(method,"GET"); json(x,200,publicData()); return; }
            if(path.equals("/default-head.png")) {
                if(!Set.of("GET","HEAD").contains(method))throw new HttpError(405,"Methode nicht erlaubt.");
                x.getResponseHeaders().set("Cache-Control","public, max-age=86400");
                send(x,200,"image/png",SkinHeads.defaultHead()); return;
            }
            if(path.startsWith("/heads/")) {
                if(!Set.of("GET","HEAD").contains(method))throw new HttpError(405,"Methode nicht erlaubt.");
                if(!path.matches("/heads/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.png"))throw new HttpError(404,"Nicht gefunden.");
                UUID id=UUID.fromString(path.substring(7,path.length()-4)); ContentStore.Settings settings=content.get().settings();
                PlayerUpdates.View view=live.players(settings,System.currentTimeMillis());
                if(!settings.showPlayers() || !view.available() || view.snapshot().players().stream().noneMatch(p->p.uuid().equals(id)))throw new HttpError(404,"Nicht gefunden.");
                if(heads!=null && heads.ready(id))x.getResponseHeaders().set("Cache-Control","private, max-age=60");
                send(x,200,"image/png",heads==null?SkinHeads.defaultHead():heads.get(id)); return;
            }
            if(path.startsWith("/api/admin/")) {
                origin(x);
                String flags="; Path=/api/admin; HttpOnly; SameSite=Strict"+(x instanceof HttpsExchange || publicOrigin.startsWith("https://")?"; Secure":"");
                if(path.equals("/api/admin/login")) {
                    method(method,"POST");
                    if(!limiter.allow("login:"+ip,5,600000) || !limiter.allow("login-global",30,60000))throw new HttpError(429,"Zu viele Loginversuche. Bitte zehn Minuten warten.");
                    JsonObject input=body(x); AdminAccess.Session session=admin.login(credential(input,"username",64),credential(input,"password",256));
                    x.getResponseHeaders().set("Set-Cookie","blockoverview_admin="+session.token()+"; Max-Age=3600"+flags);
                    json(x,200,Map.of("csrf",session.csrf())); return;
                }
                AdminAccess.Session session=admin.require(x.getRequestHeaders().getFirst("Cookie"));
                if(!method.equals("GET") && !session.csrf().equals(x.getRequestHeaders().getFirst("X-CSRF-Token")))throw new HttpError(403,"Sitzungsprüfung fehlgeschlagen. Bitte erneut anmelden.");
                if(path.equals("/api/admin/session")) { method(method,"GET"); json(x,200,Map.of("csrf",session.csrf())); return; }
                if(path.equals("/api/admin/settings")) {
                    if(method.equals("GET")) { json(x,200,content.get()); return; }
                    method(method,"POST"); JsonObject input=body(x);
                    JsonElement rev=input.get("revision"), settings=input.get("settings");
                    if(rev==null || !rev.toString().matches("[0-9]{1,16}") || settings==null || !settings.isJsonObject())throw new HttpError(400,"Einstellungen oder Version fehlen.");
                    json(x,200,content.update(rev.getAsLong(),settings.getAsJsonObject())); return;
                }
                if(path.equals("/api/admin/logout")) {
                    method(method,"POST"); admin.logout(session);
                    x.getResponseHeaders().set("Set-Cookie","blockoverview_admin=; Max-Age=0"+flags); json(x,200,Map.of("ok",true)); return;
                }
            }
            throw new HttpError(404,"Nicht gefunden.");
        } catch(HttpError e) {
            if(e.status==429)x.getResponseHeaders().set("Retry-After","600");
            json(x,e.status,Map.of("error",e.getMessage()));
        } catch(Exception e) {
            if(x.getResponseCode()<0)json(x,503,Map.of("error","Vorübergehend nicht erreichbar. Bitte erneut versuchen."));
        } finally { timeout.cancel(false); x.close(); }
    }
    @Override public void close() { active=false; server.stop(0); workers.shutdownNow(); watchdog.shutdownNow(); }
}
