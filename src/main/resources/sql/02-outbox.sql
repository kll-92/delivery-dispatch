CREATE TABLE IF NOT EXISTS dispatch_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=待投递，1=已投递',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_dispatch_outbox_status_id (status, id),
    KEY idx_dispatch_outbox_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
