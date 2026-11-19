# 接口限流 · 设计说明

> 状态：**待实现**
> 目标：防止单用户/单 IP 刷接口，保护数据库与缓存；超限时给出明确的重试提示。

---

## 1. 要解决什么

| 场景 | 后果 |
|---|---|
| 脚本刷商家注册 | 垃圾账号灌满 `app_account` |
| 爬虫刷附近骑手查询 | Redis GEO + MySQL 被打满 |
| 下单接口被反复点击 | 大量无效订单与 Outbox 消息 |

限流保护的是**自己**，不是限制正常用户。

---

## 2. 算法选择：令牌桶

| 算法 | 能否应对突发 | 实现复杂度 | 结论 |
|---|---|---|---|
| 固定窗口 | 不能（临界突刺：59s 和 61s 各 100 次 = 2s 内 200 次） | 低 | ✗ |
| 滑动窗口 | 能 | 中 | 备选 |
| **令牌桶** | **能（允许攒令牌后一次性用掉）** | **低** | **✓ 采用** |
| 漏桶 | 不能（强制匀速） | 中 | ✗ |

令牌桶模型：

```
桶容量 capacity      —— 最多能攒多少令牌（= 允许的最大突发）
补充速率 refill      —— 每秒放几个令牌
每次请求             —— 消耗 1 个令牌；没有就拒绝并算出还需等几秒
```

---

## 3. 存储：Redis + Lua

### 为什么必须用 Lua

「读出令牌数 → 计算补充 → 写回」如果分三次发给 Redis，两个并发请求会读到同一个旧值，都以为有令牌 → **超发**。

Lua 在 Redis 里单线程原子执行，整段逻辑不可分割。

### 为什么时间用 Redis 的

```lua
local t = redis.call('TIME')
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000
```

多实例部署时各机器时钟可能偏差几秒。**用 Redis 的时钟做唯一时间源**，避免"这台机器以为过了一秒，那台以为过了三秒"。

### key 结构

```
rate:{scope}:{identifier}:{method}:{path}
```

示例：

```
rate:user:2:POST:/api/v1/merchant/me/orders
rate:ip:113.88.1.1:POST:/api/v1/auth/register/merchant
```

value 用 Hash：`{ tokens, ts }`。TTL 设为「桶从空填满所需时间 + 60 秒」，避免长期占内存。

### Lua 脚本

```lua
-- KEYS[1] 限流 key
-- ARGV[1] capacity   桶容量
-- ARGV[2] refillRate 每秒补充令牌数
local capacity = tonumber(ARGV[1])
local refill   = tonumber(ARGV[2])

local t = redis.call('TIME')
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000

local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- 按时间差补令牌，不超过容量
tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill)

local allowed = 0
local retryAfter = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retryAfter = math.ceil(1 / refill)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('EXPIRE', KEYS[1], math.ceil(capacity / refill) + 60)

return { allowed, retryAfter }
```

---

## 4. 限流维度

| 维度 | identifier | 用于 |
|---|---|---|
| 用户 | 账号 id（`AccountPrincipal`） | 已登录接口 |
| IP | 客户端 IP（`X-Forwarded-For` 优先） | 匿名接口：注册、登录 |

**为什么不按接口做全局计数**：那样一个人刷就能把所有人挡住，违背"保护自己"的初衷。

---

## 5. 真实高并发场景下的限流设计

### 5.1 三个规模档位

| 档位 | 日订单 | 骑手数（按 30 单/人/天） | 高峰订单占比 |
|---|---|---|---|
| 区域平台 | 10 万 | 3,300 | 45% 集中在 3 小时 |
| 城市平台 | 100 万 | 33,000 | 45% 集中在 3 小时 |
| 头部平台（美团级） | 5,000 万 | 166 万 | 45% 集中在 3 小时 |

推峰值公式：

```
高峰平均 QPS = 日订单 × 45% / (3 × 3600)
瞬时峰值     ≈ 高峰平均 × 3    （12:00 整点那五分钟）
```

| 档位 | 高峰平均下单 QPS | 瞬时峰值下单 QPS |
|---|---|---|
| 10 万 | 4.2 | 约 13 |
| 100 万 | 41.7 | 约 125 |
| 5,000 万 | 2,083 | 约 6,250 |

