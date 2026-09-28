# 学习记录：RocketMQ 异步派单

## 为什么从 Redis Stream 迁移

Redis 在本项目中保留缓存、GEO 和锁的职责，异步事件改由专门的消息中间件 RocketMQ 承担。RocketMQ 直接提供 Topic/Tag 路由、消费组、失败重试和死信队列，项目不再自己扫描 Redis Pending 并 claim 消息。

## 当前链路

```text
创建订单
  → 同一 MySQL 事务写 delivery_order + dispatch_outbox(status=0)
  → DispatchOutboxPublisher 锁定待投递行
  → syncSend 到 delivery-dispatch-events:dispatch-request
  → Broker 返回 SEND_OK，Outbox 改为 status=1
  → delivery-dispatch-workers 消费组接收消息
  → GEO 找候选骑手 + MySQL 条件更新派单
  → 正常返回表示成功；异常触发重试，超过 5 次进入 DLQ
```

## 四个核心名词

- **NameServer**：像地址簿，告诉客户端 Topic 在哪个 Broker。它不执行派单业务。
- **Broker**：真正接收、保存和投递消息的服务。
- **Topic + Tag**：Topic 是一类消息的主题，Tag 是主题内的进一步筛选。当前是 `delivery-dispatch-events:dispatch-request`。
- **Consumer Group**：同组多实例共同分担消息。当前组名为 `delivery-dispatch-workers`。

## 可靠性边界

Outbox 解决“MySQL 订单已提交，但发消息失败”时事件可以继续重试的问题，但不保证永不重复。若 Broker 已经收到消息，而 Outbox 还没来得及改为 1 应用就崩溃，下次会再发一次。因此消费者使用订单状态做幂等：只有待派单才继续，已派单直接结束。

DLQ 只是把多次失败的消息隔离起来，不会自动修复业务。真正上线还应对 DLQ 做告警、原因分类和人工重放。

## 当前验收

2026-09-27 本地启动 RocketMQ 5.3.2，创建订单 5 后 Outbox `0 → 1`，Topic 偏移量增加 1，消费组 `Diff=0/Inflight=0`，订单异步派给骑手 2，随后流转至完成并归还骑手容量。
