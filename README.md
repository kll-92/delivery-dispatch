# 同城即时配送智能调度与履约平台

一个面向 Java 后端实习求职的配送调度项目：包括骑手与订单基础业务、Redis Cache Aside、Redis GEO 附近骑手候选、MySQL 条件更新并发保护，以及通过 Transactional Outbox + RocketMQ 实现的异步派单。Redis 继续负责缓存、GEO 和缓存重建锁，不再承担消息队列职责。

## 运行项目

本地 MySQL 已使用 Docker 在 `3307` 端口启动。不要把真实密码提交到仓库；先复制 `.env.example` 的变量到 IDE 的运行配置，或在终端临时设置：

```bash
export DB_PASSWORD='你的本地数据库密码'
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

## 基础业务闭环（Swagger 测试顺序）

1. `POST /api/v1/riders` 创建一名可接单骑手。
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

## 测试

```bash
mvn test
```

当前测试包含订单服务、骑手并发边界、Outbox 发布器和 RocketMQ 消费者单元测试，共 13 项。2026-09-27 已在 JDK 21 下执行通过（13/13）；`pom.xml` 通过 Surefire 在测试 JVM 启动时显式加载 Mockito Agent，避免运行期自附加失败。

2026-09-27 完成 RocketMQ 真实本地闭环验收：创建订单 `id=5` 后，Outbox `0 → 1`，Topic 产生 1 条消息，消费组 `Diff=0` 且 `Inflight=0`，订单异步派给骑手 2，然后完整流转 `0 → 1 → 2 → 3`，骑手接单数 `0 → 1 → 0`。原 Redis Stream 实现已从运行代码中移除。

## JVM 运行检查

本地验收使用 JDK 21 默认 G1。Actuator 显示应用健康状态为 `UP`，检查时 JVM 已用内存约 159 MB，仅观察到 1 次年轻代 GC，暂停约 3 ms；`jcmd GC.heap_info` 显示实际已提交堆约 108 MB。当前没有内存、GC 暂停或吞吐异常证据，因此没有盲目设置 `-Xms/-Xmx` 或更换垃圾收集器。若未来压测出现问题，应先用 Actuator、`jcmd`、GC 日志和线程栈定位，再按证据调优。

本机 Maven 的全局和用户级 `settings.xml` 仍有第 53 行 XML 格式警告，但不阻断当前编译、测试和运行；这是本机工具配置问题，不属于项目业务代码。

> Actuator 的 `metrics` 用于本地学习观测。生产部署时不要在未鉴权的公网接口暴露管理指标。

## 学习、进度和验收资料

- [当前进度与最后验收顺序](LEARNING_ROADMAP.md)
- [框架、知识点与面试复习单](PROJECT_KNOWLEDGE_REVIEW.md)
- [Redis 分课总笔记](REDIS_STUDY_NOTES.md)
- [离线总指导书](OFFLINE_PROJECT_GUIDE.md)
