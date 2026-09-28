# Redis 学习总笔记：同城配送智能调度平台

> 用法：本文件是 Redis 阶段唯一的总复习入口。每完成一课，就在对应章节补充“已学会”和项目代码位置；不要只依赖聊天记录。
>
> 2026-09-27 说明：本文的 Redis Stream 章节是迁移前的学习记录。当前项目已使用 Transactional Outbox + RocketMQ 处理异步派单；Redis 当前职责是 Cache Aside、GEO 和缓存重建锁。

## 学习总路线

- [x] 00. Redis 定位、Docker 环境、MySQL 与 Redis 分工
- [x] 01. String、Key、TTL 与最小命令练习
- [x] 02. Spring Boot `StringRedisTemplate` 与骑手详情缓存
- [x] 03. 缓存旁路模式、缓存一致性、缓存穿透/击穿/雪崩与 Redis 故障降级
- [x] 04. GEO：写入骑手坐标、按距离寻找附近骑手
- [x] 05. 自动派单：附近、可接单、低负载的候选排序
- [x] 06. 并发原子性：MySQL 条件更新；Lua 用于缓存互斥锁安全释放（骑手容量 Lua 预占未实现）
- [x] 07. 持久化、内存淘汰、监控与 Redis 面试复盘（复习资料已整理；本机配置实验暂不执行）

---

## 00. Redis 定位、Docker 环境、MySQL 与 Redis 分工

### 0.1 Redis 是什么

Redis 可以先理解为一个非常快的“键 → 值”数据仓库：通过一个 Key 找到一个 Value。它主要在内存中处理数据，并提供 String、Hash、List、Set、ZSet、GEO 等数据结构；因此不只是“把 MySQL 查询结果缓存一下”。

```text
Key                         Value
rider:detail:2              骑手 2 的详情 JSON
geo:rider:available          当前代码使用的可用骑手 GEO 索引 Key
rider:load:2                假设的接单数缓存 Key（当前项目没有创建它）
```

### 0.2 为什么配送项目需要它

假设 1,000 名骑手每 5 秒上报一次位置：

```text
1,000 / 5 秒 = 每秒 200 次位置写入
```

派单时还要快速找出商家 3 公里内的骑手。若每次上报和距离检索都压到 MySQL，数据库会承担大量高频读写；而位置本身是实时数据，允许短暂不精确。因此把这类数据放 Redis 更合适。

### 0.3 MySQL 与 Redis 的边界

| 问题 | MySQL | Redis |
| --- | --- | --- |
| 是否保存正式订单 | 是，订单最终事实 | 否，不能作为唯一订单依据 |
| 是否保存骑手手机号/姓名 | 是，要求长期可靠 | 通常不作为唯一来源 |
| 是否保存实时位置 | 可以备份或归档 | 是，适合实时查询 |
| 是否保存热点骑手详情 | 最终来源 | 是，减少重复查询 |
| 是否做并发接单计数 | 是，当前项目用条件 UPDATE 作为最终正确来源 | 当前项目不在 Redis 保存接单数，避免 MySQL/Redis 两套容量值需要额外一致性治理 |

**一句话：MySQL 保证“最终正确”；Redis 解决“高频且要快”。**

### 0.4 本项目的 Redis 环境

| 项目 | 值 | 含义 |
| --- | --- | --- |
| 容器名 | `delivery-redis` | 只服务本配送项目 |
| 镜像 | `redis:7.4-alpine` | Redis 服务程序 |
| 宿主机端口 | `6380` | Java 程序在 Mac 上连接它使用的端口 |
| 容器端口 | `6379` | Redis 容器内部默认端口 |
| 数据卷 | `delivery-redis-data` | 删除/重建容器时保留数据 |
| AOF | 已开启（`appendfsync everysec`） | 每秒刷盘一次；提升重启恢复能力，但不是零数据丢失保证 |

端口映射可以读成：

```text
Java 程序 localhost:6380
        ↓ Docker 转发
Redis 容器 6379
```

### 0.5 Spring Boot 里有什么

