-- Pure Lua protocol code. No Unreal objects or game mutations in this module.
local M = {}
local ARRAY = {}
function M.array(t) return setmetatable(t or {}, ARRAY) end
local function integer(n, min, max)
    return type(n) == "number" and n == math.floor(n) and n >= min and n <= max
end
M.integer = integer
function M.token(s)
    return type(s) == "string" and #s > 0 and #s <= 128 and s:match("^[A-Za-z0-9_-]+$") ~= nil
end
local function quote(s)
    return '"' .. s:gsub('[%z\1-\31\\"]', function(c)
        if c == '"' then return '\\"' end
        if c == '\\' then return '\\\\' end
        return string.format("\\u%04x", c:byte())
    end) .. '"'
end
function M.json(value)
    local t = type(value)
    if t == "nil" then return "null" end
    if t == "boolean" then return value and "true" or "false" end
    if t == "number" then
        assert(value == value and math.abs(value) ~= math.huge, "non-finite JSON number")
        return tostring(value)
    end
    if t == "string" then return quote(value) end
    assert(t == "table", "JSON contains non-data value")
    local pieces = {}
    if getmetatable(value) == ARRAY then
        for _, v in ipairs(value) do pieces[#pieces + 1] = M.json(v) end
        return "[" .. table.concat(pieces, ",") .. "]"
    end
    local keys = {}
    for key in pairs(value) do assert(type(key) == "string"); if key ~= "end" then keys[#keys + 1] = key end end
    table.sort(keys)
    for _, key in ipairs(keys) do pieces[#pieces + 1] = quote(key) .. ":" .. M.json(value[key]) end
    if value["end"] ~= nil then pieces[#pieces + 1] = '"end":' .. M.json(value["end"]) end
    return "{" .. table.concat(pieces, ",") .. "}"
end
function M.parse(line)
    local p = {}; for part in line:gmatch("%S+") do p[#p + 1] = part end
    if not M.token(p[1]) or not p[2] or not p[2]:match("^%d+$") then return nil, "invalid_envelope" end
    local seq = tonumber(p[2])
    -- Java BridgeState/BridgeSession use signed 32-bit sequence numbers.
    if not integer(seq, 1, 2147483647) then return nil, "invalid_sequence" end
    local cmd = {clientId=p[1], seq=seq, matchId=M.token(p[4]) and p[4] or ""}
    if #p ~= 7 or p[3] ~= "dmg" or not M.token(p[4]) then return cmd, "invalid_command" end
    for i = 5, 7 do if not p[i]:match("^%d+$") then return cmd, "invalid_number" end end
    cmd.turn, cmd.cardId, cmd.amount = tonumber(p[5]), tonumber(p[6]), tonumber(p[7])
    if not integer(cmd.turn, 0, 2147483647) or not integer(cmd.cardId, 1, 2147483647)
        or not integer(cmd.amount, 1, 100) then return cmd, "invalid_number" end
    return cmd
end
function M.new(sessionId)
    assert(M.token(sessionId))
    local self = {sessionId=sessionId, high={}, results=M.array(), clientId="", lastCmd=0}
    function self:ack(cmd, accepted, damage, attack, reason)
        local result = {clientId=cmd.clientId, seq=cmd.seq, matchId=cmd.matchId,
            accepted=accepted == true, damage=damage or 0, attack=attack or 0, reason=reason or "unknown"}
        self.results[#self.results + 1] = result
        if #self.results > 256 then table.remove(self.results, 1) end
        self.clientId, self.lastCmd = cmd.clientId, cmd.seq
        return result
    end
    function self:process(text, getContext, damageCard, budget)
        if type(text) ~= "string" or #text > 32768 then return 0, "command_file_too_large" end
        local lines = {}
        -- An interrupted writer's unterminated last line is never executed.
        for line in text:gmatch("([^\n]*)\n") do
            if line:match("%S") then lines[#lines + 1] = line end
        end
        if #lines > 64 then return 0, "too_many_commands" end
        local count, status = 0, nil
        for _, line in ipairs(lines) do
            local cmd, errorCode = M.parse(line)
            if not cmd then status = errorCode
            elseif cmd.seq > (self.high[cmd.clientId] or 0) then
                if count >= (budget or 4) then break end
                count = count + 1
                -- Claim before calling into native code. Exceptions never cause a retry.
                self.high[cmd.clientId] = cmd.seq
                local reason = errorCode
                local ok, context = pcall(getContext)
                if not reason then
                    if not ok or not context then reason = "backend_unavailable"
                    elseif context.matchId ~= cmd.matchId then reason = "stale_match"
                    elseif not context.inMatch or context.ended then reason = "match_finished"
                    elseif context.ai ~= true then reason = "not_ai_match"
                    elseif context.myTurn ~= true then reason = "not_your_turn"
                    elseif context.turn ~= cmd.turn then reason = "stale_turn"
                    elseif context.busy then reason = "game_busy"
                    elseif context.ready ~= true then reason = context.status or "backend_not_ready" end
                end
                if reason then self:ack(cmd, false, 0, 0, reason)
                else
                    local success, result = pcall(damageCard, cmd, context)
                    if not success or type(result) ~= "table" then self:ack(cmd, false, 0, 0, "native_error")
                    else self:ack(cmd, result.accepted, result.damage, result.attack, result.reason) end
                end
            end
        end
        return count, status
    end
    function self:decorate(snapshot)
        snapshot.protocol, snapshot.sessionId = 2, self.sessionId
        snapshot.clientId, snapshot.lastCmd = self.clientId, self.lastCmd
        snapshot.results, snapshot["end"] = self.results, 1
        return snapshot
    end
    return self
end
return M
