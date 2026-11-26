# 同城即时配送调度平台

**商家下单 → 平台派单 → 骑手履约**，三端可真实操作的完整业务闭环。

后端 Spring Boot 3.5 / MySQL / Redis / RocketMQ，前端 Vue 3 + TypeScript + Element Plus。

## 三端闭环

| 端 | 角色 | 能做什么 |
|---|---|---|
| 商家端 | `MERCHANT` | 自助注册开店、下单、查看自己的订单 |
| 平台端 | `ADMIN` / `DISPATCHER` | 订单管理、手动/自动派单、骑手管理、看板、操作审计 |
| 骑手端 | `RIDER` | 接单、开始配送、送达、上报位置 |

订单状态机：`0 待派单 → 1 已派单 → 2 配送中 → 3 已完成`（另有 `4 已取消`）

## 技术栈

| 分类 | 技术 |
|---|---|
| 框架 | Spring Boot 3.5、Spring MVC、Spring Security（Session + CSRF） |
| 数据 | MySQL 8.4、MyBatis-Plus |
| 缓存 | Redis 7.4（Cache Aside、GEO 索引、Lua 脚本） |
| 消息 | RocketMQ 5.3（Transactional Outbox） |
| 可观测 | Actuator、springdoc（Swagger）、traceId（MDC） |
| 测试 | JUnit 5、Mockito、MockMvc，113 个用例 |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus |
| 运维 | Docker、Docker Compose |

## 关键设计

1. **并发正确性**：派单用条件更新（`WHERE current_order_count < 3` 与 `WHERE status = 0`），抢单失败时整个事务回滚，已占用的名额随之释放。100 并发抢同一骑手，结果恰好 3 单成功、97 单拒绝。
2. **最终一致性**：业务与事件在同一 MySQL 事务写入 Outbox，后台投递 RocketMQ，Broker 确认后才标记已投递；消费失败由 Broker 重试，超额进死信；另有定时补偿扫描兜底。
3. **缓存治理**：Cache Aside + 空值缓存（穿透）+ 随机 TTL（雪崩）+ 互斥重建（击穿）；GEO 只是派生索引，可由定时校准恢复。
4. **接口限流**：Redis + Lua 实现令牌桶（原子补充与扣减），按用户 / IP 分维度，P0–P3 业务分级，超限返回 `429` + `Retry-After`；位置上报另用「最小间隔」语义限流。
5. **链路追踪**：traceId 在请求入口生成并写入 MDC，随 Outbox 记录持久化、经 MQ 消息传递，消费端恢复后同一条链路日志可串联。
6. **权限与数据归属**：四种角色 + 资源级校验；商家端和骑手端接口**不接收** `merchantId` / `riderId`，身份一律从登录态解析，跨租户访问返回 `403`。

## 实测数据

| 指标 | 数值 | 来源 |
|---|---|---|
| 单元测试 | 113 个全通过 | `mvn test` |
| 骑手详情接口 | 1712 rps，P99 86.57 ms | `verification/` |
| GEO 附近查询 | 1126 rps，P99 66.72 ms | `verification/` |
| 100 并发抢同一骑手 | 3 成功 / 97 拒绝，零超卖 | `verification/` |
| 删除 vs 派单竞态 | 30 轮零容量泄漏 | `verification/` |
| Outbox → 派单完成 | 4190 ms | `verification/` |

## 快速开始

### 方式一：Docker（整套，含前端）

```bash
docker compose up -d
```

启动 6 个容器：MySQL、Redis、RocketMQ（NameServer + Broker）、应用、前端 Nginx。
首次启动自动建表（`src/main/resources/sql` 挂载为初始化脚本）。

| 入口 | 地址 |
|---|---|
| 前端 | http://localhost:3000 |
| 后端 API | http://localhost:8080/api/v1 |
| Swagger | http://localhost:8080/swagger-ui.html |

> 默认数据库密码来自 `DB_PASSWORD` 环境变量；不设置时为 `Delivery123!`。
> 端口若冲突可改 `docker-compose.yml` 里的 `ports` 映射。

