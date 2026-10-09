-- KardsBridge protocol v2. All UObject access happens on the game thread.
-- Damage mode is selected once at startup. Neither path changes validation or
-- synthesizes server actions; successful ACKs require a live-card getter change.
local Core = require("bridge_core")
local Adapter = require("kards_adapter")
local Camera = require("camera_follow")
local loaded, settings = pcall(require, "bridge_settings")
local configured = loaded and type(settings) == "table" and type(settings.directory) == "string"
    and #settings.directory > 0 and settings.directory or nil
local environmentDirectory = os.getenv("KARDSCRAFT_BRIDGE_DIR")
if environmentDirectory == "" then environmentDirectory = nil end
local temp = environmentDirectory or configured or os.getenv("TEMP") or os.getenv("TMP")
assert(temp and #temp > 0, "KardsBridge requires TEMP or KARDSCRAFT_BRIDGE_DIR")
local separator = package.config:sub(1, 1)
local statePath = temp .. separator .. "KardsBridge_state.json"
local commandPath = temp .. separator .. "KardsBridge_cmd.txt"
local cameraPath = temp .. separator .. "KardsCraft_camera.json"
local damageLogPath = temp .. separator .. "KardsBridge_damage.jsonl"
local damageLogLimit = 1024 * 1024
local sessionId = "kb-" .. os.time() .. "-" .. tostring({}):gsub("[^A-Za-z0-9]", "")
local protocol = Core.new(sessionId)
local adapter = Adapter.new(sessionId, loaded and type(settings)=="table" and settings or {})
local camera = Camera.new(sessionId)
local scheduled, previousError, damageLogError = false, nil, nil
local slowTick = 0
local function log(s) print("[KardsBridge] " .. tostring(s) .. "\n") end
local function readCommands()
    local file = io.open(commandPath, "rb")
    if not file then return "" end
    local text = file:read(32769) or ""
    file:close()
    return text
end
local function readCamera()
    local file = io.open(cameraPath, "rb")
    if not file then return nil end
    local text = file:read(4097)
    file:close()
    return text
end
local function publish(snapshot)
    local text = Core.json(protocol:decorate(snapshot))
    local staged = statePath .. "." .. sessionId .. ".tmp"
    local file, err = io.open(staged, "wb")
    if not file then error(err) end
    local wrote, writeError = file:write(text)
    local closed, closeError = file:close()
    if not wrote or not closed then os.remove(staged); error(writeError or closeError) end
    local renamed = os.rename(staged, statePath)
    if not renamed then
        -- Windows Lua rename cannot replace an existing file. Readers retry this
        -- brief missing-file interval; a half-written JSON is never published.
        os.remove(statePath)
        local ok, renameError = os.rename(staged, statePath)
        if not ok then os.remove(staged); error(renameError) end
    end
end
local function recordDamage(result)
    -- The rolling snapshot only retains the latest request. Keep bounded history
    -- across ticks and restarts so no-effect and native-error evidence survives.
    -- Logging must never change an ACK or cause a native call to be retried.
    local ok, err = pcall(function()
        local text = Core.json({schema=1, sessionId=sessionId, observedAt=os.date("!%Y-%m-%dT%H:%M:%SZ"),
            result=result, diagnostic=adapter.lastDamage}) .. "\n"
        local existing = io.open(damageLogPath, "rb")
        local size = 0
        if existing then size = existing:seek("end") or 0; existing:close() end
        if size + #text > damageLogLimit then
            local previous = damageLogPath .. ".1"
            local old = io.open(previous, "rb")
            if old then old:close(); assert(os.remove(previous)) end
            assert(os.rename(damageLogPath, previous))
        end
        local file, openError = io.open(damageLogPath, "ab")
        if not file then error(openError) end
        local wrote, writeError = file:write(text)
        local closed, closeError = file:close()
        if not wrote or not closed then error(writeError or closeError) end
    end)
    if not ok then
        local message = tostring(err):sub(1, 400)
        if message ~= damageLogError then log("damage journal error: " .. message) end
        damageLogError = message
    else damageLogError = nil end
end
local function tick()
    local ok, err = pcall(function()
        -- Camera updates need frame cadence. Keep command processing and state
        -- publication near their previous 10 Hz rate, on the same game thread.
        local publishNow = slowTick == 0
        slowTick = (slowTick + 1) % 6
        local count, commandStatus = 0, nil
        if publishNow then
            count, commandStatus = protocol:process(readCommands(), function() return adapter:context() end,
                function(cmd, context)
                    local result = adapter:damage(cmd, context)
                    recordDamage(result)
                    return result
                end, 4)
        end
        local context = adapter:context()
        local camFollow = camera:tick(readCamera(), context, Camera.clockMillis())
        if publishNow then
            local snapshot = adapter:snapshot(context)
            snapshot.camFollow = camFollow
            if commandStatus then snapshot.commandStatus = commandStatus end
            snapshot.damageLogError = damageLogError
            snapshot.processedThisTick = count
            publish(snapshot)
        end
    end)
    if not ok then
        -- A backend exception must not strand the player in our camera.
        pcall(function() camera:tick(nil, {}, nil) end)
        if tostring(err) ~= previousError then log("tick error: " .. tostring(err)); previousError = tostring(err) end
        pcall(publish, {inMatch=false, ai=false, myTurn=false, mySide=0, turn=0,
            matchId="", status="bridge_error", error=tostring(err):sub(1, 400), cards=Core.array()})
    else previousError = nil end
    scheduled = false
end
local function schedule()
    if not scheduled then
        scheduled = true
        local ok, err = pcall(ExecuteInGameThread, tick)
        if not ok then scheduled = false; log("cannot schedule game thread: " .. tostring(err)) end
    end
    return false
end
LoopAsync(16, schedule)
RegisterKeyBind(Key.F8, function() slowTick = 0; return schedule() end) -- Optional immediate refresh.
log("protocol v2 loaded; damageMode=" .. tostring(adapter.damageMode) .. "; state=" .. statePath
    .. "; F8 refresh; game verification pending")
