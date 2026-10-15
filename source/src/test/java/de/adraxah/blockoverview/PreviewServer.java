package de.adraxah.blockoverview;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Local visual-test fixture. Never used by the plugin. */
public final class PreviewServer {
    public static void main(String[] args) throws Exception {
        CombinedIntegrationTest.Pair pair=new CombinedIntegrationTest.Pair(); IntegrationTest.Fixture f=pair.overview;
        JsonObject s=new Gson().toJsonTree(f.content.get().settings()).getAsJsonObject();
        LocalDateTime now=LocalDateTime.now(ContentStore.ZONE); DateTimeFormatter fmt=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        s.addProperty("address","play.example.test"); s.addProperty("projectStart",now.minusDays(2).format(fmt)); s.addProperty("netherOpen",now.plusDays(3).format(fmt)); s.addProperty("endOpen",now.plusDays(9).format(fmt));
        s.addProperty("announcementTitle","Das nächste Abenteuer wartet!"); s.addProperty("announcementText","Der Nether öffnet bald. Packt eure Spitzhacken ein und schaut bei der gemeinsamen Erkundung vorbei.");
        f.content.update(1,s);
        Runtime.getRuntime().addShutdownHook(new Thread(pair::close)); System.out.println("PREVIEW "+f.base);
        while(true) { long t=System.currentTimeMillis(); f.live.publish(new LiveStatus.Snapshot(t,t-88200000,7,30,14,"1.21.4",List.of("LenaCraft","BenBuilds","NoraBlocks","MaxMiner","Lukas","Emilia","PixelPaul").stream().map(IntegrationTest::player).toList(),19.98)); Thread.sleep(1000); }
    }
}
