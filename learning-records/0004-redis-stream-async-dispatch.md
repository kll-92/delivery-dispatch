# 学习记录：Redis Stream 异步派单

> 历史课程：2026-09-27 异步通道已迁移到 RocketMQ。本文保留用于对比 Stream 和专业 MQ，不再代表当前运行代码。

## 本次目标

理解 Redis Stream 为什么适合配送订单的异步派单，以及它如何和 MySQL 事务、GEO 候选查询、原子占用组合。

## 已掌握

- Pub/Sub 是实时广播，订阅者离线时消息可能丢失；Stream 是可保留、可确认的消息流。
- `XADD` 追加订单派单事件，`XREADGROUP` 由消费者组读取，`XACK` 在业务成功后确认。
- 创建订单事务中同时插入 `delivery_order` 和 `dispatch_outbox` 待投递事件；后台发布器再写入 Stream。
- 消费者处理失败不 ACK，消息留在 Pending List，后续可再次处理。
- Stream 具有至少一次投递语义，重复事件是正常情况；`dispatchFromStream` 通过订单状态检查实现幂等。
- Stream 只负责可靠传递派单意图；GEO 负责附近候选，MySQL 条件 UPDATE 负责最终派单正确性。
- 消费者宕机后消息会停留在 Pending List；本项目扫描超过 30 秒未推进的 Pending 消息，用 `claim` 重新认领并复用正常处理逻辑。
- 恢复消息仍可能重复执行，所以用订单状态判断保证幂等，而不是追求“绝不重复投递”。
- Outbox 发布顺序是先 XADD、再更新状态；两者间故障会导致重复事件，所以仍需幂等。Redis 不可用时 Outbox 保持待投递并由定时任务重试。

## 项目实现

`dispatch_outbox` 保存尚未投递的事件，发布器每 5 秒批量锁定待投递行并写入 `stream:dispatch:events`。消费者组为 `dispatch-workers`；定时任务每 5 秒先恢复超时 Pending、再处理自身 Pending、最后读取新消息。执行数据库脚本 `src/main/resources/sql/02-outbox.sql` 后才能创建订单并运行 Outbox 发布器。后续生产化可加入退避重试和死信队列。

## 面试表达

订单创建事务提交后发布派单事件到 Redis Stream，由消费者组异步执行 GEO 候选筛选和 MySQL 原子派单；成功后 ACK，重复投递通过订单状态条件判断保证幂等，避免重复分配骑手。