- `pom.xml` 中的 `spring-boot-starter-data-redis`：引入 Spring Data Redis 和默认客户端 Lettuce。
- `application.yml` 中的 `spring.data.redis.host/port`：告诉 Spring Boot Redis 在哪里。
- 当前项目已用 `StringRedisTemplate` 实现详情缓存、TTL、GEO、Stream 等操作；各功能见后续章节。

### 0.6 面试表达

> 在同城配送场景中，订单、骑手档案和接单容量由 MySQL 保存；Redis 保存骑手实时位置索引、热点详情缓存，并通过 Stream 传递派单事件。接单容量仍由 MySQL 条件更新最终裁决，避免维护两套计数来源。

### 0.7 这一章的易错点

- Redis 快不等于 Redis 永远不会丢数据；内存、持久化策略、故障恢复都需要考虑。
- Redis 不是把所有数据都搬过去；正式订单仍要落 MySQL。
- 本地 `6380` 不是 Redis 默认端口，只是为了避免占用电脑上可能已在使用的 `6379`。

---

## 01. String、Key、TTL 与最小命令练习

### 1.1 String 是什么

Redis String 是最基础的 Value 类型。虽然叫 String，但可以保存文本、数字、JSON、序列化对象等字节数据。项目第一份骑手详情缓存会以 JSON 形式存进去：

```text
Key:   rider:detail:2
Value: {"id":2,"name":"李四"}
```

### 1.2 五条必须会的命令

| 命令 | 作用 | 典型结果 | 项目意义 |
| --- | --- | --- | --- |
| `SET key value` | 写入/覆盖 Value | `OK` | 缓存回填 |
| `GET key` | 读取 Value | Value 或 `(nil)` | 先查缓存 |
| `EXPIRE key 60` | 设置 60 秒过期 | `1` 成功，`0` Key 不存在 | 防止旧缓存永久存在 |
| `TTL key` | 看剩余过期秒数 | 正数、`-1`、`-2` | 排查缓存是否正确过期 |
| `DEL key` | 主动删除 Key | `1` 删除成功，`0` 原本不存在 | 修改数据库后删除旧缓存 |

### 1.3 TTL 的三个返回值

```text
TTL > 0  ：Key 存在，数字是剩余秒数
TTL = -1 ：Key 存在，但没有设置过期时间
TTL = -2 ：Key 已不存在
```

### 1.4 本次实操结果与现象

已完成：写入骑手缓存 → 读取 → 发现 TTL 为 `-1` → 设置 60 秒 TTL → 验证 TTL 为正数 → 删除 → 读取 `(nil)`。

`redis-cli` 中显示的 `\xe6\x9d\x8e...` 是 UTF-8 中文“李四”的字节转义展示，不代表数据损坏。Redis 保存的是字节；默认命令行为了安全显示不可见/非 ASCII 字节，会转义。若只是想看原始展示，可以用：

```bash
docker exec -it delivery-redis redis-cli --raw
```

### 1.5 为什么骑手详情选 String + JSON，而不是 Hash

当前的读法是“一次得到整份骑手详情”，String + JSON 便于 Java 序列化和反序列化。Hash 更适合“按字段读取或修改”，例如只改昵称而不取整份对象。不是 Hash 更高级，而是要匹配读取方式。

### 1.6 后续如何落到 Java

查询骑手详情时，先 `GET rider:detail:{id}`：

```text
有缓存 → 反序列化 JSON → 返回
没有缓存 → 查 MySQL → JSON 写入 Redis（带 TTL）→ 返回
```

修改骑手资料后：先更新 MySQL，再 `DEL rider:detail:{id}`。下次读取自然会从 MySQL 获取新值并回填缓存。

---

## 02. Spring Boot `StringRedisTemplate` 与骑手详情缓存（已完成）

### 2.1 本项目实际代码在做什么

文件：`rider/service/RiderService.java`。

```text
getById(id)
  → Redis GET rider:detail:{id}
  → 命中：JSON 转 Rider，直接返回
  → 未命中：从 MySQL 查 Rider
  → ObjectMapper 转 JSON
  → Redis SET，TTL 600～660 秒随机抖动
  → 返回 Rider
```

