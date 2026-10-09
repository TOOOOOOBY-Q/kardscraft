-- Current-match discovery and observed native or in-memory defense changes.
-- Ordinary discovery is fresh each tick. Pending visual deaths retain only their
-- captured actors, protected by the originating match/state/board identities.
local Core = require("bridge_core")
local M = {}
-- Both this Blueprint and its native CardFunctionsStub parent declare DamageCard.
-- Resolve the full Blueprint path to avoid depending on member-name lookup order.
local DAMAGE_OWNER = "/Game/Blueprints/Cards/BP_CardFunctions.BP_CardFunctions_C"
local DAMAGE_FUNCTION = DAMAGE_OWNER .. ":DamageCard"
local KEY_PROVIDER = DAMAGE_OWNER .. ":provideKeysAndFrameCount"
local CARD_OWNER = "/Script/kards.BaseCardObject"
local DEFENSE_SETTER = CARD_OWNER .. ":setAndEncryptDefense"
local DESTROY_FUNCTION = DAMAGE_OWNER .. ":DestroyCard"
local ENGINE_IS_VALID = "/Script/Engine.KismetSystemLibrary:IsValid"
local function attempt(fn)
    local ok, value = pcall(fn)
    if ok then return value end
end
local function valid(obj)
    return obj ~= nil and attempt(function() return obj:IsValid() end) == true
end
local function prop(obj, name)
    return attempt(function() return obj[name] end)
end
local function call(obj, name, ...)
    local args = table.pack(...)
    return attempt(function() return obj[name](obj, table.unpack(args, 1, args.n)) end)
end
local function fullName(obj)
    if valid(obj) then return call(obj, "GetFullName") end
end
local function same(a, b)
    local name = fullName(a)
    return name ~= nil and name == fullName(b)
end
local function live(obj)
    local name = fullName(obj)
    return type(name) == "string" and not name:find("Default__", 1, true)
end
local function all(className)
    return attempt(function() return FindAllOf(className) end) or {}
end
local function cdo(path)
    return attempt(function() return StaticFindObject(path) end)
end
local function out(obj, method, parameter, ...)
    local args, result = table.pack(...), {}
    args.n = args.n + 1; args[args.n] = result
    local ok = pcall(function() obj[method](obj, table.unpack(args, 1, args.n)) end)
    if ok then return result[parameter] end
end
local function namedOutput(outputs, parameter)
    -- UE4SS 5418fa4e can collect multiple out params into the first output table.
    -- Accept both packed and separate layouts, but only explicit matching names.
    local value, found = nil, false
    for _, output in ipairs(outputs) do
        for name, candidate in pairs(output) do
            if type(name) == "string" and name:lower() == parameter:lower() then
                if found and candidate ~= value then return nil end
                value, found = candidate, true
            end
        end
    end
    return value
