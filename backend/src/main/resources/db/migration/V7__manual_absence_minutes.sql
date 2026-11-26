ALTER TABLE attendance_audit
    MODIFY event_type ENUM('SCAN', 'STATUS_CHANGE', 'DAILY_CLOSE', 'ACCOUNT_RESET', 'MANUAL_ABSENCE_MINUTES') NOT NULL,
    ADD COLUMN request_id CHAR(36) NULL,
    ADD UNIQUE INDEX uq_attendance_audit_request (attendance_id, request_id);