### 5.2 角色 × 场景矩阵（以「城市平台 · 100 万单/天」为例）

| 角色 | 场景 | 频次特征 | 峰值 QPS | 该限什么维度 | 阈值依据 |
|---|---|---|---|---|---|
| 用户 | 下单 | 高峰极度集中 | ~125 | 用户 + IP | 单人 1 分钟 3 单 |
| 用户 | 查订单 | 等餐时反复刷新 | **~1,500** | 用户 | 单人 10 次/分钟 |
| 商家 | 接单/出餐 | 每单 2–3 次 | ~375 | 商家 | 每分钟 30 次 |
| 商家 | 查单/看板 | 后台轮询 | ~200 | 商家 | 每分钟 10 次 |
| 骑手 | 抢单 | 派单推送后瞬时点击 | ~500 | 骑手 | 每分钟 5 次 |
| 骑手 | **位置上报** | 每 3 秒 1 次 | **~11,100** | 骑手 | **按最小间隔，不按次数** |
| 骑手 | 送达 | 每单 1 次 | ~125 | 骑手 | 每分钟 10 次 |
| 系统 | 派单/召回 | 内部调用 | ~500 | 不限流 | 内部可信调用 |

### 5.3 三个反直觉的结论

**① 压力最大的是位置上报，不是下单**

3.3 万个骑手每 3 秒上报一次 = **11,100 QPS**，是下单 QPS 的 **89 倍**。

真实平台的做法不是"限流拒掉"，而是**改变上报方式**：批量上报、只报变化、降低频率（5–10 秒）、端上聚合。**限流只是兜底。**

**② 下单 QPS 其实很低，但绝对不能拒绝**

125 QPS 对任何现代服务都不算高。但下单是**收入入口**，一旦限流误伤，直接损失订单。

所以下单的阈值要给足余量（正常峰值的 3–5 倍），并且**过载时优先牺牲查询接口**。

**③ 读放大：查单 QPS 是下单的 12 倍**

一个用户等餐 30 分钟，平均刷新 8–10 次。100 万单 × 8 次 = 800 万次/天。

**这说明查单的主要解法是缓存与读写分离，限流只防极端刷子。**

### 5.4 限流必须分层

| 层 | 位置 | 解决什么 | 典型算法 |
|---|---|---|---|
| **接入层** | 网关 / Nginx | 保护整体容量，挡住明显洪峰 | 全局令牌桶、连接数限制 |
| **服务层** | 应用内拦截器 | 防单用户超量，保证公平 | 用户级令牌桶 |
| **接口特化** | 业务逻辑内 | 语义化的频率控制 | 最小上报间隔、幂等键 |

只做服务层，挡不住大规模 DDoS；只做网关层，一个人刷就能占满配额。

### 5.5 分级限流：过载时先牺牲谁

真实系统在容量不足时，不是"一视同仁地拒绝"，而是按业务价值排队：

| 级别 | 接口 | 过载策略 |
|---|---|---|
| **P0** | 下单、接单、送达、支付 | **不拒绝**，必要时降级非核心逻辑 |
| **P1** | 查订单、查骑手位置 | 降级：只读缓存，不查库 |
| **P2** | 列表、看板、统计、导出 | 直接拒绝，返回 503 |
| **P3** | 注册、改资料 | 拒绝，提示稍后重试 |

**这个优先级表，比限流算法本身更能体现你在设计系统，而不是调参数。**

### 5.6 位置上报这种接口，不能用令牌桶

令牌桶回答的是"单位时间允许多少次"，但位置上报的真实语义是**"两次上报之间至少间隔 N 秒"**。

```
错误做法：每分钟允许 20 次 → 骑手可以 1 秒内传 20 次，之后 59 秒静默
正确做法：最小间隔 3 秒 → 距上次上报不足 3 秒直接丢弃（不是报错）
```

而且**丢弃是静默的**——位置数据丢一条无所谓，报错会让客户端疯狂重试，反而放大流量。

### 5.7 阈值怎么定：三条原则

1. **阈值 = 正常峰值的 2–3 倍**。低了误伤，高了等于没限
2. **按业务语义定维度**：下单按"单人下单数"，位置上报按"最小间隔"，查单按"刷新频率"
3. **阈值必须可配、可热更**：上线后看 429 比例调，误伤就调高