### 方式二：本地开发（前端热更新）

后端跑在本机，中间件用 Docker：

```bash
export DB_PASSWORD='你的本地数据库密码'
export BOOTSTRAP_ADMIN_PASSWORD='至少八位'
export REDIS_PORT=6380
mvn spring-boot:run
```

```bash
cd frontend-vue
npm install
npm run dev        # http://localhost:5173
```

管理员账号由 `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` 在首次启动时创建；
商家可自助注册，骑手由平台端创建。

## 本地环境说明

应用依赖 MySQL、Redis 和 RocketMQ。整套环境可以用 `docker compose up -d` 起（见上方「快速开始」）；如果只想把中间件放进 Docker、应用跑在本机（便于热更新），用 `docker compose -f docker-compose.rocketmq.yml up -d` 起 RocketMQ，MySQL 和 Redis 用本机已有的实例。默认连接 MySQL `localhost:3307` 上的 `delivery_dispatch` 数据库（用户 `delivery_user`），以及 Redis `localhost:6380`。数据库、MySQL 用户和授权需要自行创建，项目不会自动创建它们。如果本机端口、库名或用户名不同，请设置 `DB_URL`、`DB_USERNAME`、`REDIS_HOST` 和 `REDIS_PORT`。当前 Redis 配置没有密码项，需要使用未启用密码的本地 Redis。

`DB_PASSWORD` 填本机 MySQL 账号的密码；`BOOTSTRAP_ADMIN_PASSWORD` 是应用首次初始化管理员账号时使用的登录密码，两者不是同一个密码。把这些值填入 IDE 的运行配置，或在启动应用的终端中设置。`.env.example` 只提供变量名和示例值，Spring Boot 不会自动读取它；不要把真实密码提交到仓库。

```bash
export DB_PASSWORD='你的本地数据库密码'
export BOOTSTRAP_ADMIN_USERNAME='admin'
export BOOTSTRAP_ADMIN_PASSWORD='自行设置至少八位的本地密码'
docker compose -f docker-compose.rocketmq.yml up -d
mvn spring-boot:run
```

RocketMQ 的 Docker 环境使用 NameServer `19876` 和 Broker `20911`，避开本机已有的 `9876/10911` 端口。首次启动会由 Broker 自动创建 Topic；也可显式创建：

```bash
docker exec delivery-rocketmq-broker sh mqadmin updateTopic \
  -n rocketmq-namesrv:9876 -c DefaultCluster \
  -t delivery-dispatch-events
```

启动后访问：