- `StringRedisTemplate`：Java 调用 Redis String 命令的工具。
- `ObjectMapper`：在 Java `Rider` 对象与 Redis JSON 字符串之间翻译。
- Key 规则：`rider:detail:{id}`，例如 `rider:detail:1`。
- TTL：代码使用约 600～660 秒随机 TTL；Redis `MONITOR` 中可看到实际 `EX` 秒数。

### 2.2 已完成的 Cache Aside 写流程

更新、停用骑手时不使用缓存中的对象做判断，而是从 MySQL 读取当前数据。数据库字段更新成功后执行：

```java
stringRedisTemplate.delete(RIDER_DETAIL_KEY_PREFIX + id);
```

完整顺序：

```text
更新 MySQL 成功 → DEL rider:detail:{id} → 下一次 GET 查 MySQL 并回填新缓存
```

为什么不先删缓存：若先删 Redis，再有并发请求读到 MySQL 旧值并回填缓存，之后 MySQL 才更新成功，Redis 会保留旧数据直到过期。

### 2.3 本次验证证据

学员使用 Redis `MONITOR` 观察到：

```text
GET rider:detail:1
SET rider:detail:1 ... EX 600
```

这证明 Java 服务确实先查 Redis、未命中后从 MySQL 查询并写入本地 `delivery-redis` 容器；随后完成更新接口和 TTL 验证，确认写操作会删除旧缓存。

### 2.4 Redis 不可用时的降级（已完成代码）

读取、写入、删除 Redis 均捕获 `DataAccessException` 并记录 WARN 日志。Redis 暂时不可用时，查询会回源 MySQL；更新/停用以 MySQL 成功为准，删除缓存失败不会让接口失败。

代价是：删除缓存失败时，旧缓存若仍存在，可能保留到 TTL 过期。因此 Redis 恢复后应结合日志和监控排查；本地项目用 TTL 作为最终兜底。

### 2.5 缓存穿透与空值缓存（已实现，待复习）

对于一个不存在的骑手 ID，例如 `999999`，若每次都执行“Redis 未命中 → MySQL 查不到 → 抛异常”，攻击者或错误客户端可以持续把无效请求打到 MySQL，这叫<strong>缓存穿透</strong>。

解法：第一次查不到时，写入短 TTL 的空字符串：

```text
SET rider:detail:999999 "" EX 60
```

后续 60 秒内读到空字符串，就直接返回“骑手不存在”，不再查询 MySQL。注意：Redis 返回 `null` 表示 Key 不存在；Redis 返回 `""` 表示 Key 存在且数据库已确认不存在，二者不能混为一谈。

### 2.6 缓存雪崩与随机 TTL

缓存雪崩是大量<strong>原本存在</strong>的数据缓存，在相近时刻一起过期，导致大量请求同时回源 MySQL。它不同于缓存穿透：穿透针对不存在的数据。

本项目将正常骑手详情的 TTL 从固定 600 秒改为 `600 ~ 660` 秒随机值，打散缓存失效时间。随机 TTL 只能减少“集体到期”的尖峰；它不能解决 Redis 整体故障，也不能单独解决一个热点 Key 的高并发回源。本项目已用带 token 的互斥锁控制缓存重建，并用 Lua 比较 token 后安全解锁。

### 2.7 缓存击穿（热点 Key）与互斥锁

缓存击穿是<strong>某一个热点 Key</strong>过期时，大量并发请求同时发现缓存未命中，随后同时查询 MySQL。它与雪崩不同：雪崩影响大量 Key，击穿集中在一个或少数热点 Key。

第一层解法是 Redis 互斥锁：只允许一个请求获得 `rebuild:lock:rider:{id}` 并重建缓存；其他请求短暂等待后重新读取 Redis。锁必须带 TTL，防止持锁服务崩溃造成永久死锁。生产环境的释放锁还需 token 校验，避免一个过期请求误删新请求的锁。

### 2.8 Redis GEO：附近骑手候选集

