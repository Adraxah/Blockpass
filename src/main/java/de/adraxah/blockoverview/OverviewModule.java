package de.adraxah.blockoverview;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import javax.net.ssl.*;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;

/** Overview is a module owned by Blockpass, not a second Bukkit plugin. */
public final class OverviewModule implements AutoCloseable {
    private final JavaPlugin plugin;
    private final LiveStatus live=new LiveStatus();
    private final LiveStatus.TickRate ticks=new LiveStatus.TickRate();
    private final SkinHeads heads=new SkinHeads();
    private final long processStartedAt=ManagementFactory.getRuntimeMXBean().getStartTime();
    private EmbeddedWebServer web;
    private BukkitTask task;
    private int counter, peak;
    private String address="";
    public OverviewModule(JavaPlugin plugin) { this.plugin=plugin; }
    public String address() { return address; }
    public void start(com.sun.net.httpserver.HttpHandler whitelist,SSLContext tls) throws Exception {
        FileConfiguration config=plugin.getConfig();
        String bind=config.getString("web.bind-address","0.0.0.0");
        int port=config.getInt("web.port",8080);
        if(port<1 || port>65535 || port==Bukkit.getPort())throw new IllegalArgumentException("web.port muss frei sein und sich vom Minecraft-Port unterscheiden.");
        String publicUrl=config.getString("web.public-url","").trim(), origin="";
        if(!publicUrl.isEmpty()) {
            URI u=URI.create(publicUrl);
            if(!Set.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null || (!u.getPath().isEmpty() && !u.getPath().equals("/")))throw new IllegalArgumentException("web.public-url muss eine http(s)-Adresse ohne Unterpfad sein.");
            origin=u.getScheme()+"://"+u.getRawAuthority();
        }
        ContentStore content=new ContentStore(plugin.getDataFolder().toPath().resolve("overview/content.json"));
        AdminAccess admin=new AdminAccess(config.getString("overview.admin.username",config.getString("admin.username","admin")),config.getString("overview.admin.password-hash",config.getString("admin.password-hash","")));
        try {
            sample();
            web=new EmbeddedWebServer(new InetSocketAddress(bind,port),content,live,admin,origin,tls,heads);
            web.mountWhitelist(whitelist);
            task=Bukkit.getScheduler().runTaskTimer(plugin,()->{ ticks.tick(System.nanoTime()); if(++counter%20==0)sample(); },1L,1L);
            web.start();
            address=origin.isEmpty()?(tls==null?"http":"https")+"://SERVER-IP:"+port:origin;
            plugin.getLogger().info("Übersicht: "+address+" | Admin direkt unter /admin");
        } catch(Exception e) { close(); throw e; }
    }
    private void sample() {
        List<LiveStatus.Player> players=new ArrayList<>(); Set<UUID> ids=new HashSet<>();
        for(org.bukkit.entity.Player player:Bukkit.getOnlinePlayers()) {
            UUID id=player.getUniqueId(); ids.add(id); players.add(new LiveStatus.Player(player.getName(),id));
            URL skin=null;
            try { skin=player.getPlayerProfile().getTextures().getSkin(); } catch(RuntimeException ignored) {}
            heads.observe(id,skin);
        }
        players.sort(Comparator.comparing(LiveStatus.Player::name,String.CASE_INSENSITIVE_ORDER)); peak=Math.max(peak,players.size());
        live.publish(new LiveStatus.Snapshot(System.currentTimeMillis(),processStartedAt,players.size(),Bukkit.getMaxPlayers(),peak,Bukkit.getBukkitVersion().split("-",2)[0],players,ticks.value()));
        for (LiveStatus.Player player : live.displayedPlayers()) ids.add(player.uuid());
        heads.retain(ids);
    }
    @Override public void close() { if(task!=null){task.cancel();task=null;} if(web!=null){web.close();web=null;} heads.close(); }
}