- `http://localhost:8080/api/v1/ping`
- `http://localhost:8080/swagger-ui.html`
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/actuator/metrics`

如果 Actuator 首页没有 `metrics` 链接，先在 IDEA 停止旧的 Spring Boot 进程，再重新启动；改配置不会自动作用于已运行的 JVM。

## 数据库脚本

- 先创建 `delivery_dispatch` 数据库，再执行 `src/main/resources/sql/01-schema.sql` 创建业务表。
- Transactional Outbox 功能增加后，已有数据库还需在 `delivery_dispatch` 数据库中执行一次 `src/main/resources/sql/02-outbox.sql`，创建派单事件表。该脚本使用 `CREATE TABLE IF NOT EXISTS`，可安全重复执行。
- 登录和操作记录功能还需要依次执行 `src/main/resources/sql/03-accounts.sql`、`src/main/resources/sql/04-operation-log.sql`。两个脚本均可重复执行，不会删除既有骑手或订单。
- 骑手位置有效期功能需要执行 `src/main/resources/sql/05-rider-location.sql`；它检查列是否存在后再添加 `last_location_at`，可重复运行。旧骑手的位置时间保持 `NULL`，不能凭已有坐标推断其位置仍有效，需要重新上报位置。
- 首次启动且账号表为空时，应用用 `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` 初始化管理员；数据库只保存 BCrypt 密码摘要。已有账号后，修改这两个环境变量不会重置密码。

## 登录、角色和管理操作

前端未登录时展示本地演示数据；登录成功后才读取真实业务接口。匿名访客的演示操作仅修改浏览器中的演示数据。

| 身份 | 能力 |
| --- | --- |
| 访客 | 浏览和操作本地演示数据 |
| 调度员 `DISPATCHER` | 查看真实骑手和订单，创建订单、派单、开始配送、完成和取消待派单订单 |
| 管理员 `ADMIN` | 调度员的全部能力；管理骑手和账号、修改配送费、删除订单、查看操作记录 |

使用 Session 登录：`GET /api/v1/auth/csrf` 取得令牌，再调用 `POST /api/v1/auth/login`，请求体为 `{ "username": "你的账号", "password": "你的密码" }`。写请求需带令牌返回的 `headerName` 和 `token`，并保留会话 Cookie；登录后重新获取 CSRF 令牌。前端已自动处理这些步骤。`GET /api/v1/auth/me` 查看当前身份，`POST /api/v1/auth/logout` 退出。未登录返回 401，权限不足返回 403。

停用账号后，旧会话也会在后续请求中失效。管理员不能停用自己或最后一个启用的管理员。账号、订单和骑手操作记录与业务修改使用同一个 MySQL 事务；操作失败回滚时不会留下成功记录。后台派单的操作者记录为 `SYSTEM`。

管理员仍可删除任何状态的订单。删除已派单或配送中订单会释放骑手名额，独立的操作记录保留删除人、原因和删除前快照。配送费允许为 0，最多两位小数，范围为 `0～99999999.99`。

## 分页与查询

- `GET /api/v1/orders/page`：`page`、`size`、`status`、`orderNo`、`createdFrom`、`createdTo`。
- `GET /api/v1/riders/page`：`page`、`size`、`status`、`keyword`。
- `GET /api/v1/operation-logs/page`：管理员查询，支持 `targetType`、`targetId`、`actor`、`action`。
- `GET /api/v1/dashboard/summary`：按全库汇总订单状态和骑手数量，不受当前页和筛选条件影响；可接单骑手指启用且接单数低于容量上限的骑手，位置新鲜度过滤留待下一阶段。

分页响应统一为 `{ "records": [], "total": 0, "page": 1, "size": 20 }`，`page` 从 1 开始，`size` 在 1～100 之间。数据库连接统一使用 UTC；接口的日期时间字段和创建时间筛选参数为 UTC 的无时区 ISO 字符串，例如 `2026-10-07T00:00:00`。前端将接口时间转换为浏览器本地时间，时间选择器也按本地时间填写，再转换成 UTC 查询。列表按 ID 倒序展示最近记录。旧数组列表接口为脚本兼容保留，最多返回 200 条，页面使用分页接口。

## 基础业务闭环（Swagger 测试顺序）

1. 管理员登录后，`POST /api/v1/riders` 创建一名可接单骑手。骑手停用后可调用 `PATCH /api/v1/riders/{id}/enable` 恢复接单；位置上报使用 `PUT /api/v1/riders/{id}/location`。
2. `POST /api/v1/orders` 创建订单。接口返回时订单可能仍是状态 `0`（待派单）；Outbox 发布器和 RocketMQ 消费者会异步尝试派单。稍等后用 `GET /api/v1/orders/{id}` 查看最终结果。
3. 异步派单成功后，订单为 `1`（已派单），再调用 `PATCH /api/v1/orders/{orderId}/start-delivery`：订单 `1 -> 2`（配送中）。
4. `PATCH /api/v1/orders/{orderId}/complete`：订单 `2 -> 3`（已完成），并将骑手接单数减 1。

`PATCH /api/v1/orders/{orderId}/assign/{riderId}` 和 `PATCH /api/v1/orders/{orderId}/dispatch` 是额外的手动/同步派单接口。正常演示异步链路时不要在订单创建后再调用它们，以免和后台消费者争抢同一订单。
仅在待派单阶段允许调用 `PATCH /api/v1/orders/{orderId}/cancel`，订单状态从 `0` 变为 `4`（已取消）。

创建订单还会在同一个 MySQL 事务中创建 Outbox 待投递记录。后台发布器将事件同步发送到 RocketMQ，Broker 确认 `SEND_OK` 后才将 Outbox 标记为已投递；消费者再异步尝试派单。**Outbox 已投递不等于订单已派单**；应同时查看 Outbox 状态和订单状态。

| 订单状态 | 含义 |
| --- | --- |
| `0` | 待派单 |
| `1` | 已派单 |
| `2` | 配送中 |
| `3` | 已完成 |
| `4` | 已取消 |

## 骑手位置有效期与 GEO 恢复

MySQL 中的 `last_location_at` 是独立的位置上报时间（UTC，微秒精度），不同于资料修改时间 `updated_at`。客户端不能指定或延长它：

- 创建骑手时带有效坐标，算首次位置提交；不带坐标则没有位置时间。
- `PUT /api/v1/riders/{id}/location` 是位置上报，即使坐标没变也更新时间。
- 修改资料时只有坐标真正变化才更新时间；修改姓名、电话、启用或停用骑手不会让旧位置变新。

默认有效期为 300 秒，达到或超过有效期（位置年龄 ≥ TTL）、没有上报时间、时间在未来、坐标非法、已停用或满载的骑手都不参与派单。附近查询只用 Redis GEO 召回候选，再用 MySQL 的位置事实校验，并按数据库坐标重新计算距离。Redis 坐标陈旧时可能暂时漏掉候选，但不能凭旧索引给不合格骑手派单。

手动与自动派单共用最终校验：先锁住骑手当前行，锁等待结束后再读数据库时间；名额条件更新继续检查状态、容量和位置时间，自动派单还检查实际距离。订单条件更新之后再次检查位置是否在等待订单锁时过期，失败则整个事务回滚，包括刚占用的名额。手动指派不强制自动派单的 5 公里范围，但同样要求位置有效。

GEO 是派生索引，不是位置事实。事务提交后只把骑手 ID 交给有界后台队列（单工作线程、最多 100 个等待任务），避免请求线程在事务资源清理前等待另一个数据库连接。同步在每骑手 Redis 锁内以新事务读取数据库最新状态，再由 Lua 校验锁 token 后原子添加或移除 GEO 成员。过期锁持有者不能覆盖新同步；队列满或 Redis 同步失败只记录失败，由定时恢复兜底，不把已提交的位置修改伪装成回滚。

应用启动时执行一批校准，随后定时循环扫描数据库 ID 和 GEO 成员：恢复仍有效且可调度的骑手，清理过期、停用、满载及数据库中不存在的成员。不删除整个 GEO key，不刷新位置时间，也不把整个索引设置为一个统一 TTL。Redis 不可用时保留数据库事实，恢复连接后靠后续扫描收敛；这是最终一致的恢复机制，不是 MySQL 与 Redis 的分布式事务。

| 环境变量 | 默认值 | 含义 |
| --- | ---: | --- |
| `RIDER_LOCATION_TTL_SECONDS` | 300 | 位置有效期（秒） |
| `RIDER_GEO_RECOVERY_ENABLED` | true | 是否执行启动/定时恢复 |
| `RIDER_GEO_RECOVERY_DELAY_MS` | 30000 | 恢复批次间隔（毫秒） |
| `RIDER_GEO_RECOVERY_BATCH_SIZE` | 100 | 每轮数据库及 GEO 各处理的骑手数预算 |

时间和批次配置必须为正数。全库校准需要多轮，恢复耗时与骑手数量、批次大小和间隔有关；调大数据量时应据此调整，而不是期待一轮恢复所有骑手。目前实现针对现有单实例 Redis，未增加 Redis Cluster 或新的中间件。

## 消息链路排查

查看 Topic 队列偏移量：

```bash
docker exec delivery-rocketmq-broker sh mqadmin topicStatus \
  -n rocketmq-namesrv:9876 -t delivery-dispatch-events