同城配送调度需要快速从实时位置中找到取货点附近的骑手。Redis GEO 的 Key 为 `geo:rider:available`，成员为骑手 ID（如 `rider:12`），坐标为<strong>经度在前、纬度在后</strong>。`GEOADD` 同成员再次写入即更新位置，`GEOSEARCH ... FROMLONLAT ... BYRADIUS ... ASC WITHDIST` 按距离返回附近成员。

GEO 只做实时空间筛选，不能替代业务状态判断：候选人还要经 MySQL 校验骑手状态、在途订单数；最终派单要使用原子占用防止并发抢同一骑手。Redis GEO 底层是 Sorted Set，移除成员使用 `ZREM`。

#### 本项目的 Java 落地

项目提供两个接口：

| 接口 | 职责 | 为什么这样分开 |
| --- | --- | --- |
| `PUT /api/v1/riders/{id}/location` | 更新骑手最后位置，并同步 `geo:rider:available` | 位置上报和派单是两件不同的事 |
| `GET /api/v1/riders/nearby?longitude=...&latitude=...` | 以取货点为圆心，返回距离从近到远的候选骑手 | 返回候选人不等于已经分配 |

写位置时，MySQL 保存最后位置这个<strong>业务事实</strong>；Redis GEO 保存用于快速空间搜索的<strong>索引</strong>。查询时先由 GEO 返回近邻 ID 和距离，再用 MySQL 批量读取骑手并过滤“不是可用状态”的旧索引数据。这个“双层校验”能避免 Redis 位置索引稍有延迟时直接错误派单。

当前项目的自动派单链路是：`取货点 → GEO 候选骑手 → 状态/负载筛选 → MySQL 条件占用骑手 → 条件更新订单`。

### 2.10 同城配送：GEO 候选集与数据库原子派单

自动派单不是“GEO 返回第一个骑手就直接分配”。完整链路为：

```text
订单商家坐标
→ Redis GEO 查询 5 公里内、距离升序的候选骑手
→ MySQL 批量校验骑手仍可用
→ 对候选人逐个执行条件 UPDATE 抢占容量
→ 条件更新订单：待派单 → 已派单
```

核心 SQL 是：

```sql
UPDATE rider
SET current_order_count = current_order_count + 1
WHERE id = ?
  AND status = 1
  AND current_order_count < 3;
```

受影响行数为 `1`，表示当前事务成功占用一个骑手名额；为 `0`，表示该骑手刚被其他订单抢走、已停用，或已经达到 3 单上限。判断条件和加一在<strong>同一条 SQL</strong>中执行，因此不会出现“两个请求都读到 0、最后却只写回 1”的并发更新丢失。

订单更新也使用 `WHERE id = ? AND status = PENDING_DISPATCH`，避免同一订单被重复分配。两条 UPDATE 都位于 `@Transactional` 方法内：若骑手占用成功、但订单状态已被其他请求改变，代码抛出运行时异常，数据库事务回滚，骑手接单量不会被错误加一。

面试表达：<strong>Redis GEO 负责低延迟生成空间候选集；MySQL 条件更新负责最终的并发正确性。Redis 不承担最终派单事实。</strong>

#### 完成订单时的原子释放

派单时用 `current_order_count = current_order_count + 1` 占用名额；订单完成时必须使用对应的条件 UPDATE 释放名额：

```sql
UPDATE rider
SET current_order_count = current_order_count - 1
WHERE id = ? AND current_order_count > 0;
```

订单也用 `WHERE id = ? AND status = DELIVERING` 条件更新为已完成。这样即便配送完成回调重复到达，也只有第一个请求能把订单改为完成并释放容量；其余请求更新 0 行，不会把骑手接单量减成负数。

### 2.11 Redis Stream：异步派单与 Transactional Outbox

Redis Stream 是 Redis 内置的追加式消息流，可用于消息队列。与 Pub/Sub 不同，Stream 的消息会保留；消费者可以组成消费者组，并且只有业务处理成功后才执行确认（ACK）。未确认的消息会留在消费者组的 Pending List 中。

#### Outbox 具体是什么

