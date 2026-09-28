# 同城配送智能调度平台：框架与知识点总复习

> 按 2026-09-27 工作区代码整理。它记录“当前真的写了什么”，不是理想化的生产架构。Redis Stream 已被 RocketMQ 替代；旧 Stream 课程只是历史学习记录。

## 1. 先用一句话说清项目

这是一个同城配送后端：管理骑手和订单，利用 Redis GEO 找附近骑手，用 MySQL 条件更新和事务保护派单正确性，并通过 MySQL Outbox + RocketMQ 把“创建订单”和“异步派单”连接起来。

MySQL 是业务事实和并发正确性的最终来源；Redis 用于加速查询、建立位置候选索引和保护缓存重建锁；RocketMQ 负责异步事件通道。

## 2. 请求从哪里进、最后落在哪里

### 普通 HTTP 请求

```text
客户端 / Swagger
  → Spring MVC Controller：解析 URL、PathVariable、RequestBody
  → Service：业务校验、状态机、事务边界和业务流程
  → Mapper（MyBatis-Plus / MyBatis）：生成或执行 SQL
  → MySQL：持久化业务状态
  → JSON 响应返回客户端
```

Controller 不应该塞满 SQL 和复杂业务规则；Service 不应该依赖 Swagger；Mapper 是 Java 业务代码与数据库 SQL 之间的边界。

### 创建订单到异步派单

```text
POST /orders
  → 一个 MySQL 事务：插入 delivery_order + dispatch_outbox(status=0)
  → 每 5 秒运行的发布器：锁定待投递 Outbox 行，同步发送到 RocketMQ
  → Broker 返回 SEND_OK 后：Outbox status 改为 1
  → delivery-dispatch-workers 消费组读取事件：用 GEO 找附近候选、再由 MySQL 条件更新抢占骑手和订单
  → 正常返回表示消费成功；抛出异常由 RocketMQ 重试，超过 5 次进入 DLQ
  → GET /orders/{id} 查看最终订单状态
```

**两个状态不要混为一谈：**Outbox `status=1` 只说明 Broker 已接收事件；订单 `status=1` 才表示订单已派单。

订单状态当前为：`0 待派单 → 1 已派单 → 2 配送中 → 3 已完成`；待派单订单可以取消为 `4 已取消`。

## 3. 框架、工具和它们在项目里的职责

| 技术 | 在项目中做什么 | 你要能解释的重点 |
| --- | --- | --- |
| Java 21 | 编写实体、业务服务、集合处理、异常处理 | 类型、对象、接口、泛型、异常、集合；`Long` 对应 BIGINT 主键，`BigDecimal` 适合金额/坐标精度 |
| Spring Boot 3.5.7 | 启动应用、自动装配组件、管理 Bean | `@SpringBootApplication`、依赖注入、组件扫描；`@Service` / `@RestController` 如何变成 Spring Bean |
| Spring MVC + 内嵌 Tomcat | 接收 HTTP 并路由到 Controller | Tomcat 是 HTTP/Web 容器；Swagger 是 API 文档与调试界面，两者不是替代关系 |
| Spring Validation | POM 中包含请求校验 starter | 当前 Controller 主要接收实体/请求对象，核心业务非空规则在 Service 手工校验；尚未系统改造成 DTO + `@Valid` 校验 |
| Spring `@Transactional` | 把多条 MySQL 更新放在同一个本地事务中 | 事务提交/回滚；同一事务内一条失败时，已执行的数据库更新可以回滚；它不把 Redis 和 MySQL 变成一个分布式事务 |
| Spring `@Scheduled` | 定时轮询 Outbox | 当前发布器周期为 5 秒；RocketMQ 消费者不需要定时拉取代码 |
| MyBatis-Plus 3.5.12 | 提供 `BaseMapper` CRUD、条件构造器和实体映射 | 简单 CRUD 用框架；并发关键逻辑保留显式条件 SQL，不用“先查后无条件更新” |
| MySQL 8 / InnoDB | 保存骑手、订单和 Outbox，是业务最终事实来源 | 主键、唯一键、索引、事务、行锁、条件更新；影响行数 1 通常表示本次占用成功，0 表示条件已不满足 |
| Spring Data Redis + `StringRedisTemplate` | 通过 Spring API 操作 Redis | String 缓存与 TTL、GEO 和分布式锁；已不再承担消息队列 |
| Redis GEO | 以经纬度做附近骑手候选搜索 | 省去扫描全体骑手算距离；候选仍要回 MySQL 核实状态/容量，GEO 不是最终资格判断 |
| RocketMQ 5.3.2 | 保存并投递派单事件 | Topic/Tag 路由，Consumer Group 集群消费，失败重试和 DLQ；当前没有使用 RabbitMQ/Kafka |
| Redis Lua | 将“比较锁 token 并删除锁”作为一次原子操作 | 只用于缓存重建锁安全释放；当前骑手接单容量没有用 Redis Lua 预占 |
| springdoc-openapi / Swagger UI | 从 Controller 接口生成 API 文档并手动发请求 | 它帮助看和调 API；不是 Web 服务器，也不负责实现业务 |
| Spring Boot Actuator | 暴露健康状态和运行指标 | 当前配置开放 `health,info,metrics`；重启后观察 `/actuator/metrics`，不要无鉴权公开到公网 |
| Lombok | 减少构造器、getter/setter 等样板代码 | 例如 `@RequiredArgsConstructor` 给 `final` 字段生成构造器，Spring 据此注入依赖 |
| Maven | 管理依赖、编译、测试和打包 | `pom.xml` 声明依赖；`mvn test` 执行自动化测试 |
| Docker Desktop | 本地启动 MySQL、Redis、RocketMQ | RocketMQ NameServer `19876 → 9876`，Broker `20911 → 20911` |
| JUnit 5 + Mockito | 写自动化单元测试、替换外部 Mapper/Redis 依赖 | 单元测试验证 Java 分支和交互；它不等于真实数据库/Redis 集成测试或压测 |

