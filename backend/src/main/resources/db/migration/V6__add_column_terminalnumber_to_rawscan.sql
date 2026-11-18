ALTER TABLE terminal MODIFY terminal_id BIGINT;
ALTER TABLE raw_scan ADD COLUMN terminal_id BIGINT;