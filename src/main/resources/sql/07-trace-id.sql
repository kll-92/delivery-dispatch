-- 07-trace-id.sql
-- 给 Outbox 记录加 trace_id：下单时的 traceId 存下来，异步发布/消费时恢复，
-- 这样「下单请求」和「派单完成」的日志能串成一条线。
-- 可重复执行：先查 information_schema 再决定是否执行 DDL。

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_outbox' AND COLUMN_NAME = 'trace_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE dispatch_outbox ADD COLUMN trace_id VARCHAR(32) NULL AFTER event_type',
    'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 旧数据 trace_id 为 NULL：消费端会跳过恢复，日志显示 [-] 而不是报错。
