-- Independent UE4SS-shaped fixture for the opt-in/default memory setter adapter.
-- These are isolated mocks; they do not establish real game or server persistence.
local Core = require("bridge_core")
local Adapter = require("kards_adapter")
local OWNER = "/Game/Blueprints/Cards/BP_CardFunctions.BP_CardFunctions_C"
local BASE_CARD = "/Script/kards.BaseCardObject"
local PROVIDER = OWNER .. ":provideKeysAndFrameCount"
local SETTER = BASE_CARD .. ":setAndEncryptDefense"
local DESTROY = OWNER .. ":DestroyCard"
local NATIVE = OWNER .. ":DamageCard"
local ENGINE_VALID = "/Script/Engine.KismetSystemLibrary:IsValid"
local ENGINE_SYSTEM = "/Script/Engine.Default__KismetSystemLibrary"
local previousFind, previousStatic = FindAllOf, StaticFindObject
local passed = 0
local function check(value, message)
    assert(value, message or "check failed"); passed = passed + 1
end
local function equal(actual, expected, message)
    check(actual == expected, (message or "values differ") .. ": expected=" .. tostring(expected) .. " actual=" .. tostring(actual))
end
local function object(name, fields)
    local obj = fields or {}
    obj.IsValid = function(self) return not self.invalid end
    obj.GetFullName = function(self) return self.fullName or name end
    return obj
end
local function array(items)
    return {GetArrayNum=function() return #items end,
        ForEach=function(_, fn) for i,v in ipairs(items) do fn(i,{get=function() return v end}) end end}
