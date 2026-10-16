package de.adraxah.blockoverview;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Deterministic schedule tests plus real HTTP, authentication and disk persistence. */
public final class PlayerUpdatesTest {
    private static final Gson JSON = new Gson();
    private static int passed;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); passed++; System.out.println("PASS " + message); }
    private static long at(String berlin) { return LocalDateTime.parse(berlin).atZone(ContentStore.ZONE).toInstant().toEpochMilli(); }
    private static LiveStatus.Snapshot snapshot(long time, int online, int peak) {
        return new LiveStatus.Snapshot(time,time-3600000,online,20,peak,"1.20.4",online==0?List.of():List.of(IntegrationTest.player("Alex")),20.0);
    }
    private static JsonObject json(ContentStore.Settings settings) { return JSON.toJsonTree(settings).getAsJsonObject(); }
    private static ContentStore.Settings schedule(ContentStore.Settings base, boolean enabled, String start, String end) throws Exception {
        JsonObject s=json(base); s.addProperty("playerPauseEnabled",enabled); s.addProperty("playerPauseStart",start); s.addProperty("playerPauseEnd",end); return ContentStore.validate(s);
    }
    private static boolean invalid(JsonObject s) { try { ContentStore.validate(s); return false; } catch (HttpError e) { return e.status==400; } }
    private static HttpResponse<String> request(HttpClient client, String base, String path, JsonObject body, String csrf) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5));
        if(body!=null) builder.header("Content-Type","application/json").header("Origin",base).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if(csrf!=null) builder.header("X-CSRF-Token",csrf);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static JsonObject body(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
    public static void main(String[] args) throws Exception {
        Path file=Files.createTempDirectory("player-pause-").resolve("content.json");
        ContentStore content=new ContentStore(file); ContentStore.Settings defaults=content.get().settings();
        check(defaults.playerPauseEnabled() && defaults.playerPauseStart().equals("06:00") && defaults.playerPauseEnd().equals("16:00"),"default pause is enabled from 06:00 to 16:00");
        JsonObject legacy=JSON.toJsonTree(content.get()).getAsJsonObject();
        for(String key:List.of("playerPauseEnabled","playerPauseStart","playerPauseEnd")) legacy.getAsJsonObject("settings").remove(key);
        Files.writeString(file,legacy.toString());
        check(new ContentStore(file).get().settings().equals(defaults),"old saved settings import new pause defaults without losing content");
        for(String value:List.of("24:00","6:00","06:60","12:00:00","")) { JsonObject s=json(defaults);s.addProperty("playerPauseStart",value);check(invalid(s),"invalid time rejected: "+value); }
        JsonObject s=json(defaults);s.addProperty("playerPauseEnd","06:00");check(invalid(s),"equal pause boundaries rejected");
        s=json(defaults);s.addProperty("playerPauseEnabled","false");check(invalid(s),"non-boolean pause switch rejected");

        long before=at("2026-10-16T05:59:59"), start=at("2026-10-16T06:00"), end=at("2026-10-16T16:00");
        PlayerUpdates updates=new PlayerUpdates(); LiveStatus.Snapshot original=snapshot(before,1,4);
        check(!updates.select(original,defaults,before).paused(),"updates remain live before 06:00");
        var frozen=updates.select(snapshot(start,8,9),defaults,start);
        check(frozen.paused() && frozen.snapshot()==original && frozen.resumesAt()==end,"06:00 freezes last pre-pause count, names and peak");
        check(updates.select(snapshot(end-1,12,15),defaults,end-1).snapshot()==original,"all player fields remain frozen until 16:00, including new visits");
        LiveStatus.Snapshot current=snapshot(end,12,15);
        check(updates.select(current,defaults,end).snapshot()==current && !updates.select(current,defaults,end).paused(),"16:00 resumes current players and the measured peak");
        check(!updates.select(current,defaults,end+15000).available(),"stale live data is unavailable outside a pause");
        long nextStart=at("2026-10-17T06:00"); updates.select(snapshot(nextStart-1000,0,15),defaults,nextStart-1000);
        check(updates.select(snapshot(nextStart,3,15),defaults,nextStart).snapshot().online()==0,"next day's pause uses the new baseline, including zero players");
        var disabled=schedule(defaults,false,"06:00","16:00");
        check(updates.select(snapshot(nextStart+1000,5,15),disabled,nextStart+1000).snapshot().online()==5,"admin can disable a running pause immediately");
        var shifted=schedule(defaults,true,"07:00","17:00");
        check(!updates.select(snapshot(nextStart+2000,6,15),shifted,nextStart+2000).paused(),"changed start time takes effect without restart");
        PlayerUpdates startup=new PlayerUpdates();long noon=at("2026-10-16T12:00");
        check(!startup.select(null,defaults,noon).available(),"startup without Minecraft data never invents a player count");
        var first=snapshot(noon+1000,1,1);
        check(startup.select(first,defaults,noon+1000).snapshot()==first && startup.select(snapshot(noon+2000,2,2),defaults,noon+2000).snapshot()==first,"startup during pause captures exactly one available baseline");
        check(startup.select(first,defaults,noon+60000).available(),"intentional frozen data stays available while live data becomes stale");
        var overnight=schedule(defaults,true,"22:00","08:00");
        check(PlayerUpdates.window(overnight,at("2026-10-16T23:00"))!=null && PlayerUpdates.window(overnight,at("2026-10-17T07:59"))!=null && PlayerUpdates.window(overnight,at("2026-10-17T08:00"))==null,"overnight pause spans midnight and resumes at its end");
        check(PlayerUpdates.window(defaults,Instant.parse("2026-07-01T04:00:00Z").toEpochMilli())!=null && PlayerUpdates.window(defaults,Instant.parse("2026-12-01T04:00:00Z").toEpochMilli())==null,"06:00 follows Berlin summer and winter offsets");
        var spring=PlayerUpdates.window(overnight,at("2026-03-29T07:00"));
        var autumn=PlayerUpdates.window(overnight,at("2026-10-25T07:00"));
        check(spring.end()-spring.start()==9*3600000L && autumn.end()-autumn.start()==11*3600000L,"overnight durations account for both DST changes");

        LocalTime local=LocalTime.now(ContentStore.ZONE); DateTimeFormatter fmt=DateTimeFormatter.ofPattern("HH:mm");
        JsonObject active=json(schedule(defaults,true,local.minusMinutes(30).format(fmt),local.plusMinutes(30).format(fmt)));
        content.update(content.get().revision(),active);
        LiveStatus live=new LiveStatus(); long time=System.currentTimeMillis(); live.publish(snapshot(time,1,4));
        try(EmbeddedWebServer web=new EmbeddedWebServer(new InetSocketAddress("127.0.0.1",0),content,live,new AdminAccess("admin",IntegrationTest.hash()),"",null)) {
            web.start();String base="http://127.0.0.1:"+web.port();
            HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            var beta=IntegrationTest.player("Steve");
            live.publish(new LiveStatus.Snapshot(System.currentTimeMillis(),time-5000,2,20,7,"1.20.4",List.of(beta),12.5));
            JsonObject data=body(request(client,base,"/api/overview",null,null)).getAsJsonObject("live");
            check(data.get("online").getAsInt()==1 && data.get("peakPlayers").getAsInt()==4 && data.getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString().equals("Alex"),"HTTP retains frozen count, peak and names while Minecraft changes");
            check(data.get("playersPaused").getAsBoolean() && data.get("playersObservedAt").getAsLong()==time && data.get("tps").getAsDouble()==12.5 && data.get("processStartedAt").getAsLong()==time-5000,"HTTP supplies pause timestamp while TPS and uptime stay live");
            JsonObject visit=body(request(HttpClient.newHttpClient(),base,"/api/overview",null,null)).getAsJsonObject("live");
            check(visit.equals(data),"new visitor receives exactly the same frozen data");
            check(request(client,base,"/heads/"+IntegrationTest.player("Alex").uuid()+".png",null,null).statusCode()==200 && request(client,base,"/heads/"+beta.uuid()+".png",null,null).statusCode()==404,"head routes follow frozen roster, not newly joined players");
            check(live.displayedPlayers().get(0).name().equals("Alex"),"frozen players remain available for head-cache retention");
            JsonObject login=new JsonObject();login.addProperty("username","admin");login.addProperty("password",IntegrationTest.PASSWORD);
            String csrf=body(request(client,base,"/api/admin/login",login,null)).get("csrf").getAsString();
            JsonObject edit=body(request(client,base,"/api/admin/settings",null,null));edit.getAsJsonObject("settings").addProperty("showPlayers",false);
            check(request(client,base,"/api/admin/settings",edit,csrf).statusCode()==200 && body(request(client,base,"/api/overview",null,null)).getAsJsonObject("live").getAsJsonArray("players").isEmpty() && request(client,base,"/heads/"+IntegrationTest.player("Alex").uuid()+".png",null,null).statusCode()==404,"privacy setting immediately hides frozen names and heads");
            edit=body(request(client,base,"/api/admin/settings",null,null));edit.getAsJsonObject("settings").addProperty("playerPauseEnabled",false);edit.getAsJsonObject("settings").addProperty("showPlayers",true);
            check(request(client,base,"/api/admin/settings",edit,csrf).statusCode()==200,"admin saves pause switch through authenticated API");
            data=body(request(client,base,"/api/overview",null,null)).getAsJsonObject("live");
            check(!data.get("playersPaused").getAsBoolean() && data.get("online").getAsInt()==2 && data.get("peakPlayers").getAsInt()==7 && data.getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString().equals("Steve"),"disabled pause immediately exposes current snapshot");
            check(!new ContentStore(file).get().settings().playerPauseEnabled(),"saved pause configuration survives restart");
            edit=body(request(client,base,"/api/admin/settings",null,null));edit.getAsJsonObject("settings").addProperty("playerPauseStart","29:00");
            check(request(client,base,"/api/admin/settings",edit,csrf).statusCode()==400 && !new ContentStore(file).get().settings().playerPauseStart().equals("29:00"),"invalid admin time rejected without changing the saved schedule");
        }
        System.out.println("RESULT: "+passed+" player-update checks passed. Real HTTP and disk; Minecraft snapshots simulated.");
    }
}
