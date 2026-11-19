-- 令牌桶限流（原子执行）
-- KEYS[1] = 限流 key
-- ARGV[1] = capacity         桶容量（允许的最大突发）
-- ARGV[2] = refillPerSecond  每秒补充令牌数（长期平均速率）
-- 返回 { allowed, retryAfterSeconds }

local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])

-- 时间取自 Redis，避免多实例机器时钟不一致
local t = redis.call('TIME')
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000

local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- 按流逝时间补令牌，但不超过容量
local elapsed = now - ts
if elapsed < 0 then
  elapsed = 0
end
tokens = math.min(capacity, tokens + elapsed * refill)

local allowed = 0
local retryAfter = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  if refill > 0 then
    retryAfter = math.ceil((1 - tokens) / refill)
  else
    retryAfter = 60
  end
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- 桶从空填满所需时间 + 60 秒，避免 key 长期占用内存
redis.call('EXPIRE', KEYS[1], math.ceil(capacity / refill) + 60)

return { allowed, retryAfter }
