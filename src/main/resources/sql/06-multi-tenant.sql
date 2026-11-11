-- 06-multi-tenant.sql
-- 三端改造：商家实体、订单归属、骑手登录身份。
-- 可重复执行：MySQL 8.4 不支持 ADD COLUMN IF NOT EXISTS，因此先查 information_schema 再决定是否执行 DDL。
-- 全部为新表/可空新列，不影响已有数据：旧订单 merchant_id 为 NULL，旧骑手 account_id 为 NULL。

-- ① 账号显示名（商家即店铺名，骑手即姓名）
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_account' AND COLUMN_NAME = 'display_name'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE app_account ADD COLUMN display_name VARCHAR(64) NULL AFTER username',
    'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ② 商家实体表
--    account_id 唯一：一个登录账号对应一个店铺
--    name/phone/address/坐标 是"当前资料"；订单里仍保留同名快照字段，因为订单是历史凭证
create table if not exists merchant(
    id BIGINT not null auto_increment,
    account_id BIGINT not null,
    name varchar(64) not null,
    phone varchar(20) not null,
    address varchar(255) not null,
    longitude decimal(10,7) not null,
    latitude decimal(10,7) not null,
    enabled tinyint not null default 1,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,

    primary key (id),
    unique key uk_merchant_account(account_id),
    key idx_merchant_name(name)
);

-- ③ 订单归属：指向 merchant.id。快照字段（merchant_name/phone/address）保留不动。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_order' AND COLUMN_NAME = 'merchant_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE delivery_order ADD COLUMN merchant_id BIGINT NULL AFTER id',
    'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_order' AND INDEX_NAME = 'idx_order_merchant'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_order_merchant ON delivery_order (merchant_id)',
    'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ④ 骑手绑定登录账号。唯一索引允许多个 NULL，所以未绑定账号的旧骑手不受影响。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND COLUMN_NAME = 'account_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE rider ADD COLUMN account_id BIGINT NULL AFTER id',
    'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND INDEX_NAME = 'uk_rider_account'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE UNIQUE INDEX uk_rider_account ON rider (account_id)',
    'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 旧数据说明：
--   · 已有订单 merchant_id 为 NULL，属于"平台代下单"；商家端只查 merchant_id = 自己的店铺，因此看不到旧订单。
--   · 已有骑手 account_id 为 NULL，无法登录骑手端；需要管理员为其生成账号后绑定。
