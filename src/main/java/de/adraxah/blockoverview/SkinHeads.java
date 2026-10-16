package de.adraxah.blockoverview;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** Only online-player texture URLs are queued; browsers receive same-origin head images. */
public final class SkinHeads implements AutoCloseable {
    @FunctionalInterface public interface Fetcher { byte[] fetch(URI uri) throws Exception; }
    private static final int MAX_BYTES=262144;
    private static final byte[] DEFAULT_HEAD=fallback();
    private static final class Entry { URI source; byte[] png; long nextAttempt; boolean loading; }
    private final Map<UUID,Entry> cache=new HashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"Blockpass-skins");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Fetcher fetcher;
    private volatile boolean closed;
    public SkinHeads() {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
        fetcher=uri->{
            HttpRequest req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<InputStream> response=client.send(req,HttpResponse.BodyHandlers.ofInputStream());
            // Body read is independently time-limited: ofInputStream completes at headers.
            try(InputStream body=response.body()) {
                if(response.statusCode()!=200)throw new IOException("Skin nicht verfügbar.");
                ScheduledFuture<?> deadline=closer.schedule(()->{try{body.close();}catch(IOException ignored){}},6,TimeUnit.SECONDS);
                try { byte[] bytes=body.readNBytes(MAX_BYTES+1); if(bytes.length>MAX_BYTES)throw new IOException("Skin zu groß."); return bytes; }
                finally { deadline.cancel(false); }
            }
        };
        init();
    }
    public SkinHeads(Fetcher fetcher) { this.fetcher=fetcher; init(); }
    private final ScheduledThreadPoolExecutor closer=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"Blockpass-skin-timeout");t.setDaemon(true);return t;});
    private void init() { workers.allowCoreThreadTimeOut(true); closer.setRemoveOnCancelPolicy(true); }
    static URI allowed(URL url) {
        if(url==null)return null;
        try {
            URI u=url.toURI();
            if(!Set.of("http","https").contains(u.getScheme()) || !"textures.minecraft.net".equalsIgnoreCase(u.getHost()) || u.getUserInfo()!=null || u.getPort()!=-1 || u.getQuery()!=null || u.getFragment()!=null || !u.getRawPath().matches("/texture/[a-fA-F0-9]{32,64}"))return null;
            return URI.create("https://textures.minecraft.net"+u.getRawPath());
        } catch(Exception e) { return null; }
    }
    public synchronized void observe(UUID uuid,URL skin) {
        if(closed)return;
        URI uri=allowed(skin);
        Entry entry=cache.get(uuid);
        if(entry==null) { if(cache.size()>=1024)return; entry=new Entry(); cache.put(uuid,entry); }
        if(!Objects.equals(entry.source,uri)) { entry.source=uri; entry.png=null; entry.nextAttempt=0; }
        if(uri==null || entry.loading || entry.nextAttempt>System.currentTimeMillis())return;
        entry.loading=true; Entry target=entry;
        try { workers.execute(()->{
            byte[] result=null;
            try { result=render(fetcher.fetch(uri)); } catch(Exception ignored) {}
            synchronized(SkinHeads.this) {
                target.loading=false;
                if(!closed && Objects.equals(uri,target.source)) { if(result!=null)target.png=result; target.nextAttempt=System.currentTimeMillis()+(result==null?60000:3600000); }
            }
        }); } catch(RejectedExecutionException e) { target.loading=false; target.nextAttempt=System.currentTimeMillis()+10000; }
    }
    public synchronized void retain(Set<UUID> online) { cache.keySet().removeIf(id->!online.contains(id)); }
    public synchronized byte[] get(UUID uuid) { Entry e=cache.get(uuid); return (e!=null && e.png!=null?e.png:DEFAULT_HEAD).clone(); }
    public synchronized boolean ready(UUID uuid) { Entry e=cache.get(uuid); return e!=null && e.png!=null; }
    public static byte[] defaultHead() { return DEFAULT_HEAD.clone(); }
    static byte[] render(byte[] bytes) throws IOException {
        if(bytes==null || bytes.length>MAX_BYTES || bytes.length<8 || !Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))throw new IOException("Kein PNG-Skin.");
        try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(in);
            if(!readers.hasNext())throw new IOException("Skin nicht lesbar.");
            ImageReader reader=readers.next();
            try {
                reader.setInput(in,true,true);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w!=64 || (h!=32 && h!=64))throw new IOException("Unerwartete Skin-Größe.");
                BufferedImage skin=reader.read(0), head=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
                Graphics2D g=head.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(skin,0,0,32,32,8,8,16,16,null);
                    // Legacy opaque hat areas are not a real second layer.
                    boolean translucent=false;
                    for(int y=8;y<16;y++)for(int x=40;x<48;x++)if((skin.getRGB(x,y)>>>24)<255)translucent=true;
                    if(translucent || h==64)g.drawImage(skin,0,0,32,32,40,8,48,16,null);
                } finally { g.dispose(); }
                ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(head,"png",out); return out.toByteArray();
            } finally { reader.dispose(); }
        }
    }
    private static byte[] fallback() {
        // A neutral pixel head while a skin is unavailable; never player initials.
        BufferedImage image=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
        int[] palette={0xff544132,0xffba8b66,0xffd7ab81,0xffefeee2,0xff4f6b71,0xff79553d};
        String[] rows={"00000000","00000000","01122110","12222221","13322331","14422441","12255221","11255211"};
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)image.setRGB(x,y,palette[rows[y].charAt(x)-'0']);
        try { ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(image,"png",out); return out.toByteArray(); }
        catch(IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @Override public synchronized void close() { closed=true; cache.clear(); workers.shutdownNow(); closer.shutdownNow(); }
}