end
local function fixture(options)
    local f = {key1=-1987654321,key2=1765432109,frame=123456789,providerCalls=0,setterCalls=0,
        deathCalls=0,uiCalls=0,nativeCalls=0,boundCalls=0,lookups={},options=options,
        visualDestroyCalls=0,visualFallbackCalls=0,widgetCleanupCalls=0,widgetFallbackCalls=0,
        engineValidityCalls=0,validityMemberCalls=0}
    local function card(id,side,def,atk,loc)
        local c = object("BaseCard World.Card_"..id,{CardID=id,side=side,actualDefense=def,actualAttack=atk,
            totalBonus=0,Location=loc,locationNumber=1,Name="Card"..id,defense=7654321,attack=1234567,
            ownerClass=BASE_CARD})
        c.IsA = function(self,path) return path == self.ownerClass end
        c.getAndDecryptDefense = function(self,out) out.decryptedDefense=self.actualDefense end
        c.getAndDecryptAttack = function(self,out) out.decryptedAttack=self.actualAttack end
        c.getTotalDefense = function(self,out)
            if not self.totalUnavailable then out.totalDefense=self.actualDefense+self.totalBonus end
        end
        c.getTotalAttack = function(self,out) out.totalAttack=self.actualAttack+1 end
        c.setAndEncryptDefense = function() f.boundCalls=f.boundCalls+1; error("ambiguous member setter used") end
        return c
    end
    f.left,f.right,f.target=card(1,1,20,0,5),card(2,2,20,0,6),card(10,2,5,3,7)
    f.cards={[1]=f.left,[2]=f.right,[10]=f.target}
    local map={ForEach=function(_,fn) for id,c in pairs(f.cards) do fn({get=function() return id end},{get=function() return c end}) end end}
    f.state=object("State World.State",{AllCardsInBattle=map,CurrentTurnNumberInBattle=4,
        Left_HQ_CardID=1,Rgith_HQ_CardID=2,Left_HQ_Card_Ref=f.left,Right_HQ_Card_Ref=f.right,
        IsLocalClientTurn=true,MatchFinished=false,ActionProcess=false,stopFurtherActions=false})
    f.state.GetClientSide=function(_,out) out.OutClientSide=f.mySide or 1 end
    f.logic=object("Logic World.Logic",{GameStateRef=f.state,matchHasEnded=false,isReplayMatch=false,isSpectate=false})
    f.funcs=object("Functions World.Functions",{GameStateRef=f.state,TriggerQueue=array({}),ownerClass=OWNER})
    f.funcs.IsA=function(self,path) return path==self.ownerClass end
    f.funcs.DamageCard=function() f.boundCalls=f.boundCalls+1; error("bound DamageCard used") end
    f.funcs.provideKeysAndFrameCount=function() f.boundCalls=f.boundCalls+1; error("bound key provider used") end
    f.funcs.DestroyCard=function() f.boundCalls=f.boundCalls+1; error("bound DestroyCard used") end
    f.controller=object("Controller World.Controller",{match={match_id=77},isResyncingMatch=false,ai=true})
    f.controller.isAIMatch=function(self) return self.ai end
    f.match=object("Match World.Match",{GameStateRef=f.state,cardFunctions=f.funcs,Logic=f.logic,mySide=0,
        isResolvingAction=false,actionsPaused=false,isValidatingActionSent=false,actionsQueue=array({}),
        pendingCardsSelectingHandTargets=array({}),enemyPendingCardsSelectingHandTargets=array({})})
    f.logic.onlineMatch=f.match
    f.actors={}
    for id,c in pairs(f.cards) do
        local actor=object("BoardCard World.Actor_"..id,{CardID=id,GameStateRef=f.state,selfBaseCardRef=c,
            K2_GetActorLocation=function() return {X=id*100,Y=0,Z=5} end})
        actor.SetDefense=function(self,value)
            f.uiCalls=f.uiCalls+1
            if f.uiError then error("visual update failed") end
            self.visibleDefense=value
        end
        local widget=object("CardWidget World.Widget_"..id,{card=c})
        actor.BoardCardWidget=widget
        actor.IsActorBeingDestroyed=function(self) return self.destroying==true end
        actor.DestroyCard=function(self)
            f.visualDestroyCalls=f.visualDestroyCalls+1
            check(not self.invalid,"visual DestroyCard is never called on a dead actor")
            if f.visualDestroyError then error("visual destroy failed") end
            if not f.visualNoEffect then
                self.invalid=true
                if not f.preserveWidget then widget.invalid=true end
            end
        end
        actor.K2_DestroyActor=function(self)
            f.visualFallbackCalls=f.visualFallbackCalls+1
            check(not self.invalid,"actor fallback is never called on a dead actor")
            if f.visualFallbackError then error("visual fallback failed") end
            if not f.visualFallbackNoEffect then self.invalid=true end
        end
        widget.IsActorBeingDestroyed=function(self) return self.destroying==true end
        widget.CleanUp=function(self,owner)
            f.widgetCleanupCalls=f.widgetCleanupCalls+1
            equal(owner,actor,"widget cleanup receives its captured owner")
            check(not owner.invalid,"widget cleanup is never passed an invalid owner UObject")
            check(not self.invalid,"widget cleanup is never called on a dead widget")
            if f.widgetCleanupError then error("widget cleanup failed") end
            if not f.widgetNoEffect then self.invalid=true end
        end
        widget.K2_DestroyActor=function(self)
            f.widgetFallbackCalls=f.widgetFallbackCalls+1
            check(not self.invalid,"widget fallback is never called on a dead widget")
            if f.widgetFallbackError then error("widget fallback failed") end
            if not f.widgetFallbackNoEffect then self.invalid=true end
        end
        f.actors[id]=actor
    end
    f.board=object("Board World.Board",{GameStateRef=f.state,GetVisualBoardCardCardFromID=function(_,id,actorOut,foundOut)
        if f.boardLookupError then error("board lookup failed") end
        if f.boardOutputs then return f.boardOutputs(id,actorOut,foundOut) end
        actorOut.cardRef=f.actors[id]; foundOut.Found=actorOut.cardRef~=nil
    end})
    f.logic.cachedBoard=f.board
    f.camera=object("Camera World.Camera",{GetCameraLocation=function() return {X=100,Y=0,Z=800} end,
        GetCameraRotation=function() return {Pitch=-60,Yaw=0,Roll=0} end,GetFOVAngle=function() return 60 end})
    f.gameplay=object("GameplayStatics",{GetGameState=function() return f.state end,
        GetGameMode=function() return f.logic end,GetPlayerCameraManager=function() return f.camera end})
    f.controllerClass=object("MatchControllerCDO",{GetMatchController=function() return f.controller end})
    f.system=object("KismetSystemLibrary Default__KismetSystemLibrary")
    f.system.IsValid=function(_,unexpectedActor)
        if unexpectedActor~=nil then f.validityMemberCalls=f.validityMemberCalls+1 end
        return true -- UObject wrapper method; it deliberately ignores a supplied actor.
    end
    local function fn(path, implementation)
        local callable=object("Function "..path,{implementation=implementation})
        callable.GetFunctionFlags=function() return 0x04020400 end
        return setmetatable(callable,{__call=function(self,...) return self.implementation(...) end})
    end
    f.functions={}
    f.functions[ENGINE_VALID]=fn(ENGINE_VALID,function(receiver,actor)
        assert(receiver==f.system,"engine validity UFunction requires the Kismet CDO context")
        f.engineValidityCalls=f.engineValidityCalls+1
        if actor.engineValidityError then error("engine validity unavailable") end
        if actor.engineValidityNil then return nil end
        return not actor.invalid and not actor.engineInvalid
    end)
    f.functions[PROVIDER]=fn(PROVIDER,function(receiver,key1,key2,frame)
        f.providerCalls=f.providerCalls+1
        equal(receiver,f.funcs,"key provider uses the live CardFunctions receiver")
        check(type(key1)=="table" and type(key2)=="table" and type(frame)=="table","three out parameters are tables")
        check(key1~=key2 and key1~=frame and key2~=frame,"out parameters are three independent tables")
        if f.providerError then error("provider leaked "..tostring(f.key1)..":"..tostring(f.key2)) end
        if f.keyOutputs then return f.keyOutputs(key1,key2,frame) end
        key1.key1=f.key1; key2.key2=f.key2; frame.FrameCount=f.frame
    end)
    f.functions[SETTER]=fn(SETTER,function(receiver,newDefense,key1,key2,frame)
        f.setterCalls=f.setterCalls+1
        check(f.cards[receiver.CardID]==receiver,"setter receives the current card object")
        equal(key1,f.key1,"setter receives current key1"); equal(key2,f.key2,"setter receives current key2")
        equal(frame,f.frame,"setter receives current frame")
        f.writtenDefense=newDefense
        if f.setterError then error("setter leaked "..tostring(key1)..":"..tostring(key2)) end
        if not f.noEffect then receiver.actualDefense=newDefense end
        if f.onlyDecryptedChanged then receiver.totalBonus=receiver.totalBonus+(5-newDefense) end
        if f.dropTotalAfterWrite then receiver.totalUnavailable=true end
        if f.setterPostError then error("post-write leaked "..tostring(key1)..":"..tostring(key2)) end
    end)
    f.functions[DESTROY]=fn(DESTROY,function(receiver,...)
        f.deathCalls=f.deathCalls+1
        equal(receiver,f.funcs,"DestroyCard uses live CardFunctions")
        if f.deathError then error("death failed") end
        if not f.deathNoEffect then f.target.Location=8 end
        if f.deathRemoveFromMap then f.cards[10]=nil end
        f.deathArgs=table.pack(...)
    end)
    f.functions[NATIVE]=fn(NATIVE,function()
        f.nativeCalls=f.nativeCalls+1
        error("native damage must never be called by memory mode")
    end)
    FindAllOf=function(class)
        if class=="BP_OnlineMatch_C" then return {f.match} end
        if class=="BP_BoardCard_C" and not f.disableActorFallback then
            local a={} for _,actor in pairs(f.actors) do a[#a+1]=actor end return a
        end
        return {}
    end
    StaticFindObject=function(path)
        f.lookups[#f.lookups+1]=path
        if path=="/Script/Engine.Default__GameplayStatics" then return f.gameplay end
        if path=="/Script/kards.Default__MatchControllerV2" then return f.controllerClass end
        if path==ENGINE_SYSTEM then return f.system end
        return f.functions[path]
    end
    f.adapter=Adapter.new("memory_session",options)
    function f:damage(id,amount,seq)
        local context=self.adapter:context()
        return self.adapter:damage({clientId="memory_client",seq=seq or 1,cardId=id or 10,
            amount=amount or 2,matchId=context.matchId,turn=context.turn},context)
    end
    function f:process(id,amount,seq)
        self.protocol=self.protocol or Core.new("memory_session")
        local context=self.adapter:context()
        local command="memory_client "..(seq or 1).." dmg "..context.matchId.." "..context.turn.." "..(id or 10).." "..(amount or 2).."\n"
        return self.protocol:process(command,function() return self.adapter:context() end,
            function(cmd,ctx) return self.adapter:damage(cmd,ctx) end)
    end
    function f:observe(times)
        local snapshot
        for _=1,times or 1 do snapshot=self.adapter:snapshot() end
        return snapshot
    end
    return f
end
local function noNative(f)
    equal(f.nativeCalls,0,"memory mode does not fall back to DamageCard")
    equal(f.boundCalls,0,"memory mode does not call ambiguous bound methods")
end
local function noKeyLeak(f)
    local encoded=Core.json(f.adapter.lastDamage)
    check(not encoded:find(tostring(f.key1),1,true),"diagnostic does not contain key1")
    check(not encoded:find(tostring(f.key2),1,true),"diagnostic does not contain key2")
end
local function rejected(f,reason,setterCalls)
    local result=f:damage()
    equal(result.accepted,false); equal(result.reason,reason); equal(result.damage,0); equal(result.attack,0)
    equal(f.adapter.lastDamage.accepted,false); equal(f.adapter.lastDamage.reason,reason)
    equal(f.setterCalls,setterCalls or 0); noNative(f)
    return result
end

-- Default mode and explicit memory mode both use current decrypted values, not encrypted fields.
for _,options in ipairs({{}, {damageMode="memory_setter"}}) do
    local f=fixture(options)
    local result=f:damage()
    equal(result.accepted,true); equal(result.reason,"memory_damage_observed")
    equal(result.damage,2); equal(result.attack,4)
    equal(f.target.actualDefense,3); equal(f.target.defense,7654321,"raw encrypted field is not directly overwritten")
    equal(f.providerCalls,1); equal(f.setterCalls,1); equal(f.deathCalls,0)
    equal(f.actors[10].visibleDefense,3); equal(f.uiCalls,1)
    local d=f.adapter.lastDamage
    equal(d.dispatch,"memory_setter"); equal(d.functionPath,SETTER); equal(d.functionFullName,"Function "..SETTER)
    equal(d.keyProviderPath,PROVIDER); equal(d.keysAvailable,true); equal(d.setterInvoked,true)
    equal(d.amount,2); equal(d.sourceId,1); equal(d.cardId,10); equal(d.seq,1)
    equal(d.clientId,"memory_client"); equal(d.matchId,f.adapter.matchId)
    equal(d.before,5); equal(d.after,3); equal(d.beforeDecrypted,5); equal(d.afterDecrypted,3)
    equal(d.beforeRaw,7654321); equal(d.afterRaw,7654321); equal(d.targetDefense,3)
    equal(d.uiRefresh,"call_completed"); equal(d.deathResolution,"not_needed"); equal(d.deathInvoked,false)
    noKeyLeak(f); noNative(f)
    local snapshot=f.adapter:snapshot()
    equal(snapshot.backend,"memory_setter_observation_only"); equal(snapshot.damageMode,"memory_setter")
    equal(snapshot.serverPersistenceVerified,false,"observed local change does not assert server persistence")
end
do
    local f=fixture(nil); equal(f:damage().accepted,true,"omitted options select memory mode"); noNative(f)
end
do
    local f=fixture({damageMode="typo"}); rejected(f,"damage_mode_unsupported")
    equal(f.providerCalls,0,"unknown mode fails before keys are requested")
end
do
    local f=fixture({damageMode="native"})
    equal(f:damage().reason,"native_error","explicit native mode retains the original dispatch")
    equal(f.nativeCalls,1); equal(f.setterCalls,0); equal(f.providerCalls,0); equal(f.boundCalls,0)
end

-- A buff changes total defense, so the setter must subtract damage from decrypted base defense.
do
    local f=fixture(); f.target.totalBonus=3
    local r=f:damage(); equal(r.damage,2); equal(f.writtenDefense,3); equal(f.adapter.lastDamage.before,8)
    equal(f.adapter.lastDamage.after,6); equal(f.actors[10].visibleDefense,6)
end
do
    local f=fixture(); f.mySide=2
    equal(f:damage().accepted,true); equal(f.adapter.lastDamage.sourceId,2,"source HQ follows local side")
end
for _,triple in ipairs({{-2147483648,2147483647,-2147483648},{2147483647,-2147483648,2147483647},{0,0,0}}) do
    local f=fixture(); f.key1,f.key2,f.frame=table.unpack(triple)
    equal(f:damage().accepted,true,"all signed32 key/frame boundary values are accepted")
    equal(f.setterCalls,1); noNative(f)
end

-- Installed UE4SS 5418fa4e may write every scalar out parameter into the first
-- table. The adapter must identify explicit names across tables, never positions.
for _,layout in ipairs({"separate","packed_first","packed_mixed_case","scattered_mixed_case"}) do
    local f=fixture(); f.disableActorFallback=true
    if layout=="packed_first" then
        f.keyOutputs=function(first)
            first.key1=f.key1; first.key2=f.key2; first.FrameCount=f.frame
        end
        f.boardOutputs=function(id,first)
            first.cardRef=f.actors[id]; first.Found=first.cardRef~=nil
        end
    elseif layout=="packed_mixed_case" then
        f.keyOutputs=function(first)
            first.Key1=f.key1; first.kEy2=f.key2; first.FRAMECOUNT=f.frame
        end
        f.boardOutputs=function(id,first)
            first.CARDREF=f.actors[id]; first.fOuNd=first.CARDREF~=nil
        end
    elseif layout=="scattered_mixed_case" then
        f.keyOutputs=function(first,second,third)
            first.KEY2=f.key2; second.framecount=f.frame; third.KEY1=f.key1
        end
        f.boardOutputs=function(id,first,second)
            first.found=f.actors[id]~=nil; second.CardRef=f.actors[id]
        end
    end
    local r=f:damage()
    equal(r.accepted,true,"supported out layout: "..layout); equal(r.damage,2)
    equal(f.setterCalls,1); equal(f.uiCalls,1); equal(f.actors[10].visibleDefense,3)
    equal(f.adapter.lastDamage.uiRefresh,"call_completed")
    local fieldLayouts=f.adapter.lastDamage.keyOutputFields
    equal(#fieldLayouts,3,"diagnostics retain all three output-table layouts")
    if layout=="packed_first" or layout=="packed_mixed_case" then
        equal(#fieldLayouts[1],3); equal(#fieldLayouts[2],0); equal(#fieldLayouts[3],0)
    else equal(#fieldLayouts[1],1); equal(#fieldLayouts[2],1); equal(#fieldLayouts[3],1) end
    for _,fields in ipairs(fieldLayouts) do
        for _,field in ipairs(fields) do check(field:match("^[%a%d]+:number$")~=nil,"output diagnostics contain only a field name and type") end
    end
    noKeyLeak(f); noNative(f)
    local snapshot=f.adapter:snapshot()
    equal(snapshot.geometryReady,true,"authoritative board lookup supplies geometry without actor-scan fallback")
    equal(snapshot.unpositionedCards,0); equal(#snapshot.cards,3)
    equal(snapshot.hqLeft.x,100); equal(snapshot.hqRight.x,200)
    for _,c in ipairs(snapshot.cards) do
        equal(c.pos.x,c.id*100,"snapshot uses the actor returned for this card ID")
        equal(c.pos.y,0); equal(c.pos.z,5)
    end
end
for _,field in ipairs({"key1","key2","FrameCount"}) do
    for _,sameTable in ipairs({false,true}) do
        local f=fixture()
        f.keyOutputs=function(first,second,third)
            first.key1=f.key1; second.key2=f.key2; third.FrameCount=f.frame
            local canonical=field=="key1" and first or field=="key2" and second or third
            local duplicate=sameTable and canonical or (canonical==first and third or first)
            local original=field=="key1" and f.key1 or field=="key2" and f.key2 or f.frame
            duplicate[field:upper()]=original+1
        end
        rejected(f,"memory_keys_unavailable")
        equal(f.target.actualDefense,5,"conflicting named outputs prevent all mutation")
    end
end
for _,layout in ipairs({"numeric_positions","unrelated_names","name_prefixes"}) do
    local f=fixture()
    f.keyOutputs=function(first,second,third)
        if layout=="numeric_positions" then first[1]=f.key1; first[2]=f.key2; first[3]=f.frame
        elseif layout=="unrelated_names" then first.value=f.key1; second.result=f.key2; third.output=f.frame
        else first.key1Value=f.key1; second.key2Value=f.key2; third.FrameCountValue=f.frame end
    end
    rejected(f,"memory_keys_unavailable"); equal(f.target.actualDefense,5)
end
do
    local f=fixture()
    f.keyOutputs=function(first,second,third)
        first.key1=f.key1; first.key2=f.key2; first.FrameCount=f.frame
        second.KEY1=f.key1; second[1]=999; third.extra="irrelevant"
    end
    equal(f:damage().accepted,true,"identical duplicates and unrelated fields do not conflict with explicit named outputs")
    equal(f.setterCalls,1); noKeyLeak(f); noNative(f)
end

-- Found=false, conflicting names, and positional values must never select a visual actor.
for _,layout in ipairs({"packed_false","found_conflict","actor_conflict","positional","wrong_names"}) do
    local f=fixture(); f.disableActorFallback=true
    f.boardOutputs=function(id,first,second)
        if layout=="packed_false" then first.cardRef=f.actors[id]; first.Found=false
        elseif layout=="found_conflict" then first.cardRef=f.actors[id]; first.Found=true; second.found=false
        elseif layout=="actor_conflict" then
            first.cardRef=f.actors[id]; first.Found=true
            second.CARDREF=f.actors[id==1 and 2 or 1]
        elseif layout=="positional" then first[1]=f.actors[id]; first[2]=true
        else first.actor=f.actors[id]; first.wasFound=true end
    end
    local r=f:damage()
    equal(r.accepted,true,"unavailable visual actor does not revoke confirmed mutation: "..layout)
    equal(r.damage,2); equal(f.setterCalls,1); equal(f.uiCalls,0)
    equal(f.adapter.lastDamage.uiRefresh,"unavailable")
    local snapshot=f.adapter:snapshot()
    equal(snapshot.geometryReady,false,"invalid board output cannot establish geometry without a fallback")
    equal(snapshot.unpositionedCards,3); equal(#snapshot.cards,0)
    equal(snapshot.hqLeft,nil); equal(snapshot.hqRight,nil); noNative(f)
end

-- Resolve and validate both exact APIs before writing; unavailable/wrong API is never a fallback signal.
for _,path in ipairs({PROVIDER,SETTER}) do
    for _,mode in ipairs({"missing","invalid","mismatch"}) do
        local f=fixture()
        if mode=="missing" then f.functions[path]=nil
        elseif mode=="invalid" then f.functions[path].invalid=true
        else f.functions[path].fullName="Function /Script/kards.WrongOwner:WrongFunction" end
        rejected(f,mode=="mismatch" and "memory_api_mismatch" or "memory_api_unavailable")
        equal(f.target.actualDefense,5,"API rejection has no mutation")
    end
end
for _,target in ipairs({"funcs","target"}) do
    local f=fixture(); f[target].ownerClass="/Script/WrongOwner"
    rejected(f,"memory_context_mismatch"); equal(f.providerCalls,0)
end
do
    local f=fixture(); f.funcs.IsA=function() return nil end
    rejected(f,"memory_context_mismatch")
end
do
    local f=fixture(); f.providerError=true
    rejected(f,"memory_keys_error"); equal(f.providerCalls,1); noKeyLeak(f)
end
do
    local f=fixture()
    f.functions[PROVIDER].implementation=function() return f.key1,f.key2,f.frame end
    rejected(f,"memory_keys_unavailable")
    equal(f.target.actualDefense,5,"return values cannot substitute for named out parameters")
end
for _,field in ipairs({"key1","key2","frame"}) do
    for _,value in ipairs({false,"1",1.25,2147483648,-2147483649,math.huge,0/0}) do
        local f=fixture(); f[field]=value
        rejected(f,"memory_keys_unavailable"); equal(f.providerCalls,1)
        equal(f.adapter.lastDamage.keysAvailable,false)
    end
    local f=fixture(); f[field]=nil
    rejected(f,"memory_keys_unavailable"); equal(f.providerCalls,1)
end
do
    local f=fixture(); f.setterError=true
    rejected(f,"memory_write_error",1); noKeyLeak(f)
    equal(f.target.actualDefense,5); equal(f.deathCalls,0); equal(f.uiCalls,0)
    equal(f.adapter.lastDamage.setterInvoked,true)
end
do
    local f=fixture(); f.setterPostError=true
    equal(f:process(),1)
    local result=f.protocol.results[1]
    equal(result.accepted,true,"confirmed health loss remains accepted if setter raises after mutation")
    equal(result.reason,"memory_damage_observed"); equal(result.damage,2)
    equal(f.target.actualDefense,3); equal(f.setterCalls,1)
    check(type(f.adapter.lastDamage.error)=="string","post-write error remains visible without keys")
    noKeyLeak(f); noNative(f)
    f.setterPostError=false
    equal(f:process(),0,"accepted partial-error request is not retried")
    equal(f.setterCalls,1); equal(f.providerCalls,1); equal(#f.protocol.results,1)
end
do
    local f=fixture(); f.setterPostError=true; f.dropTotalAfterWrite=true
    rejected(f,"memory_readback_unavailable",1)
    equal(f.target.actualDefense,3); noKeyLeak(f)
    equal(f.uiCalls,0); equal(f.deathCalls,0)
end
do
    local f=fixture(); f.noEffect=true
    rejected(f,"memory_no_effect",1); equal(f.providerCalls,1); equal(f.uiCalls,0)
end
do
    local f=fixture(); f.onlyDecryptedChanged=true
    rejected(f,"memory_no_effect",1)
    equal(f.target.actualDefense,3,"getter of base defense alone cannot authorize ACK")
end
do
    local f=fixture(); f.target.totalUnavailable=true
    rejected(f,"memory_total_defense_unavailable")
    equal(f.target.actualDefense,5,"missing total defense fails before any write")
end
do
    local f=fixture(); f.target.actualDefense=15; f.target.totalBonus=5; f.dropTotalAfterWrite=true
    local result=f:damage(10,1)
    equal(result.accepted,false); equal(result.reason,"memory_readback_unavailable"); equal(result.damage,0)
    equal(f.setterCalls,1); equal(f.target.actualDefense,14)
    equal(f.adapter.lastDamage.before,20,"before read is the buffed total")
    equal(f.adapter.lastDamage.afterDecrypted,14,"base getter may remain valid when total read fails")
    equal(f.uiCalls,0); equal(f.deathCalls,0); noNative(f)
end
do
    local f=fixture(); f.left.actualDefense=1; f.left.totalBonus=5
    local result=f:damage(1,1)
    equal(result.accepted,true); equal(result.damage,1); equal(result.attack,0)
    equal(f.left.actualDefense,0); equal(f.adapter.lastDamage.after,5)
    equal(f.adapter.lastDamage.deathResolution,"not_needed")
    local context=f.adapter:context()
    equal(context.inMatch,true,"HQ with zero base but positive total defense is alive")
    equal(context.ended,false); equal(context.ready,true)
    equal(context.hqZeroPending,false,"HQ zero-pending uses total defense, not its unbuffed base")
    equal(f.deathCalls,0); noNative(f)
end
for _,side in ipairs({"left","right"}) do
    local f=fixture(); f[side].totalUnavailable=true
    local context=f.adapter:context()
    equal(context.ready,false,"both HQ total getters must be available")
    equal(f:process(),1); equal(f.protocol.results[1].accepted,false)
    equal(f.setterCalls,0); equal(f.providerCalls,0); noNative(f)
end
do
    local f=fixture(); f.target.actualDefense=-100000; f.target.totalBonus=100001
    rejected(f,"memory_defense_out_of_range")
end

-- Retry suppression applies to both accepted and failed requests, even if the backend is repaired later.
for _,failure in ipairs({false,true}) do
    local f=fixture(); f.noEffect=failure
    equal(f:process(),1); equal(f.setterCalls,1)
    equal(f.protocol.results[1].accepted,not failure)
    f.noEffect=false
    equal(f:process(),0); equal(f.setterCalls,1); equal(f.providerCalls,1)
    equal(#f.protocol.results,1,"duplicate request does not publish another ACK")
    noNative(f)
end
do
    local f=fixture()
    equal(f:process(10,1,1),1)
    f.key1,f.key2,f.frame=-1345678901,1567890123,987654321
    equal(f:process(10,1,2),1)
    equal(f.providerCalls,2,"fresh requests obtain current keys instead of cached keys")
    equal(f.setterCalls,2); equal(f.target.actualDefense,3); noKeyLeak(f); noNative(f)
end
for _,guard in ipairs({"turn","busy","ai","ready","finished"}) do
    local f=fixture()
    if guard=="turn" then f.state.IsLocalClientTurn=false
    elseif guard=="busy" then f.state.ActionProcess=true
    elseif guard=="ai" then f.controller.ai=false
    elseif guard=="ready" then f.state.stopFurtherActions=nil
    else f.state.MatchFinished=true end
    equal(f:process(),1); equal(f.protocol.results[1].accepted,false)
    equal(f.setterCalls,0); equal(f.providerCalls,0); noNative(f)
end

-- Visual refresh is isolated from the observed mutation and never updates a mismatched actor.
for _,mode in ipairs({"missing_board","missing_actor","wrong_id","wrong_state","missing_setter","ui_error","lookup_error"}) do
    local f=fixture()
    if mode=="missing_board" then f.logic.cachedBoard=nil
    elseif mode=="missing_actor" then f.actors[10]=nil
    elseif mode=="wrong_id" then f.actors[10].CardID=99
    elseif mode=="wrong_state" then f.actors[10].GameStateRef=object("State World.Other")
    elseif mode=="missing_setter" then f.actors[10].SetDefense=nil
    elseif mode=="ui_error" then f.uiError=true
    else f.boardLookupError=true end
    local r=f:damage()
    equal(r.accepted,true,"UI failure cannot undo observed local damage: "..mode)
    equal(r.damage,2); equal(f.setterCalls,1); equal(f.target.actualDefense,3)
    if mode=="ui_error" or mode=="missing_setter" then equal(f.adapter.lastDamage.uiRefresh,"error")
    else equal(f.adapter.lastDamage.uiRefresh,"unavailable") end
    if mode~="ui_error" then equal(f.uiCalls,0,"bad actor is never updated") end
    noNative(f)
end

-- Lethal ordinary-card cleanup is separate from damage; HQ death must not forge match completion.
do
    local f=fixture(); local r=f:damage(10,100)
    equal(r.accepted,true); equal(r.damage,5); equal(r.reason,"memory_damage_observed")
    equal(f.writtenDefense,0,"overkill is capped by current total defense")
    equal(f.setterCalls,1); equal(f.deathCalls,1); equal(f.target.Location,8)
    local snapshot=f:observe(2)
    equal(f.adapter.lastDamage.deathResolution,"resolved"); equal(f.adapter.lastDamage.deathInvoked,true)
    equal(f.adapter.lastDamage.logicalRemoved,true); equal(f.adapter.lastDamage.visualCaptured,true)
    equal(f.adapter.lastDamage.visualActorRemoved,true); equal(f.adapter.lastDamage.visualWidgetCaptured,true)
    equal(f.adapter.lastDamage.visualWidgetRemoved,true)
    equal(f.visualDestroyCalls,1); equal(f.visualFallbackCalls,0)
    check(type(snapshot.deathResolutions)=="table","snapshot publishes visual death evidence separately from damage ACK")
    check(pcall(Core.json,snapshot),"snapshot lifecycle diagnostics do not retain UObject references")
    noNative(f)
end
for _,mode in ipairs({"missing","mismatch","invalid","no_effect","error"}) do
    local f=fixture()
    if mode=="missing" then f.functions[DESTROY]=nil
    elseif mode=="mismatch" then f.functions[DESTROY].fullName="Function /Script/Wrong:DestroyCard"
    elseif mode=="invalid" then f.functions[DESTROY].invalid=true
    elseif mode=="no_effect" then f.deathNoEffect=true
    else f.deathError=true end
    local r=f:damage(10,5)
    equal(r.accepted,true); equal(r.damage,5); equal(f.setterCalls,1)
    equal(f.adapter.lastDamage.deathResolution,mode=="error" and "error" or "pending")
    equal(f.deathCalls,(mode=="error" or mode=="no_effect") and 1 or 0)
    noNative(f)
end

-- Data removal alone was the live false positive: the original actor and its
-- bound widget must actually become invalid before lifecycle cleanup resolves.
do
    local f=fixture(); f.visualNoEffect=true; f.visualFallbackNoEffect=true; f.widgetNoEffect=true
    local r=f:damage(10,5); equal(r.accepted,true); equal(r.damage,5)
    f:observe(2)
    local d=f.adapter.lastDamage
    equal(d.logicalRemoved,true); equal(d.visualActorRemoved,false)
    equal(d.deathResolution,"pending","off-board data plus a visible actor is not resolved")
    equal(f.actors[10].invalid,nil); equal(f.visualDestroyCalls,1)
    -- Losing the board lookup entry does not imply the previously captured actor disappeared.
    f.boardOutputs=function(id,first) first.cardRef=f.actors[id]; first.Found=id~=10 end
    f:observe(3)
    equal(d.deathResolution,"pending"); equal(d.visualActorRemoved,false)
    equal(f.setterCalls,1); equal(f.deathCalls,1); equal(f.visualDestroyCalls,1)
end
do
    local f=fixture(); f.deathNoEffect=true
    equal(f:damage(10,5).accepted,true)
    f.actors[10].bHidden=true
    f:observe(30)
    equal(f.adapter.lastDamage.logicalRemoved,false)
    equal(f.adapter.lastDamage.deathResolution,"pending","hiding an actor cannot replace logical removal")
    equal(f.visualDestroyCalls,0); equal(f.visualFallbackCalls,0); equal(f.widgetCleanupCalls,0)
    equal(f.setterCalls,1); equal(f.deathCalls,1)
end
do
    local f=fixture(); f.visualNoEffect=true; f.visualFallbackNoEffect=true; f.widgetNoEffect=true
    f:damage(10,5); f.actors[10].bHidden=true
    f:observe(3)
    equal(f.adapter.lastDamage.deathResolution,"pending","a hidden but valid actor is not removed")
    equal(f.adapter.lastDamage.visualActorRemoved,false)
end
do
    local f=fixture(); f.deathRemoveFromMap=true
    equal(f:damage(10,5).accepted,true); f:observe(2)
    equal(f.cards[10],nil); equal(f.adapter.lastDamage.logicalRemoved,true)
    equal(f.adapter.lastDamage.deathResolution,"resolved","map removal still requires confirmed visual cleanup")
end
do
    local f=fixture(); f.visualNoEffect=true; f.widgetNoEffect=true
    f:damage(10,5)
    local sameContext=f.adapter:context()
    f.adapter:observeDeaths(sameContext)
    for _=1,50 do f.adapter:observeDeaths(sameContext) end
    equal(f.visualDestroyCalls,1,"reusing an observation context does not repeat visual DestroyCard")
    equal(f.visualFallbackCalls,0,"the same context does not advance the fallback grace period")
    equal(f.widgetCleanupCalls,0)
    f:observe(8)
    equal(f.visualFallbackCalls,0,"fallback waits across real observations instead of firing immediately")
    f:observe(20)
    equal(f.visualFallbackCalls,1); equal(f.widgetCleanupCalls,1)
    check(f.actors[10].invalid==true,"delayed actor fallback is observed, not inferred from return")
    equal(f.adapter.lastDamage.deathResolution,"pending","remaining widget keeps lifecycle pending")
    f:observe(25)
    equal(f.widgetFallbackCalls,1); equal(f.adapter.lastDamage.deathResolution,"resolved")
    f:observe(30)
    equal(f.visualDestroyCalls,1); equal(f.visualFallbackCalls,1)
    equal(f.widgetCleanupCalls,1); equal(f.widgetFallbackCalls,1)
    equal(f.setterCalls,1); equal(f.deathCalls,1,"observing cleanup never retries logical damage or death")
end
do
    local f=fixture(); f.preserveWidget=true
    f:damage(10,5); f:observe(2)
    equal(f.actors[10].invalid,true); equal(f.actors[10].BoardCardWidget.invalid,nil)
    equal(f.adapter.lastDamage.deathResolution,"pending")
    f:observe(25)
    equal(f.widgetCleanupCalls,0,"invalid owner is never passed into widget CleanUp")
    equal(f.widgetFallbackCalls,1); equal(f.adapter.lastDamage.deathResolution,"resolved")
    equal(f.visualFallbackCalls,0)
end
do
    local f=fixture(); f.visualNoEffect=true; f.actors[10].destroying=true
    f:damage(10,5); f:observe(30)
    equal(f.visualDestroyCalls,0,"already-destroying actor does not receive another visual DestroyCard")
    equal(f.uiCalls,0,"already-destroying actor does not receive SetDefense")
    equal(f.widgetCleanupCalls,0,"widget cleanup is not passed a destroying owner")
    equal(f.visualFallbackCalls,0,"already-destroying actor is not submitted to K2 fallback again")
    equal(f.adapter.lastDamage.deathResolution,"pending","being destroyed is not the same as removed")
    f.actors[10].invalid=true; f.actors[10].BoardCardWidget.invalid=true
    f:observe(2); equal(f.adapter.lastDamage.deathResolution,"resolved")
end
do
    local f=fixture(); f.visualNoEffect=true
    f:damage(10,5); f:observe(2)
    equal(f.actors[10]:IsValid(),true,"wrapper can remain valid after engine destruction")
    f.actors[10].engineInvalid=true; f.actors[10].BoardCardWidget.engineInvalid=true
    local sameContext=f.adapter:context()
    f.adapter:observeDeaths(sameContext)
    equal(f.adapter.lastDamage.deathResolution,"pending","engine-invalid evidence requires a second observation tick")
    for _=1,30 do f.adapter:observeDeaths(sameContext) end
    equal(f.adapter.lastDamage.deathResolution,"pending","same-context repeats cannot confirm engine removal")
    f:observe(1)
    equal(f.adapter.lastDamage.deathResolution,"resolved","exact engine validity detects removal before wrapper GC")
    equal(f.validityMemberCalls,0,"Kismet :IsValid member collision is bypassed")
    check(f.engineValidityCalls>0,"engine validity used the exact callable UFunction")
end
do
    local f=fixture(); f.visualNoEffect=true
    f:damage(10,5); f:observe(2)
    f.actors[10].engineInvalid=true; f.actors[10].BoardCardWidget.engineInvalid=true
    f:observe(1)
    f.actors[10].engineInvalid=false; f.actors[10].BoardCardWidget.engineInvalid=false
    f:observe(1)
    equal(f.adapter.lastDamage.deathResolution,"pending","transient engine invalidity cannot resolve a still-live actor")
    equal(f.adapter.lastDamage.visualActorRemoved,false)
end
do
    local f=fixture(); f.visualNoEffect=true; f.actors[10].BoardCardWidget.destroying=true
    f:damage(10,5); f:observe(50)
    equal(f.widgetCleanupCalls,0,"already-destroying widget does not receive CleanUp")
    equal(f.widgetFallbackCalls,0,"already-destroying widget does not receive K2 fallback")
    equal(f.adapter.lastDamage.deathResolution,"pending")
end
for _,field in ipairs({"effectBar","VeteranSlamAndSmoke","myShowCaseCard","placeholderCard","myCardHelp"}) do
    local f=fixture()
    local residualCalls=0
    local residual=object("Residual World."..field,{})
    residual.K2_DestroyActor=function() residualCalls=residualCalls+1 end
    residual.DestroyCard=function() residualCalls=residualCalls+1 end
    residual.CleanUp=function() residualCalls=residualCalls+1 end
    f.actors[10][field]=residual
    equal(f:damage(10,5).accepted,true); f:observe(45)
    local d=f.adapter.lastDamage
    equal(d.logicalRemoved,true); equal(d.visualActorRemoved,true); equal(d.visualWidgetRemoved,true)
    equal(d.deathResolution,"pending","surviving independent visual actor prevents false resolution: "..field)
    equal(d.deathStatus,"residual_visual_unverified"); equal(residualCalls,0,"independent residual actors are read-only")
    local evidence
    for _,item in ipairs(d.residualVisuals) do if item.field==field then evidence=item end end
    check(evidence~=nil); equal(evidence.known,true); equal(evidence.removed,false)
    equal(evidence.refName,"Residual World."..field)
    for key in pairs(evidence) do
        check(key=="field" or key=="refName" or key=="known" or key=="removed","residual evidence retains only allowed scalar fields")
    end
    check(pcall(Core.json,d),"residual diagnostics serialize without retaining UObject references")
    residual.invalid=true
    f:observe(2)
    equal(d.deathResolution,"resolved","resolution follows confirmed residual removal")
    equal(residualCalls,0)
end
do
    local f=fixture()
    setmetatable(f.actors[10],{__index=function(_,key)
        if key=="effectBar" then error("residual field unavailable") end
    end})
    f:damage(10,5); f:observe(45)
    local d=f.adapter.lastDamage
    check(d.deathResolution~="resolved","a failed residual read is not proof of absence")
    equal(d.deathStatus,"residual_visual_unverified")
    local evidence
    for _,item in ipairs(d.residualVisuals) do if item.field=="effectBar" then evidence=item end end
    check(evidence~=nil); equal(evidence.known,false); equal(evidence.removed,false)
end
do
    local f=fixture(); local residual=object("Residual World.Unknown",{engineValidityError=true})
    f.actors[10].effectBar=residual
    f:damage(10,5); f:observe(45)
    check(f.adapter.lastDamage.deathResolution~="resolved","unavailable residual validity is not removal")
    equal(f.adapter.lastDamage.deathStatus,"residual_visual_unverified")
end
for _,mode in ipairs({"missing_function","mismatched_function","missing_cdo","engine_error","engine_nil"}) do
    local f=fixture(); f.visualNoEffect=true
    if mode=="missing_function" then f.functions[ENGINE_VALID]=nil
    elseif mode=="mismatched_function" then f.functions[ENGINE_VALID].fullName="Function /Script/Wrong:IsValid"
    elseif mode=="missing_cdo" then f.system=nil
    elseif mode=="engine_error" then f.actors[10].engineValidityError=true
    else f.actors[10].engineValidityNil=true end
    equal(f:damage(10,5).accepted,true)
    f:observe(45)
    check(f.adapter.lastDamage.deathResolution~="resolved","unknown engine validity cannot prove removal: "..mode)
    equal(f.visualDestroyCalls,0); equal(f.visualFallbackCalls,0)
    equal(f.widgetCleanupCalls,0); equal(f.widgetFallbackCalls,0)
    equal(f.validityMemberCalls,0,"engine query never falls back to the colliding member")
end
do
    local f=fixture(); f.visualNoEffect=true
    f:damage(10,5); f:observe(2)
    f.actors[10].engineValidityError=true
    f:observe(30)
    check(f.adapter.lastDamage.deathResolution~="resolved","failed observation after capture is not disappearance")
    equal(f.visualFallbackCalls,0); equal(f.widgetCleanupCalls,0); equal(f.widgetFallbackCalls,0)
    equal(f.setterCalls,1); equal(f.deathCalls,1)
end
for _,mode in ipairs({"id","state","name"}) do
    local f=fixture(); f.visualNoEffect=true
    f:damage(10,5); f:observe(2)
    if mode=="id" then f.actors[10].CardID=99
    elseif mode=="state" then f.actors[10].GameStateRef=object("State World.Other")
    else f.actors[10].fullName="BoardCard World.ReusedActor" end
    f:observe(30)
    check(f.adapter.lastDamage.deathResolution~="resolved","changed actor identity cancels unsafe cleanup: "..mode)
    equal(f.visualFallbackCalls,0); equal(f.widgetCleanupCalls,0); equal(f.widgetFallbackCalls,0)
end
for _,mode in ipairs({"wrong_actor_id","wrong_actor_state","wrong_card_ref","wrong_board_state","wrong_widget_card","unknown_actor_validity","unknown_widget_property"}) do
    local f=fixture()
    if mode=="wrong_actor_id" then f.actors[10].CardID=99
    elseif mode=="wrong_actor_state" then f.actors[10].GameStateRef=object("State World.Unrelated")
    elseif mode=="wrong_card_ref" then f.actors[10].selfBaseCardRef=f.left
    elseif mode=="wrong_board_state" then f.board.GameStateRef=object("State World.Unrelated")
    elseif mode=="wrong_widget_card" then f.actors[10].BoardCardWidget.card=f.left
    elseif mode=="unknown_actor_validity" then f.actors[10].IsValid=function() error("validity unavailable") end
    else
        f.actors[10].BoardCardWidget=nil
        setmetatable(f.actors[10],{__index=function(_,key) if key=="BoardCardWidget" then error("widget access unavailable") end end})
    end
    equal(f:damage(10,5).accepted,true,"visual identity problems cannot negate confirmed loss: "..mode)
    f:observe(45)
    check(f.adapter.lastDamage.deathResolution~="resolved","unverified visual identity cannot count as resolved: "..mode)
    equal(f.widgetCleanupCalls,0); equal(f.widgetFallbackCalls,0)
    if mode~="wrong_widget_card" and mode~="unknown_widget_property" then
        equal(f.visualDestroyCalls,0); equal(f.visualFallbackCalls,0)
    end
    equal(f.setterCalls,1); equal(f.deathCalls,1)
end
do
    local f=fixture(); f.actors[10].BoardCardWidget=nil
    f:damage(10,5); f:observe(2)
    equal(f.adapter.lastDamage.deathResolution,"resolved","explicitly absent widget requires no invented cleanup")
    equal(f.adapter.lastDamage.visualWidgetCaptured,false)
    equal(f.widgetCleanupCalls,0); equal(f.widgetFallbackCalls,0)
end
do
    local f=fixture(); f.visualNoEffect=true; f.widgetNoEffect=true
    f:damage(10,5); f:observe(2)
    local oldMatch=f.adapter.matchId
    f.controller.match.match_id=78
    local snapshot=f:observe(50)
    check(snapshot.matchId~=oldMatch,"new KARDS match receives a fresh identity")
    equal(f.visualDestroyCalls,1); equal(f.visualFallbackCalls,0)
    equal(f.widgetCleanupCalls,0); equal(f.widgetFallbackCalls,0)
    equal(#snapshot.deathResolutions,0,"new match drops old visual cleanup work")
end
for _,id in ipairs({1,2}) do
    local f=fixture(); local r=f:damage(id,100)
    equal(r.accepted,true); equal(r.damage,20); equal(r.attack,0,"HQ never returns counterattack")
    equal(f.setterCalls,1); equal(f.deathCalls,0)
    equal(f.adapter.lastDamage.deathResolution,"hq_pending"); equal(f.adapter.lastDamage.deathInvoked,false)
    equal(f.state.MatchFinished,false,"memory mode does not forge HQ victory")
    equal(f.logic.matchHasEnded,false); equal(f.cards[id].Location,id==1 and 5 or 6)
    local matchId=f.adapter.matchId
    for _=1,3 do
        local context=f.adapter:context()
        equal(context.inMatch,true,"HQ zero preserves bridge usability until a real finish flag")
        equal(context.ended,false); equal(context.ready,true); equal(context.hqZeroPending,true)
        equal(context.matchId,matchId,"HQ zero does not trigger repeated match epochs")
    end
    local snapshot=f.adapter:snapshot()
    equal(snapshot.inMatch,true); equal(snapshot.ready,true); equal(snapshot.hqZeroPending,true)
    equal(snapshot.matchId,matchId)
    equal(f.visualDestroyCalls,0); equal(f.visualFallbackCalls,0)
    equal(f.widgetCleanupCalls,0); equal(f.widgetFallbackCalls,0)
    noNative(f)
end
do
    local f=fixture(); f.left.actualDefense=0; f.right.actualDefense=0
    local context=f.adapter:context()
    equal(context.inMatch,true,"even both zero HQs require KARDS settlement evidence")
    equal(context.hqZeroPending,true); equal(context.ready,true)
    equal(f:process(10,1,1),1); equal(f.protocol.results[1].accepted,true)
    equal(f.target.actualDefense,4,"a zero HQ alone does not disable the remaining board")
end
for _,flag in ipairs({"MatchFinished","matchHasEnded"}) do
    local f=fixture(); f.right.actualDefense=0
    local before=f.adapter:context()
    if flag=="MatchFinished" then f.state.MatchFinished=true else f.logic.matchHasEnded=true end
    local ended=f.adapter:context()
    equal(ended.ended,true); equal(ended.inMatch,false); equal(ended.hqZeroPending,false)
    equal(ended.status,"match_finished"); equal(ended.matchId,before.matchId)
    equal(f:process(10,1,1),1)
    equal(f.protocol.results[1].accepted,false); equal(f.protocol.results[1].reason,"match_finished")
    equal(f.setterCalls,0); equal(f.providerCalls,0)
    local snapshot=f.adapter:snapshot()
    equal(snapshot.inMatch,false); equal(snapshot.hqZeroPending,false)
    noNative(f)
end

-- Exercise the actual main entry with a temporary bridge directory, mocked UObject discovery,
-- and a manually drained game-thread scheduler. Never load UE4SS or touch the live bridge files.
do
    check(type(TEST_TEMP)=="string" and #TEST_TEMP>0,"runner supplies an isolated TEST_TEMP directory")
    local f=fixture()
    local savedGetenv,savedLoop,savedExecute,savedRegister,savedKey=os.getenv,LoopAsync,ExecuteInGameThread,RegisterKeyBind,Key
    local savedSettings,savedMain,savedCamera=package.loaded.bridge_settings,package.loaded.main,package.loaded.camera_follow
    local loop,queued
    local function write(path,text)
        local file=assert(io.open(path,"wb")); assert(file:write(text)); assert(file:close())
    end
    local function read(path)
        local file=assert(io.open(path,"rb")); local text=file:read("a"); file:close(); return text
    end
    local statePath=TEST_TEMP.."/KardsBridge_state.json"
    local commandPath=TEST_TEMP.."/KardsBridge_cmd.txt"
    local journalPath=TEST_TEMP.."/KardsBridge_damage.jsonl"
    -- The caller may reuse the temporary directory from another mock suite.
    write(commandPath,""); write(journalPath,"")
    os.getenv=function(name) if name=="KARDSCRAFT_BRIDGE_DIR" then return TEST_TEMP end; return savedGetenv(name) end
    LoopAsync=function(interval,callback) equal(interval,16); loop=callback end
    ExecuteInGameThread=function(callback) check(queued==nil,"only one pending game-thread callback"); queued=callback end
    Key={F8=119}; RegisterKeyBind=function(key,callback) equal(key,119); check(type(callback)=="function") end
    package.loaded.bridge_settings={directory="must_not_use_live_bridge",damageMode="memory_setter"}
    local cameraTicks=0
    package.loaded.camera_follow={clockMillis=function() return 0 end,new=function()
        return {tick=function() cameraTicks=cameraTicks+1; return {active=false,status="fixture_idle"} end}
    end}
    package.loaded.main=nil
    require("main")
    local started=false
    local function tick()
        local callbacks=started and 6 or 1
        local beforeCameraTicks=cameraTicks
        for _=1,callbacks do
            loop(); check(type(queued)=="function","main queues a game-thread callback")
            local callback=queued; queued=nil; callback()
        end
        started=true
        equal(cameraTicks-beforeCameraTicks,callbacks,"main advances camera every frame callback")
        return read(statePath)
    end
    local first=tick()
    check(first:find('"damageMode":"memory_setter"',1,true)~=nil,"main passes mode from settings")
    check(first:find('"backend":"memory_setter_observation_only"',1,true)~=nil)
    local match=assert(first:match('"matchId":"([^"]+)"'))
    local function request(seq)
        write(commandPath,"memory_main "..seq.." dmg "..match.." 4 10 2\n")
        return tick()
    end
    local success=request(1)
    equal(f.setterCalls,1); equal(f.target.actualDefense,3)
    check(success:find('"reason":"memory_damage_observed"',1,true)~=nil)
    local journal=read(journalPath)
    equal(select(2,journal:gsub("\n","")),1,"main writes one diagnostic record for accepted request")
    check(journal:find('"seq":1',1,true)~=nil); check(journal:find('"clientId":"memory_main"',1,true)~=nil)
    check(journal:find('"dispatch":"memory_setter"',1,true)~=nil)
    check(not journal:find(tostring(f.key1),1,true) and not journal:find(tostring(f.key2),1,true),"persistent journal omits encryption keys")
    tick(); equal(f.setterCalls,1); equal(read(journalPath),journal,"duplicate request neither mutates nor appends log")
    f.noEffect=true
    local failure=request(2)
    equal(f.setterCalls,2); equal(f.target.actualDefense,3)
    check(failure:find('"reason":"memory_no_effect"',1,true)~=nil)
    journal=read(journalPath)
    equal(select(2,journal:gsub("\n","")),2,"failed observation is recorded once")
    check(journal:find('"accepted":false',1,true)~=nil)
    f.noEffect=false
    tick(); equal(f.setterCalls,2); equal(read(journalPath),journal,"failed requests never retry after backend recovery")
    noNative(f)
    os.getenv,LoopAsync,ExecuteInGameThread,RegisterKeyBind,Key=savedGetenv,savedLoop,savedExecute,savedRegister,savedKey
    package.loaded.bridge_settings,package.loaded.main,package.loaded.camera_follow=savedSettings,savedMain,savedCamera
end

FindAllOf,StaticFindObject=previousFind,previousStatic
print("Memory setter mock checks passed: "..passed)
return Core.json({passed=passed,backend="memory_setter_observation_only",serverPersistenceVerified=false})
