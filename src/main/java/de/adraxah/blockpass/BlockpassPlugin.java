package de.adraxah.blockpass;

import com.google.gson.*;
import de.adraxah.blockoverview.OverviewModule;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Standalone plugin: HTTP server, bundled UI, local invitations, native whitelist. */
public final class BlockpassPlugin extends JavaPlugin implements CommandExecutor,TabCompleter {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private AccessStore store;
    private ModpackStore modpacks;
    private volatile EmbeddedWebServer web;
    private OverviewModule overview;
    private WhitelistService service;
    private ExecutorService admin;
    private volatile long epoch;
    private volatile boolean running;
    private String address="";
    @Override public void onEnable(){
        if(getServer().getPluginManager().getPlugin("BlockOverview")!=null){
            getLogger().severe("Bitte die alte BlockOverview-JAR entfernen und neu starten. Blockpass 3 enthält bereits beide Webseiten; die Datenordner behalten.");
            getServer().getPluginManager().disablePlugin(this);return;
        }
        try{if(LegacyMigration.run(getDataFolder().toPath()))getLogger().info("Bestehende Übersichtseinstellungen übernommen. Der alte Datenordner bleibt als Sicherung erhalten.");}
        catch(Exception e){getLogger().severe("Übernahme fehlgeschlagen: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        saveDefaultConfig();
        // Existing v1 configuration receives the standalone defaults. Cloud credentials are unused.
        getConfig().options().copyDefaults(true);
        for(String old:List.of("site-url","api-key","sites-access-token","poll-seconds","allow-local-http"))getConfig().set(old,null);
        // Adopt the new display name for installations still using the original default.
        if ("Mein Minecraft-Server".equals(getConfig().getString("server.name")))
            getConfig().set("server.name", "Nürnberger.lol");
        saveConfig();
        Objects.requireNonNull(getCommand("blockpass")).setExecutor(this);
        Objects.requireNonNull(getCommand("blockpass")).setTabCompleter(this);
        admin=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Blockpass-admin");t.setDaemon(true);return t;});
        try{store=new AccessStore(getDataFolder().toPath().resolve("data.json"));modpacks=new ModpackStore(getDataFolder().toPath());configure();}
        catch(Exception e){getLogger().severe("Blockpass konnte nicht starten: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);}
    }
    @Override public void onDisable(){stopWeb();if(admin!=null)admin.shutdownNow();}
    private void stopWeb(){running=false;epoch++;if(overview!=null){overview.close();overview=null;}if(web!=null){web.close();web=null;}if(service!=null){service.close();service=null;}}
    private void configure()throws Exception{
        stopWeb();reloadConfig();
        if(!Bukkit.getOnlineMode())throw new IllegalStateException("online-mode=true erforderlich. Offline-Server und Offline-Proxy-Backends werden nicht unterstützt.");
        String bind=getConfig().getString("web.bind-address","0.0.0.0");int port=getConfig().getInt("web.port",8080);
        if(port<1||port>65535||port==Bukkit.getPort())throw new IllegalArgumentException("web.port muss ein freier Port zwischen 1 und 65535 sein, nicht der Minecraft-Port.");
        String publicUrl=getConfig().getString("web.public-url","").trim();String publicOrigin="";
        if(!publicUrl.isEmpty()){
            URI u=URI.create(publicUrl);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||(!u.getPath().isEmpty()&&!u.getPath().equals("/")))throw new IllegalArgumentException("web.public-url muss eine http(s)-Adresse ohne Unterpfad sein.");
            publicOrigin=u.getScheme()+"://"+u.getRawAuthority();
        }
        String serverName=getConfig().getString("server.name","Nürnberger.lol"),serverAddress=getConfig().getString("server.address","");
        if(serverName.length()>64||serverAddress.length()>120)throw new IllegalArgumentException("Servername oder Serveradresse zu lang.");
        SSLContext tls=createTls();long generation=epoch;
        running=true;
        service=new WhitelistService(store,new WhitelistService.Gateway(){
            public WhitelistService.Profile resolve(String name)throws Exception{
                HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"+name)).timeout(Duration.ofSeconds(7)).GET().build();
                HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()==404||response.statusCode()==204)throw new AccessStore.Rejected(400,"Dieser Minecraft-Java-Name wurde nicht gefunden.");
                if(response.statusCode()!=200||response.body().length()>16384)throw new AccessStore.Rejected(503,"Minecraft-Namensprüfung nicht erreichbar. Dein Code bleibt gültig.");
                JsonObject data=JsonParser.parseString(response.body()).getAsJsonObject();String id=data.get("id").getAsString(),canonical=data.get("name").getAsString();
                if(!id.matches("[a-fA-F0-9]{32}")||!canonical.equalsIgnoreCase(name))throw new AccessStore.Rejected(503,"Minecraft-Profil nicht bestätigt.");
                UUID uuid=UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})","$1-$2-$3-$4-$5"));return new WhitelistService.Profile(uuid,canonical);
            }
            public void whitelist(WhitelistService.Profile profile)throws Exception{
                if(!running||generation!=epoch)throw new InterruptedException();
                // Potentially blocking profile/cache lookup stays outside the Minecraft tick thread.
                OfflinePlayer player=Bukkit.getOfflinePlayer(profile.name());
                if(!profile.uuid().equals(player.getUniqueId()))throw new WhitelistService.PermanentFailure("Spielerprofil stimmt nicht mehr mit der gespeicherten UUID überein.");
                if(!running||generation!=epoch)throw new InterruptedException();
                Future<Boolean> write=Bukkit.getScheduler().callSyncMethod(BlockpassPlugin.this,()->{
                    if(!running||generation!=epoch)return false;
                    if(!Bukkit.getOnlineMode())throw new IllegalStateException("Online-Modus wurde deaktiviert.");
                    if(player.isBanned())throw new IllegalStateException("Spieler ist gesperrt.");
                    player.setWhitelisted(true);
                    if(!player.isWhitelisted())throw new IllegalStateException("Whitelist-Eintrag fehlt.");
                    return true;
                });
                try{if(!write.get(15,TimeUnit.SECONDS))throw new InterruptedException();}
                catch(TimeoutException e){write.cancel(false);throw new WhitelistService.TemporaryFailure("Minecraft-Hauptthread ausgelastet.");}
                catch(ExecutionException e){throw new WhitelistService.PermanentFailure("Whitelist konnte nicht geschrieben werden; Spielersperren und Serverlog prüfen.");}
            }
        },message->getLogger().warning(message));
        try{
            web=new EmbeddedWebServer(null,store,service,serverName,serverAddress,publicOrigin,tls,new AdminAccess(getConfig().getString("admin.username","admin"),getConfig().getString("admin.password-hash","")),modpacks);
            overview=new OverviewModule(this);overview.start(web::handle,tls);service.start();
            if(getConfig().getBoolean("enable-whitelist",true))Bukkit.setWhitelist(true);
            address=(publicOrigin.isEmpty()?(tls==null?"http":"https")+"://SERVER-IP:"+port:publicOrigin)+"/whitelist/";
            getLogger().info("Webseite läuft auf "+bind+":"+port+". Adresse für Spieler: "+address);
            getLogger().info("Zugangscode erstellen: /blockpass code [Spielername|*] [Tage]");
        }catch(Exception e){stopWeb();throw e;}
    }
    private SSLContext createTls()throws Exception{
        if(!getConfig().getBoolean("web.https.enabled",false))return null;
        Path file=getDataFolder().toPath().resolve(getConfig().getString("web.https.keystore-file","web.p12"));
        String variable=getConfig().getString("web.https.password-env","BLOCKPASS_KEYSTORE_PASSWORD"),value=System.getenv(variable);
        if(value==null)throw new IllegalArgumentException("HTTPS-Keystore-Passwort fehlt in Umgebungsvariable "+variable);
        char[] password=value.toCharArray();try{
            KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(file)){keys.load(in,password);}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);
            SSLContext context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),null,null);return context;
        }finally{Arrays.fill(password,'\0');}
    }
    private void reply(CommandSender sender,String text){
        if(!isEnabled())return;try{Bukkit.getScheduler().runTask(this,()->sender.sendMessage(ChatColor.GREEN+"[Blockpass] "+ChatColor.GRAY+text));}catch(IllegalStateException ignored){}
    }
    @FunctionalInterface private interface Task{void run()throws Exception;}
    private void work(CommandSender sender,Task task){
        try{admin.submit(()->{try{task.run();}catch(AccessStore.Rejected e){reply(sender,e.getMessage());}catch(Exception e){reply(sender,"Aktion fehlgeschlagen. Bitte Serverlog prüfen.");getLogger().warning("Code-Verwaltung: "+e.getClass().getSimpleName());}});}
        catch(RejectedExecutionException e){sender.sendMessage("Blockpass wird gerade beendet.");}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("blockpass.admin")){sender.sendMessage(ChatColor.RED+"Keine Berechtigung.");return true;}
        if(args.length==0){sender.sendMessage("/blockpass code [Name|*] [Tage] | list | revoke <ID> | retry <ID> | status | reload | cleanup");return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("status")&&args.length==1){sender.sendMessage("Whitelist: "+(running&&web!=null?address:"gestoppt")+" | Übersicht: "+(overview!=null?overview.address():"gestoppt"));return true;}
        if(sub.equals("reload")&&args.length==1){try{configure();sender.sendMessage("Beide Webseiten neu geladen. Whitelist: "+address+" | Übersicht: "+overview.address());}catch(Exception e){stopWeb();getLogger().severe("Webserver konnten nicht starten: "+e.getMessage());sender.sendMessage("Webserver gestoppt. Ports/Konfiguration und Serverlog prüfen, dann /blockpass reload.");}return true;}
        if(sub.equals("code")){
            if(args.length>3||(args.length>=2&&!args[1].equals("*")&&!AccessStore.validName(args[1]))){sender.sendMessage("/blockpass code [Minecraft-Name|*] [Tage 1-90]");return true;}
            int days=7;try{if(args.length==3)days=Integer.parseInt(args[2]);}catch(NumberFormatException e){days=0;}final int validity=days;
            work(sender,()->{AccessStore.Issued x=store.issue(args.length==1||args[1].equals("*")?null:args[1],validity);reply(sender,"Code für "+(x.username()==null?"beliebigen Namen":x.username())+": "+x.code());reply(sender,"ID: "+x.id()+" | "+validity+" Tage gültig | einmal verwendbar.");});return true;
        }
        if(sub.equals("list")&&args.length==1){work(sender,()->{List<AccessStore.Entry> rows=store.recent();if(rows.isEmpty())reply(sender,"Noch keine Codes. /blockpass code");for(AccessStore.Entry e:rows){String state=e.status().equals("unused")&&e.expires()<System.currentTimeMillis()/1000?"expired":e.status();reply(sender,e.id().substring(0,8)+" | "+(e.username()!=null?e.username():e.boundName()!=null?e.boundName():"*")+" | "+state);}});return true;}
        if(sub.equals("revoke")&&args.length==2){work(sender,()->{store.revoke(args[1]);reply(sender,"Code widerrufen.");});return true;}
        if(sub.equals("retry")&&args.length==2){work(sender,()->{store.retry(args[1]);reply(sender,"Freischaltung wird erneut versucht.");});return true;}
        if(sub.equals("cleanup")&&args.length==1){work(sender,()->reply(sender,store.cleanup()+" alte Einträge entfernt (seit mindestens 90 Tagen abgelaufen, keine offenen Anfragen)."));return true;}
        sender.sendMessage("Unbekannter Befehl. Hilfe: /blockpass");return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(!sender.hasPermission("blockpass.admin"))return List.of();if(args.length==1)return List.of("code","list","revoke","retry","status","reload","cleanup").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();return List.of();
    }
}