### 几个容易说错的地方

- **Tomcat ≠ Swagger。**Tomcat 接 HTTP；Swagger 描述和试调用 HTTP API。
- **MyBatis-Plus ≠ MySQL。**前者是 ORM/持久层框架；后者才真正保存并执行 SQL。
- **Spring 事务 ≠ 全链路分布式事务。**`@Transactional` 管理 MySQL 事务；它不能同时原子提交 RocketMQ 发送。
- **RocketMQ 成功收到 ≠ 业务已完成。**Broker 保存消息后，消费者仍可能派单失败。
- **测试通过 ≠ 线上高并发证明。**当前 13 个测试通过；还没有真实并发基准或可引用的 QPS/性能提升数据。

## 4. Redis 部分的四条主线

### 骑手详情缓存：Cache Aside

项目的读路径是先读 `rider:detail:{id}`，有有效缓存时直接返回；未命中时再读 MySQL 并回填 Redis。不存在的骑手写短 TTL 空值，普通详情使用约 600～660 秒随机 TTL。并发缓存重建用带过期时间的 token 锁，释放时用 Lua 校验“锁仍归当前 token 所有”才删除，避免误删别人的锁。

写路径以 MySQL 为准，数据库修改成功后删除对应详情缓存。Redis 读取失败时记录日志并回源数据库；Redis 不是可用性单点。缓存击穿锁只把同一个热点键的回源者收敛为一个，不代表所有业务请求被锁住。

### 附近骑手：GEO 是候选索引

骑手上报位置时先持久化 MySQL，再更新 Redis GEO 位置索引，并失效详情缓存。查询附近时 GEO 按中心点与半径找候选，再由 MySQL 根据骑手状态和接单数过滤。**为什么还要 GEO？**普通字段只保存“某骑手最新坐标”，GEO 索引提供“谁在这个圆附近”的空间查询能力；它不是存储业务资格状态的替代品。

### 容量保护：MySQL 条件更新才是最终闸门

骑手 Mapper 的更新核心形态是：

```sql
UPDATE rider
SET current_order_count = current_order_count + 1
WHERE id = ? AND status = ? AND current_order_count < 3;
```

数据库在同一行上协调并发更新。只有影响 1 行，才表示占位成功；影响 0 行时，骑手可能停用、已满载或不存在。订单同样使用 `WHERE status = 待派单` 的条件更新，避免重复绑定。两条派单更新放在 MySQL 事务中：后续订单更新失败会抛异常并回滚前面的骑手占位。开始配送、取消订单和停用骑手也用状态/接单数条件作为最终写闸门；资料/位置更新只写允许修改的列，避免把并发变化的接单数整行覆盖。

### 异步派单：Transactional Outbox + RocketMQ

订单与 Outbox 同时写进 MySQL，避免“订单提交了但消息没记下来”。发布器按批次锁定 Outbox 待发送行（`FOR UPDATE SKIP LOCKED`），调用 `syncSend` 并确认 `SEND_OK` 后才标记已投递。这是**至少一次投递**：Broker 收到消息、Outbox 尚未更新时进程故障，会导致下次重复发送。消费者先检查订单是否仍待派单，避免重复占用骑手；异常向上抛出交给 RocketMQ 重试，超过 5 次进入 `%DLQ%delivery-dispatch-workers`。

当前是单消费者教学配置，Outbox/消费者固定间隔轮询。尚无最大重试次数、指数退避、死信流或人工补偿台；这属于可选生产化增强，当前不能声称已实现。

## 5. 一组关键代码应该怎样讲

### 订单创建

`DeliveryOrderService.create(order)` 先校验必要业务字段，生成订单号并设成待派单，然后在 `@Transactional` 内插入订单和 `dispatch_outbox` 待投递事件。方法成功返回只表示 MySQL 事务提交；派单是后台稍后发生的。

### 候选骑手与原子派单

`DeliveryOrderService.dispatch(orderId)` 用商家坐标查询附近候选并按距离/负载排序，再逐个尝试占用。它不是“查到一个人就保证能分配”：候选查询之后状态仍可能变化，因此最后必须靠 `tryClaimForOrder` 条件 SQL 决定能不能拿下；拿下骑手名额后，还要用 `assignIfPending` 更新订单。

