-- Isolated camera protocol/native-shape mocks. No game, window, I/O or UObject
-- is created here; real PlayerCameraManager confirmation remains a live check.
local Camera=require("camera_follow")
local Core=require("bridge_core")
local passed=0
local function check(value,message) assert(value,message or "check failed"); passed=passed+1 end
local function equal(actual,expected,message)
    check(actual==expected,(message or "values differ")..": "..tostring(actual).." ~= "..tostring(expected))
end
local NOW=1791482165078
local function request(overrides)
    local r={schema=1,mode="follow",seq=1,sessionId="kb-test",matchId="kb-test-m3",sentAtMs=NOW,
        x=12.3456,y=-40.25,z=43,pitch=-12.5,yaw=87.25,roll=0,fov=101.2}
    for k,v in pairs(overrides or {}) do r[k]=v end
    return Core.json(r)
end
local function release(seq,at)
    return Core.json({schema=1,mode="release",seq=seq or 2,sentAtMs=at or NOW})
end
local function object(name)
    return {name=name,IsValid=function(self) return not self.invalid end,
        GetFullName=function(self) return self.name end}
end
local function fixture()
    local f={moves=0,spawns=0,destroys=0,targets=0}
    f.original=object("original"); f.controller=object("controller"); f.world=object("world")
    f.target=f.original
    f.context={inMatch=true,ai=true,ready=true,matchId="kb-test-m3",refs={}}
    f.api={valid=function(o) return o~=nil and o:IsValid() end,
        same=function(a,b) return a~=nil and b~=nil and a==b end,
        resolve=function() if f.resolveError then error("resolve failed") end; return f.controller,f.world end,
        target=function() return f.target end,
        spawn=function()
            f.spawns=f.spawns+1
            if f.spawnError then error("spawn failed") end
            f.actor=object("camera"..f.spawns); return f.actor
        end,
        configure=function()
            if f.configureError then error("configure failed") end
            f.camera=object("component"); return f.camera
        end,
        apply=function(_,_,pose)
            f.moves=f.moves+1
            if f.applyError then error("apply failed") end
            f.pose=pose
        end,
        setTarget=function(_,target)
            f.targets=f.targets+1
            if f.restoreError and target==f.original then error("restore failed") end
            if f.targetError then error("target failed") end
            f.target=target
        end,
        destroy=function(actor) f.destroys=f.destroys+1; actor.invalid=true end}
    f.follow=Camera.new("kb-test",{api=f.api})
    function f:tick(raw,now) return self.follow:tick(raw or request(),self.context,now or NOW) end
    return f
end

-- MC's 64-bit milliseconds and fractional poses survive without int32 truncation.
do
    local r=assert(Camera.parse(request()))
    equal(r.sentAtMs,NOW); equal(r.x,12.3456); equal(r.y,-40.25)
    equal(r.pitch,-12.5); equal(r.fov,101.2)
    equal(assert(Camera.parse(release())).mode,"release")
    local exponent=request():gsub('"x":12.3456','"x":1.23456e1')
    check(math.abs(assert(Camera.parse(exponent)).x-12.3456)<1e-10)
    local escaped=request():gsub('"sessionId"','"session\\u0049d"')
    equal(assert(Camera.parse(escaped)).sessionId,"kb-test")
end
do
    local invalid={"", "{}", "[]", "null", request().."x", request().."{}", "return {}",
        request():gsub('"seq":1','"seq":01'),request():gsub('"x":12.3456','"x":1.'),
        request():gsub('"x":12.3456','"x":.1'),request():gsub('"x":12.3456','"x":NaN'),
        request():gsub('"x":12.3456','"x":Infinity'),request():gsub('"x":12.3456','"x":1e999'),
        request():gsub('"x":12.3456','"x":true'),request():gsub('"x":12.3456','"x":{}'),
        request():gsub('"x":12.3456','"x":[]'),request():gsub('"x":12.3456','"x":1e+'),
        request({seq=-1}),request({seq=1.5}),request({seq=9007199254740992}),
        request({sentAtMs=0}),request({sentAtMs=NOW+0.25}),request({schema=2}),
        request({mode="unknown"}),request({fov=0}),request({fov=180}),request({pitch=91}),
        request({x=10000001}),request({sessionId=""}),request({matchId="other match"}),
        request({unknown=1}),request():gsub('"x":12.3456','"x":12.3456,"x":3'),
        request():gsub('"seq":1','"seq":1,"se\\u0071":2'),
        request():gsub('"sessionId":"kb%-test"','"sessionId":"\\ud800"'),
        request():gsub('"sessionId":"kb%-test"','"sessionId":"\\q"'),
        release():gsub('"seq":2','"seq":2,"sessionId":"kb-test"'),
        string.rep(" ",4097), request():gsub('"x":12.3456','"x":12.3456,')}
    for _,raw in ipairs(invalid) do check(Camera.parse(raw)==nil,"accepted malformed request: "..tostring(raw)) end
