package de.adraxah.blockpass;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Exact routes, bounded workers/body/rate limits; never exposes the plugin directory. */
public final class EmbeddedWebServer implements AutoCloseable {
    private static final Gson JSON=new Gson();
    private static final Map<String,String> ASSETS=Map.ofEntries(Map.entry("/","index.html"),Map.entry("/index.html","index.html"),Map.entry("/style.css","style.css"),Map.entry("/app.js","app.js"),Map.entry("/favicon.svg","favicon.svg"),Map.entry("/font.ttf","font.ttf"),Map.entry("/admin","admin.html"),Map.entry("/admin/","admin.html"),Map.entry("/admin.js","admin.js"),Map.entry("/admin.css","admin.css"),Map.entry("/modrinth.png","modrinth.png"),Map.entry("/curseforge.png","curseforge.png"));
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor watchdog=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"Blockpass-http-timeout");t.setDaemon(true);return t;});
    private final AccessStore store;
    private final AdminAccess admin;
    private final ModpackStore modpacks;
    private final Semaphore downloadSlots=new Semaphore(2);
    private final WhitelistService service;
    private final String serverName,serverAddress,publicOrigin;
    private final Map<String,byte[]> assets=new HashMap<>();
    private final RateLimiter limiter=new RateLimiter();
    private volatile boolean active=true;
    private static final class Bucket{long expires;int count;Bucket(long expires){this.expires=expires;}}
    private static final class RateLimiter{
        final Map<String,Bucket> entries=new HashMap<>();
        synchronized boolean allow(String key,int limit,long period){
            long now=System.currentTimeMillis();if(entries.size()>1024)entries.values().removeIf(b->b.expires<now);
            Bucket b=entries.get(key);if(b==null||b.expires<now){if(entries.size()>=8192)return false;b=new Bucket(now+period);entries.put(key,b);}return ++b.count<=limit;
        }
    }
    public EmbeddedWebServer(InetSocketAddress address,AccessStore store,WhitelistService service,String name,String mcAddress,String publicOrigin,SSLContext tls)throws IOException{
        this(address,store,service,name,mcAddress,publicOrigin,tls,null);
    }
    public EmbeddedWebServer(InetSocketAddress address,AccessStore store,WhitelistService service,String name,String mcAddress,String publicOrigin,SSLContext tls,AdminAccess admin)throws IOException{
        this(address,store,service,name,mcAddress,publicOrigin,tls,admin,null);
    }
    public EmbeddedWebServer(InetSocketAddress address,AccessStore store,WhitelistService service,String name,String mcAddress,String publicOrigin,SSLContext tls,AdminAccess admin,ModpackStore modpacks)throws IOException{
        this.modpacks=modpacks;
        this.admin=admin;
        this.store=store;this.service=service;serverName=name;serverAddress=mcAddress;this.publicOrigin=publicOrigin;
        // Verify all assets before binding a port; a missing asset must not leak a listener.
        for(String asset:new HashSet<>(ASSETS.values()))try(InputStream in=getClass().getResourceAsStream("/web/"+asset)){if(in==null)throw new IOException("Web-Datei fehlt: "+asset);assets.put(asset,in.readAllBytes());}
        if(tls==null)server=HttpServer.create(address,32);else{HttpsServer secure=HttpsServer.create(address,32);secure.setHttpsConfigurator(new HttpsConfigurator(tls));server=secure;}
        executor=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"Blockpass-http");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        watchdog.setRemoveOnCancelPolicy(true);executor.allowCoreThreadTimeOut(true);server.setExecutor(executor);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    private void common(HttpExchange x){
        Headers h=x.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("X-Frame-Options","DENY");h.set("Referrer-Policy","no-referrer");
        h.set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
        h.set("Cache-Control","no-store");h.set("Connection","close");
    }
    private void send(HttpExchange x,int status,String type,byte[] data)throws IOException{
        x.getResponseHeaders().set("Content-Type",type);if(x.getRequestMethod().equals("HEAD")){x.getResponseHeaders().set("Content-Length",String.valueOf(data.length));x.sendResponseHeaders(status,-1);return;}
        x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);
    }
    private void json(HttpExchange x,int status,Object data)throws IOException{send(x,status,"application/json; charset=utf-8",JSON.toJson(data).getBytes(StandardCharsets.UTF_8));}
    private static String string(JsonObject data,String key,int max)throws AccessStore.Rejected{
        JsonElement value=data.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new AccessStore.Rejected(400,"Eingaben fehlen.");
        String s=value.getAsString().trim();if(s.length()>max)throw new AccessStore.Rejected(400,"Eingabe zu lang.");return s;
    }
    private JsonObject body(HttpExchange x)throws IOException,AccessStore.Rejected{
        String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new AccessStore.Rejected(415,"JSON erwartet.");
        byte[] bytes=x.getRequestBody().readNBytes(2049);if(bytes.length>2048)throw new AccessStore.Rejected(413,"Anfrage zu gross.");
        try{return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException ex){throw new AccessStore.Rejected(400,"Ungueltige Anfrage.");}
    }
    private void origin(HttpExchange x)throws AccessStore.Rejected{
        if("cross-site".equals(x.getRequestHeaders().getFirst("Sec-Fetch-Site")))throw new AccessStore.Rejected(403,"Fremder Ursprung.");
        String origin=x.getRequestHeaders().getFirst("Origin");if(origin==null)return;
        String host=x.getRequestHeaders().getFirst("Host"),scheme=x instanceof HttpsExchange?"https":"http";
        if(host==null||(!origin.equals(scheme+"://"+host)&&!origin.equals(publicOrigin)))throw new AccessStore.Rejected(403,"Fremder Ursprung.");
    }
    private void handle(HttpExchange x)throws IOException{
        common(x);
        ScheduledFuture<?> timeout=watchdog.schedule(x::close,x.getRequestURI().getRawPath().startsWith("/downloads/")?1800:15,TimeUnit.SECONDS);
        try{
            if(!active)throw new AccessStore.Rejected(503,"Server wird neu gestartet.");
            String ip=x.getRemoteAddress().getAddress().getHostAddress(),path=x.getRequestURI().getRawPath(),method=x.getRequestMethod();
            if(!limiter.allow("all:"+ip,180,60000))throw new AccessStore.Rejected(429,"Zu viele Anfragen. Bitte kurz warten.");
            if(ASSETS.containsKey(path)){
                if(!Set.of("GET","HEAD").contains(method))throw new AccessStore.Rejected(405,"Methode nicht erlaubt.");
                String asset=ASSETS.get(path),type=asset.endsWith(".css")?"text/css":asset.endsWith(".js")?"text/javascript":asset.endsWith(".svg")?"image/svg+xml":asset.endsWith(".ttf")?"font/ttf":asset.endsWith(".png")?"image/png":"text/html";
                send(x,200,type+((type.startsWith("text/"))?"; charset=utf-8":""),assets.get(asset));return;
            }
            if(path.startsWith("/api/admin/")&&admin!=null){
                origin(x);
                boolean secure=x instanceof HttpsExchange||publicOrigin.startsWith("https://");
                String cookieFlags="; Path=/api/admin; HttpOnly; SameSite=Strict"+(secure?"; Secure":"");
                if(path.equals("/api/admin/login")&&method.equals("POST")){
                    if(!limiter.allow("admin-login:"+ip,5,600000)||!limiter.allow("admin-login-global",30,60000))throw new AccessStore.Rejected(429,"Zu viele Loginversuche. Bitte zehn Minuten warten.");
                    JsonObject data=body(x);AdminAccess.Session session=admin.login(string(data,"username",64),string(data,"password",256));
                    x.getResponseHeaders().set("Set-Cookie","blockpass_admin="+session.token()+"; Max-Age=3600"+cookieFlags);
                    json(x,200,Map.of("csrf",session.csrf()));return;
                }
                AdminAccess.Session session=admin.require(x.getRequestHeaders().getFirst("Cookie"));
                if(path.equals("/api/admin/session")&&method.equals("GET")){json(x,200,Map.of("csrf",session.csrf()));return;}
                if(path.equals("/api/admin/codes")&&method.equals("GET")){json(x,200,Map.of("entries",store.adminRows()));return;}
                if(path.equals("/api/admin/codes")&&method.equals("POST")){
                    if(!session.csrf().equals(x.getRequestHeaders().getFirst("X-CSRF-Token")))throw new AccessStore.Rejected(403,"Sitzungspruefung fehlgeschlagen.");
                    if(!limiter.allow("admin-create:"+session.token(),30,60000))throw new AccessStore.Rejected(429,"Zu viele Codes erstellt. Bitte eine Minute warten.");
                    body(x); // A generic invitation is assigned to a player only when redeemed.
                    AccessStore.Issued issued=store.issue(null,7);
                    json(x,201,issued);return;
                }
                if(path.equals("/api/admin/modpacks")&&modpacks!=null){
                    if(method.equals("GET")){json(x,200,Map.of("packs",modpacks.adminPacks()));return;}
                    if(method.equals("POST")){
                        if(!session.csrf().equals(x.getRequestHeaders().getFirst("X-CSRF-Token")))throw new AccessStore.Rejected(403,"Sitzungspruefung fehlgeschlagen.");
                        JsonObject data=body(x);JsonElement enabled=data.get("enabled");
                        if(enabled==null||!enabled.isJsonPrimitive()||!enabled.getAsJsonPrimitive().isBoolean())throw new AccessStore.Rejected(400,"Download-Status fehlt.");
                        modpacks.select(string(data,"format",20),string(data,"filename",200),string(data,"version",60),enabled.getAsBoolean());json(x,200,Map.of("ok",true));return;
                    }
                    throw new AccessStore.Rejected(405,"Methode nicht erlaubt.");
                }
                if(path.equals("/api/admin/logout")&&method.equals("POST")){
                    if(!session.csrf().equals(x.getRequestHeaders().getFirst("X-CSRF-Token")))throw new AccessStore.Rejected(403,"Sitzungspruefung fehlgeschlagen.");
                    admin.logout(session);x.getResponseHeaders().set("Set-Cookie","blockpass_admin=; Max-Age=0"+cookieFlags);json(x,200,Map.of("ok",true));return;
                }
                throw new AccessStore.Rejected(404,"Nicht gefunden.");
            }
            if(path.equals("/api/modpacks")&&method.equals("GET")){json(x,200,Map.of("packs",modpacks==null?List.of():modpacks.publicPacks()));return;}
            if(path.startsWith("/downloads/")&&modpacks!=null){
                if(!Set.of("GET","HEAD").contains(method))throw new AccessStore.Rejected(405,"Methode nicht erlaubt.");
                download(x,path.substring("/downloads/".length()));return;
            }
            if(path.equals("/api/info")&&method.equals("GET")){json(x,200,Map.of("serverName",serverName,"serverAddress",serverAddress,"connected",true,"configured",true));return;}
            if(path.equals("/api/claim")&&method.equals("POST")){
                origin(x);if(!limiter.allow("claim:"+ip,8,600000)||!limiter.allow("global-claim",120,60000))throw new AccessStore.Rejected(429,"Zu viele Versuche. Bitte warte einige Minuten.");
                JsonObject data=body(x);json(x,200,service.claim(string(data,"code",80),string(data,"username",16),data.has("receipt")?string(data,"receipt",48):AccessStore.token(24)));return;
            }
            if(path.equals("/api/status")&&method.equals("POST")){
                origin(x);AccessStore.Entry e=store.status(string(body(x),"receipt",48));JsonObject out=new JsonObject();out.addProperty("username",e.username());out.addProperty("status",e.status());if(e.error()!=null)out.addProperty("error",e.error());json(x,200,out);return;
            }
            json(x,404,Map.of("error","Nicht gefunden."));
        }catch(AccessStore.Rejected e){if(e.httpStatus==429)x.getResponseHeaders().set("Retry-After","60");json(x,e.httpStatus,Map.of("error",e.getMessage()));}
        catch(Exception e){if(x.getResponseCode()<0)json(x,503,Map.of("error","Voruebergehend nicht erreichbar. Bitte erneut versuchen; eine angenommene Anfrage bleibt gespeichert."));}
        finally{timeout.cancel(false);x.close();}
    }
    private void download(HttpExchange x,String id)throws IOException,AccessStore.Rejected{
        if(!downloadSlots.tryAcquire())throw new AccessStore.Rejected(503,"Aktuell laufen zwei Downloads. Bitte kurz warten und erneut versuchen.");
        try(ModpackStore.Download file=modpacks.open(id)){
            long length=file.size();if(length<=0)throw new AccessStore.Rejected(404,"Modpack-Datei ist leer.");
            Headers h=x.getResponseHeaders();h.set("Content-Type","application/octet-stream");
            String fallback=file.filename().replaceAll("[^A-Za-z0-9._-]","_");
            h.set("Content-Disposition","attachment; filename=\""+fallback+"\"; filename*=UTF-8''"+URLEncoder.encode(file.filename(),StandardCharsets.UTF_8).replace("+","%20"));
            h.set("Content-Length",Long.toString(length));
            if(x.getRequestMethod().equals("HEAD")){x.sendResponseHeaders(200,-1);return;}
            x.sendResponseHeaders(200,length);ByteBuffer buffer=ByteBuffer.allocate(65536);long remaining=length;
            while(remaining>0){buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),remaining));int n=file.channel().read(buffer);if(n<0)throw new EOFException("Datei waehrend des Downloads geaendert.");if(n==0)continue;x.getResponseBody().write(buffer.array(),0,n);remaining-=n;}
        }finally{downloadSlots.release();}
    }
    @Override public void close(){active=false;server.stop(0);executor.shutdownNow();watchdog.shutdownNow();}
}
