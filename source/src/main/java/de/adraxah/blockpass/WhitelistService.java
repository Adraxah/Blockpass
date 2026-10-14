package de.adraxah.blockpass;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class WhitelistService implements AutoCloseable {
    public record Profile(UUID uuid,String name){}
    public interface Gateway {
        Profile resolve(String name)throws Exception;
        void whitelist(Profile profile)throws Exception;
    }
    public static final class TemporaryFailure extends Exception {public TemporaryFailure(String message){super(message);}}
    public static final class PermanentFailure extends Exception {public PermanentFailure(String message){super(message);}}
    private final AccessStore store;
    private final Gateway gateway;
    private final Consumer<String> log;
    private long lastWarning;
    private final AtomicBoolean active=new AtomicBoolean(true);
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Blockpass-whitelist");t.setDaemon(true);return t;});
    public WhitelistService(AccessStore store,Gateway gateway,Consumer<String> log){this.store=store;this.gateway=gateway;this.log=log;}
    public void start(){worker.scheduleWithFixedDelay(this::process,1,3,TimeUnit.SECONDS);}
    private void process(){
        if(!active.get())return;
        for(AccessStore.Entry e:store.pending()){
            if(!active.get())return;
            try{
                gateway.whitelist(new Profile(UUID.fromString(e.uuid()),e.username()));
                if(active.get())store.finish(e.id(),null);
            }catch(PermanentFailure ex){
                try{if(active.get())store.finish(e.id(),"Freischaltung fehlgeschlagen. Bitte das Server-Team kontaktieren.");}catch(Exception write){log.accept("Status konnte nicht gespeichert werden. Auftrag bleibt offen.");}
                log.accept("Freischaltung "+e.id().substring(0,8)+" fehlgeschlagen: "+ex.getMessage());
            }catch(InterruptedException ex){Thread.currentThread().interrupt();return;}
            catch(Exception ex){long now=System.currentTimeMillis();if(active.get()&&now-lastWarning>60000){lastWarning=now;log.accept("Freischaltung wird erneut versucht ("+ex.getClass().getSimpleName()+").");}return;}
        }
    }
    public AccessStore.Claim claim(String code,String name,String receipt)throws Exception{
        if(!active.get())throw new AccessStore.Rejected(503,"Server wird neu geladen. Bitte kurz warten.");
        AccessStore.Claim previous=store.resume(code,name,receipt);if(previous!=null)return previous;
        store.check(code,name);
        Profile profile=gateway.resolve(name);
        if(profile==null||!AccessStore.validName(profile.name())||!profile.name().equalsIgnoreCase(name)||profile.uuid()==null)throw new AccessStore.Rejected(503,"Minecraft-Profil konnte nicht bestaetigt werden.");
        if(!active.get())throw new AccessStore.Rejected(503,"Server wird neu geladen. Dein Code bleibt gueltig.");
        return store.claim(code,profile.name(),profile.uuid(),receipt);
    }
    @Override public void close(){active.set(false);worker.shutdownNow();}
}