end

-- Reuses one camera at 60Hz, independent of turn/busy. Diagnostics never echo cam.
do
    local f=fixture()
    f.context.myTurn=false; f.context.busy=true
    for i=1,61 do
        local d=f:tick(request({seq=i,sentAtMs=NOW+(i-1)*16,x=i}),NOW+(i-1)*16)
        check(d.active); equal(d.seq,i); equal(d.cam,nil)
    end
    equal(f.spawns,1); equal(f.moves,61); equal(f.targets,1); equal(f.pose.x,61)
    local d=f:tick(release(62,NOW+977),NOW+977)
    check(not d.active); equal(d.reason,"released"); equal(f.target,f.original); equal(f.destroys,1)
    f:tick(release(62,NOW+977),NOW+990); equal(f.destroys,1)
end
do
    local f=fixture(); f:tick()
    f:tick(request({x=999}),NOW+100); equal(f.pose.x,12.3456)
    check(f:tick(request(),NOW+500).active)
    local d=f:tick(request(),NOW+501)
    check(not d.active); equal(f.target,f.original); equal(f.destroys,1)
    equal(f:tick(request(),NOW+502).reason,"stale_request"); equal(f.spawns,1)
end
do
    local f=fixture(); f:tick(request({sentAtMs=NOW+499}))
    check(f:tick(request({sentAtMs=NOW+499}),NOW+500).active)
    equal(f:tick(request({sentAtMs=NOW+499}),NOW+501).reason,"request_timeout")
    equal(f.target,f.original)
end
for _,case in ipairs({{"ai",false,"not_ai_match"},{"ready",false,"backend_not_ready"},
    {"inMatch",false,"not_in_match"},{"ended",true,"not_in_match"},
    {"matchId","kb-test-m4","match_changed"}}) do
    local f=fixture(); f:tick(); f.context[case[1]]=case[2]
    equal(f:tick().reason,case[3]); equal(f.target,f.original); equal(f.destroys,1)
end
for _,case in ipairs({{sessionId="other"},{matchId="kb-test-m4"},{sentAtMs=NOW-501},
    {sentAtMs=NOW+501},{x=10000001}}) do
    local f=fixture(); f:tick(); check(not f:tick(request(case)).active)
    equal(f.target,f.original); equal(f.destroys,1)
end
do
    local f=fixture(); f:tick()
    check(not f:tick("broken").active); equal(f.target,f.original)
    local g=fixture(); g:tick()
    equal(g.follow:tick(nil,g.context,NOW+20).reason,"no_request"); equal(g.target,g.original)
    local h=fixture(); h:tick()
    equal(h.follow:tick(request(),nil,NOW+20).reason,"not_in_match"); equal(h.target,h.original)
end
do
    local f=fixture(); f:tick()
    equal(f.follow:tick(request(),f.context,nil).reason,"clock_unavailable"); equal(f.target,f.original)
    local g=fixture(); g:tick()
    equal(g:tick(request(),NOW-1).reason,"clock_moved_backwards"); equal(g.target,g.original)
end
-- Lower/reordered seq cannot replace an active request. Restart can recover only
-- after release and with a later timestamp; a file reread cannot respawn a camera.
do
    local f=fixture(); f:tick(request({seq=100}))
    equal(f:tick(request({seq=99,sentAtMs=NOW+10}),NOW+10).reason,"reordered_request")
    equal(f:tick(request({seq=99,sentAtMs=NOW+10}),NOW+11).reason,"applied")
    f:tick(release(101,NOW+20),NOW+20)
    equal(f:tick(request({seq=100,sentAtMs=NOW+19}),NOW+30).reason,"replayed_request")
    check(f:tick(request({seq=1,sentAtMs=NOW+31}),NOW+31).active)
