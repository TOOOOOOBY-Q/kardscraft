-- KardsRecon: read-only discovery mod. Dumps class/object info so we can find
-- board, unit, damage and turn classes. Writes nothing into the game.
local OUT = "KardsRecon_"

local function log(s) print("[KardsRecon] " .. tostring(s) .. "\n") end

local function dumpMatching(tag)
    local patterns = {"card","unit","board","hq","headquart","damage","health","defen","attack",
                      "turn","match","battle","frontline","popup","dialog","network","offline","training","ai"}
    local f = io.open(OUT .. tag .. "_classes.txt", "w")
    local n = 0
    ForEachUObject(function(obj)
        if obj and obj:IsValid() then
            local ok, full = pcall(function() return obj:GetFullName() end)
            if ok and full then
                local low = string.lower(full)
                if string.find(low, "^class ") or string.find(low, "^function ") or string.find(low, "^blueprintgeneratedclass ") or string.find(low, "^widgetblueprintgeneratedclass ") then
                    for _, p in ipairs(patterns) do
                        if string.find(low, p, 1, true) then f:write(full, "\n"); n = n + 1; break end
                    end
                end
            end
        end
    end)
    f:close()
    log("wrote " .. n .. " matching names (" .. tag .. ")")
end

local function fullDump(tag)
    log("dump start " .. tag)
    dumpMatching(tag)
    pcall(function() DumpAllActors() end)
    pcall(function() GenerateSDK() end)
    log("dump done " .. tag)
end

-- automatic dump shortly after the game finishes booting
ExecuteWithDelay(45000, function() ExecuteInGameThread(function() fullDump("boot") end) end)

-- F7: dump again (use this inside a training match)
RegisterKeyBind(Key.F7, function() ExecuteInGameThread(function() fullDump("match") end) end)

log("loaded")