```

查看消费组是否有积压（`Diff=0` 表示当前没有待消费消息）：

```bash
docker exec delivery-rocketmq-broker sh mqadmin consumerProgress \
  -n rocketmq-namesrv:9876 -g delivery-dispatch-workers
```

在 IDEA 数据库控制台查看 Outbox：

```sql
SELECT id, order_id, event_type, status, created_at, published_at
FROM dispatch_outbox
ORDER BY id DESC
LIMIT 20;
```

`status=0` 表示待投递，`status=1` 表示 RocketMQ Broker 已确认接收。消费者随后是否成功派单，要查看订单的 `status` 和 `rider_id`。消费失败会由 RocketMQ 重试，超过 5 次后进入死信队列。

已投递但仍待派单超过 30 秒的订单，还会由补偿任务每 30 秒扫描一次，每轮最多处理 20 条并按订单 ID 轮转。附近没有骑手时保留待派单状态，下轮继续尝试；骑手上线后可自动派单。可用 `DISPATCH_PENDING_RECOVERY_ENABLED=false` 暂停补偿，或用 `DISPATCH_PENDING_RECOVERY_DELAY_MS` 调整扫描间隔；`/actuator/metrics/dispatch.recovery.attempts` 可查看处理、无骑手和异常次数。

## 接口限流

`common/ratelimit/` 实现基于 Redis 的令牌桶：

- **算法**：令牌桶（允许短时突发，避免固定窗口的临界突刺）
- **原子性**：Redis + Lua，把「补令牌 → 判断 → 扣减 → 写回」一次执行，避免并发超发
- **时钟**：Lua 内取 `redis.call('TIME')`，多实例下不受机器时钟偏差影响
- **维度**：已登录按账号 id，匿名按 IP；key 里用规则的 path 模板而非真实 URI（否则每个订单 id 会生成一个独立的桶）
- **分级**：规则带 `P0`–`P3` 优先级，过载时按业务价值依次牺牲
- **降级**：Redis 不可用时 **fail-open**（放行并记 WARN），限流组件不成为新的故障点
- **语义化限流**：位置上报用 `SETNX` 实现「最小间隔 3 秒」，间隔内静默丢弃（不报错，避免客户端重试放大流量）

超限响应：`429` + `Retry-After` + `X-RateLimit-Limit-Level`。阈值在 `application.yml` 的 `rate-limit.rules` 配置，
压测时可用 `RATE_LIMIT_ENABLED=false` 整体关闭。完整设计见 [`docs/rate-limit-design.md`](docs/rate-limit-design.md)。

## 链路追踪（traceId）

```
HTTP 请求 → TraceIdFilter 生成/继承 → MDC[traceId]
              ↓
         业务日志自动带 [traceId]
              ↓
     下单时写入 dispatch_outbox.trace_id
              ↓
     发布到 MQ 的 DispatchMessage 带上 traceId
              ↓
     消费端 TraceContext.put() 恢复 → 消费日志同一条链路
