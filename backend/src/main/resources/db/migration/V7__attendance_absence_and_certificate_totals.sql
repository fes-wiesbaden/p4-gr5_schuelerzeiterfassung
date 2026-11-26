ALTER TABLE attendance_audit
    MODIFY event_type ENUM('SCAN', 'STATUS_CHANGE', 'DAILY_CLOSE', 'ACCOUNT_RESET', 'MANUAL_ABSENCE_MINUTES', 'TIMED_EXCUSAL') NOT NULL,
    ADD COLUMN request_id CHAR(36) NULL,
    ADD UNIQUE INDEX uq_attendance_audit_request (attendance_id, request_id),
    ADD COLUMN excusal_ends_at DATETIME NULL,
    ADD COLUMN excusal_status ENUM('ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT') NULL,
    ADD COLUMN excusal_reason VARCHAR(250) NULL,
    ADD CONSTRAINT chk_attendance_audit_excusal
        CHECK (
            (event_type <> 'TIMED_EXCUSAL' AND excusal_ends_at IS NULL
                AND excusal_status IS NULL AND excusal_reason IS NULL)
            OR (event_type = 'TIMED_EXCUSAL' AND excusal_ends_at IS NOT NULL
                AND excusal_ends_at > occurred_at AND attendance_id IS NOT NULL
                AND changed_by_staff_id IS NOT NULL AND terminal_id IS NULL
                AND old_status IS NULL AND new_status IS NULL)
        );

ALTER TABLE attendance
    ADD COLUMN full_absence ENUM('ABWESEND', 'ENTSCHULDIGT') NULL,
    ADD COLUMN full_unexcused_minutes INT NOT NULL DEFAULT 0,
    ADD COLUMN full_excused_minutes INT NOT NULL DEFAULT 0;

ALTER TABLE student
    ADD COLUMN unexcused_days_account INT NOT NULL DEFAULT 0,
    ADD COLUMN excused_days_account INT NOT NULL DEFAULT 0,
    ADD COLUMN full_unexcused_minutes_account INT NOT NULL DEFAULT 0,
    ADD COLUMN full_excused_minutes_account INT NOT NULL DEFAULT 0;