`Outbox` 字面意思是“发件箱”。Transactional Outbox 模式中，它通常是一张<strong>与业务数据同库的数据库表</strong>，保存应用已经决定要发送、但尚未确认交给消息系统的事件。它不是 Redis Stream，也不是 Spring 的某个专有类；Stream 是发布后供消费者读取的消息通道，Outbox 是 MySQL 中可靠暂存待发送事件的地方。

本项目的 `dispatch_outbox` 记录例如：`order_id=42`、`event_type=DISPATCH_REQUEST`、`status=0`（待投递）。后台成功写入 Redis Stream 后，再把 `status` 改成 `1`（已投递）。可以把它想象成业务档案旁边的“待寄发件托盘”。

#### Outbox 的作用：解决数据库和消息系统的双写缺口

如果先提交订单，再直接调用 Redis `XADD`，两步之间存在失败窗口：MySQL 已经有订单，但 Redis 不可用时派单事件就丢失。先 XADD 再提交也不对，因为订单事务可能回滚，Stream 却已经通知消费者处理一张不存在的订单。这就是跨 MySQL 和 Redis 的“双写”问题。

本项目改用 Transactional Outbox：在创建订单的<strong>同一个 MySQL 事务</strong>中，写入订单和一条 `dispatch_outbox` 待投递记录；后台发布器轮询 Outbox，将事件写入 Stream 后再标记为已投递。

```text
HTTP 创建订单
→ 同一个 MySQL 事务：插入 delivery_order + 插入 Outbox(status=0)
→ 提交：订单和待投递事件同时存在

后台发布器（每 5 秒）
→ SELECT 待投递行 FOR UPDATE SKIP LOCKED
→ XADD 到 stream:dispatch:events
→ 更新 Outbox 为已投递（status=1）
→ 事务提交

消费者组读取 → GEO + MySQL 原子派单 → 成功 XACK
```

这样若订单事务回滚，订单和 Outbox 记录一起消失；若 Redis 写入失败，Outbox 仍是待投递状态，发布器之后重试。`FOR UPDATE SKIP LOCKED` 在多个发布器并行时让它们锁定不同的待处理行，而不是重复拿同一批；它适合队列表的并发领取，不代表整张表被锁住。

仍要认识一个重要边界：若 `XADD` 成功，但数据库还没来得及把 Outbox 标为已投递就发生故障，下一轮可能再次发送同一事件。因此 Outbox 提供的是至少一次投递，不保证绝不重复；`dispatchFromStream` 检查订单状态，保证同一订单不会重复占用骑手。Redis 暂不可用时，Outbox 发布器会按固定轮询周期重试；消费者处理失败则消息留在 Pending 并由恢复逻辑重试。当前教学实现还没有最大重试次数、指数退避或死信流，这是后续生产化增强，不要说成已经实现。

常用命令：`XADD` 追加消息；`XREADGROUP` 由消费者组读取；`XACK` 在业务成功后确认。Stream 是事件通道，MySQL 的订单状态才是订单最终事实。

#### Pending 消息恢复：为什么要 claim

消费者从 Stream 读取消息后，消息会进入当前消费者的 Pending List。若消费者在处理期间宕机而没有执行 `XACK`，消息不会自动回到“新消息”队列，可能一直挂在原消费者名下。

本项目每 5 秒扫描消费者组的 Pending 摘要：只有某条消息超过 30 秒没有再次投递，才使用 `XCLAIM`（Spring Data Redis 的 `claim` 方法）将它转给当前消费者，然后复用正常处理逻辑。30 秒是教学项目的恢复阈值，不是业务固定值；生产环境要根据正常处理耗时、重试策略和告警要求配置。

```text
扫描 Pending
→ elapsed >= 30s？
  → 否：暂不处理，避免抢走正常执行中的消息
  → 是：claim 转移所有权
        → 复用 handleRecord
        → 成功 XACK；失败不 ACK，等待下一轮重试
```

因为 Stream 是至少一次投递，恢复过程可能导致同一事件再次执行；`dispatchFromStream` 先检查订单是否仍是待派单状态，因此重复事件只会被跳过，不会再次占用骑手。

