CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    action VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT,
    actor VARCHAR(64) NOT NULL,
    before_json JSON,
    after_json JSON,
    reason VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_operation_log_target (target_type, target_id, id),
    KEY idx_operation_log_actor_action (actor, action, id)
);
