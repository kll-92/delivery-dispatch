-- Add nullable location freshness metadata without fabricating freshness for existing rows.
-- MySQL 8.4 does not support ADD COLUMN IF NOT EXISTS, so inspect metadata and prepare one DDL.
SET @rider_location_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rider'
      AND COLUMN_NAME = 'last_location_at'
);
SET @rider_location_ddl := IF(
    @rider_location_column_exists = 0,
    'ALTER TABLE rider ADD COLUMN last_location_at DATETIME(6) NULL AFTER latitude',
    'SELECT 1'
);
PREPARE rider_location_migration FROM @rider_location_ddl;
EXECUTE rider_location_migration;
DEALLOCATE PREPARE rider_location_migration;
