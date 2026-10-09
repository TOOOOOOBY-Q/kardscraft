local Core = require("bridge_core")
local Adapter = require("kards_adapter")
local DAMAGE_FUNCTION_PATH = "/Game/Blueprints/Cards/BP_CardFunctions.BP_CardFunctions_C:DamageCard"
local DAMAGE_FUNCTION_NAME = "Function " .. DAMAGE_FUNCTION_PATH
local DAMAGE_OWNER_PATH = "/Game/Blueprints/Cards/BP_CardFunctions.BP_CardFunctions_C"
local MOCK_FUNCTION_FLAGS = 0x04020400
local passed = 0
local function check(value, message)
    assert(value, message or "check failed"); passed = passed + 1
end
local function equal(actual, expected, message)
    check(actual == expected, (message or "values differ") .. ": expected=" .. tostring(expected) .. " actual=" .. tostring(actual))
end
local function context()
    return {matchId="match_1",inMatch=true,ended=false,ai=true,myTurn=true,turn=4,ready=true,busy=false}
end
local calls = 0
local function mutate()
    calls = calls + 1
    return {accepted=true,damage=2,attack=3,reason="native_damage_observed"}
end
local protocol = Core.new("session_1")
local command = "client_1 1 dmg match_1 4 10 2\n"
equal(protocol:process(command, context, mutate), 1, "first request processed")
equal(protocol:process(command, context, mutate), 0, "duplicate skipped")
equal(calls, 1, "duplicate native call suppressed")
equal(protocol.results[1].accepted, true)
equal(protocol.results[1].damage, 2)
equal(protocol.lastCmd, 1)
equal(protocol.clientId, "client_1")
for index, item in ipairs({{"matchId","other","stale_match"}, {"inMatch",false,"match_finished"},
    {"ended",true,"match_finished"}, {"ai",false,"not_ai_match"}, {"myTurn",false,"not_your_turn"},
    {"turn",5,"stale_turn"}, {"busy",true,"game_busy"}, {"ready",false,"backend_not_ready"}}) do
    local state = context(); state[item[1]] = item[2]
    protocol:process("client_1 " .. (index + 1) .. " dmg match_1 4 10 2\n", function() return state end, mutate)
    equal(protocol.results[#protocol.results].reason, item[3])
    equal(protocol.results[#protocol.results].accepted, false)
end
equal(calls, 1, "all guards blocked native call")
protocol:process("client_2 1 dmg match_1 4 10 2\n", context, mutate)
equal(calls, 2, "new client starts at one")
protocol:process("client_2 2 dmg match_1 4 10 101\n", context, mutate)
equal(protocol.results[#protocol.results].reason, "invalid_number")
protocol:process("client_2 3 dmg match_1 4 10 2.5\n", context, mutate)
equal(protocol.results[#protocol.results].reason, "invalid_number")
protocol:process("client_2 4 dmg match_1 4 10 2\n", context, function() error("native fail") end)
equal(protocol.results[#protocol.results].reason, "native_error")
protocol:process("client_2 4 dmg match_1 4 10 2\n", context, mutate)
equal(calls, 2, "failed native call never retried")
equal(protocol:process("client_2 5 dmg match_1 4 10 2", context, mutate), 0, "partial command ignored")
equal(protocol:process(string.rep("client_2 5 dmg match_1 4 10 2\n", 65), context, mutate), 0, "oversize batch ignored")
local batch = ""
for i=5,10 do batch=batch .. "client_2 " .. i .. " dmg match_1 4 10 2\n" end
equal(protocol:process(batch, context, mutate), 4, "per-tick budget")
equal(protocol:process(batch, context, mutate), 2, "remaining batch resumes")
for i=11,310 do protocol:process("client_2 " .. i .. " dmg match_1 4 10 2\n", context, mutate) end
equal(#protocol.results, 256, "bounded ACK history")
equal(protocol.high.client_2, 310)
equal(Core.json(Core.array()), "[]", "empty arrays encoded as arrays")
equal(Core.json({["end"]=1,name="a\"b\\c\n"}), '{"name":"a\\"b\\\\c\\u000a","end":1}')
check(not pcall(Core.json, {bad=0/0}), "nonfinite JSON rejected")
local overflow, overflowReason=Core.parse("client_2 2147483648 dmg match_1 4 10 2")
equal(overflow,nil,"out-of-range Java sequence rejected")
equal(overflowReason,"invalid_sequence")

-- Strict UE4SS-shaped mock: methods with native out parameters mutate named keys.
local function object(name, properties)
    local o = properties or {}
    o.IsValid=function(self) return not self.invalid end
    o.GetFullName=function() return name end
    return o
end
local function array(items)
    return {GetArrayNum=function() return #items end,
        ForEach=function(_, fn) for i,v in ipairs(items) do fn(i,{get=function() return v end}) end end}
end
local function card(id, side, defense, attack, location)
    local c = object("BaseCard World.Card_" .. id, {CardID=id, side=side, actualDefense=defense, actualAttack=attack,
        Location=location, locationNumber=1, Name="Card" .. id, defense=1234567, attack=7654321})
    c.getAndDecryptDefense=function(self, output) output.decryptedDefense=self.actualDefense end
    c.getAndDecryptAttack=function(self, output) output.decryptedAttack=self.actualAttack end
    c.getTotalDefense=function(self, output) output.totalDefense=self.actualDefense end
    c.getTotalAttack=function(self, output) output.totalAttack=self.actualAttack + 1 end
    return c
end
local gameThread = true
local threadViolations = 0
local function assertGameThread()
    if not gameThread then threadViolations = threadViolations + 1; error("UObject accessed outside game thread") end
end
local function fixture()
    local left, right, target = card(1,1,20,0,5), card(2,2,20,0,6), card(10,2,5,3,7)
    local cards = {[1]=left,[2]=right,[10]=target}
    local map = {ForEach=function(_, fn) for id,c in pairs(cards) do fn({get=function() return id end},{get=function() return c end}) end end}
    local state = object("State World.GameState", {AllCardsInBattle=map,CurrentTurnNumberInBattle=4,Left_HQ_CardID=1,
        Rgith_HQ_CardID=2,Left_HQ_Card_Ref=left,Right_HQ_Card_Ref=right,
        IsLocalClientTurn=true,MatchFinished=false,ActionProcess=false,stopFurtherActions=false})
    state.GetClientSide=function(_, output) output.OutClientSide=1 end
    local logic=object("Logic World.Logic",{GameStateRef=state,matchHasEnded=false,isReplayMatch=false,isSpectate=false})
    local funcs=object("Functions World.Functions",{GameStateRef=state,TriggerQueue=array({})})
    funcs.ownerClass=DAMAGE_OWNER_PATH
    funcs.IsA=function(self,path)
        assertGameThread()
        equal(path,DAMAGE_OWNER_PATH,"DamageCard receiver checked against exact Blueprint owner")
        return self.ownerClass==path
    end
    local mc=object("Controller World.MatchController",{match={match_id=77},isResyncingMatch=false,ai=true})
    mc.isAIMatch=function(self) return self.ai end
    local match=object("Match World.OnlineMatch",{GameStateRef=state,cardFunctions=funcs,Logic=logic,mySide=0,
        isResolvingAction=false,actionsPaused=false,isValidatingActionSent=false,actionsQueue=array({}),
        pendingCardsSelectingHandTargets=array({}),enemyPendingCardsSelectingHandTargets=array({})})
    logic.onlineMatch=match
    local actors={}
    for id,c in pairs(cards) do
        actors[#actors+1]=object("Board World.Actor_" .. id, {GameStateRef=state,CardID=id,selfBaseCardRef=c,
            K2_GetActorLocation=function() return {X=id*100,Y=0,Z=5} end})
    end
    local camera=object("Camera World.Camera",{GetCameraLocation=function() return {X=100,Y=0,Z=800} end,
        GetCameraRotation=function() return {Pitch=-60,Yaw=0,Roll=0} end,GetFOVAngle=function() return 60 end})
    local gameplay=object("GameplayStatics",{GetGameState=function() return state end,GetGameMode=function() return logic end,
        GetPlayerCameraManager=function() return camera end})
    local controllerClass=object("MatchControllerCDO",{GetMatchController=function() return mc end})
    local matches={match}
    local functionBinding={lookups={}}
    FindAllOf=function(name)
        assertGameThread()
        if name=="BP_OnlineMatch_C" then return matches end
        if name=="BP_BoardCard_C" then return actors end
        return {}
    end
    StaticFindObject=function(path)
        assertGameThread()
        if path=="/Script/Engine.Default__GameplayStatics" then return gameplay end
        if path=="/Script/kards.Default__MatchControllerV2" then return controllerClass end
        if path==DAMAGE_FUNCTION_PATH then
            functionBinding.lookups[#functionBinding.lookups+1]=path
            return functionBinding.value
        end
        return nil
    end
    funcs.damageCalls=0
    funcs.DamageCard=function(self, victim, amount, source, redirected, fight, defender, destroyed)
        assertGameThread()
        equal(source,1,"native damage source is own HQ")
        equal(redirected,false); equal(fight,false); equal(defender,false)
        self.damageCalls=self.damageCalls+1
        victim.actualDefense=victim.actualDefense-amount
        destroyed.targetDestroyed=victim.actualDefense<=0
        if destroyed.targetDestroyed then victim.Location=8 end
    end
    -- UFunction is a callable object, not the instance's potentially inherited DamageCard binding.
    -- Preserve the native implementation separately so replacing the bound method cannot affect dispatch.
    local damageFunction=object(DAMAGE_FUNCTION_NAME,{calls=0,implementation=funcs.DamageCard})
    damageFunction.GetFunctionFlags=function() assertGameThread(); return MOCK_FUNCTION_FLAGS end
    setmetatable(damageFunction,{__call=function(self,receiver,...)
        assertGameThread()
        equal(receiver,funcs,"explicit UFunction receives the live CardFunctions object")
        self.calls=self.calls+1
        return self.implementation(receiver,...)
    end})
    functionBinding.value=damageFunction
    return {state=state,logic=logic,funcs=funcs,mc=mc,match=match,target=target,cards=cards,matches=matches,actors=actors,
        damageFunction=damageFunction,functionBinding=functionBinding,boundCalls=0}
end
local f=fixture()
local adapter=Adapter.new("sdk_mock",{damageMode="native"})
local state=adapter:snapshot()
check(state.inMatch and state.ai and state.ready and state.geometryReady,"usable mock snapshot")
equal(state.mySide,1,"named out parameter overrides OnlineMatch.mySide=0")
equal(state.cards[3].def,5,"defense comes from getter")
equal(state.cards[3].atk,4,"attack includes native total")
equal(state.cards[3].rawDefense,1234567,"raw diagnostic preserved")
equal(state.cards[3].pos.x,1000)
equal(state.cam.pitch,-60)
local savedActors={table.unpack(f.actors)}
for i=#f.actors,1,-1 do if f.actors[i].CardID==10 then table.remove(f.actors,i) end end
local noPosition=adapter:snapshot()
equal(#noPosition.cards,2,"unpositioned card is omitted for strict Java parser")
equal(noPosition.unpositionedCards,1)
equal(noPosition.status,"awaiting_board_geometry")
for _,actor in ipairs(savedActors) do if actor.CardID==10 then f.actors[#f.actors+1]=actor end end
local matchId=state.matchId
equal(adapter:snapshot().matchId,matchId,"stable match id")
f.funcs.DamageCard=function()
    f.boundCalls=f.boundCalls+1
    error("ambiguous instance DamageCard binding must never be called")
end
local outcome=adapter:damage({cardId=10,amount=2},adapter:context())
equal(outcome.accepted,true); equal(outcome.damage,2); equal(outcome.attack,4)
equal(f.boundCalls,0,"bound instance DamageCard is bypassed")
equal(f.damageFunction.calls,1,"exact callable UFunction invoked once")
check(#f.functionBinding.lookups>0,"native function resolved by its explicit path")
equal(f.functionBinding.lookups[#f.functionBinding.lookups],DAMAGE_FUNCTION_PATH)
equal(adapter.lastDamage.amount,2,"requested amount retained in diagnostics")
equal(adapter.lastDamage.sourceId,1,"source HQ retained in diagnostics")
equal(adapter.lastDamage.functionPath,DAMAGE_FUNCTION_PATH)
equal(adapter.lastDamage.functionFullName,DAMAGE_FUNCTION_NAME)
equal(adapter.lastDamage.functionFlags,MOCK_FUNCTION_FLAGS)
equal(adapter.lastDamage.targetDestroyedType,"boolean","native false out value retains its type")
equal(adapter.lastDamage.nativeDestroyed,false)
equal(adapter.lastDamage.dispatch,"explicit_blueprint")
equal(adapter.lastDamage.nativeInvoked,true)
equal(adapter.lastDamage.functionsRef,"Functions World.Functions")
equal(adapter.lastDamage.targetRef,"BaseCard World.Card_10")
equal(adapter.lastDamage.gameStateRef,"State World.GameState")
equal(adapter.lastDamage.beforeRaw,adapter.lastDamage.afterRaw,"unchanged encrypted field doesn't hide actual loss")
local original=f.damageFunction.implementation
f.damageFunction.implementation=function() end
outcome=adapter:damage({cardId=10,amount=2},adapter:context())
equal(outcome.accepted,false); equal(outcome.reason,"native_no_effect"); equal(outcome.attack,0)
equal(adapter.lastDamage.targetDestroyedType,"nil","unpopulated native out is distinguishable from false")
equal(adapter.lastDamage.nativeDestroyed,false)
local nilOutDiagnostic=Core.json(adapter.lastDamage)
check(nilOutDiagnostic:find('"targetDestroyedType":"nil"',1,true)~=nil,"nil out type survives JSON diagnostics")
f.damageFunction.implementation=function(_,_,_,_,_,_,_,destroyed) destroyed.targetDestroyed=false end
outcome=adapter:damage({cardId=10,amount=2},adapter:context())
equal(outcome.accepted,false); equal(outcome.reason,"native_no_effect")
equal(adapter.lastDamage.targetDestroyedType,"boolean","explicit false native out retains boolean type")
equal(adapter.lastDamage.nativeDestroyed,false)
check(Core.json(adapter.lastDamage):find('"targetDestroyedType":"boolean"',1,true)~=nil,"false out type survives JSON diagnostics")
f.damageFunction.implementation=function(_,_,_,_,_,_,_,destroyed) destroyed.targetDestroyed=true end
outcome=adapter:damage({cardId=10,amount=2},adapter:context())
equal(outcome.accepted,false,"out flag without observed destruction is not success")
f.damageFunction.implementation=function() error("native failure") end
outcome=adapter:damage({cardId=10,amount=2},adapter:context())
equal(outcome.reason,"native_error"); equal(outcome.accepted,false)
f.damageFunction.implementation=original
outcome=adapter:damage({cardId=10,amount=20},adapter:context())
equal(outcome.accepted,true); equal(outcome.damage,3); equal(outcome.reason,"native_destroyed_observed")
equal(f.target.defense,1234567,"never writes raw field")
f.target.Location=7
local dying=adapter:snapshot()
equal(dying.cards[3].def,0,"negative dying defense is normalized for Java")
check(dying.cards[3].decryptedDefense<0,"negative native getter retained for diagnosis")
KARDSBRIDGE_DYING_FIXTURE=Core.json(Core.new("dying_fixture"):decorate(dying))
f.state.MatchFinished=true
check(not adapter:context().inMatch,"finished match blocked")
f.state.MatchFinished=false
check(adapter:context().matchId~=matchId,"reused game state gets new match epoch")
local newId=adapter:context().matchId
f.mc.match.match_id=78
check(adapter:context().matchId~=newId,"server match transition gets new epoch")
f.funcs.GameStateRef=object("State World.Unrelated")
equal(adapter:context().ready,false,"unrelated functions rejected")
f=fixture(); adapter=Adapter.new("guard_mock",{damageMode="native"})
local core=Core.new("guard_mock")
local function run(seq)
    local c=adapter:context()
    core:process("guard "..seq.." dmg "..c.matchId.." 4 10 2\n",function() return adapter:context() end,
        function(cmd, ctx) return adapter:damage(cmd,ctx) end)
    return core.results[#core.results]
end
f.state.IsLocalClientTurn=false
equal(run(1).reason,"not_your_turn")
f.state.IsLocalClientTurn=true; f.mc.ai=false
equal(run(2).reason,"not_ai_match")
f.mc.ai=true; f.match.isResolvingAction=true
equal(run(3).reason,"game_busy")
f.match.isResolvingAction=false; f.mc.isResyncingMatch=true
equal(run(4).reason,"game_busy")
f.mc.isResyncingMatch=false; f.logic.isSpectate=true
equal(run(5).reason,"replay_or_spectator")
equal(f.funcs.damageCalls,0,"adapter guard scenarios never call DamageCard")
f.logic.isSpectate=false; f.match.isResolvingAction=nil
local missingState=adapter:snapshot()
equal(missingState.ready,false)
check(table.concat(missingState.missingFields,","):find("isResolvingAction",1,true)~=nil,"missing field names exposed")
f.match.isResolvingAction=false
f.logic.isSpectate=false; f.cards[2].actualDefense=0
local zeroHQMatch=adapter:context().matchId
local zeroHQContext=adapter:context()
equal(zeroHQContext.inMatch,true,"zero HQ defense does not fabricate KARDS settlement")
equal(zeroHQContext.ended,false); equal(zeroHQContext.ready,true); equal(zeroHQContext.hqZeroPending,true)
equal(run(6).reason,"native_damage_observed","other targets remain usable while KARDS has not settled")
equal(adapter:context().matchId,zeroHQMatch,"zero HQ defense does not create a new match epoch")
local zeroHQSnapshot=adapter:snapshot()
equal(zeroHQSnapshot.inMatch,true); equal(zeroHQSnapshot.hqZeroPending,true)
f.cards[2].actualDefense=20
f.logic.isSpectate=false; f.target.getAndDecryptDefense=function() error("SDK changed") end
local unreadable=adapter:snapshot()
equal(unreadable.status,"card_getter_unavailable"); equal(unreadable.ready,false)
equal(run(7).reason,"target_unavailable")

-- Explicit UFunction resolution fails closed. Neither a bad binding nor a failed native call is retried.
local resolutionCases={
    {name="missing_function",reason="damage_function_unavailable",calls=0,
        setup=function(mock) mock.functionBinding.value=nil end},
    {name="invalid_function",reason="damage_function_unavailable",calls=0,
        setup=function(mock) mock.damageFunction.invalid=true end},
    {name="wrong_function",reason="damage_function_mismatch",calls=0,
        setup=function(mock) mock.damageFunction.GetFullName=function() return "Function /Script/kards.CardFunctions:DamageCard" end end},
    {name="wrong_context",reason="damage_context_mismatch",calls=0,
        setup=function(mock) mock.funcs.ownerClass="/Script/kards.UnrelatedFunctions" end},
    {name="unknown_context",reason="damage_context_mismatch",calls=0,
        setup=function(mock) mock.funcs.IsA=function() return nil end end},
    {name="native_throws",reason="native_error",calls=1,outType="nil",
        setup=function(mock) mock.damageFunction.implementation=function() error("explicit native failure before mutation") end end},
    {name="native_no_change",reason="native_no_effect",calls=1,outType="nil",
        setup=function(mock) mock.damageFunction.implementation=function() end end},
    {name="native_no_change_false_out",reason="native_no_effect",calls=1,outType="boolean",
        setup=function(mock) mock.damageFunction.implementation=function(_,_,_,_,_,_,_,out) out.targetDestroyed=false end end}
}
for _,case in ipairs(resolutionCases) do
    local mock=fixture()
    local nativeImplementation=mock.damageFunction.implementation
    local validFullName=mock.damageFunction.GetFullName
    local validIsA=mock.funcs.IsA
    mock.funcs.DamageCard=function()
        mock.boundCalls=mock.boundCalls+1
        error("fallback to bound DamageCard is forbidden")
    end
    case.setup(mock)
    local nativeAdapter=Adapter.new("dispatch_"..case.name,{damageMode="native"})
    local nativeCore=Core.new("dispatch_"..case.name)
    local ctx=nativeAdapter:context()
    local nativeCommand="dispatch_client 1 dmg "..ctx.matchId.." 4 10 2\n"
    local function process()
        return nativeCore:process(nativeCommand,function() return nativeAdapter:context() end,
            function(cmd,current) return nativeAdapter:damage(cmd,current) end)
    end
    equal(process(),1,case.name..": request is recorded once")
    equal(nativeCore.results[1].reason,case.reason,case.name..": explicit failure reason")
    equal(nativeCore.results[1].accepted,false,case.name..": never acknowledges damage")
    equal(nativeCore.results[1].damage,0,case.name..": no fictional damage")
    equal(nativeCore.results[1].attack,0,case.name..": no counterattack success")
    equal(mock.damageFunction.calls,case.calls,case.name..": exact native invocation count")
    equal(mock.boundCalls,0,case.name..": no instance-method fallback")
    equal(mock.funcs.damageCalls,0,case.name..": native mutation implementation did not run")
    equal(mock.target.actualDefense,5,case.name..": health untouched")
    equal(mock.target.defense,1234567,case.name..": encrypted field untouched")
    equal(mock.target.Location,7,case.name..": card remains on the board")
    check(nativeAdapter.lastDamage~=nil,case.name..": diagnostic survives failure")
    equal(nativeAdapter.lastDamage.reason,case.reason)
    equal(nativeAdapter.lastDamage.dispatch,"explicit_blueprint")
    equal(nativeAdapter.lastDamage.nativeInvoked,case.calls>0)
    equal(nativeAdapter.lastDamage.functionPath,DAMAGE_FUNCTION_PATH)
    equal(nativeAdapter.lastDamage.amount,2)
    equal(nativeAdapter.lastDamage.sourceId,1)
    if case.outType then equal(nativeAdapter.lastDamage.targetDestroyedType,case.outType) end
    check(pcall(Core.json,nativeAdapter.lastDamage),case.name..": diagnostics contain serializable values only")
    -- Repair every possible mock fault, then replay the same command: even now it must not execute again.
    mock.functionBinding.value=mock.damageFunction
    mock.damageFunction.invalid=false
    mock.damageFunction.GetFullName=validFullName
    mock.damageFunction.implementation=nativeImplementation
    mock.funcs.IsA=validIsA
    mock.funcs.ownerClass=DAMAGE_OWNER_PATH
    equal(process(),0,case.name..": duplicate remains suppressed after backend repair")
    equal(mock.damageFunction.calls,case.calls,case.name..": no late native retry")
    equal(mock.target.actualDefense,5,case.name..": replay cannot mutate health")
    equal(#nativeCore.results,1,case.name..": no duplicate ACK")
end

-- The reported live failure was an enemy HQ (20 -> 20), not just an ordinary unit.
-- Confirm that the same explicit dispatch can acknowledge HQ loss without counterattack.
f=fixture(); adapter=Adapter.new("hq_dispatch",{damageMode="native"})
local hqProtocol=Core.new("hq_dispatch")
local hqContext=adapter:context()
local hqCommand="hq_client 1 dmg "..hqContext.matchId.." 4 2 2\n"
local function processHQ()
    return hqProtocol:process(hqCommand,function() return adapter:context() end,
        function(cmd,ctx) return adapter:damage(cmd,ctx) end)
end
equal(processHQ(),1)
equal(hqProtocol.results[1].accepted,true,"HQ damage requires observed health loss")
equal(hqProtocol.results[1].damage,2)
equal(hqProtocol.results[1].attack,0,"HQ never gains a counterattack from damage ACK")
equal(adapter.lastDamage.before,20)
equal(adapter.lastDamage.after,18)
equal(adapter.lastDamage.sourceId,1)
equal(adapter.lastDamage.cardId,2)
equal(adapter.lastDamage.functionFullName,DAMAGE_FUNCTION_NAME)
equal(processHQ(),0,"same HQ command is never applied twice")
equal(f.damageFunction.calls,1)
equal(f.cards[2].actualDefense,18)

-- Main-loop smoke test writes only to Python's isolated temporary directory.
f=fixture()
local savedGetenv=os.getenv
os.getenv=function(name) if name=="KARDSCRAFT_BRIDGE_DIR" then return TEST_TEMP end; return savedGetenv(name) end
local loop, queued
LoopAsync=function(interval,fn) equal(interval,16); loop=fn end
ExecuteInGameThread=function(fn) check(not queued,"at most one queued callback"); queued=fn end
Key={F8=119}
RegisterKeyBind=function(key,fn) equal(key,119); check(type(fn)=="function") end
local savedCameraModule=package.loaded.camera_follow
local cameraTicks=0
package.loaded.camera_follow={clockMillis=function() return 0 end,new=function()
    return {tick=function() cameraTicks=cameraTicks+1; return {active=false,status="fixture_idle"} end}
end}
gameThread=false
package.loaded.bridge_settings={directory="should_not_override_environment",damageMode="native"}
require("main")
loop(); loop()
check(queued~=nil,"async loop schedules game-thread work")
local work=queued; queued=nil; gameThread=true; work(); gameThread=false
local snapshotFile=assert(io.open(TEST_TEMP.."/KardsBridge_state.json","rb"))
local json=snapshotFile:read("*a"); snapshotFile:close()
check(json:find('"protocol":2',1,true)~=nil)
check(json:find('"end":1}',1,true)~=nil)
loop(); work=queued; queued=nil; gameThread=true; work()

-- Journal integration stays inside TEST_TEMP; actual UE4SS objects and game files are never used.
local commandFilePath=TEST_TEMP.."/KardsBridge_cmd.txt"
local journalPath=TEST_TEMP.."/KardsBridge_damage.jsonl"
local smokeMatchId=assert(json:match('"matchId":"([^"]+)"'))
local smokeImplementation=f.damageFunction.implementation
local function readText(path)
    local file=assert(io.open(path,"rb"))
    local text=file:read("*a"); file:close(); return text
end
local function writeText(path,text)
    local file=assert(io.open(path,"wb"))
    assert(file:write(text)); assert(file:close())
end
local function journalLines()
    local lines={}
    for line in readText(journalPath):gmatch("([^\n]+)\n") do lines[#lines+1]=line end
    return lines
end
local function tickOnce()
    local beforeCameraTicks=cameraTicks
    for _=1,6 do
        gameThread=false; loop()
        check(queued~=nil,"main loop queued journal test tick")
        local callback=queued; queued=nil; gameThread=true; callback()
    end
    equal(cameraTicks-beforeCameraTicks,6,"camera ticks every callback while damage runs on the six-tick cadence")
end
local function smokeCommand(sequence,amount)
    writeText(commandFilePath,"journal_client "..sequence.." dmg "..smokeMatchId.." 4 10 "..amount.."\n")
    tickOnce()
end
local function snapshotResult(sequence)
    local snapshot=readText(TEST_TEMP.."/KardsBridge_state.json")
    local results=assert(snapshot:match('"results":(%b[])'),"published results array")
    for result in results:gmatch("%b{}") do
        if result:match('"seq":'..sequence..'[,}]') then return result,snapshot end
    end
    error("ACK not found for sequence "..sequence)
end
smokeCommand(1,2)
equal(f.target.actualDefense,3,"journaled successful call mutates only the mock target")
equal(f.damageFunction.calls,1,"first journal request invokes native once")
f.damageFunction.implementation=function() end
smokeCommand(2,2)
local lines=journalLines()
equal(#lines,2,"success and native no-effect each write one journal line")
local successResult=assert(lines[1]:match('"result":(%b{})'))
local failureResult=assert(lines[2]:match('"result":(%b{})'))
check(successResult:find('"accepted":true',1,true)~=nil,"journal success is acknowledged")
check(successResult:find('"reason":"native_damage_observed"',1,true)~=nil)
check(failureResult:find('"accepted":false',1,true)~=nil,"journal no-effect remains a rejection")
check(failureResult:find('"reason":"native_no_effect"',1,true)~=nil)
for index,line in ipairs(lines) do
    check(line:find('"schema":1',1,true)~=nil,"journal schema recorded")
    check(line:match('"observedAt":"%d%d%d%d%-%d%d%-%d%dT%d%d:%d%d:%d%dZ"')~=nil,"journal timestamp is UTC")
    check(line:find('"sessionId":',1,true)~=nil,"journal session recorded")
    local diagnostic=assert(line:match('"diagnostic":(%b{})'))
    check(diagnostic:find('"functionFullName":"'..DAMAGE_FUNCTION_NAME..'"',1,true)~=nil,"exact native function journaled")
    check(diagnostic:find('"seq":'..index..',',1,true)~=nil,"diagnostic linked to matching request")
    check(diagnostic:find('"targetRef":"BaseCard World.Card_10"',1,true)~=nil,"target reference journaled")
end
local beforeDuplicate=readText(journalPath)
tickOnce()
equal(readText(journalPath),beforeDuplicate,"duplicate request does not append a second journal record")
equal(f.damageFunction.calls,2,"duplicate journal request does not call native again")
equal(f.target.actualDefense,3,"no-effect and duplicate leave mock health unchanged")

-- A disk error cannot turn a successful native call into a rejected ACK or make it run twice.
f.damageFunction.implementation=smokeImplementation
local originalIoOpen=io.open
io.open=function(path,mode)
    if tostring(path):gsub("\\","/")==journalPath and mode=="ab" then
        return nil,"simulated journal append denial"
    end
    return originalIoOpen(path,mode)
end
smokeCommand(3,1)
io.open=originalIoOpen
local ackAfterLogFailure,snapshotAfterLogFailure=snapshotResult(3)
check(ackAfterLogFailure:find('"accepted":true',1,true)~=nil,"journal failure does not corrupt successful ACK")
check(snapshotAfterLogFailure:find('"damageLogError":',1,true)~=nil,"journal failure remains visible in snapshot")
equal(f.target.actualDefense,2,"native damage remains applied when journaling fails")
equal(f.damageFunction.calls,3,"failed journal append does not trigger an extra native invocation")
equal(readText(journalPath),beforeDuplicate,"failed journal append retains preceding records")
tickOnce()
equal(f.damageFunction.calls,3,"repeated command after journal failure is still not retried")
equal(readText(journalPath),beforeDuplicate,"repeated command after journal failure is not re-journaled")

-- Crossing the configured 1 MiB boundary keeps one previous generation and starts a new valid record.
local fullJournal=string.rep("x",1024*1024)
writeText(journalPath,fullJournal)
writeText(journalPath..".1","older-generation")
smokeCommand(4,1)
equal(readText(journalPath..".1"),fullJournal,"rotation replaces the previous generation with the bounded full journal")
local rotated=journalLines()
equal(#rotated,1,"rotated journal begins with this request only")
check(rotated[1]:find('"reason":"native_damage_observed"',1,true)~=nil)
local rotatedAck,rotatedSnapshot=snapshotResult(4)
check(rotatedAck:find('"accepted":true',1,true)~=nil,"journal rotation preserves ACK")
check(rotatedSnapshot:find('"damageLogError":',1,true)==nil,"successful journal write clears prior I/O error")
equal(f.target.actualDefense,1)
equal(f.damageFunction.calls,4,"rotation does not rerun native code")
tickOnce()
equal(#journalLines(),1,"duplicate after rotation does not append")
equal(f.damageFunction.calls,4)
assert(os.remove(commandFilePath))

-- An older deployed folder without the optional settings module also starts.
local originalRequire=require
package.loaded.main=nil
require=function(name) if name=="bridge_settings" then error("module not found") end; return originalRequire(name) end
gameThread=false
require("main")
loop(); work=queued; queued=nil; gameThread=true; work()
require=originalRequire
package.loaded.main=nil
package.loaded.bridge_settings={directory=TEST_TEMP}
os.getenv=function(name) if name=="KARDSCRAFT_BRIDGE_DIR" then return nil end; return "should_not_override_settings" end
gameThread=false
require("main")
loop(); work=queued; queued=nil; gameThread=true; work()
os.getenv=savedGetenv
package.loaded.camera_follow=savedCameraModule
equal(threadViolations,0,"no swallowed off-thread UObject access")
print("KardsBridge mock checks passed: "..passed)
return json
