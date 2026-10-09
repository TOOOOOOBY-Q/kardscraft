package dev.invasion.kardscraft.bridge;

import com.google.gson.*;
import java.util.*;

/** Immutable, validated snapshot. Only protocol 2 can authorize interaction. */
public final class BridgeState {
    public static final int LOC_SUPPORT_LEFT=5, LOC_SUPPORT_RIGHT=6, LOC_FRONTLINE=7;
    public final int protocol, lastCmd, mySide, turn, hqLeftId, hqRightId;
    public final boolean inMatch, ai, myTurn, ready, busy, geometryReady;
    public final String sessionId, matchId, clientId, status;
    public final double[] hqLeft, hqRight;
    public final Cam cam;
    public final List<Card> cards;
    public final List<Result> results;

    public static final class Cam {
        public final double x,y,z,pitch,yaw,roll,fov;
        Cam(JsonObject j) {
            x=number(j,"x"); y=number(j,"y"); z=number(j,"z");
            pitch=number(j,"pitch"); yaw=number(j,"yaw"); roll=number(j,"roll"); fov=number(j,"fov");
            if(fov<=1 || fov>=179) throw new IllegalArgumentException("Invalid FOV");
        }
    }
    public static final class Card {
        public final int id,side,loc,num,atk,def;
        public final String name;
        public final boolean hq;
        public final double x,y,z;
        Card(JsonObject j) {
            id=integer(j,"id"); side=integer(j,"side"); loc=integer(j,"loc"); num=integer(j,"num");
            atk=integer(j,"atk"); def=integer(j,"def"); name=string(j,"name"); hq=bool(j,"hq");
            double[] p=vec(j.get("pos"));
            if(p==null || id<0 || (side!=1 && side!=2) || atk<0 || def<0) throw new IllegalArgumentException("Invalid card");
            x=p[0]; y=p[1]; z=p[2];
        }
    }
    public static final class Result {
        public final String clientId,matchId,reason;
        public final int seq,damage,attack;
        public final boolean accepted;
        public Result(String clientId,int seq,String matchId,boolean accepted,int damage,int attack,String reason) {
            this.clientId=clientId; this.seq=seq; this.matchId=matchId; this.accepted=accepted;
            this.damage=damage; this.attack=attack; this.reason=reason;
        }
        Result(JsonObject j) { this(string(j,"clientId"),integer(j,"seq"),string(j,"matchId"),bool(j,"accepted"),
            Math.max(0,integer(j,"damage")),Math.max(0,integer(j,"attack")),string(j,"reason")); }
    }
    private BridgeState(JsonObject o) {
        protocol=integer(o,"protocol"); sessionId=string(o,"sessionId"); matchId=string(o,"matchId");
        clientId=string(o,"clientId"); status=string(o,"status");
        inMatch=bool(o,"inMatch"); ai=bool(o,"ai"); myTurn=bool(o,"myTurn");
        ready=bool(o,"ready"); busy=bool(o,"busy");
        geometryReady=bool(o,"geometryReady");
        lastCmd=integer(o,"lastCmd"); mySide=integer(o,"mySide"); turn=integer(o,"turn");
        hqLeftId=integer(o,"hqLeftId"); hqRightId=integer(o,"hqRightId");
        hqLeft=vec(o.get("hqLeft")); hqRight=vec(o.get("hqRight"));
        cam=o.has("cam") && o.get("cam").isJsonObject()?new Cam(o.getAsJsonObject("cam")):null;
        List<Card> c=new ArrayList<>(); Set<Integer> ids=new HashSet<>();
        if(o.has("cards")) for(JsonElement e:o.getAsJsonArray("cards")) {
            Card card=new Card(e.getAsJsonObject());
            if(!ids.add(card.id)) throw new IllegalArgumentException("Duplicate card ID");
            c.add(card);
        }
        cards=List.copyOf(c);
        List<Result> r=new ArrayList<>();
        if(o.has("results")) for(JsonElement e:o.getAsJsonArray("results")) r.add(new Result(e.getAsJsonObject()));
        results=List.copyOf(r);
    }
    public static BridgeState parse(String text) {
        if(text==null || text.length()>1_048_576) return null;
        try {
            JsonObject o=JsonParser.parseString(text).getAsJsonObject();
            if(integer(o,"end")!=1 || integer(o,"protocol")!=2) return null;
            BridgeState s=new BridgeState(o);
            if(!token(s.sessionId)) return null;
            if(s.inMatch && (!token(s.matchId) || s.turn<0)) return null;
            return s;
        } catch(RuntimeException e) { return null; }
    }
    public boolean usable() { return protocol==2 && inMatch && ai && (mySide==1 || mySide==2) && token(matchId); }
    public int enemySide() { return mySide==1?2:1; }
    static boolean token(String s) { return s!=null && s.matches("[A-Za-z0-9_-]{1,128}"); }
    private static String string(JsonObject o,String k) { return o.has(k) && !o.get(k).isJsonNull()?o.get(k).getAsString():""; }
    private static boolean bool(JsonObject o,String k) { return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean(); }
    private static int integer(JsonObject o,String k) {
        if(!o.has(k)) return 0;
        double v=o.get(k).getAsDouble();
        if(!Double.isFinite(v) || v!=Math.rint(v) || v<Integer.MIN_VALUE || v>Integer.MAX_VALUE) throw new IllegalArgumentException(k);
        return (int)v;
    }
    private static double number(JsonObject o,String k) {
        double v=o.get(k).getAsDouble();
        if(!Double.isFinite(v)) throw new IllegalArgumentException(k);
        return v;
    }
    private static double[] vec(JsonElement e) {
        if(e==null || e.isJsonNull()) return null;
        JsonObject j=e.getAsJsonObject(); return new double[]{number(j,"x"),number(j,"y"),number(j,"z")};
    }
}