end
local function outputFields(outputs)
    local layouts = Core.array()
    for _, output in ipairs(outputs) do
        local fields = Core.array()
        for name, value in pairs(output) do
            if type(name) == "string" and #fields < 16 then
                fields[#fields+1] = name:sub(1,64) .. ":" .. type(value)
            end
        end
        table.sort(fields); layouts[#layouts+1] = fields
    end
    return layouts
end
local function number(n, min, max)
    if Core.integer(n, min or -100000, max or 100000) then return n end
end
local function count(array)
    return number(call(array, "GetArrayNum"), 0, 100000)
end
local function asString(s)
    if type(s) == "string" then return s end
    return call(s, "ToString") or ""
end
local function vector(v)
    if not v then return nil end
    local x, y, z = prop(v, "X"), prop(v, "Y"), prop(v, "Z")
    for _, n in ipairs({x, y, z}) do
        if type(n) ~= "number" or n ~= n or math.abs(n) > 100000000 then return nil end
    end
    if x == nil or y == nil or z == nil then return nil end
    return {x=x, y=y, z=z}
end
local function isBoard(loc) return loc == 5 or loc == 6 or loc == 7 end

local function validity(obj)
    if obj == nil then return false,"absent" end
    local ok, value = pcall(function() return obj:IsValid() end)
    if ok and value == false then return false,"wrapper" end
    if not ok or value ~= true then return nil end
    -- UObject:IsValid is a Lua wrapper method. Calling library:IsValid(obj)
    -- would hit that method and ignore obj, so invoke the exact UFunction.
    local library = cdo("/Script/Engine.Default__KismetSystemLibrary")
    local fn = cdo(ENGINE_IS_VALID)
    if not valid(library) or not valid(fn) or fullName(fn) ~= "Function " .. ENGINE_IS_VALID then return nil end
    local engineOK, engineValid = pcall(function() return fn(library,obj) end)
    if engineOK and type(engineValid) == "boolean" then return engineValid,"engine" end
    -- A failed validity query is not evidence that an actor disappeared.
end
local function visualMatches(actor, id, state, name)
    if validity(actor) ~= true or prop(actor,"CardID") ~= id
        or not same(prop(actor,"GameStateRef"),state) then return false end
    if name and fullName(actor) ~= name then return false end
    for _, field in ipairs({"selfBaseCardRef","CardRef_BoardCard"}) do
        local ref = prop(actor,field)
        if valid(ref) and prop(ref,"CardID") ~= id then return false end
    end
    return true
end
local function captureVisual(r, id)
    local captured = {status="visual_actor_unavailable",widgetKnown=false,residuals={}}
    local board = prop(r.logic,"cachedBoard")
    if not valid(board) then return captured end
    captured.board = board
    local boardState = prop(board,"GameStateRef")
    if valid(boardState) and not same(boardState,r.state) then
        captured.status="visual_board_mismatch"; return captured
    end
    local actorOut, foundOut = {}, {}
    local ok = pcall(function() board:GetVisualBoardCardCardFromID(id,actorOut,foundOut) end)
    local actor = namedOutput({actorOut,foundOut},"cardRef")
    if not ok or namedOutput({actorOut,foundOut},"Found") ~= true then return captured end
    if not visualMatches(actor,id,r.state) then
        captured.status="visual_actor_mismatch"; return captured
    end
    captured.actor, captured.actorName, captured.board = actor, fullName(actor), board
    captured.status = "visual_captured"
    local widgetOK, widget = pcall(function() return actor.BoardCardWidget end)
    local widgetValid, widgetProof
    if widgetOK then widgetValid,widgetProof=validity(widget) end
    if widgetOK and widgetValid == false and widgetProof ~= "engine" then
        captured.widgetKnown = true -- Explicitly absent at the time of capture.
    elseif (widgetValid == true and prop(prop(widget,"card"),"CardID") == id)
        or (widgetValid == false and widgetProof == "engine") then
        captured.widget, captured.widgetName, captured.widgetKnown = widget,fullName(widget),true
    else captured.status="visual_widget_unverified" end
    -- These SDK fields hold independent visual actors, not owned components.
    -- Observe them conservatively; never blindly destroy a shared effect/help actor.
    for _, field in ipairs({"effectBar","VeteranSlamAndSmoke","myShowCaseCard","placeholderCard","myCardHelp"}) do
        local readOK, ref = pcall(function() return actor[field] end)
        captured.residuals[#captured.residuals+1]={field=field,ref=readOK and ref or nil,known=readOK,
            refName=readOK and validity(ref) == true and fullName(ref) or nil}
    end
    return captured
end

local function cardInfo(card, leftId, rightId)
    if not valid(card) then return nil end
    local id, loc = number(prop(card, "CardID"), 1, 2147483647), number(prop(card, "Location"), 0, 10)
    local side = number(prop(card, "side"), 1, 2)
    if not id or not loc or not side then return nil end
    local decryptedDef = number(out(card, "getAndDecryptDefense", "decryptedDefense"))
    local decryptedAtk = number(out(card, "getAndDecryptAttack", "decryptedAttack"))
    local totalDef = number(out(card, "getTotalDefense", "totalDefense"))
    local totalAtk = number(out(card, "getTotalAttack", "totalAttack"), 0, 100000)
    -- Raw encrypted fields are diagnostics only and never become gameplay values.
    if decryptedDef == nil or decryptedAtk == nil then return nil end
    return {id=id, name=asString(prop(card, "Name")), side=side, loc=loc,
        num=number(prop(card, "locationNumber"), -1, 1000) or 0,
        atk=totalAtk or math.max(0, decryptedAtk), def=math.max(0, totalDef or decryptedDef), totalDefense=totalDef,
        hq=id == leftId or id == rightId, decryptedDefense=decryptedDef,
        decryptedAttack=decryptedAtk, rawDefense=prop(card, "defense"), rawAttack=prop(card, "attack")}
end
local function cardMap(state)
    local cards = {}
    local ok = pcall(function()
        state.AllCardsInBattle:ForEach(function(_, item)
            local obj = item:get()
            if valid(obj) then
                local id = number(prop(obj, "CardID"), 1, 2147483647)
                if id then cards[id] = obj end
            end
        end)
    end)
    return ok and cards or nil
end

local function references()
    local candidates, issues = {}, Core.array()
    local gameplay = cdo("/Script/Engine.Default__GameplayStatics")
    local controllerClass = cdo("/Script/kards.Default__MatchControllerV2")
    for _, match in ipairs(all("BP_OnlineMatch_C")) do
        if live(match) then
            local state, funcs, logic = prop(match, "GameStateRef"), prop(match, "cardFunctions"), prop(match, "Logic")
            local linked = true
            for _, check in ipairs({
                {valid(state), "OnlineMatch.GameStateRef"}, {valid(funcs), "OnlineMatch.cardFunctions"},
                {valid(logic), "OnlineMatch.Logic"}, {same(prop(funcs,"GameStateRef"),state), "CardFunctions.GameStateRef:mismatch"},
                {same(prop(logic,"GameStateRef"),state), "Logic.GameStateRef:mismatch"},
                {same(prop(logic,"onlineMatch"),match), "Logic.onlineMatch:mismatch"},
                {same(call(gameplay,"GetGameState",match),state), "GameplayStatics.GetGameState:mismatch"},
                {same(call(gameplay,"GetGameMode",match),logic), "GameplayStatics.GetGameMode:mismatch"}}) do
                if not check[1] then linked=false; issues[#issues+1]=check[2] end
            end
            if linked then
                local mc = call(controllerClass, "GetMatchController", state)
                if valid(mc) then
                    candidates[#candidates + 1] = {match=match, state=state, funcs=funcs, logic=logic, mc=mc,
                        ended=prop(state, "MatchFinished") == true or prop(logic, "matchHasEnded") == true}
                else issues[#issues+1]="MatchControllerV2.GetMatchController:unavailable" end
            end
        end
    end
    local active = {}
    for _, r in ipairs(candidates) do if not r.ended then active[#active + 1] = r end end
    if #active == 1 then return active[1], nil, issues end
    if #active == 0 and #candidates == 1 then return candidates[1], nil, issues end
    if #issues == 0 then issues[1] = #active > 1 and "multiple_active_matches" or "BP_OnlineMatch_C:not_found" end
    return nil, #active > 1 and "ambiguous_match" or "awaiting_match_references", issues
end

function M.new(sessionId, options)
    options = options or {}
    local self = {serial=0, fingerprint=nil, matchId="", lastTurn=nil, ended=false, lastDamage=nil,
        pendingDeaths={}, deathResolutions=Core.array(), deathTick=0,
        damageMode=options.damageMode or "memory_setter"}
    local deathGraceTicks = 20 -- Twenty 100 ms snapshots allow normal destruction to finish.
    function self:clearDeaths()
        -- Never run cleanup against a previous world's retained UObject references.
        for _, entry in ipairs(self.pendingDeaths) do
            entry.diagnostic.deathStatus = "context_changed"
        end
        self.pendingDeaths, self.deathResolutions, self.lastDeathContext = {}, Core.array(), nil
    end
    function self:observeDeath(entry, context)
        local d, r = entry.diagnostic, context.refs
        if entry.cancelled then return end
        if not r or context.matchId ~= entry.matchId
            or not same(entry.state,r.state) or not same(entry.match,r.match)
            or not same(entry.logic,r.logic)
            or (entry.board and not same(entry.board,prop(r.logic,"cachedBoard"))) then
            d.deathStatus, entry.cancelled = "context_changed", true
            return
        end
        local cards = cardMap(r.state)
        if not cards then d.deathStatus="death_card_map_unavailable"; return end
        local current = cards[entry.cardId]
        if current and fullName(current) ~= entry.targetName then
            d.deathResolution,d.deathStatus,entry.cancelled="error","death_card_identity_changed",true
            return
        end
        local logicalRemoved = current == nil or prop(current,"Location") == 8
        if not logicalRemoved then
            if d.logicalRemoved then
                d.deathResolution,d.deathStatus,entry.cancelled="error","death_card_returned",true
            else d.deathStatus="awaiting_logical_removal" end
            return
        end
        d.logicalRemoved = true
        if not entry.actor then d.deathStatus=entry.captureStatus; return end
        local actorValid = validity(entry.actor)
        d.visualActorRemoved = actorValid == false
        if actorValid == nil then d.deathStatus="visual_actor_validity_unknown"; return end
        if actorValid and not visualMatches(entry.actor,entry.cardId,r.state,entry.actorName) then
            d.deathResolution,d.deathStatus,entry.cancelled="error","visual_actor_identity_changed",true
            return
        end
        local widgetValid = validity(entry.widget)
        if entry.widget and widgetValid == true and (fullName(entry.widget) ~= entry.widgetName
            or prop(prop(entry.widget,"card"),"CardID") ~= entry.cardId) then
            d.deathResolution,d.deathStatus,entry.cancelled="error","visual_widget_identity_changed",true
            return
        end
        -- Captured references, not a board index miss or hiding, prove removal.
        d.visualWidgetRemoved = entry.widgetKnown and widgetValid == false
        local function removed(ref, key)
            local state, proof = validity(ref)
            if state ~= false then entry[key]=nil; return false end
            if proof ~= "engine" then return true end
            -- Pending-kill can precede the end of the current engine tick.
            -- Reconfirm through the engine on a later snapshot before resolving.
            entry[key] = entry[key] or self.deathTick
            return self.deathTick > entry[key]
        end
        local function updateResolution()
            d.visualActorRemoved = removed(entry.actor,"actorAbsentTick")
            d.visualWidgetRemoved = entry.widgetKnown and removed(entry.widget,"widgetAbsentTick")
            local residualsRemoved = true
            d.residualVisuals = Core.array()
            for _, residual in ipairs(entry.residuals) do
                local gone = residual.known and removed(residual.ref,"residualAbsentTick_"..residual.field)
                d.residualVisuals[#d.residualVisuals+1]={field=residual.field,refName=residual.refName,
                    known=residual.known,removed=gone or false}
                if not gone then residualsRemoved=false end
            end
            d.residualVisualsRemoved=residualsRemoved
            if d.visualActorRemoved and d.visualWidgetRemoved and residualsRemoved then
                d.deathResolution,d.deathStatus="resolved","logical_and_visual_removed"
                return true
            end
            return false
        end
        if updateResolution() then return end
        if actorValid and not d.visualDestroyInvoked and call(entry.actor,"IsActorBeingDestroyed") ~= true then
            d.visualDestroyInvoked,entry.visualStartTick=true,self.deathTick
            local ok = pcall(function() entry.actor:DestroyCard() end)
            if not ok then d.deathResolution,d.deathError="error","visual DestroyCard raised an error" end
            if updateResolution() then return end
        end
        entry.visualStartTick = entry.visualStartTick or self.deathTick
        local elapsed = self.deathTick-entry.visualStartTick
        actorValid = validity(entry.actor)
        widgetValid = validity(entry.widget)
        -- Let the actor's normal owner-destruction handlers clear its independent
        -- text actor first. Never pass an invalid owner back into ProcessEvent.
        if elapsed >= deathGraceTicks and actorValid == true and widgetValid == true
            and not d.visualWidgetCleanupInvoked and call(entry.actor,"IsActorBeingDestroyed") ~= true
            and call(entry.widget,"IsActorBeingDestroyed") ~= true then
            d.visualWidgetCleanupInvoked,entry.widgetStartTick=true,self.deathTick
            local ok = pcall(function() entry.widget:CleanUp(entry.actor) end)
            if not ok then d.deathResolution,d.deathError="error","visual widget CleanUp raised an error" end
        end
        if elapsed >= deathGraceTicks and visualMatches(entry.actor,entry.cardId,r.state,entry.actorName)
            and not d.visualFallbackInvoked and call(entry.actor,"IsActorBeingDestroyed") ~= true then
            d.visualFallbackInvoked=true
            local ok = pcall(function() entry.actor:K2_DestroyActor() end)
            if not ok then d.deathResolution,d.deathError="error","visual K2_DestroyActor raised an error" end
        end
        local widgetElapsed = self.deathTick-(entry.widgetStartTick or entry.visualStartTick)
        if widgetElapsed >= deathGraceTicks and entry.widget and validity(entry.widget) == true
            and fullName(entry.widget) == entry.widgetName and prop(prop(entry.widget,"card"),"CardID") == entry.cardId
            and not d.visualWidgetFallbackInvoked and call(entry.widget,"IsActorBeingDestroyed") ~= true then
            d.visualWidgetFallbackInvoked=true
            local ok = pcall(function() entry.widget:K2_DestroyActor() end)
            if not ok then d.deathResolution,d.deathError="error","visual widget K2_DestroyActor raised an error" end
        end
        if updateResolution() then return end
        if d.visualActorRemoved and d.visualWidgetRemoved and not d.residualVisualsRemoved then
            d.deathStatus="residual_visual_unverified"
        elseif not entry.widgetKnown then d.deathStatus="visual_widget_unverified"
        elseif validity(entry.widget) == nil then d.deathStatus="visual_widget_validity_unknown"
        elseif d.visualFallbackInvoked or d.visualWidgetFallbackInvoked then d.deathStatus="awaiting_engine_visual_removal"
        else d.deathStatus="awaiting_visual_removal" end
    end
    function self:observeDeaths(context)
        context = context or self:context()
        if self.lastDeathContext == context then return end
        self.lastDeathContext,self.deathTick=context,self.deathTick+1
        if not context.refs then self:clearDeaths(); return end
        local pending = {}
        for _, entry in ipairs(self.pendingDeaths) do
            self:observeDeath(entry,context)
            if not entry.cancelled and entry.diagnostic.deathResolution ~= "resolved" then
                pending[#pending+1]=entry
            end
        end
        self.pendingDeaths=pending
    end
    function self:context()
        local r, reason, referenceIssues = references()
        if not r then
            self:clearDeaths()
            self.fingerprint, self.lastTurn = nil, nil
            return {inMatch=false, ai=false, myTurn=false, mySide=0, turn=0, ready=false,
                matchId=self.matchId, status=reason, referenceIssues=referenceIssues, missingFields=Core.array()}
        end
        local turn = number(prop(r.state, "CurrentTurnNumberInBattle"), 0, 2147483647)
        local leftHQ, rightHQ = prop(r.state, "Left_HQ_Card_Ref"), prop(r.state, "Right_HQ_Card_Ref")
        local leftHP = number(out(leftHQ, "getAndDecryptDefense", "decryptedDefense"))
        local rightHP = number(out(rightHQ, "getAndDecryptDefense", "decryptedDefense"))
        local leftTotalHP = number(out(leftHQ, "getTotalDefense", "totalDefense"))
        local rightTotalHP = number(out(rightHQ, "getTotalDefense", "totalDefense"))
        -- A zero-health HQ is not proof of KARDS settlement. Only references()'s
        -- MatchFinished / matchHasEnded flags may terminate this bridge session.
        local hqZeroPending = not r.ended and ((leftTotalHP and leftTotalHP <= 0)
            or (rightTotalHP and rightTotalHP <= 0)) or false
        local serverId = number(prop(prop(r.mc, "match"), "match_id"), 0, 2147483647) or 0
        local fingerprint = (fullName(r.state) or "") .. ":" .. serverId
        if fingerprint ~= self.fingerprint or (self.lastTurn and turn and turn < self.lastTurn)
            or (self.ended and not r.ended) then
            self:clearDeaths()
            self.serial = self.serial + 1
            self.matchId = sessionId .. "-m" .. self.serial
            self.fingerprint = fingerprint
        end
        self.lastTurn, self.ended = turn, r.ended
        local side = number(out(r.state, "GetClientSide", "OutClientSide"), 1, 2)
        if not side then side = number(call(cdo("/Script/kards.Default__CombatHelperFunctions"), "GetClientSide", r.state), 1, 2) end
        -- BP_OnlineMatch.mySide is known to be zero in this build; never use it.
        local leftId = number(prop(r.state, "Left_HQ_CardID"), 1, 2147483647)
        local rightId = number(prop(r.state, "Rgith_HQ_CardID"), 1, 2147483647)
        local localTurn = prop(r.state, "IsLocalClientTurn")
        local myTurn = localTurn == true
        local aiValue = call(r.mc, "isAIMatch")
        local ai = aiValue == true
        local busy, known, missing, busyReasons = false, true, Core.array(), Core.array()
        for _, entry in ipairs({{r.state,"ActionProcess"}, {r.state,"stopFurtherActions"},
            {r.match,"isResolvingAction"}, {r.match,"actionsPaused"},
            {r.match,"isValidatingActionSent"}, {r.mc,"isResyncingMatch"}}) do
            local flag = prop(entry[1], entry[2])
            if type(flag) ~= "boolean" then known = false; missing[#missing+1]=entry[2]
            elseif flag then busy = true; busyReasons[#busyReasons+1]=entry[2] end
        end
        for _, queue in ipairs({{r.match,"actionsQueue"}, {r.funcs,"TriggerQueue"},
            {r.match,"pendingCardsSelectingHandTargets"}, {r.match,"enemyPendingCardsSelectingHandTargets"}}) do
            local n = count(prop(queue[1], queue[2]))
            if n == nil then known = false; missing[#missing+1]=queue[2]..".GetArrayNum"
            elseif n > 0 then busy = true; busyReasons[#busyReasons+1]=queue[2].."="..n end
        end
        local spectating = prop(r.logic, "isReplayMatch") ~= false or prop(r.logic, "isSpectate") ~= false
        local finishedKnown = type(prop(r.state, "MatchFinished")) == "boolean"
            and type(prop(r.logic, "matchHasEnded")) == "boolean"
        if type(prop(r.state,"MatchFinished"))~="boolean" then missing[#missing+1]="GameState.MatchFinished" end
        if type(prop(r.logic,"matchHasEnded"))~="boolean" then missing[#missing+1]="Logic.matchHasEnded" end
        if type(prop(r.logic,"isReplayMatch"))~="boolean" then missing[#missing+1]="Logic.isReplayMatch" end
        if type(prop(r.logic,"isSpectate"))~="boolean" then missing[#missing+1]="Logic.isSpectate" end
        if type(localTurn)~="boolean" then known=false; missing[#missing+1]="GameState.IsLocalClientTurn" end
        if type(aiValue)~="boolean" then known=false; missing[#missing+1]="MatchController.isAIMatch" end
        if not side then missing[#missing+1]="GameState.GetClientSide.OutClientSide" end
        if not turn or turn<1 then missing[#missing+1]="GameState.CurrentTurnNumberInBattle:awaiting_positive_turn" end
        if not leftId then missing[#missing+1]="GameState.Left_HQ_CardID" end
        if not rightId then missing[#missing+1]="GameState.Rgith_HQ_CardID" end
        if not leftHP then missing[#missing+1]="Left_HQ_Card_Ref.getAndDecryptDefense.decryptedDefense" end
        if not rightHP then missing[#missing+1]="Right_HQ_Card_Ref.getAndDecryptDefense.decryptedDefense" end
        if leftTotalHP == nil then missing[#missing+1]="Left_HQ_Card_Ref.getTotalDefense.totalDefense" end
        if rightTotalHP == nil then missing[#missing+1]="Right_HQ_Card_Ref.getTotalDefense.totalDefense" end
        local ready = known and finishedKnown and side ~= nil and turn ~= nil and turn >= 1
            and leftId ~= nil and rightId ~= nil and leftHP ~= nil and rightHP ~= nil
            and leftTotalHP ~= nil and rightTotalHP ~= nil and not spectating
        local status = "ready_unverified"
        if r.ended then status = "match_finished"
        elseif not ai then status = "not_ai_match"
        elseif spectating then status = "replay_or_spectator"
        elseif not ready then status = "backend_fields_unavailable"
        elseif busy then status = "game_busy"
        elseif not myTurn then status = "opponent_turn" end
        return {refs=r, inMatch=not r.ended, ended=r.ended, ai=ai, myTurn=myTurn, mySide=side or 0,
            turn=turn or 0, matchId=self.matchId, ready=ready, busy=busy, status=status,
            hqLeftId=leftId or 0, hqRightId=rightId or 0,missingFields=missing,
            referenceIssues=referenceIssues,busyReasons=busyReasons,hqZeroPending=hqZeroPending}
    end

    function self:memoryDamage(cmd, context)
        local r = context.refs
        local cards = cardMap(r.state)
        local obj = cards and cards[cmd.cardId]
        local before = cardInfo(obj, context.hqLeftId, context.hqRightId)
        local sourceId = context.mySide == 1 and context.hqLeftId or context.hqRightId
        local diagnostic = {cardId=cmd.cardId, matchId=context.matchId, clientId=cmd.clientId, seq=cmd.seq,
            amount=cmd.amount, sourceId=sourceId, turn=context.turn, dispatch="memory_setter",
            functionPath=DEFENSE_SETTER, keyProviderPath=KEY_PROVIDER, functionsRef=fullName(r.funcs),
            targetRef=fullName(obj), gameStateRef=fullName(r.state), setterInvoked=false, keysAvailable=false,
            uiRefresh="not_needed", deathResolution="not_needed", deathInvoked=false,
            before=before and before.def, beforeDecrypted=before and before.decryptedDefense,
            beforeRaw=before and before.rawDefense}
        self.lastDamage = diagnostic
        local function reject(reason)
            diagnostic.reason, diagnostic.accepted, diagnostic.damage = reason, false, 0
            return {accepted=false, damage=0, attack=0, reason=reason}
        end
        if not before or not isBoard(before.loc) or before.def <= 0 then return reject("target_unavailable") end
        if before.totalDefense == nil then return reject("memory_total_defense_unavailable") end
        local source = cards[sourceId]
        if not valid(source) then return reject("source_hq_unavailable") end
        local setter, provider = cdo(DEFENSE_SETTER), cdo(KEY_PROVIDER)
        diagnostic.functionFullName = fullName(setter)
        diagnostic.keyProviderFullName = fullName(provider)
        if not valid(setter) or not valid(provider) then return reject("memory_api_unavailable") end
        if fullName(setter) ~= "Function " .. DEFENSE_SETTER or fullName(provider) ~= "Function " .. KEY_PROVIDER then
            return reject("memory_api_mismatch")
        end
        if call(r.funcs,"IsA",DAMAGE_OWNER) ~= true or call(obj,"IsA",CARD_OWNER) ~= true then
            return reject("memory_context_mismatch")
        end
        -- The game supplies the current encryption parameters. Do not guess keys,
        -- cache them across ticks, write raw offsets, or alter validation behavior.
        local key1, key2, frame = {}, {}, {}
        local keysOK = pcall(function() provider(r.funcs, key1, key2, frame) end)
        diagnostic.keyOutputFields = outputFields({key1,key2,frame})
        if not keysOK then
            diagnostic.error="native key provider raised an error"
            return reject("memory_keys_error")
        end
        local currentKey1 = namedOutput({key1,key2,frame},"key1")
        local currentKey2 = namedOutput({key1,key2,frame},"key2")
        local currentFrame = namedOutput({key1,key2,frame},"FrameCount")
        if not number(currentKey1,-2147483648,2147483647)
            or not number(currentKey2,-2147483648,2147483647)
            or not number(currentFrame,-2147483648,2147483647) then
            return reject("memory_keys_unavailable")
        end
        diagnostic.keysAvailable = true
        local targetDefense = before.decryptedDefense - math.min(cmd.amount, before.def)
        diagnostic.targetDefense = targetDefense
        if not number(targetDefense) then return reject("memory_defense_out_of_range") end
        -- Capture while the target still belongs to the board: native destruction
        -- may immediately drop its visual lookup entry without destroying actors.
        local visual = captureVisual(r,cmd.cardId)
        local ok = pcall(function()
            diagnostic.setterInvoked = true
            setter(obj, targetDefense, currentKey1, currentKey2, currentFrame)
        end)
        -- Only scalar observations leave this call; encryption parameters are never logged.
        local after = cardInfo(obj, context.hqLeftId, context.hqRightId)
        diagnostic.after = after and after.def
        diagnostic.afterDecrypted = after and after.decryptedDefense
        diagnostic.afterRaw = after and after.rawDefense
        if not ok then
            diagnostic.error="native defense setter raised an error"
        end
        -- Do not mix a pre-write total with a post-write base-only fallback.
        if not after or after.totalDefense == nil then return reject("memory_readback_unavailable") end
        local actual = after and math.max(0, before.def-after.def) or 0
        if actual <= 0 then return reject(ok and "memory_no_effect" or "memory_write_error") end
        -- A setter can throw after mutating. Confirmed health loss remains a fact;
        -- retain the error separately and never replay the command to repair it.
        diagnostic.accepted, diagnostic.damage, diagnostic.reason = true, actual, "memory_damage_observed"

        -- The visual actor may hold a snapshot copy. Update its display only after
        -- the live card's getters prove that its actual defense decreased.
        diagnostic.uiRefresh = "unavailable"
        if visualMatches(visual.actor,cmd.cardId,r.state,visual.actorName)
            and call(visual.actor,"IsActorBeingDestroyed") ~= true then
            local refreshed = pcall(function() visual.actor:SetDefense(after.def) end)
            diagnostic.uiRefresh = refreshed and "call_completed" or "error"
        end
        -- A setter does not imply a death trigger or a completed match. For units,
        -- attempt the game's normal destroy entry once, then observe its result.
        if after.def <= 0 then
            diagnostic.deathResolution = before.hq and "hq_pending" or "pending"
            if not before.hq then
                diagnostic.logicalRemoved,diagnostic.visualCaptured=false,visual.actor ~= nil
                diagnostic.visualActorRef,diagnostic.visualWidgetRef=visual.actorName,visual.widgetName
                diagnostic.visualActorRemoved,diagnostic.visualWidgetRemoved=false,false
                diagnostic.visualWidgetCaptured=visual.widget ~= nil
                diagnostic.visualDestroyInvoked,diagnostic.visualFallbackInvoked=false,false
                diagnostic.visualWidgetCleanupInvoked,diagnostic.visualWidgetFallbackInvoked=false,false
                local entry = {diagnostic=diagnostic,cardId=cmd.cardId,matchId=context.matchId,
                    state=r.state,match=r.match,logic=r.logic,board=visual.board,targetName=fullName(obj),
                    actor=visual.actor,actorName=visual.actorName,widget=visual.widget,widgetName=visual.widgetName,
                    widgetKnown=visual.widgetKnown,captureStatus=visual.status,residuals=visual.residuals}
                self.pendingDeaths[#self.pendingDeaths+1]=entry
                self.deathResolutions[#self.deathResolutions+1]=diagnostic
                if #self.deathResolutions > 64 then table.remove(self.deathResolutions,1) end
                local destroy = cdo(DESTROY_FUNCTION)
                if valid(destroy) and fullName(destroy) == "Function " .. DESTROY_FUNCTION then
                    local destroyedOK = pcall(function()
                        diagnostic.deathInvoked = true
                        destroy(r.funcs,obj,source)
                    end)
                    if not destroyedOK then
                        diagnostic.deathResolution,diagnostic.deathError="error","logical DestroyCard raised an error"
                    end
                end
                -- No mutation is retried here or by future snapshots. This only
                -- advances the independent visual lifecycle after real departure.
                self:observeDeath(entry,context)
            end
        end
        return {accepted=true, damage=actual, attack=not before.hq and before.atk or 0, reason=diagnostic.reason}
    end

    function self:damage(cmd, context)
        if self.damageMode == "memory_setter" then return self:memoryDamage(cmd,context) end
        if self.damageMode ~= "native" then
            self.lastDamage={cardId=cmd.cardId,clientId=cmd.clientId,seq=cmd.seq,matchId=context.matchId,
                dispatch=self.damageMode,accepted=false,damage=0,reason="damage_mode_unsupported"}
            return {accepted=false,damage=0,attack=0,reason="damage_mode_unsupported"}
        end
        local r = context.refs
        local cards = cardMap(r.state)
        local obj = cards and cards[cmd.cardId]
        local before = cardInfo(obj, context.hqLeftId, context.hqRightId)
        local sourceId = context.mySide == 1 and context.hqLeftId or context.hqRightId
        local diagnostic = {cardId=cmd.cardId, matchId=context.matchId, clientId=cmd.clientId, seq=cmd.seq,
            amount=cmd.amount, sourceId=sourceId, turn=context.turn, dispatch="explicit_blueprint",
            functionPath=DAMAGE_FUNCTION, functionsRef=fullName(r.funcs), targetRef=fullName(obj),
            gameStateRef=fullName(r.state), nativeInvoked=false, nativeDestroyed=false,
            targetDestroyedType="nil", before=before and before.def,
            beforeDecrypted=before and before.decryptedDefense, beforeRaw=before and before.rawDefense}
        self.lastDamage = diagnostic
        local function reject(reason)
            diagnostic.reason, diagnostic.accepted, diagnostic.damage = reason, false, 0
            return {accepted=false, damage=0, attack=0, reason=reason}
        end
        if not before or not isBoard(before.loc) or before.def <= 0 then
            return reject("target_unavailable")
        end
        if not valid(cards[sourceId]) then return reject("source_hq_unavailable") end
        -- StaticFindObject produces an unbound UFunction in this UE4SS build.
        -- Its first argument is the live execution context; ProcessEvent then
        -- invokes this exact function. Never retry through a second damage API.
        local damageFunction = cdo(DAMAGE_FUNCTION)
        diagnostic.functionFullName = fullName(damageFunction)
        diagnostic.memberFunctionFullName = fullName(prop(r.funcs, "DamageCard"))
        if not valid(damageFunction) then return reject("damage_function_unavailable") end
        if diagnostic.functionFullName ~= "Function " .. DAMAGE_FUNCTION then
            return reject("damage_function_mismatch")
        end
        diagnostic.functionFlags = number(call(damageFunction, "GetFunctionFlags"), 0, 4294967295)
        if call(r.funcs, "IsA", DAMAGE_OWNER) ~= true then return reject("damage_context_mismatch") end
        local targetDestroyed = {}
        local ok, err = pcall(function()
            -- Self HQ is the explicit damage source. MC damage is non-combat damage;
            -- the MC caller implements its configured counterattack after this ACK.
            diagnostic.nativeInvoked = true
            damageFunction(r.funcs, obj, cmd.amount, sourceId, false, false, false, targetDestroyed)
        end)
        local after = cardInfo(obj, context.hqLeftId, context.hqRightId)
        local afterCards = cardMap(r.state)
        local confirmedDestroyed = targetDestroyed.targetDestroyed == true
            and ((after and (after.def <= 0 or after.loc == 8)) or (afterCards and not afterCards[cmd.cardId]))
        local actual = after and math.max(0, before.def - math.max(0, after.def)) or 0
        if confirmedDestroyed then actual = before.def end
        local accepted = ok and (actual > 0 or confirmedDestroyed)
        local resultReason = accepted and (confirmedDestroyed and "native_destroyed_observed" or "native_damage_observed")
            or (ok and "native_no_effect" or "native_error")
        diagnostic.after = after and after.def
        diagnostic.afterDecrypted = after and after.decryptedDefense
        diagnostic.afterRaw = after and after.rawDefense
        diagnostic.targetDestroyedType = type(targetDestroyed.targetDestroyed)
        diagnostic.nativeDestroyed = targetDestroyed.targetDestroyed == true
        diagnostic.reason, diagnostic.accepted = resultReason, accepted
        diagnostic.damage = accepted and actual or 0
        diagnostic.error = not ok and tostring(err):sub(1, 400) or nil
        return {accepted=accepted, damage=accepted and actual or 0,
            attack=accepted and not before.hq and before.atk or 0, reason=resultReason}
    end

    function self:snapshot(context)
        context = context or self:context()
        self:observeDeaths(context)
        local r = context.refs
        local snapshot = {inMatch=context.inMatch, ai=context.ai, myTurn=context.myTurn, mySide=context.mySide,
            turn=context.turn, matchId=context.matchId, status=context.status, ready=context.ready,
            busy=context.busy or false, hqLeftId=context.hqLeftId or 0, hqRightId=context.hqRightId or 0,
            cards=Core.array(), lastDamage=self.lastDamage, deathResolutions=self.deathResolutions,
            missingFields=context.missingFields,referenceIssues=context.referenceIssues,
            busyReasons=context.busyReasons or Core.array(),
            hqZeroPending=context.hqZeroPending or false,
            damageMode=self.damageMode, backend=self.damageMode == "memory_setter"
                and "memory_setter_observation_only" or "native_DamageCard_observation_only",
            serverPersistenceVerified=false}
        if not r then return snapshot end
        local cards = cardMap(r.state)
        if not cards then snapshot.ready=false; snapshot.status="card_map_unavailable"; return snapshot end
        local positions = {}
        -- Prefer the current board's ID lookup. Visual cards can reference a
        -- snapshot clone rather than the live AllCardsInBattle object.
        local board = prop(r.logic, "cachedBoard")
        if valid(board) then
            for id, card in pairs(cards) do
                if isBoard(prop(card, "Location")) then
                    local actorOut, foundOut = {}, {}
                    local ok = pcall(function() board:GetVisualBoardCardCardFromID(id, actorOut, foundOut) end)
                    local actor = namedOutput({actorOut,foundOut},"cardRef")
                    if ok and namedOutput({actorOut,foundOut},"Found") == true and valid(actor) and prop(actor, "CardID") == id
                        and same(prop(actor, "GameStateRef"), r.state) then
                        positions[id] = vector(call(actor, "K2_GetActorLocation"))
                    end
                end
            end
        end
        for _, class in ipairs({"BP_BoardCard_C", "BP_BaseCard_C"}) do
            for _, actor in ipairs(all(class)) do
                if live(actor) and same(prop(actor, "GameStateRef"), r.state) then
                    local id = number(prop(actor, "CardID"), 1, 2147483647)
                    if id and cards[id] and not positions[id] and prop(prop(actor, "selfBaseCardRef"), "CardID") == id then
                        local position = vector(call(actor, "K2_GetActorLocation"))
                        if position then positions[id] = position end
                    end
                end
            end
        end
        local unreadable, unpositioned = 0, 0
        for id, card in pairs(cards) do
            if isBoard(prop(card, "Location")) then
                local info = cardInfo(card, snapshot.hqLeftId, snapshot.hqRightId)
                if info and positions[id] then
                    info.pos = positions[id]; snapshot.cards[#snapshot.cards + 1] = info
                elseif info then unpositioned = unpositioned + 1
                else unreadable = unreadable + 1 end
            end
        end
        table.sort(snapshot.cards, function(a, b) return a.id < b.id end)
        snapshot.hqLeft, snapshot.hqRight = positions[snapshot.hqLeftId], positions[snapshot.hqRightId]
        local gameplay = cdo("/Script/Engine.Default__GameplayStatics")
        local manager = call(gameplay, "GetPlayerCameraManager", r.state, 0)
        if valid(manager) then
            local position, rotation = vector(call(manager, "GetCameraLocation")), call(manager, "GetCameraRotation")
            local fov = call(manager, "GetFOVAngle")
            local pitch, yaw, roll = prop(rotation, "Pitch"), prop(rotation, "Yaw"), prop(rotation, "Roll")
            if position and type(fov) == "number" and fov > 1 and fov < 179
                and type(pitch) == "number" and type(yaw) == "number" and type(roll) == "number" then
                snapshot.cam = {x=position.x,y=position.y,z=position.z,pitch=pitch,yaw=yaw,roll=roll,fov=fov}
            end
        end
        snapshot.unreadableCards = unreadable
        snapshot.unpositionedCards = unpositioned
        snapshot.geometryReady = snapshot.hqLeft ~= nil and snapshot.hqRight ~= nil and snapshot.cam ~= nil
        if unreadable > 0 then snapshot.status="card_getter_unavailable"; snapshot.ready=false
        elseif (not snapshot.geometryReady or unpositioned > 0) and snapshot.status == "ready_unverified" then
            snapshot.status="awaiting_board_geometry"
        end
        return snapshot
    end
    return self
end
return M