### RocketMQ 消费和幂等

`DispatchRocketMqConsumer.onMessage(json)` 反序列化事件、校验类型，再调用 `dispatchFromMessage(orderId)`。正常返回时 RocketMQ 认为消费成功；抛出异常时触发重试。重复事件到达时，非待派单订单直接返回，避免重复占用骑手。

### 错误响应

业务非法参数目前由 `GlobalExceptionHandler` 转为 HTTP 400；未预期异常转为通用 HTTP 500。不要把内部堆栈或数据库敏感细节直接暴露给客户端。

## 6. 自动化检查与当前边界

测试代码共 13 项，覆盖订单创建、重复事件幂等、订单状态竞态、骑手容量与缓存失效、Outbox 发布成功/失败，以及 RocketMQ 消费者的正常、忽略和重试分支。2026-09-27 `mvn test` 在 JDK 21 下 13/13 通过。

本机 Maven 的全局和用户级 `settings.xml` 仍有第 53 行 XML 格式警告，但不阻断项目编译和测试。Mockito 运行期自附加问题已由测试 JVM 启动参数解决。

2026-09-27 完成 RocketMQ 端到端联调：NameServer/Broker 启动成功，Outbox `0 → 1`，Topic 产生 1 条消息，消费组 `Diff=0/Inflight=0`，消费者将订单 5 派给骑手 2，订单再流转至完成，骑手容量 `0 → 1 → 0`。Docker 权限初始化也已加入 Compose，避免 UID 3000 无法写入持久化卷。

Actuator 已验证 `health=UP`，JVM metrics 可读取。检查时 JVM 使用 JDK 21 默认 G1，已用内存约 159 MB，年轻代 GC 暂停约 3 ms，`jcmd` 显示实际提交堆约 108 MB。没有证据表明需要调整堆参数或收集器，因此本轮正确选择是保留默认配置并建立观测基线，而不是制造“调优成果”。

## 7. 可直接用于面试的项目介绍（验收后按结果微调）

> 我做了一个同城配送智能调度后端。订单创建时，在一个 MySQL 事务里同时写订单和 Outbox 事件，再由发布器投递到 RocketMQ；消费者异步查询 Redis GEO 附近骑手并派单。骑手容量和订单状态由 MySQL 条件更新和事务保护；消息采用至少一次语义，用订单状态检查保证幂等，失败时由 RocketMQ 重试并在超限后进入 DLQ。Redis 仅负责 Cache Aside、GEO 和锁。当前 13 项自动化测试通过，并已完成 MySQL、Redis、Outbox、RocketMQ 的本地端到端联调。

当前可以如实写“完成本地端到端联调”，但没有真实并发压测数据，仍不能说百万 QPS、提升 80% 等结论。

## 8. 面试前的自测问题

1. 画出 `Controller → Service → Mapper → MySQL`，每层各负责什么？
2. Tomcat、Spring MVC、Swagger UI 分别解决什么问题？
3. 为什么缓存命中不查 MySQL？缓存更新为什么常用“更新库后删缓存”？
4. 缓存穿透、击穿、雪崩各是什么，项目各用了什么缓解办法？这些办法各自不能解决什么？
5. GEO 里有坐标为什么还要回 MySQL？候选查询和资格判断为什么分开？
6. 两个线程同时派同一个骑手，`current_order_count < 3` 在数据库中解决了什么竞态？
7. `@Transactional` 包住了哪些表更新？Redis 为什么不随 MySQL 一起回滚？
8. “先插订单再发消息”有什么故障窗口？Outbox 如何缩小这个窗口？
9. 为什么 Outbox 仍可能重复投递？RocketMQ 如何重试，DLQ 解决什么问题？
10. 订单已派单后再收到重复 RocketMQ 事件，为什么不会再次占骑手？
11. Redis 宕机、附近没有骑手、MySQL 条件更新返回 0 分别会怎样？
12. 当前方案还缺哪些生产能力？至少能说出重试退避/上限、死信与告警、消费组多实例、真实集成测试/压测。

## 9. 2026-09-25 最后验收记录

1. 当前代码启动成功，Actuator `health=UP`，`metrics` 和 JVM 指标可访问。
2. `dispatch_outbox` 表已按 `02-outbox.sql` 创建。
3. 创建坐标靠近商家的验收骑手和订单后，订单从待派单自动变为已派单。
4. 对应 Outbox 行从 `status=0` 变为 `status=1`，RocketMQ 消费组最终 `Diff=0`、`Inflight=0`。
5. 开始配送和完成接口通过，订单状态完整走完 `0 → 1 → 2 → 3`。
6. 首轮发现骑手容量在 MySQL 已回到 0，但 Redis 详情缓存仍为 1；已补充容量变化后的缓存失效，并用第二条订单复验为 `0 → 1 → 0`。
7. `mvn test` 13/13 通过。下一阶段是简历撰写、项目讲解训练和模拟面试；真实并发压测仍为可选增强。
