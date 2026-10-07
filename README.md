# 同城即时配送智能调度与履约平台

一个面向 Java 后端实习求职的配送调度项目：包括骑手与订单基础业务、Redis Cache Aside、Redis GEO 附近骑手候选、MySQL 条件更新并发保护，以及通过 Transactional Outbox + RocketMQ 实现的异步派单。Redis 继续负责缓存、GEO 和缓存重建锁，不再承担消息队列职责。

## 运行项目

本地 MySQL 已使用 Docker 在 `3307` 端口启动。不要把真实密码提交到仓库；先复制 `.env.example` 的变量到 IDE 的运行配置，或在终端临时设置：

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

- 首次建库时执行 `src/main/resources/sql/01-schema.sql`。
- Transactional Outbox 功能增加后，已有数据库还需在 `delivery_dispatch` 数据库中执行一次 `src/main/resources/sql/02-outbox.sql`，创建派单事件表。该脚本使用 `CREATE TABLE IF NOT EXISTS`，可安全重复执行。
- 登录和操作记录功能还需要依次执行 `src/main/resources/sql/03-accounts.sql`、`src/main/resources/sql/04-operation-log.sql`。两个脚本均可重复执行，不会删除既有骑手或订单。
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

## 测试

```bash
mvn test
```

2026-10-07 在 JDK 21 下执行 `mvn test`，69 项测试全部通过，无失败或跳过。覆盖登录与权限、账号安全、请求校验、分页、审计事务、全库统计，以及原有订单并发、Redis 故障回源、GEO、补偿扫描和消息组件。`pom.xml` 通过 Surefire 在测试 JVM 启动时显式加载 Mockito Agent，避免运行期自附加失败。

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

## 后续开发顺序

本轮补全登录权限、分页查询、请求 DTO 和操作记录。后续依次完善骑手位置有效期及 GEO 重建、失败派单处理和人工重试、SSE 订单状态推送，再补 Testcontainers 与 CI 的可复现验证。课程及学习笔记已由用户移出本仓库；项目运行和接口说明以本 README 为入口。
