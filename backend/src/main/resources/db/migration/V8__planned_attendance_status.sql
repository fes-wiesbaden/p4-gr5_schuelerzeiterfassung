CREATE TABLE planned_attendance_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NOT NULL,
    status ENUM('ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT') NOT NULL,
    changed_by_staff_id BIGINT NOT NULL,
    deletion_date_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT chk_planned_attendance_period CHECK (starts_at < ends_at),
    CONSTRAINT fk_planned_attendance_student FOREIGN KEY (student_id) REFERENCES student(id),
    CONSTRAINT fk_planned_attendance_staff FOREIGN KEY (changed_by_staff_id) REFERENCES staff(id),
    CONSTRAINT fk_planned_attendance_deletion_date FOREIGN KEY (deletion_date_id) REFERENCES deletion_date(id),
    INDEX idx_planned_attendance_student_date (student_id, attendance_date, starts_at)
);