### 5.8 回到本项目

你的项目是区域平台量级（日均几千单、几十个骑手），真实 QPS 是个位数到几十。

参数按实际规模配（例如商家下单 `capacity=10, refill=0.2/s`），但**设计方法和上面一致**：

- 分层（虽然你只有服务层）
- 分级（P0 下单/接单不拒绝）
- 位置上报按最小间隔
- fail-open（Redis 挂了放行）

**面试时讲的是"如果规模到 100 万单/天，我会怎么调整"，而不是"我现在配了多少"。**

---

## 6. 实现位置：Spring MVC 拦截器

```java
public class RateLimitInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        RateLimitResult result = rateLimitService.tryAcquire(req);
        if (result.allowed()) return true;
        writeTooManyRequests(resp, result.retryAfterSeconds());
        return false;
    }
}
```

**为什么不用 Filter**：Filter 在 Security 之前，那时还拿不到登录用户（`SecurityContext` 未填充），只能用 IP。

**为什么不用 AOP**：拦截器能直接拿到 `HttpServletResponse`，方便写 `Retry-After` 头。

> 遗留问题：限流拦截器在 Security 之后，意味着**未通过鉴权的请求不会被限流**。这是可接受的——非法请求在 Security 层就被挡住了。但对匿名接口（注册/登录）要确保拦截器仍然生效，需要验证一下拦截器链的顺序。

---

## 7. 响应

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 7
Content-Type: application/json

{"status":429,"message":"请求过于频繁，请 7 秒后重试"}
```

**为什么是 429 不是 500**：429 是标准语义，前端能据此提示"稍后再试"，监控也不会把它算成故障。

---

## 8. Redis 不可用时怎么办（面试必问）

两种策略：

| 策略 | 行为 | 代价 |
|---|---|---|
| **fail-open（采用）** | Redis 挂了就放行 | 可能被刷，但不影响可用性 |
| fail-closed | Redis 挂了全拒绝 | 限流组件变成单点故障 |

**选择 fail-open**：限流是"保护措施"，不该成为"新的故障点"。同时打 WARN 日志 + 埋一个计数器，便于告警。

---

## 9. 代码结构

```
common/ratelimit/
├── RateLimitProperties.java    # 配置绑定（@ConfigurationProperties）
├── RateLimitRule.java          # 单条规则
├── RateLimitResult.java        # allowed + retryAfterSeconds
├── RateLimiter.java            # 执行 Lua 的组件
├── RateLimitService.java       # 解析 scope、拼 key、选规则
├── RateLimitInterceptor.java   # preHandle
└── RateLimitConfig.java        # 注册拦截器
resources/lua/rate_limit_token_bucket.lua
```

---

## 10. 测试计划

| 用例 | 验证点 |
|---|---|
| 容量内连续请求 | 全部通过 |
| 超过容量 | 返回 429 + Retry-After |
| 等待后恢复 | 令牌按速率补充 |
| 不同用户互不影响 | key 隔离正确 |
| 未命中规则用默认值 | 兜底生效 |
| Redis 异常 | fail-open，请求放行 |

单元测试用 Mockito 模拟 `StringRedisTemplate`；真实 Redis 行为用一条手工 curl 验证。

---

## 11. 执行计划

| 步骤 | 内容 | 预计 |
|---|---|---|
| 1 | 配置类 + Lua 脚本 + `RateLimiter` | 3 小时 |
| 2 | `RateLimitService` + `Interceptor` + 注册 | 2 小时 |
| 3 | 单元测试（上表 6 个用例） | 2 小时 |
| 4 | 手工验证：curl 连刷 10 次，看 429 | 0.5 小时 |
| 5 | 文档更新（README + 接口契约补 429） | 1 小时 |

**合计约 1 天**（此前估 0.5 天偏乐观，含测试与文档应为 1 天）。

---

## 12. 明确不做

- 分布式全局限流（跨机房配额）
- 熔断降级（下一阶段再看）
- 引入 Sentinel / Bucket4j（手写 Lua 更能讲清细节，且与已有 `RedisLockService` 风格一致）
