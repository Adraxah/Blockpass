package de.adraxah.blockpass;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real HTTP + real disk persistence; only Minecraft/Mojang calls are simulated. */
public final class IntegrationTest {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static int passed;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);passed++;System.out.println("PASS "+label);}
    private static final UUID ID=UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private record Reply(int status,JsonObject body,String contentType){}
    private static final class Gateway implements WhitelistService.Gateway{
        final AtomicInteger lookups=new AtomicInteger(),writes=new AtomicInteger();
        final AtomicBoolean outage=new AtomicBoolean(),ban=new AtomicBoolean();
        public WhitelistService.Profile resolve(String name)throws Exception{lookups.incrementAndGet();if(outage.get())throw new WhitelistService.TemporaryFailure("Mojang offline");return new WhitelistService.Profile(ID,name);}
        public void whitelist(WhitelistService.Profile p)throws Exception{if(ban.get())throw new WhitelistService.PermanentFailure("Banned");writes.incrementAndGet();}
    }
    private static final class Fixture implements AutoCloseable{
        final Path file;
        final AccessStore store;
        final Gateway gateway=new Gateway();
        final WhitelistService service;
        final EmbeddedWebServer web;
        final String base;
        Fixture(Path file)throws Exception{this.file=file;store=new AccessStore(file);service=new WhitelistService(store,gateway,s->{});web=new EmbeddedWebServer(new InetSocketAddress("127.0.0.1",0),store,service,"Testserver","play.example.test","",null);web.start();base="http://127.0.0.1:"+web.port();}
        void startWorker(){service.start();}
        @Override public void close(){web.close();service.close();}
    }
    private static Reply request(Fixture f,String path,JsonObject data,String origin)throws Exception{
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(f.base+path)).timeout(Duration.ofSeconds(4));
        if(data!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(data.toString()));
        if(origin!=null)b.header("Origin",origin);
        HttpResponse<String> response=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(),response.headers().firstValue("content-type").orElse("").contains("json")?JsonParser.parseString(response.body()).getAsJsonObject():new JsonObject(),response.headers().firstValue("content-type").orElse(""));
    }
    private static JsonObject claim(String code,String name,String receipt){JsonObject x=new JsonObject();x.addProperty("code",code);x.addProperty("username",name);x.addProperty("receipt",receipt);return x;}
    private static JsonObject receipt(String receipt){JsonObject x=new JsonObject();x.addProperty("receipt",receipt);return x;}
    private static Path fresh()throws Exception{return Files.createTempDirectory("blockpass-test-").resolve("data.json");}
    private static void awaitStatus(Fixture f,String receipt,String status)throws Exception{
        long until=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<until){if(f.store.status(receipt).status().equals(status))return;Thread.sleep(50);}throw new AssertionError("Waiting for "+status);
    }
    public static void main(String[] args)throws Exception{
        Path file=fresh();String retained;
        try(Fixture f=new Fixture(file)){
            check(request(f,"/",null,null).status()==200,"bundled webpage served over real HTTP");
            check(request(f,"/app.js",null,null).contentType().startsWith("text/javascript"),"bundled JS served locally");
            check(request(f,"/font.ttf",null,null).status()==200,"pixel font included, no CDN dependency");
            check(request(f,"/config.yml",null,null).status()==404&&request(f,"/data.json",null,null).status()==404,"configuration and code hashes not exposed");
            check(request(f,"/api/admin/code",new JsonObject(),null).status()==404,"no unauthenticated web administration");
            check(request(f,"/api/info",null,null).body().get("serverName").getAsString().equals("Testserver"),"server identity from plugin settings");
            AccessStore.Issued issue=f.store.issue("Notch",7);String raw=Files.readString(file);
            check(!raw.contains(issue.code())&&!raw.contains(AccessStore.normalize(issue.code())),"raw invitation codes not saved to disk");
            int before=f.gateway.lookups.get();
            check(request(f,"/api/claim",claim(issue.code(),"WrongName",AccessStore.token(24)),f.base).status()==400&&f.gateway.lookups.get()==before,"bound name checked before Mojang lookup");
            check(request(f,"/api/claim",claim(issue.code(),"Notch",AccessStore.token(24)),"http://evil.test").status()==403,"cross-origin writes rejected");
            retained=AccessStore.token(24);JsonObject data=claim(issue.code(),"Notch",retained);
            check(request(f,"/api/claim",data,f.base).status()==200,"valid code queues request");
            check(f.store.status(retained).status().equals("pending")&&f.gateway.writes.get()==0,"no success before Minecraft confirmation");
            check(request(f,"/api/claim",data,f.base).status()==200,"same receipt can recover a lost HTTP response");
            check(request(f,"/api/claim",claim(issue.code(),"Notch",AccessStore.token(24)),f.base).status()==400,"different receipt cannot reuse a code");
            check(request(f,"/api/status",receipt(AccessStore.token(24)),f.base).status()==404,"random receipts cannot read player status");
        }
        try(Fixture restarted=new Fixture(file)){
            check(restarted.store.status(retained).status().equals("pending"),"pending request survives HTTP/plugin restart");restarted.startWorker();awaitStatus(restarted,retained,"applied");
            check(restarted.gateway.writes.get()==1,"recovered request applied once by worker");
            check(request(restarted,"/api/status",receipt(retained),restarted.base).body().get("status").getAsString().equals("applied"),"website receives confirmed whitelist success");
        }
        try(Fixture f=new Fixture(file)){check(f.store.status(retained).status().equals("applied"),"completed state survives another restart");}
        try(Fixture f=new Fixture(fresh())){
            AccessStore.Issued x=f.store.issue("Notch",7);f.gateway.outage.set(true);
            check(request(f,"/api/claim",claim(x.code(),"Notch",AccessStore.token(24)),f.base).status()==503,"Mojang failure reported without spending code");f.gateway.outage.set(false);
            String token=AccessStore.token(24);check(request(f,"/api/claim",claim(x.code(),"Notch",token),f.base).status()==200,"code still works after lookup outage");
            f.gateway.ban.set(true);f.startWorker();awaitStatus(f,token,"failed");check(f.gateway.writes.get()==0,"banned player not whitelisted");f.gateway.ban.set(false);f.store.retry(x.id().substring(0,8));awaitStatus(f,token,"applied");check(f.gateway.writes.get()==1,"administrator retry succeeds after ban removed");
            AccessStore.Issued revoked=f.store.issue("Notch",7);f.store.revoke(revoked.id());check(request(f,"/api/claim",claim(revoked.code(),"Notch",AccessStore.token(24)),f.base).status()==400,"revoked code rejected");
        }
        try(Fixture f=new Fixture(fresh())){
            AccessStore.Issued x=f.store.issue(null,7);ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                Future<Reply> a=pool.submit(()->request(f,"/api/claim",claim(x.code(),"Notch",AccessStore.token(24)),f.base));Future<Reply> b=pool.submit(()->request(f,"/api/claim",claim(x.code(),"Alex",AccessStore.token(24)),f.base));List<Integer> codes=new ArrayList<>(List.of(a.get().status(),b.get().status()));Collections.sort(codes);check(codes.equals(List.of(200,400)),"two players racing for an unbound code produce exactly one claim");
            }finally{pool.shutdownNow();}
            check(f.store.pending().size()==1,"only one persistent job after concurrent requests");
        }
        try(Fixture f=new Fixture(fresh())){
            for(int i=0;i<8;i++)request(f,"/api/claim",claim("ABCDE-ABCDE-ABCDE-ABCDE","Notch",AccessStore.token(24)),f.base);
            check(request(f,"/api/claim",claim("ABCDE-ABCDE-ABCDE-ABCDE","Notch",AccessStore.token(24)),f.base).status()==429,"repeated guesses rate limited");
        }
        try(Fixture f=new Fixture(fresh())){
            JsonObject huge=new JsonObject();huge.addProperty("code","a".repeat(3000));check(request(f,"/api/claim",huge,f.base).status()==413,"oversized request rejected");
            AccessStore.Issued x=f.store.issue(null,1);String saved=Files.readString(f.file);long now=System.currentTimeMillis()/1000;
            saved=saved.replace("\"created\": "+now,"\"created\": "+(now-172800)).replace("\"expires\": "+x.expires(),"\"expires\": "+(now-86400));Files.writeString(f.file,saved);
            AccessStore expired=new AccessStore(f.file);boolean rejected=false;try{expired.check(x.code(),"Notch");}catch(AccessStore.Rejected ex){rejected=true;}check(rejected,"expired code rejected after restart");
        }
        Path broken=fresh();Files.writeString(broken,"broken");boolean safe=false;try{new AccessStore(broken);}catch(java.io.IOException expected){safe=true;}check(safe&&Files.readString(broken).equals("broken"),"corrupt data fails closed without overwriting original");
        System.out.println("RESULT: "+passed+" checks passed. Minecraft gateway simulated; HTTP and disk are real.");
    }
}