面试表达：<strong>在 MySQL 本地事务中同时保存订单和 Outbox 事件，再由轮询发布器投递到 Redis Stream，避免订单提交成功但派单事件丢失；发布可能重复，所以通过订单状态校验保证消费幂等。</strong>

### 补充对照：黑马点评项目的技术栈

黑马点评是一个以 Redis 实战为主线的 Spring Boot 项目。原版课程的重点不是“堆很多中间件”，而是把 Redis 的不同数据结构和高并发问题放进真实业务：登录、店铺缓存、秒杀、附近商户、签到、点赞、关注推送、UV 统计。

| 技术 / 组件 | 在黑马点评中的用途 | 我们配送项目的对应关系 |
| --- | --- | --- |
| Java、Spring Boot、Spring MVC | 编写 Web 接口、组织业务代码 | 当前项目同样使用 |
| MySQL、MyBatis-Plus | 保存用户、店铺、优惠券、订单等持久数据 | 保存骑手、配送订单等最终事实 |
| Redis String | 验证码、共享 Session、缓存、计数、全局 ID | 骑手详情缓存、空缓存、锁 token |
| Redis Hash | 店铺等对象字段存储（课程会介绍） | 后续可用于骑手实时状态 |
| Redis GEO | 按用户坐标找附近店铺 | 按取货点找附近骑手 |
| Redis BitMap | 用户连续签到统计 | 可扩展为骑手出勤 / 在线打卡 |
| Redis HyperLogLog | UV（独立访客近似去重统计） | 可扩展为调度页面访客统计 |
| Redis Set / ZSet | 点赞、共同关注、排行榜等 | 可扩展为骑手评分或热度排行 |
| Redis Lua | 秒杀资格校验、扣库存、一人一单的原子执行 | 当前用于缓存锁 compare-and-delete；骑手容量最终由 MySQL 条件更新保护 |
| Redis 分布式锁、Redisson | 防止并发重复下单和锁误删 | 当前已学习 token + Lua 安全释放；后续可认识 Redisson |
| Redis Stream | 异步处理秒杀订单、消费者组、失败重试 | 当前用于 Outbox 派单事件、消费者组、Pending 恢复与 ACK |
| Nginx（项目部署配套） | 承载前端静态资源 / 反向代理 | 后期演示部署再加入即可 |

**重要边界：** RabbitMQ 并非黑马点评 Redis 原版课程的核心必选组件。网上许多“黑马点评优化版”会自行加入 RabbitMQ、限流、Redis 集群等，这是二次扩展；原课程的异步秒杀主角是 **Redis Stream**。简历和面试必须如实区分“原项目技术”与“自己扩展技术”。

### 2.12 Redis 持久化、内存淘汰和监控：缓存为什么不自动等于可靠存储

#### 本机 Redis 现状（只读核查）

`delivery-redis` 当前使用具名 Docker volume `delivery-redis-data` 挂载到 `/data`，启动参数开启 AOF（`appendonly yes`），AOF 刷盘策略为 `appendfsync everysec`。突然断电或进程故障时，最近约 1 秒的写入仍可能丢失。当前 Redis `maxmemory=0`（Redis 未设置内存上限）、`maxmemory-policy=noeviction`（不主动淘汰 Key，内存耗尽时写入可能失败）；核查时 `aof_last_write_status=ok`。这些是本机容器的当前值，不等于生产配置建议，也不保证卷被删除、磁盘损坏时还能恢复。

#### Redis 为什么要讨论持久化

Redis 主要在内存里处理数据。若进程或容器退出，内存数据可能消失；Redis 可以将数据保存到磁盘，并在重启时恢复。常见机制：

- **RDB**：在某个时间点制作快照。恢复快、文件较紧凑，但两次快照之间的最近写入可能丢失。
- **AOF**：把写命令追加到日志，重启时回放。通常恢复粒度更细，但日志会变大，需要重写/压缩；最多丢多少仍取决于刷盘策略。
- **混合持久化**：结合快照与 AOF 日志，平衡恢复速度和数据完整性。

它们都不等于“Redis 永不丢数据”。生产仍需备份、告警和恢复演练。

#### 对配送项目有什么影响