end
-- If the game takes the view target away during a transition, releasing must
-- not replace its new choice with an obsolete board camera.
do
    local f=fixture(); f:tick(); local gameTarget=object("game transition")
    f.target=gameTarget; f:tick(release()); equal(f.target,gameTarget); equal(f.destroys,1)
end
for _,failure in ipairs({"resolveError","spawnError","configureError","applyError","targetError"}) do
    local f=fixture(); f[failure]=true
    local d=f:tick(); check(not d.active); equal(d.reason,"camera_error")
    equal(f.target,f.original)
    if failure=="configureError" or failure=="applyError" or failure=="targetError" then equal(f.destroys,1) end
end
do
    local f=fixture(); f:tick(); f.applyError=true
    equal(f:tick(request({seq=2,sentAtMs=NOW+16}),NOW+16).reason,"camera_error")
    equal(f.target,f.original); equal(f.destroys,1)
    local g=fixture(); g:tick(); g.restoreError=true
    equal(g:tick(release()).reason,"restore_failed"); equal(g.destroys,0)
    equal(g.target,g.actor); g.restoreError=false
    equal(g:tick(release()).reason,"released"); equal(g.target,g.original); equal(g.destroys,1)
end
do
    local f=fixture(); f:tick(); f.controller=object("replacement")
    equal(f:tick(request({seq=2,sentAtMs=NOW+16}),NOW+16).reason,"camera_error")
    equal(f.target,f.original); equal(f.destroys,1)
    local g=fixture(); g:tick(); g.world=object("travel")
    equal(g:tick(request({seq=2,sentAtMs=NOW+16}),NOW+16).reason,"camera_error")
    equal(g.destroys,1)
end

-- Exercise the production API shape, not just an injected fake adapter. Every
-- native mock asserts game-thread context. A single out table receives the hit.
local previousStatic=StaticFindObject
do
    local inGameThread=true
    local function onGameThread() check(inGameThread,"native call outside GameThread") end
    local world,controller,manager=object("World"),object("Controller"),object("Manager")
    local state,original,class=object("State"),object("Original"),object("CineCameraClass")
    local component=object("Component"); local actor=object("CineCamera")
    local target=original; local transformCalls=0
    state.GetWorld=function() onGameThread(); return world end
    controller.GetWorld=function() onGameThread(); return world end
    controller.GetViewTarget=function() onGameThread(); return target end
    controller.SetViewTargetWithBlend=function(_,newTarget,seconds,blend,exp,lock)
        onGameThread(); equal(seconds,0); equal(blend,0); equal(exp,0); equal(lock,false); target=newTarget
    end
    manager.PCOwner=controller
    local gameplay=object("GameplayStatics")
    gameplay.GetPlayerCameraManager=function(_,context,index)
        onGameThread(); equal(context,state); equal(index,0); return manager
    end
    world.SpawnActor=function(_,kind,position,rotation)
        onGameThread(); equal(kind,class); equal(position.X,12.3456); equal(rotation.Yaw,87.25); return actor
    end
    actor.GetCineCameraComponent=function() onGameThread(); return component end
    actor.K2_SetActorLocationAndRotation=function(_,location,rotation,sweep,out,teleport)
        onGameThread(); equal(location.Z,43); equal(rotation.Pitch,-12.5)
        equal(sweep,false); equal(teleport,true); equal(type(out),"table")
        out.SweepHitResult={bBlockingHit=false}; out.ReturnValue=true
        transformCalls=transformCalls+1; return true
    end
    actor.K2_DestroyActor=function(self) onGameThread(); self.invalid=true end
    component.FocusSettings={FocusMethod=1}
    component.SetFilmback=function(self,value) onGameThread(); self.Filmback=value end
    component.SetLensSettings=function(self,value) onGameThread(); self.LensSettings=value end
    component.SetCropSettings=function(self,value) onGameThread(); equal(value.AspectRatio,0) end
    component.SetConstraintAspectRatio=function(_,value) onGameThread(); equal(value,false) end
    component.SetAspectRatioAxisConstraint=function(_,value) onGameThread(); equal(value,1) end
    component.SetProjectionMode=function(_,value) onGameThread(); equal(value,0) end
    component.SetPostProcessBlendWeight=function(_,value) onGameThread(); equal(value,0) end
    component.SetFocusSettings=function(_,value) onGameThread(); equal(value.FocusMethod,3) end
    component.SetCustomNearClippingPlane=function(self,value) onGameThread(); self.CustomNearClippingPlane=value end
    component.SetCurrentFocalLength=function(self,value) onGameThread(); self.focal=value end
    component.GetHorizontalFieldOfView=function(self)
        onGameThread(); return math.deg(2*math.atan(self.Filmback.SensorWidth/(2*self.focal)))
    end
    local mathLib=object("MathLibrary"); local utc={Ticks=123}
    mathLib.UtcNow=function() onGameThread(); return {ReturnValue=utc} end
    mathLib.ToUnixTimestampDouble=function(_,value) onGameThread(); equal(value,utc); return {ReturnValue=NOW/1000} end
    mathLib.ToUnixTimestamp=function(_,value) onGameThread(); equal(value,utc); return {ReturnValue=math.floor(NOW/1000)} end
    mathLib.GetMillisecond=function(_,value) onGameThread(); equal(value,utc); return {ReturnValue=NOW%1000} end
    StaticFindObject=function(path)
        onGameThread()
        return ({["/Script/Engine.Default__GameplayStatics"]=gameplay,
            ["/Script/Engine.Default__KismetMathLibrary"]=mathLib,
            ["/Script/CinematicCamera.CineCameraActor"]=class})[path]
    end
    equal(Camera.clockMillis(),NOW)
    mathLib.ToUnixTimestampDouble=function() error("older SDK") end
    equal(Camera.clockMillis(),NOW)
    mathLib.GetMillisecond=function() return nil end
    equal(Camera.clockMillis(),nil)
    local follow=Camera.new("kb-test")
    local context={refs={state=state},inMatch=true,ai=true,ready=true,matchId="kb-test-m3"}
    local d=follow:tick(request(),context,NOW)
    check(d.active,d.detail); equal(target,actor); equal(transformCalls,1)
    equal(component.CustomNearClippingPlane,1); equal(component.bOverride_CustomNearClippingPlane,true)
    check(math.abs(component:GetHorizontalFieldOfView()-101.2)<1e-9)
    check(math.abs(component.focal-36/(2*math.tan(math.rad(101.2)/2)))<1e-9)
    equal(component.bOverrideAspectRatioAxisConstraint,true)
    equal(component.bUsePawnControlRotation,false); equal(component.bLockToHmd,false)
    equal(follow:tick(release(),context,NOW+16).reason,"released")
    equal(target,original); check(actor.invalid)
