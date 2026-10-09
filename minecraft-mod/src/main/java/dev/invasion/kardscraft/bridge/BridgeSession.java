package dev.invasion.kardscraft.bridge;

import java.util.*;

/** Transport-independent command lifecycle, driven by monotonic milliseconds. */
final class BridgeSession {
    static final long STALE_MS=2000;
    private record Command(int seq,String matchId,int turn,int card,int amount,long created) {}
    private String clientId=UUID.randomUUID().toString();
    private BridgeState state;
    private long updated;
    private int nextSeq;
    private final LinkedHashMap<Integer,Command> pending=new LinkedHashMap<>();
    private final ArrayDeque<BridgeState.Result> completed=new ArrayDeque<>();

    synchronized BridgeState get(long now) {
        expire(now);
        return state!=null && now-updated<STALE_MS?state:null;
    }
    synchronized void accept(BridgeState s,long now) {
        expire(now);
        if(state!=null && !state.sessionId.equals(s.sessionId)) {
            clear("backend_restarted"); clientId=UUID.randomUUID().toString(); nextSeq=0;
        }
        state=s; updated=now;
        // Drain confirmations before cancelling remaining commands on turn/terminal transitions.
        for(BridgeState.Result r:s.results) {
            Command c=pending.get(r.seq);
            if(c!=null && clientId.equals(r.clientId) && c.matchId.equals(r.matchId)) {
                pending.remove(r.seq); complete(r);
            }
        }
        for(Command c:new ArrayList<>(pending.values())) {
            if(!c.matchId.equals(s.matchId) || !s.usable() || c.turn!=s.turn || !s.myTurn) {
                pending.remove(c.seq); reject(c,"match_or_turn_changed");
            } else if(clientId.equals(s.clientId) && c.seq<=s.lastCmd) {
                pending.remove(c.seq); reject(c,"missing_result");
            }
        }
    }
    synchronized int damage(int card,int amount,long now) {
        BridgeState s=get(now);
        if(s==null || !s.usable() || !s.myTurn || !s.ready || s.busy || !s.geometryReady || amount<1 || amount>100 || pending.size()>=64) return -1;
        if(s.cards.stream().noneMatch(c->c.id==card && c.def>0)) return -1;
        int seq=++nextSeq;
        pending.put(seq,new Command(seq,s.matchId,s.turn,card,amount,now)); return seq;
    }
    synchronized String commands(long now) {
        expire(now);
        StringBuilder out=new StringBuilder();
        for(Command c:pending.values()) out.append(clientId).append(' ').append(c.seq).append(" dmg ")
            .append(c.matchId).append(' ').append(c.turn).append(' ').append(c.card).append(' ').append(c.amount).append('\n');
        return out.toString();
    }
    synchronized BridgeState.Result poll() { return completed.poll(); }
    synchronized void clear(String reason) {
        for(Command c:pending.values()) reject(c,reason);
        pending.clear();
    }
    private void expire(long now) {
        for(Command c:new ArrayList<>(pending.values())) if(now-c.created>=STALE_MS || (state!=null && now-updated>=STALE_MS)) {
            pending.remove(c.seq); reject(c,"timeout");
        }
    }
    private void reject(Command c,String reason) { complete(new BridgeState.Result(clientId,c.seq,c.matchId,false,0,0,reason)); }
    private void complete(BridgeState.Result r) { if(completed.size()>=256) completed.remove(); completed.add(r); }
}