```

- 请求头 `X-Trace-Id` 可透传（便于跨服务串联），无则生成 16 位短 id；响应头回写该 id，便于用户反馈问题时定位
- 换线程（MQ 消费、定时任务）MDC 会丢失，必须手动恢复，并在 `finally` 清理，避免污染线程池
- 日志格式：`logging.pattern.console` 中的 `%X{traceId}`

## 前端（Vue 3 + TypeScript）

```
frontend-vue/
├── src/api/http.ts        axios 封装：自动带 CSRF、401/403/429 统一提示
├── src/router/index.ts    三端路由 + 登录态与角色守卫
└── src/views/
    ├── LoginView.vue           登录
    ├── MerchantOrdersView.vue  商家下单与订单列表
    ├── AdminOrdersView.vue     平台订单列表与派单
    └── RiderWorkbenchView.vue  骑手工作台
```

- **CSRF**：写请求需带 `X-CSRF-TOKEN`；登录会重建会话并轮换 token，前端登录后重新获取一次
- **路由守卫**：角色不匹配时跳回自己的首页；但真正的权限在后端，前端守卫只是体验层
- **429**：按 `Retry-After` 提示用户等待，不自动重试

## 测试

```bash
mvn test
```

2026-10-07 本阶段最终在 JDK 21 下执行 `mvn test`，96 项测试全部通过，无失败或跳过（第一阶段基线为 69 项）。覆盖登录与权限、账号安全、请求校验、分页、审计事务、全库统计，以及订单并发、Redis 故障回源、位置有效期、GEO 校准、补偿扫描和消息组件。`pom.xml` 通过 Surefire 在测试 JVM 启动时显式加载 Mockito Agent，避免运行期自附加失败。

位置阶段的详细验收见 [`verification/location-geo-acceptance-2026-10-07.md`](verification/location-geo-acceptance-2026-10-07.md)。使用独立 MySQL 验证库、专用 Redis 容器和 18082 端口，实际验证了位置过期过滤、等待行锁后过期的事务回滚、GEO 丢失与 Redis 重启恢复；另有 16 个独立会话对 100 单争抢同一骑手，结果是 3 个成功、97 个拒绝，完成后容量回到 0。第一阶段的 18 组真实接口回归也全部通过。前端本轮未修改，`npm run build` 回归通过；真实 MQ 投递没有在本轮重测。

第一阶段真实接口验收结果见 [`verification/stage1-smoke-2026-10-07.json`](verification/stage1-smoke-2026-10-07.json)：18 组检查通过，包括匿名 401、越权 403、CSRF 拒绝、DTO 防止篡改服务端字段、分页及时间筛选、全库统计、骑手启停、配送容量 `0 → 1 → 0`、各状态订单修改费用/删除及审计、停用账号使旧会话失效。临时让审计表不可用后，创建订单返回 500，订单和 Outbox 行数均未增加，验证了真实事务回滚。

该轮使用独立的临时 MySQL 数据库、Redis database 15 和端口 18081；关闭消息发布/消费与补偿扫描，未修改原业务数据库。`--dispatch.messaging.enabled=false` 仅用于隔离验收，默认仍启用 RocketMQ 链路；这轮不代表重新验证了真实 MQ 投递或生产容量。复跑脚本 [`scripts/stage1_smoke.py`](scripts/stage1_smoke.py) 必须指定 `delivery_dispatch_verify_*` 隔离库和 `verify-*` 管理员，所需变量是 `STAGE1_ADMIN_USERNAME`、`STAGE1_ADMIN_PASSWORD`、`MYSQL_USER`、`MYSQL_PASSWORD`。

浏览器联动验收见 [`verification/stage1-ui-2026-10-07.md`](verification/stage1-ui-2026-10-07.md)：管理员登录、连续输入并保存金额、审计前后快照、会话失效回到演示模式、本地时间筛选均已检查。前端 `npm run build` 通过。验收使用的临时服务与数据已清理；当前 IDEA 中原有进程仍是旧版本，需要执行新增 SQL 脚本、配置首次管理员密码并重启后才能使用新版登录功能。

2026-09-27 完成 RocketMQ 真实本地闭环验收：创建订单 `id=5` 后，Outbox `0 → 1`，Topic 产生 1 条消息，消费组 `Diff=0` 且 `Inflight=0`，订单异步派给骑手 2，然后完整流转 `0 → 1 → 2 → 3`，骑手接单数 `0 → 1 → 0`。原 Redis Stream 实现已从运行代码中移除。

### 本地并发与异步链路验证

2026-10-06 对修改后的代码运行了 [`scripts/concurrency_smoke.py`](scripts/concurrency_smoke.py)，结果见 [`verification/concurrency-smoke-after-fixes-2026-10-06.json`](verification/concurrency-smoke-after-fixes-2026-10-06.json)。环境为 Apple M3 Pro、JDK 21.0.7、MySQL 8.4、Redis 7.4、RocketMQ 5.3.2；新代码运行在 localhost:8081，原应用仍运行在 8080，骑手详情缓存预热后开始计时。

| 场景 | 并发请求 | 结果 |
| --- | ---: | --- |
| 骑手详情（Redis 缓存命中） | 64 worker，1000 次 | 1000/1000 HTTP 200；约 1713 req/s；p95 56.60 ms，p99 86.57 ms |
| 附近骑手（Redis GEO + MySQL 资格过滤） | 64 worker，500 次 | 500/500 HTTP 200；约 1127 req/s；p95 61.84 ms，p99 66.72 ms |
| 同骑手并发派单 | 100 worker，100 个不同订单同时抢 1 名骑手 | 3 个 HTTP 200、97 个 HTTP 400；数据库接单数从 0 到 3，没有超过上限；清理后回到 0 |
| 删除与派单竞态 | 30 轮同时发起删除与派单 | 30 轮结束后骑手接单数均为 0，无临时订单残留 |
| 异步履约闭环 | 1 个订单 | 本次观察到 Outbox/RocketMQ 约 4.19 秒完成派单；状态 `0 → 1 → 2 → 3`；骑手接单数 `0 → 1 → 0`（单次观测，时延受 5 秒轮询周期影响） |

补偿路径另用 [`scripts/recovery_smoke.py`](scripts/recovery_smoke.py) 验证，结果见 [`verification/recovery-smoke-2026-10-06.json`](verification/recovery-smoke-2026-10-06.json)：一条已发布但没有骑手的待派单订单先被扫描到；临时骑手上线后约 30 秒自动派单，再完成 `0 → 1 → 2 → 3`，骑手容量恢复为 0，临时数据已清理。

Redis 故障回源另用一个连接到不可用 Redis 端口的临时应用实例验证，结果见 [`verification/redis-failover-2026-10-06.json`](verification/redis-failover-2026-10-06.json)：Actuator 健康状态为 `DOWN` 时，100 次并发骑手详情请求仍全部返回 HTTP 200，数据来自 MySQL。临时应用进程已关闭，原 8080 应用仍保持运行。

重新启动应用加载本轮代码后可复跑（脚本会创建临时订单并自动删除；默认使用空闲骑手 `id=2`，压测期间请勿让其他人操作该骑手）：

```bash
export DISPATCH_TEST_USERNAME='你的管理员账号'
export DISPATCH_TEST_PASSWORD='该账号的密码'
python3 scripts/concurrency_smoke.py --rider-id 2 --output verification/concurrency-smoke-local.json
python3 scripts/recovery_smoke.py --output verification/recovery-smoke-local.json
```

这些是 localhost、小数据量、短时验证结果，适合作为实现正确性的证据和后续性能基线；不能直接外推为生产环境容量或持续 QPS。10 月 6 日的压测早于登录鉴权改动，目前每次已登录请求会校验账号启用状态，因此旧 QPS 也不能直接当作新版本性能。新旧两次测试使用了不同的 JVM 进程，不能据此计算性能提升或下降。可在简历中写“100 路并发争抢下，MySQL 条件更新将骑手接单数严格限制在 3；增加了订单删除竞态保护和待派单补偿扫描”。

## JVM 运行检查

本地验收使用 JDK 21 默认 G1。Actuator 显示应用健康状态为 `UP`，检查时 JVM 已用内存约 159 MB，仅观察到 1 次年轻代 GC，暂停约 3 ms；`jcmd GC.heap_info` 显示实际已提交堆约 108 MB。当前没有内存、GC 暂停或吞吐异常证据，因此没有盲目设置 `-Xms/-Xmx` 或更换垃圾收集器。若未来压测出现问题，应先用 Actuator、`jcmd`、GC 日志和线程栈定位，再按证据调优。

本机 Maven 的全局和用户级 `settings.xml` 仍有第 53 行 XML 格式警告，但不阻断当前编译、测试和运行；这是本机工具配置问题，不属于项目业务代码。

> Actuator 的 `metrics` 用于本地学习观测。生产部署时不要在未鉴权的公网接口暴露管理指标。
