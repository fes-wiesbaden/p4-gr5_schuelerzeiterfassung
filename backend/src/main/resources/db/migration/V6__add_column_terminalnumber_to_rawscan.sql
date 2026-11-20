ALTER TABLE attendance_audit DROP FOREIGN KEY fk_attendance_audit_terminal;
ALTER TABLE terminal MODIFY terminal_id BIGINT NOT NULL;
ALTER TABLE attendance_audit MODIFY terminal_id BIGINT NOT NULL;
ALTER TABLE raw_scan ADD COLUMN terminal_id BIGINT;
ALTER TABLE attendance_audit ADD CONSTRAINT fk_attendance_audit_terminal
    FOREIGN KEY(terminal_id) REFERENCES terminal(terminal_id);