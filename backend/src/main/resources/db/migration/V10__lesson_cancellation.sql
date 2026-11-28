CREATE TABLE lesson_cancellation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT NOT NULL,
    lesson_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uq_lesson_cancellation UNIQUE (class_id, lesson_date, start_time),
    CONSTRAINT chk_lesson_cancellation_times CHECK (start_time < end_time),
    CONSTRAINT fk_lesson_cancellation_class
        FOREIGN KEY (class_id) REFERENCES school_class(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_cancellation_staff
        FOREIGN KEY (created_by) REFERENCES staff(id) ON DELETE SET NULL
);

ALTER TABLE attendance_audit
    MODIFY event_type ENUM('SCAN', 'STATUS_CHANGE', 'DAILY_CLOSE', 'ACCOUNT_RESET', 'MANUAL_ABSENCE_MINUTES', 'TIMED_EXCUSAL', 'LESSON_CANCELLATION') NOT NULL;
