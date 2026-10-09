-- AI-only local view target control. Every tick/release/clockMillis call must run
-- in ExecuteInGameThread. This module does no I/O, scheduling, input or gameplay.
-- camFollow.active means a view target was applied, NOT camera confirmation.
-- Keep snapshot.cam sourced from the actual PlayerCameraManager.
-- SDK: Engine.hpp AController/GetViewTarget, AActor/K2_SetActorLocationAndRotation,
-- CinematicCamera.hpp ACineCameraActor/UCineCameraComponent.
-- https://github.com/UE4SS-RE/RE-UE4SS/blob/main/docs/lua-api/classes/uworld.md
-- https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Runtime/CinematicCamera/UCineCameraComponent
local M = {}
local MAX_SAFE = 9007199254740991
local TIMEOUT_MS = 500
local function finite(n, lo, hi)
    return type(n) == "number" and n == n and n >= lo and n <= hi
end
local function integer(n, lo, hi)
    return finite(n, lo, hi) and n == math.floor(n)
end
local function token(s)
    return type(s) == "string" and #s > 0 and #s <= 128 and s:match("^[A-Za-z0-9_-]+$") ~= nil
end

-- Deliberately a flat JSON decoder, never Lua load/loadstring. Unknown fields,
-- nested values, duplicates (including escaped keys), nonfinite numbers and
-- trailing bytes are rejected. The schema uses only string/number values.
function M.parse(raw)
    if type(raw) ~= "string" or #raw == 0 then return nil, "no_request" end
    if #raw > 4096 then return nil, "request_too_large" end
    local i, length = 1, #raw
    local function char() return raw:sub(i,i) end
    local function ws() while char():match("[ \t\r\n]") do i=i+1 end end
    local function take(c) if char() ~= c then error("invalid_json",0) end; i=i+1 end
    local function stringValue()
        take('"'); local out = {}
        while i <= length do
            local c = char(); i=i+1
            if c == '"' then return table.concat(out) end
            if c:byte() < 32 then error("invalid_json",0) end
            if c == "\\" then
                local e=char(); i=i+1
                local escapes={['"']='"',['\\']='\\',['/']='/',b='\b',f='\f',n='\n',r='\r',t='\t'}
                if escapes[e] then c=escapes[e]
                elseif e == "u" then
                    local hex=raw:sub(i,i+3)
                    if #hex ~= 4 or not hex:match("^%x%x%x%x$") then error("invalid_json",0) end
                    local point=tonumber(hex,16); i=i+4
                    if point >= 0xD800 and point <= 0xDBFF then
                        if raw:sub(i,i+1) ~= "\\u" then error("invalid_json",0) end
                        local tail=raw:sub(i+2,i+5)
                        if #tail ~= 4 or not tail:match("^%x%x%x%x$") then error("invalid_json",0) end
                        local low=tonumber(tail,16)
                        if low < 0xDC00 or low > 0xDFFF then error("invalid_json",0) end
                        point=0x10000+(point-0xD800)*1024+low-0xDC00; i=i+6
                    elseif point >= 0xDC00 and point <= 0xDFFF then error("invalid_json",0) end
                    c=utf8.char(point)
                else error("invalid_json",0) end
            end
            out[#out+1]=c
        end
        error("invalid_json",0)
    end
    local function numberValue()
        local start=i
        if char()=="-" then i=i+1 end
        if char()=="0" then i=i+1
        elseif char():match("[1-9]") then repeat i=i+1 until not char():match("%d")
        else error("invalid_json",0) end
        if char()=="." then
            i=i+1; if not char():match("%d") then error("invalid_json",0) end
            repeat i=i+1 until not char():match("%d")
        end
        if char()=="e" or char()=="E" then
            i=i+1; if char()=="+" or char()=="-" then i=i+1 end
            if not char():match("%d") then error("invalid_json",0) end
            repeat i=i+1 until not char():match("%d")
        end
        local value=tonumber(raw:sub(start,i-1))
        if not finite(value,-MAX_SAFE,MAX_SAFE) then error("invalid_number",0) end
        return value
    end
    local ok, request=pcall(function()
        local result={}; ws(); take("{"); ws()
        if char() ~= "}" then
            while true do
                local key=stringValue(); ws(); take(":"); ws()
                if result[key] ~= nil then error("duplicate_key",0) end
                result[key]=char()=='"' and stringValue() or numberValue(); ws()
                if char() ~= "," then break end
                i=i+1; ws()
            end
        end
        take("}"); ws(); if i<=length then error("invalid_json",0) end
        return result
    end)
    if not ok then return nil,tostring(request) end
    local allowed={schema=true,mode=true,seq=true,sentAtMs=true,sessionId=true,matchId=true,
        x=true,y=true,z=true,pitch=true,yaw=true,roll=true,fov=true}
    for key in pairs(request) do if not allowed[key] then return nil,"unknown_field" end end
    if request.schema ~= 1 or (request.mode ~= "follow" and request.mode ~= "release")
        or not integer(request.seq,0,MAX_SAFE) or not integer(request.sentAtMs,1,MAX_SAFE) then
        return nil,"invalid_envelope"
    end
    if request.mode == "release" then
        for key in pairs(request) do
            if key ~= "schema" and key ~= "mode" and key ~= "seq" and key ~= "sentAtMs" then
                return nil,"invalid_release"
            end
        end
    else
        if not token(request.sessionId) or not token(request.matchId) then return nil,"invalid_identity" end
        for _, key in ipairs({"x","y","z"}) do
            if not finite(request[key],-10000000,10000000) then return nil,"invalid_position" end
        end
        if not finite(request.pitch,-90,90) or not finite(request.yaw,-360000,360000)
            or not finite(request.roll,-360000,360000) then return nil,"invalid_rotation" end
        if not finite(request.fov,1.001,178.999) then return nil,"invalid_fov" end
    end
    return request
end

local function attempt(fn) local ok,v=pcall(fn); if ok then return v end end
local function valid(o) return o ~= nil and attempt(function() return o:IsValid() end) == true end
local function id(o)
    if not valid(o) then return nil end
    return attempt(function() return o:GetAddress() end) or attempt(function() return o:GetFullName() end)
end
local function same(a,b) local key=id(a); return key ~= nil and key==id(b) end

-- Epoch milliseconds, matching Java System.currentTimeMillis. Wall-clock jumps
-- backwards are detected by tick and release the camera. Never use os.clock
-- (CPU time) or second-resolution os.time for the watchdog.
function M.clockMillis()
    return attempt(function()
        local function returned(value)
            -- Native returns normally arrive directly. Also tolerate wrappers
            -- used by UE4SS versions/test harnesses without confusing FDateTime.
            if type(value)=="table" and value.ReturnValue~=nil then return value.ReturnValue end
            return value
        end
        local mathLibrary=StaticFindObject("/Script/Engine.Default__KismetMathLibrary")
        if not valid(mathLibrary) then return nil end
        local utc=returned(mathLibrary:UtcNow())
        local seconds=attempt(function() return returned(mathLibrary:ToUnixTimestampDouble(utc)) end)
        if finite(seconds,1,MAX_SAFE/1000) then return math.floor(seconds*1000) end
        local whole=returned(mathLibrary:ToUnixTimestamp(utc))
        local millis=returned(mathLibrary:GetMillisecond(utc))
        if integer(whole,1,MAX_SAFE/1000) and integer(millis,0,999) then return whole*1000+millis end
    end)
end

local function nativeApi()
    return {
        valid=valid, same=same,
        resolve=function(context)
            local state=context.refs and context.refs.state
            assert(valid(state),"state_unavailable")
            local gameplay=StaticFindObject("/Script/Engine.Default__GameplayStatics")
            assert(valid(gameplay),"gameplay_statics_unavailable")
            local manager=gameplay:GetPlayerCameraManager(state,0)
            assert(valid(manager),"camera_manager_unavailable")
            local controller=manager.PCOwner
            assert(valid(controller),"controller_unavailable")
            local world=state:GetWorld()
            assert(valid(world) and same(world,controller:GetWorld()),"world_mismatch")
            return controller,world
        end,
        target=function(controller) return controller:GetViewTarget() end,
        spawn=function(world,request)
            local class=StaticFindObject("/Script/CinematicCamera.CineCameraActor")
            assert(valid(class),"cine_camera_class_unavailable")
            return world:SpawnActor(class,{X=request.x,Y=request.y,Z=request.z},
                {Pitch=request.pitch,Yaw=request.yaw,Roll=request.roll})
        end,
        configure=function(actor)
            local camera=actor:GetCineCameraComponent()
            assert(valid(camera),"cine_camera_component_unavailable")
            -- All changes are confined to this new camera. No global console
            -- near-plane command, PlayerController option or original-camera edit.
            camera:SetFilmback({SensorWidth=36,SensorHeight=20.25,SensorHorizontalOffset=0,
                SensorVerticalOffset=0,SensorAspectRatio=16/9})
            camera:SetLensSettings({MinFocalLength=0.01,MaxFocalLength=10000,MinFStop=1,MaxFStop=22,
                MinimumFocusDistance=1,SqueezeFactor=1,DiaphragmBladeCount=7})
            camera:SetCropSettings({AspectRatio=0})
            camera:SetConstraintAspectRatio(false)
            camera:SetAspectRatioAxisConstraint(1) -- SDK: MaintainXFOV=1.
            camera.bOverrideAspectRatioAxisConstraint=true
            camera:SetProjectionMode(0) -- Perspective.
            camera.bUsePawnControlRotation=false
            camera.bLockToHmd=false
            camera:SetPostProcessBlendWeight(0)
            local focus=camera.FocusSettings
            focus.FocusMethod=3 -- SDK: Disable; avoid cinema depth-of-field blur.
            camera:SetFocusSettings(focus)
            camera.bOverride_CustomNearClippingPlane=true
            camera:SetCustomNearClippingPlane(1)
            assert(camera.bOverride_CustomNearClippingPlane==true
                and finite(camera.CustomNearClippingPlane,0.001,37),"near_clip_unavailable")
            return camera
        end,
        apply=function(actor,camera,request)
            local hit={}
            local moved=actor:K2_SetActorLocationAndRotation({X=request.x,Y=request.y,Z=request.z},
                {Pitch=request.pitch,Yaw=request.yaw,Roll=request.roll},false,hit,true)
            -- UE4SS may pack every output in the first out table.
            if moved == false or hit.ReturnValue == false then error("camera_transform_failed") end
            camera:SetCurrentFocalLength(36/(2*math.tan(math.rad(request.fov)/2)))
            local actual=camera:GetHorizontalFieldOfView()
            assert(finite(actual,1,179) and math.abs(actual-request.fov)<=0.1,"camera_fov_mismatch")
        end,
        setTarget=function(controller,target) controller:SetViewTargetWithBlend(target,0,0,0,false) end,
        destroy=function(actor) actor:K2_DestroyActor() end,
    }
end

function M.new(sessionId,options)
    assert(token(sessionId),"invalid sessionId")
    options=options or {}
    local api=options.api or nativeApi() -- Test seam; production leaves this unset.
    local self={seq=0,active=false,status="idle",reason="no_request"}
    local function diagnostic(reason,detail)
        self.reason=reason
        self.status=self.active and "following" or (self.actor and "restoring" or "idle")
        return {seq=self.seq,active=self.active,status=self.status,reason=reason,
            detail=detail and tostring(detail):sub(1,240) or nil,nearClip=self.active and 1 or nil}
    end
    function self:release(reason)
        self.active=false; self.request=nil
        if self.actor then
            local ok,err=pcall(function()
                if api.valid(self.controller) and api.valid(self.actor) then
                    local current=api.target(self.controller)
                    if api.same(current,self.actor) then
                        assert(api.valid(self.original),"original_target_unavailable")
                        api.setTarget(self.controller,self.original)
                        assert(api.same(api.target(self.controller),self.original),"restore_not_observed")
                    end
                    -- If the game already selected a different target, leave it
                    -- alone; e.g. end-of-match transition or a replaced controller.
                end
                if api.valid(self.actor) then api.destroy(self.actor) end
            end)
            if not ok then return diagnostic("restore_failed",err) end
        end
        self.actor,self.camera,self.controller,self.original,self.world=nil,nil,nil,nil,nil
        self.matchId=nil
        return diagnostic(reason or "released")
    end
    function self:tick(rawText,context,nowMs)
        -- The root scheduler supplies a fresh context on the game thread; never
        -- call this from LoopAsync directly. No object operation happens in new().
        if not integer(nowMs,1,MAX_SAFE) then return self:release("clock_unavailable") end
        if self.lastNow and nowMs < self.lastNow then
            self.lastNow=nowMs; return self:release("clock_moved_backwards")
        end
        self.lastNow=nowMs
        if self.actor and not self.active then
            local cleanup=self:release("released")
            if self.actor then return cleanup end
        end
        if type(context) ~= "table" or context.inMatch ~= true or context.ended == true then
            return self:release("not_in_match")
        end
        if context.ai ~= true then return self:release("not_ai_match") end
        if context.ready ~= true then return self:release("backend_not_ready") end
        if self.matchId and context.matchId ~= self.matchId then return self:release("match_changed") end
        local request,problem=M.parse(rawText)
        if not request then return self:release(problem) end
        if nowMs-request.sentAtMs > TIMEOUT_MS or request.sentAtMs-nowMs > TIMEOUT_MS then
            return self:release("stale_request")
        end
        if request.mode == "release" then
            self.seq=math.max(self.seq,request.seq)
            self.lastSent=math.max(self.lastSent or 0,request.sentAtMs)
            return self:release("released")
        end
        if request.sessionId ~= sessionId then return self:release("session_mismatch") end
        if request.matchId ~= context.matchId then return self:release("match_mismatch") end
        local newRequest=not self.lastSent or (request.seq>self.seq and request.sentAtMs>=self.lastSent)
        -- MC process restarts reset its sequence. Only accept a lower sequence
        -- after relinquishing control, with a strictly newer, fresh timestamp.
        if not self.active and self.lastSent and request.sentAtMs>self.lastSent then newRequest=true end
        if not newRequest then
            if not self.active then return diagnostic("replayed_request") end
            if request.seq ~= self.seq or request.sentAtMs ~= self.lastSent then return self:release("reordered_request") end
            if nowMs-self.acceptedAt>TIMEOUT_MS then return self:release("request_timeout") end
            -- Do not apply changed payloads under a reused sequence or refresh
            -- the watchdog; only the last accepted request is authoritative.
            request=self.request
        else
            self.seq,self.lastSent,self.acceptedAt=request.seq,request.sentAtMs,nowMs
            self.request=request
        end
        local ok,err=pcall(function()
            local controller,world=api.resolve(context)
            if self.actor then
                assert(api.valid(self.actor) and api.valid(self.camera),"camera_invalidated")
                assert(api.same(controller,self.controller) and api.same(world,self.world),"controller_or_world_changed")
            else
                local original=api.target(controller)
                assert(api.valid(original),"original_target_unavailable")
                self.controller,self.world,self.original=controller,world,original
                self.actor=api.spawn(world,request)
                assert(api.valid(self.actor),"camera_spawn_failed")
                self.camera=api.configure(self.actor)
                self.matchId=context.matchId
            end
            api.apply(self.actor,self.camera,request)
            if not api.same(api.target(controller),self.actor) then api.setTarget(controller,self.actor) end
            assert(api.same(api.target(controller),self.actor),"view_target_not_observed")
        end)
        if not ok then
            local restored=self:release("camera_error")
            if restored.reason == "restore_failed" then return restored end
            return diagnostic("camera_error",err)
        end
        self.active=true
        return diagnostic("applied")
    end
    return self
end
return M