end
StaticFindObject=previousStatic

-- Real main/Core/Camera integration. Only the engine adapter/API and clock are
-- replaced. All file traffic stays in the runner's isolated temporary directory.
assert(type(TEST_TEMP)=="string" and #TEST_TEMP>0,"camera main integration needs TEST_TEMP")
do
    local saved={getenv=os.getenv,loop=LoopAsync,execute=ExecuteInGameThread,key=Key,
        bind=RegisterKeyBind,main=package.loaded.main,adapter=package.loaded.kards_adapter,
        camera=package.loaded.camera_follow,settings=package.loaded.bridge_settings}
    local f=fixture()
    local inGameThread=false
    for name,fn in pairs(f.api) do
        f.api[name]=function(...)
            check(inGameThread,"main camera API off game thread: "..name)
            return fn(...)
        end
    end
    local session,loop,queued,refresh
    local cameraTicks,damages,snapshots,now,clockThrows=0,0,0,NOW,false
    local actualCam={x=991,y=992,z=993,pitch=-80,yaw=15,roll=0,fov=78}
    local adapter={damageMode="fixture"}
    function adapter:context()
        check(inGameThread,"adapter context off game thread")
        return f.context
    end
    function adapter:damage(cmd)
        check(inGameThread,"adapter damage off game thread")
        damages=damages+1
        return {accepted=true,damage=cmd.amount,attack=0,reason="fixture_observed"}
    end
    function adapter:snapshot(context)
        check(inGameThread,"adapter snapshot off game thread"); equal(context,f.context)
        snapshots=snapshots+1
        return {inMatch=true,ai=true,myTurn=true,mySide=1,turn=1,ready=true,
            matchId=context.matchId,status="ready_unverified",cards=Core.array(),cam=actualCam}
    end
    package.loaded.kards_adapter={new=function(id)
        session=id; f.context.matchId=id.."-m1"; f.context.myTurn=true; f.context.turn=1
        return adapter
    end}
    package.loaded.camera_follow={clockMillis=function()
        check(inGameThread,"camera clock off game thread")
        if clockThrows then error("fixture clock failure") end
        return now
    end,new=function(id)
        equal(id,session)
        f.follow=Camera.new(id,{api=f.api})
        local realTick=f.follow.tick
        f.follow.tick=function(self,...)
            cameraTicks=cameraTicks+1
            return realTick(self,...)
        end
        return f.follow
    end}
    package.loaded.main=nil
    package.loaded.bridge_settings={directory=TEST_TEMP}
    os.getenv=function(name)
        if name=="KARDSCRAFT_BRIDGE_DIR" then return TEST_TEMP end
        return saved.getenv(name)
    end
    LoopAsync=function(interval,callback) equal(interval,16); loop=callback end
    ExecuteInGameThread=function(callback)
        check(not inGameThread,"scheduler called outside expected async mock")
        check(queued==nil,"at most one queued callback"); queued=callback
    end
    Key={F8=119}
    RegisterKeyBind=function(key,callback) equal(key,119); refresh=callback end
    local cameraPath=TEST_TEMP.."/KardsCraft_camera.json"
    local commandPath=TEST_TEMP.."/KardsBridge_cmd.txt"
    local statePath=TEST_TEMP.."/KardsBridge_state.json"
    local function write(path,text)
        local file=assert(io.open(path,"wb")); assert(file:write(text)); assert(file:close())
    end
    local function read(path)
        local file=assert(io.open(path,"rb")); local text=file:read("*a"); file:close(); return text
    end
    local function step(force)
        if force then refresh() else loop() end
        local work=assert(queued); queued=nil
        inGameThread=true; work(); inGameThread=false
    end
    require("main")
    write(commandPath,"camera_integration 1 dmg "..f.context.matchId.." 1 10 2\n")
    for i=1,6 do
        now=NOW+(i-1)*16
        write(cameraPath,request({seq=i,sessionId=session,matchId=f.context.matchId,sentAtMs=now,x=i}))
        step()
    end
    equal(cameraTicks,6); equal(f.moves,6); equal(damages,1); equal(snapshots,1)
    equal(f.spawns,1); equal(f.target,f.actor)
    local state=read(statePath)
    check(state:find('"cam":'..Core.json(actualCam),1,true)~=nil,"request must not replace actual camera")
    check(state:find('"camFollow":{"active":true',1,true)~=nil,"camera diagnostic is separate")
    equal(actualCam.x,991); equal(actualCam.fov,78)
    -- Release is handled immediately, irrespective of snapshot cadence.
    now=NOW+100; write(cameraPath,release(7,now)); step()
    equal(cameraTicks,7); equal(f.target,f.original); equal(f.destroys,1)
    equal(damages,1,"retained command is still deduplicated")
    -- A missing high-resolution clock follows the actual Camera release path.
    now=NOW+116; write(cameraPath,request({seq=8,sessionId=session,matchId=f.context.matchId,sentAtMs=now})); step()
    equal(f.target,f.actor)
    now=nil; step(); equal(f.target,f.original); equal(f.destroys,2)
    equal(f.follow.reason,"clock_unavailable")
    -- If clock evaluation throws before Camera.tick, main's catch must still
    -- invoke Camera.tick(nil,{},nil), restoring the old view target.
    now=NOW+148; write(cameraPath,request({seq=9,sessionId=session,matchId=f.context.matchId,sentAtMs=now})); step()
    equal(f.target,f.actor)
    local before=cameraTicks; clockThrows=true; step()
    equal(cameraTicks,before+1); equal(f.target,f.original); equal(f.destroys,3)
    check(read(statePath):find('"status":"bridge_error"',1,true)~=nil)
    clockThrows=false; now=NOW+180
    -- Losing a request file also releases on the next callback (not six later).
    write(cameraPath,request({seq=10,sessionId=session,matchId=f.context.matchId,sentAtMs=now})); step()
    equal(f.target,f.actor); assert(os.remove(cameraPath)); now=now+16; step()
    equal(f.target,f.original); equal(f.destroys,4); equal(f.follow.reason,"no_request")
    os.getenv=saved.getenv; LoopAsync=saved.loop; ExecuteInGameThread=saved.execute
    Key=saved.key; RegisterKeyBind=saved.bind
    package.loaded.main=saved.main; package.loaded.kards_adapter=saved.adapter
    package.loaded.camera_follow=saved.camera; package.loaded.bridge_settings=saved.settings
end
print("Camera follow isolated checks passed: "..passed)
