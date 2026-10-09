package dev.invasion.kardscraft.bridge;

import dev.invasion.kardscraft.Config;
import dev.invasion.kardscraft.KardsCraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;

/** Local file bridge. KARDS alone confirms whether a damage request actually changed its state. */
public final class Bridge {
    private static final BridgeSession SESSION=new BridgeSession();
    private static Thread thread;
    private Bridge() {}
    private static long now() { return System.nanoTime()/1_000_000L; }
    public static synchronized void start() {
        if(thread!=null) return;
        Path dir=Config.bridgePath();
        thread=new Thread(()->run(dir),"KardsCraft-Bridge"); thread.setDaemon(true); thread.start();
        KardsCraft.LOG.info("KardsCraft bridge protocol 2: {}",dir);
    }
    public static BridgeState get() { return SESSION.get(now()); }
    public static BridgeState getActive() { BridgeState s=get(); return s!=null && s.usable()?s:null; }
    public static int sendDamage(int cardId,int amount) { return SESSION.damage(cardId,amount,now()); }
    public static BridgeState.Result pollResult() { return SESSION.poll(); }
    public static void clearPending() { SESSION.clear("world_closed"); }
    private static void run(Path dir) {
        Path state=dir.resolve("KardsBridge_state.json"),command=dir.resolve("KardsBridge_cmd.txt"),tmp=dir.resolve("KardsBridge_cmd.tmp");
        FileTime lastTime=null;
        String lastWritten=null;
        long lastWarning=Long.MIN_VALUE/2;
        while(!Thread.currentThread().isInterrupted()) {
            try {
                Files.createDirectories(dir);
                try {
                    FileTime t=Files.getLastModifiedTime(state);
                    long age=System.currentTimeMillis()-t.toMillis();
                    if(!t.equals(lastTime) && age>=-1000 && age<BridgeSession.STALE_MS && Files.size(state)<=1_048_576) {
                        BridgeState s=BridgeState.parse(Files.readString(state,StandardCharsets.UTF_8));
                        if(s!=null) { SESSION.accept(s,now()); lastTime=t; }
                    }
                } catch(NoSuchFileException ignored) {}
                String text=SESSION.commands(now());
                if(!text.equals(lastWritten)) {
                    Files.writeString(tmp,text,StandardCharsets.UTF_8);
                    try { Files.move(tmp,command,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                    catch(AtomicMoveNotSupportedException e) { Files.move(tmp,command,StandardCopyOption.REPLACE_EXISTING); }
                    lastWritten=text;
                }
            } catch(Exception e) {
                if(now()-lastWarning>5000) { lastWarning=now(); KardsCraft.LOG.warn("KardsCraft bridge I/O: {}",e.toString()); }
            }
            try { Thread.sleep(40); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