骑手详情缓存丢了，可以从 MySQL 回源；GEO 位置索引也可以根据数据库重建。但 Stream 事件更敏感：Outbox 标记 `status=1` 代表发布器收到 Redis 写入成功响应，**不表示今后 Redis 即使丢盘也一定能恢复该消息**。如果 Stream 丢失而 Outbox 仍标为已投递，当前程序没有自动对账并重发的机制。

所以 Outbox 解决的是“订单落库与尝试发布消息的双写窗口”，不能宣称覆盖 Redis 自身数据丢失的所有风险。更严格的生产方案需要消息系统的持久化/复制能力，以及 Outbox 与 Stream 的对账或可重放机制。

#### 内存淘汰（eviction）是什么

Redis 达到 `maxmemory` 后，继续写入数据时可以按策略删除旧 Key，或拒绝新写入。常见策略：

- `noeviction`：不主动删 Key；内存满后拒绝增加内存的写入。
- `allkeys-lru` / `allkeys-lfu`：从所有 Key 中淘汰近期最少使用/访问最少的 Key。
- `volatile-ttl`：只从设置了过期时间的 Key 里，优先淘汰更快过期的 Key。

纯缓存被淘汰后通常可回源 MySQL；本项目缓存和 `stream:dispatch:events` 目前共用一个 Redis 服务，若生产使用会删除任意 Key 的策略，Stream 也可能被淘汰。生产要隔离缓存与消息资源，或明确内存上限、拒写/淘汰策略和告警。**即使本机 AOF 已开启，仍要核对备份、卷生命周期、磁盘空间和故障恢复；不能把“开了 AOF”说成“消息绝不丢”。**

#### 只读观察命令

```text
INFO memory       # used_memory、maxmemory 等内存信息
INFO persistence  # RDB/AOF 状态
INFO stats        # 命中、未命中、连接等统计
INFO keyspace     # Key 数量与过期统计
SLOWLOG GET 10    # 最近慢命令
```

先看内存是否持续上涨、命中率是否下降、是否有淘汰、持久化是否成功、慢命令在哪里，再决定是否调整配置；不要盲目调大内存。

#### 面试表达

> Redis 的缓存和消息数据有不同的丢失恢复策略。缓存丢了可以回源/重建；Stream 消息若在 Outbox 已标为发布后又因 Redis 数据丢失，当前 Outbox 不会自动回到待投递，因此还需要持久化、副本和对账重放。Outbox 并不消除 Redis 自身的所有数据丢失风险。

**学习边界：**这里只解释已观察到的原理，不更改本机 Docker Redis 的持久化或淘汰设置，以免影响其他项目共用的环境。要做配置实验，先核对容器卷与数据影响。

---

## 术语表

| 术语 | 白话解释 |
| --- | --- |
| Key | 查找数据时使用的名字，如 `rider:detail:2`。 |
| Value | Key 对应保存的内容，如骑手详情 JSON。 |
| TTL | Time To Live，Key 离自动过期还剩多少秒。 |
| 缓存命中 | 要的数据已经在 Redis，直接返回。 |
| 缓存未命中 | Redis 没有数据，需要查询 MySQL 后回填。 |
| 原子操作 | 多个并发请求同时执行时，操作不会被拆开插队，例如 `INCR`。 |
| GEO | Redis 中专门用于保存经纬度、按距离搜索成员的能力。 |
| Pending（Stream） | 消费者已经收到、但还没有 ACK 确认的消息；可能正在处理，也可能处理失败或消费者崩溃。 |

---

## 资料

- [Redis 官方数据类型文档](https://redis.io/docs/latest/develop/data-types/)
- [Redis 官方 String 文档](https://redis.io/docs/latest/develop/data-types/strings/)
- [Transactional Outbox 模式（Chris Richardson）](https://microservices.io/patterns/data/transactional-outbox.html)
- [MySQL 8.4 锁定读与 SKIP LOCKED](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- [黑马程序员 Redis 入门到实战](https://www.bilibili.com/video/BV1cr4y1671t/)

学习时遇到不懂的词，直接把该词或截图发给我；我会先用项目里的例子解释，再补到本笔记。
