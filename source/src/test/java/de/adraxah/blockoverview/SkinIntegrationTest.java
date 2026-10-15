package de.adraxah.blockoverview;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

public final class SkinIntegrationTest {
    private static int passed;
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); passed++; System.out.println("PASS "+message); }
    static byte[] skin() throws IOException {
        BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=8;y<16;y++)for(int x=8;x<16;x++)image.setRGB(x,y,0xff3b8e69);
        image.setRGB(40,8,0xffff0000); // A single helmet-layer pixel over the face.
        ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(image,"png",out); return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        byte[] skin=skin(); BufferedImage head=ImageIO.read(new ByteArrayInputStream(SkinHeads.render(skin)));
        check(head.getWidth()==32 && head.getHeight()==32,"head rendered at 32 by 32 pixels");
        check(head.getRGB(0,0)==0xffff0000 && head.getRGB(3,3)==0xffff0000,"helmet overlay preserved with nearest-neighbor pixels");
        check(head.getRGB(4,0)==0xff3b8e69 && head.getRGB(31,31)==0xff3b8e69,"transparent overlay retains face pixels");
        check(ImageIO.read(new ByteArrayInputStream(SkinHeads.defaultHead()))!=null,"local fallback is a valid head image");
        URL valid=new URL("http://textures.minecraft.net/texture/"+"a".repeat(64));
        check(SkinHeads.allowed(valid).getScheme().equals("https"),"official HTTP texture upgraded to HTTPS");
        for(String url:List.of("http://127.0.0.1/texture/"+"a".repeat(64),"https://textures.minecraft.net.evil.test/texture/"+"a".repeat(64),"https://textures.minecraft.net:8443/texture/"+"a".repeat(64),"https://textures.minecraft.net/texture/"+"a".repeat(64)+"?url=http://127.0.0.1"))check(SkinHeads.allowed(new URL(url))==null,"non-official texture target rejected");
        boolean rejected=false; try{SkinHeads.render(new byte[300000]);}catch(IOException e){rejected=true;}check(rejected,"oversized skin rejected before decoding");
        rejected=false;try{SkinHeads.render("not an image".getBytes());}catch(IOException e){rejected=true;}check(rejected,"invalid image rejected");
        BufferedImage large=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB); ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(large,"png",out);
        rejected=false;try{SkinHeads.render(out.toByteArray());}catch(IOException e){rejected=true;}check(rejected,"unexpected PNG dimensions rejected");
        AtomicInteger fetched=new AtomicInteger(); UUID id=UUID.randomUUID();
        try(SkinHeads cache=new SkinHeads(uri->{fetched.incrementAndGet();return skin;})) {
            cache.observe(id,valid);long until=System.currentTimeMillis()+3000;while(!cache.ready(id) && System.currentTimeMillis()<until)Thread.sleep(10);
            check(cache.ready(id) && fetched.get()==1,"skin fetched asynchronously and cached");
            for(int i=0;i<30;i++)cache.observe(id,valid);
            check(fetched.get()==1,"repeated snapshots reuse cached skin");
            ContentStore content=new ContentStore(Files.createTempDirectory("skin-http-").resolve("content.json"));
            LiveStatus live=new LiveStatus();live.publish(new LiveStatus.Snapshot(System.currentTimeMillis(),System.currentTimeMillis()-10000,1,20,1,"1.20.4",List.of(new LiveStatus.Player("Alex",id)),20.0));
            try(EmbeddedWebServer web=new EmbeddedWebServer(new InetSocketAddress("127.0.0.1",0),content,live,new AdminAccess("admin",IntegrationTest.hash()),"",null,cache)) {
                web.start();String base="http://127.0.0.1:"+web.port();HttpClient client=HttpClient.newHttpClient();
                var image=client.send(HttpRequest.newBuilder(URI.create(base+"/heads/"+id+".png")).build(),HttpResponse.BodyHandlers.ofByteArray());
                check(image.statusCode()==200 && Arrays.equals(image.body(),cache.get(id)),"head PNG served by the embedded overview server");
                check(image.headers().firstValue("content-type").orElse("").equals("image/png") && image.headers().firstValue("cache-control").orElse("").contains("max-age=60"),"head MIME and short cache lifetime correct");
                var json=client.send(HttpRequest.newBuilder(URI.create(base+"/api/overview")).build(),HttpResponse.BodyHandlers.ofString());
                check(json.body().contains(id.toString()) && json.body().contains("Alex"),"public player list includes name and UUID");
                var unknown=client.send(HttpRequest.newBuilder(URI.create(base+"/heads/"+UUID.randomUUID()+".png")).build(),HttpResponse.BodyHandlers.ofString());
                check(unknown.statusCode()==404 && fetched.get()==1,"unknown UUID cannot trigger remote downloads");
                JsonObject settings=new Gson().toJsonTree(content.get().settings()).getAsJsonObject(); settings.addProperty("showPlayers",false);content.update(content.get().revision(),settings);
                var hidden=client.send(HttpRequest.newBuilder(URI.create(base+"/heads/"+id+".png")).build(),HttpResponse.BodyHandlers.ofString());
                check(hidden.statusCode()==404,"hidden player list also disables head access");
                var fallback=client.send(HttpRequest.newBuilder(URI.create(base+"/default-head.png")).build(),HttpResponse.BodyHandlers.ofByteArray());
                check(fallback.statusCode()==200 && ImageIO.read(new ByteArrayInputStream(fallback.body()))!=null,"fallback remains available without exposing a player");
            }
            cache.retain(Set.of());check(!cache.ready(id),"offline-player cache entries removed");
        }
        System.out.println("RESULT: "+passed+" skin checks passed. Real PNG/HTTP; texture network response simulated.");
    }
}
