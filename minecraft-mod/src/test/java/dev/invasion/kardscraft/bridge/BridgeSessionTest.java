package dev.invasion.kardscraft.bridge;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgeSessionTest {
    private static <T> T present(T value) { assertNotNull(value); return value; }
    private JsonObject snapshot() {
        return JsonParser.parseString("""
          {"protocol":2,"sessionId":"boot-a","matchId":"match-a","inMatch":true,"ai":true,
           "mySide":1,"myTurn":true,"turn":3,"lastCmd":0,"clientId":"","end":1,"ready":true,"geometryReady":true,
           "cards":[{"id":7,"side":2,"loc":5,"num":0,"atk":3,"def":5,"name":"target","hq":false,"pos":{"x":0,"y":2,"z":3}}],"results":[]}
          """).getAsJsonObject();
    }
    private BridgeState parse(JsonObject j) { return present(BridgeState.parse(j.toString())); }
    private JsonObject result(String client,int seq,boolean accepted) {
        JsonObject r=new JsonObject(); r.addProperty("clientId",client); r.addProperty("seq",seq);
        r.addProperty("matchId","match-a"); r.addProperty("accepted",accepted);
        r.addProperty("damage",accepted?3:0); r.addProperty("attack",3); r.addProperty("reason",accepted?"applied":"native_no_effect"); return r;
    }
    @Test void refusesInactiveUnknownSidePvpAndInvalidTargets() {
        BridgeSession s=new BridgeSession(); assertEquals(-1,s.damage(7,3,0));
        JsonObject j=snapshot(); j.addProperty("ai",false); s.accept(parse(j),0); assertEquals(-1,s.damage(7,3,1));
        j.addProperty("ai",true); j.addProperty("mySide",0); s.accept(parse(j),2); assertEquals(-1,s.damage(7,3,3));
        j.addProperty("mySide",1); s.accept(parse(j),4);
        assertEquals(-1,s.damage(99,3,5)); assertEquals(-1,s.damage(7,0,5)); assertEquals(-1,s.damage(7,101,5));
    }
    @Test void acceptedResultDeliveredExactlyOnce() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0);
        int seq=s.damage(7,3,1); String client=s.commands(2).split(" ")[0];
        assertTrue(s.commands(2).endsWith(" dmg match-a 3 7 3\n"));
        j.addProperty("clientId",client); j.addProperty("lastCmd",seq); j.getAsJsonArray("results").add(result(client,seq,true));
        s.accept(parse(j),10); s.accept(parse(j),20);
        BridgeState.Result r=present(s.poll()); assertTrue(r.accepted); assertEquals(3,r.damage);
        assertNull(s.poll()); assertEquals("",s.commands(21));
    }
    @Test void noEffectNeverCreatesCounterSuccess() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0);
        int seq=s.damage(7,3,1); String client=s.commands(2).split(" ")[0];
        j.getAsJsonArray("results").add(result(client,seq,false)); s.accept(parse(j),10);
        assertFalse(present(s.poll()).accepted); assertEquals("",s.commands(11));
    }
    @Test void turnBoundaryClearsPendingAndRefusesNewDamage() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0); s.damage(7,3,1);
        j.addProperty("myTurn",false); j.addProperty("turn",4); s.accept(parse(j),10);
        assertEquals("",s.commands(11)); assertEquals(-1,s.damage(7,3,11)); assertFalse(present(s.poll()).accepted);
    }
    @Test void terminalSnapshotStillDeliversAlreadyConfirmedDamage() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0);
        int seq=s.damage(7,3,1); String client=s.commands(2).split(" ")[0];
        j.addProperty("inMatch",false); j.getAsJsonArray("results").add(result(client,seq,true)); s.accept(parse(j),10);
        assertTrue(present(s.poll()).accepted); assertEquals(-1,s.damage(7,3,11));
    }
    @Test void staleRequestsAreNeverReplayedAfterReconnect() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0); s.damage(7,3,1);
        assertNull(s.get(2001)); assertEquals("",s.commands(2002)); assertEquals("timeout",present(s.poll()).reason);
        s.accept(parse(j),2100); assertEquals("",s.commands(2101));
    }
    @Test void backendRestartChangesClientAndDropsOldActions() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0); s.damage(7,3,1);
        String old=s.commands(2).split(" ")[0]; j.addProperty("sessionId","boot-b"); s.accept(parse(j),10);
        assertEquals("",s.commands(11)); assertEquals("backend_restarted",present(s.poll()).reason);
        s.damage(7,3,12); assertNotEquals(old,s.commands(13).split(" ")[0]);
    }
    @Test void foreignClientOrMatchCannotAcknowledgeDamage() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0); int seq=s.damage(7,3,1);
        j.addProperty("lastCmd",100); j.addProperty("clientId","another-client");
        j.getAsJsonArray("results").add(result("another-client",seq,true)); s.accept(parse(j),10);
        assertNull(s.poll()); assertFalse(s.commands(11).isEmpty());
        j.addProperty("matchId","match-b"); s.accept(parse(j),20); assertEquals("",s.commands(21)); assertFalse(present(s.poll()).accepted);
    }
    @Test void highWaterMarkWithoutResultFailsClosed() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot(); s.accept(parse(j),0); int seq=s.damage(7,3,1);
        j.addProperty("clientId",s.commands(2).split(" ")[0]); j.addProperty("lastCmd",seq); s.accept(parse(j),10);
        BridgeState.Result r=present(s.poll()); assertFalse(r.accepted); assertEquals("missing_result",r.reason);
    }
    @Test void malformedSnapshotsRejected() {
        assertNull(BridgeState.parse("{")); JsonObject j=snapshot(); j.addProperty("end",0); assertNull(BridgeState.parse(j.toString()));
        j=snapshot(); j.addProperty("protocol",1); assertNull(BridgeState.parse(j.toString()));
        j=snapshot(); j.addProperty("matchId","bad token\n"); assertNull(BridgeState.parse(j.toString()));
        j=snapshot(); j.getAsJsonArray("cards").add(j.getAsJsonArray("cards").get(0).deepCopy()); assertNull(BridgeState.parse(j.toString()));
        j=snapshot(); j.getAsJsonArray("cards").get(0).getAsJsonObject().getAsJsonObject("pos").addProperty("x","NaN"); assertNull(BridgeState.parse(j.toString()));
    }
    @Test void pendingQueueIsBounded() {
        BridgeSession s=new BridgeSession(); s.accept(parse(snapshot()),0);
        for(int i=0;i<64;i++) assertTrue(s.damage(7,1,i+1)>0);
        assertEquals(-1,s.damage(7,1,100));
    }
    @Test void incompleteOrBusySnapshotCannotAuthorizeDamage() {
        BridgeSession s=new BridgeSession(); JsonObject j=snapshot();
        j.remove("ready"); s.accept(parse(j),0); assertEquals(-1,s.damage(7,1,1));
        j=snapshot(); j.remove("geometryReady"); s.accept(parse(j),2); assertEquals(-1,s.damage(7,1,3));
        j=snapshot(); j.addProperty("busy",true); s.accept(parse(j),4); assertEquals(-1,s.damage(7,1,5));
    }
}
