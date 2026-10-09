"""Exercise actual Java BridgeState/BridgeSession against the actual Lua core.

Requires the same local Lupa installation as test-kards-bridge.py, a JDK on PATH,
and Gson already downloaded by the project's Gradle build. No game is launched.
"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools" / "lua-test-runtime"))
from lupa import LuaRuntime

JAVA_PROBE = r'''
package dev.invasion.kardscraft.bridge;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
public final class InteropProbe {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static BridgeState parse(String raw) { BridgeState s=BridgeState.parse(raw); check(s!=null,"Lua JSON rejected"); return s; }
    public static void main(String[] args) throws Exception {
        BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        BridgeSession session=new BridgeSession();
        BridgeState initial=parse(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8));
        BridgeState dying=parse(Files.readString(Path.of(args[1]),StandardCharsets.UTF_8));
        check(dying.cards.get(2).def==0,"native negative-defense transition remains parseable");
        check(initial.ready && initial.geometryReady && initial.cards.size()==3,"initial native-shaped snapshot");
        session.accept(initial,0);
        check(session.damage(10,2,1)==1,"initial request");
        String first=session.commands(2); System.out.println(first.strip()); System.out.flush();
        String firstClient=first.split(" ")[0];
        BridgeState ack=parse(in.readLine()); session.accept(ack,10);
        BridgeState.Result result=session.poll();
        check(result!=null && result.accepted && result.damage==2 && result.attack==4,"successful Lua ACK");
        session.accept(ack,20);
        check(session.poll()==null && session.commands(20).isEmpty(),"duplicate ACK is not delivered twice");
        check(session.damage(10,1,21)==2,"second sequence");
        System.out.println(session.commands(22).strip()); System.out.flush();
        session.accept(parse(in.readLine()),40);
        result=session.poll();
        check(result!=null && !result.accepted && result.reason.equals("not_your_turn"),"turn rejection ACK before cancellation");
        check(session.commands(41).isEmpty(),"turn clears file");
        session.accept(parse(in.readLine()),60);
        check(session.damage(10,1,61)==1,"backend restart resets sequence");
        String restarted=session.commands(62);
        check(!restarted.split(" ")[0].equals(firstClient),"backend restart rotates client");
        System.out.println(restarted.strip()); System.out.flush();
        BridgeState terminal=parse(in.readLine());
        check(!terminal.inMatch,"terminal fixture");
        session.accept(terminal,80); result=session.poll();
        check(result!=null && result.accepted && result.damage==1,"terminal ACK accepted before terminal cleanup");
        session.accept(terminal,90);
        check(session.poll()==null && session.commands(90).isEmpty(),"foreign/replayed ACKs ignored");
        check(session.damage(10,1,91)==-1,"terminal forbids more damage");
        System.out.println("Java/Lua round-trip, replay, turn and restart checks passed.");
    }
}
'''

def run() -> None:
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        raise SystemExit("A JDK with java and javac on PATH is required.")
    gson_files = sorted((ROOT / "tools" / "gradle-cache" / "caches" / "modules-2").glob("**/gson-*.jar"))
    if not gson_files:
        raise SystemExit("Run the project's Gradle build first to download Gson.")
    gson = gson_files[0]
    lua = LuaRuntime(unpack_returned_tuples=True)
    lua.globals().package.path = str(ROOT / "kards-mod" / "KardsBridge" / "Scripts" / "?.lua").replace("\\", "/") + ";" + lua.globals().package.path
    core = lua.execute("return require('bridge_core')")
    if isinstance(core, tuple):
        core = core[0]

    def convert(value):
        if isinstance(value, dict):
            return lua.table_from({k: convert(v) for k, v in value.items()})
        if isinstance(value, list):
            return core.array(lua.table_from([convert(v) for v in value]))
        return value

    with tempfile.TemporaryDirectory(prefix="kards-java-interop-") as directory:
        temp = Path(directory)
        lua.globals().TEST_TEMP = temp.as_posix()
        snapshot = json.loads(lua.execute((ROOT / "tests" / "lua" / "kards_bridge_test.lua").read_text(encoding="utf-8")))
        snapshot["cards"][2]["name"] = '坦克 "quoted"\n第二行'
        state = convert(snapshot)
        protocol = core.new(state.sessionId)
        initial = temp / "initial.json"
        initial.write_text(core.json(protocol.decorate(protocol, state)), encoding="utf-8")
        dying = temp / "dying.json"
        dying.write_text(lua.globals().KARDSBRIDGE_DYING_FIXTURE, encoding="utf-8")
        probe = temp / "InteropProbe.java"
        probe.write_text(JAVA_PROBE, encoding="utf-8")
        source = ROOT / "minecraft-mod" / "src" / "main" / "java" / "dev" / "invasion" / "kardscraft" / "bridge"
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", str(gson), "-d", str(temp),
                        str(source / "BridgeState.java"), str(source / "BridgeSession.java"), str(probe)], check=True, timeout=60)
        process = subprocess.Popen([java, "-cp", os.pathsep.join([str(temp), str(gson)]),
                                    "dev.invasion.kardscraft.bridge.InteropProbe", str(initial), str(dying)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, encoding="utf-8")
        native_calls = 0

        def execute(command):
            nonlocal native_calls
            def apply(cmd, context):
                nonlocal native_calls
                native_calls += 1
                state.cards[3]["def"] -= cmd.amount
                return lua.table_from({"accepted": True, "damage": cmd.amount, "attack": 4, "reason": "native_damage_observed"})
            return protocol.process(protocol, command + "\n", lambda: state, apply, 4)

        def send_snapshot():
            process.stdin.write(core.json(protocol.decorate(protocol, state)) + "\n")
            process.stdin.flush()

        try:
            first = process.stdout.readline().strip()
            assert first and core.parse(first) is not None
            execute(first)
            execute(first)
            assert native_calls == 1
            send_snapshot()
            second = process.stdout.readline().strip()
            assert second
            state.turn += 1
            state.myTurn = False
            execute(second)
            assert native_calls == 1
            send_snapshot()
            state.sessionId += "-restart"
            state.matchId = state.sessionId + "-m1"
            state.myTurn = True
            protocol = core.new(state.sessionId)
            send_snapshot()
            restarted = process.stdout.readline().strip()
            assert restarted
            execute(first)  # Orphan command from old backend cannot mutate the new match.
            assert native_calls == 1
            execute(restarted)
            assert native_calls == 2
            state.inMatch = False
            send_snapshot()
            output, error = process.communicate(timeout=30)
            if process.returncode:
                raise AssertionError(error)
            assert "checks passed" in output, (output, error)
            print(output.strip())
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

if __name__ == "__main__":
    run()
