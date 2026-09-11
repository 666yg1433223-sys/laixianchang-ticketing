-- Validate BOTH keys before mutation: Lua errors do not roll back previous writes.
local counts = {}
for i = 1, 2 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= 'string' then return -1 end
    local raw = redis.call('GET', KEYS[i])
    local ttl = redis.call('PTTL', KEYS[i])
    if raw then
        if not string.match(raw, '^%d+$') or #raw > 6 or ttl <= 0 then return -1 end
        counts[i] = tonumber(raw)
        if counts[i] > 100000 then return -1 end
    else
        counts[i] = 0
    end
end
local ttl = tonumber(ARGV[1])
if not ttl or ttl < 1 or ttl > 11000 then return -1 end
if counts[1] >= 5 or counts[2] >= 50 then return 0 end
for i = 1, 2 do
    redis.call('INCR', KEYS[i])
    if counts[i] == 0 then redis.call('PEXPIRE', KEYS[i], ttl) end
end
return 1
