CREATE TABLE staff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    username VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(60) NOT NULL,
    role ENUM('LEHRKRAFT', 'ADMINISTRATOR') NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE timetable (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE block_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_block_plan_dates CHECK (ends_on >= starts_on)
);

CREATE TABLE school_class (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_code VARCHAR(30) NOT NULL UNIQUE,
    class_teacher_id BIGINT NOT NULL,
    block_plan_id BIGINT NOT NULL,
    timetable_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_school_class_teacher FOREIGN KEY (class_teacher_id) REFERENCES staff(id),
    CONSTRAINT fk_school_class_block_plan FOREIGN KEY (block_plan_id) REFERENCES block_plan(id),
    CONSTRAINT fk_school_class_timetable FOREIGN KEY (timetable_id) REFERENCES timetable(id),
    INDEX idx_school_class_block_plan (block_plan_id),
    INDEX idx_school_class_timetable (timetable_id)
);

CREATE TABLE student (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    birth_date DATE NOT NULL,
    school_class_id BIGINT NOT NULL,
    rfid_uid VARCHAR(64) UNIQUE,
    unexcused_minutes_account INT NOT NULL DEFAULT 0 CHECK (unexcused_minutes_account >= 0),
    excused_minutes_account INT NOT NULL DEFAULT 0 CHECK (excused_minutes_account >= 0),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_student_class FOREIGN KEY (school_class_id) REFERENCES school_class(id)
);

CREATE TABLE teacher_class (
    staff_id BIGINT NOT NULL,
    school_class_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (staff_id, school_class_id),
    CONSTRAINT fk_teacher_class_staff FOREIGN KEY (staff_id) REFERENCES staff(id),
    CONSTRAINT fk_teacher_class_class FOREIGN KEY (school_class_id) REFERENCES school_class(id)
);

CREATE TABLE room (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_number VARCHAR(30) NOT NULL UNIQUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE terminal (
    terminal_id INT PRIMARY KEY,
    room_id BIGINT NOT NULL UNIQUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_terminal_room FOREIGN KEY (room_id) REFERENCES room(id)
);

CREATE TABLE timetable_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timetable_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    weekday ENUM('MONTAG', 'DIENSTAG', 'MITTWOCH', 'DONNERSTAG', 'FREITAG') NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_timetable_slot_time CHECK (end_time > start_time),
    CONSTRAINT fk_timetable_slot_timetable FOREIGN KEY (timetable_id) REFERENCES timetable(id),
    CONSTRAINT fk_timetable_slot_room FOREIGN KEY (room_id) REFERENCES room(id),
    INDEX idx_timetable_slot_timetable_weekday_time (timetable_id, weekday, start_time, end_time)
);

CREATE TABLE block_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    block_plan_id BIGINT NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_block_assignment_dates CHECK (ends_on >= starts_on),
    CONSTRAINT fk_block_assignment_block_plan FOREIGN KEY (block_plan_id) REFERENCES block_plan(id),
    INDEX idx_block_assignment_plan_dates (block_plan_id, starts_on, ends_on)
);

CREATE TABLE class_block_assignment (
    school_class_id BIGINT NOT NULL,
    block_assignment_id BIGINT NOT NULL,
    PRIMARY KEY (school_class_id, block_assignment_id),
    CONSTRAINT fk_class_block_assignment_class FOREIGN KEY (school_class_id) REFERENCES school_class(id),
    CONSTRAINT fk_class_block_assignment_block FOREIGN KEY (block_assignment_id) REFERENCES block_assignment(id)
);

CREATE TABLE attendance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    status ENUM('ANWESEND', 'ABWESEND', 'BETRIEB', 'BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT') NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_attendance_student_date UNIQUE (student_id, attendance_date),
    CONSTRAINT fk_attendance_student FOREIGN KEY (student_id) REFERENCES student(id)
);

CREATE TABLE deletion_date (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    delete_after DATE NOT NULL UNIQUE
);

CREATE TABLE attendance_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    attendance_id BIGINT NULL,
    deletion_date_id BIGINT NOT NULL,
    event_type ENUM('SCAN', 'STATUS_CHANGE', 'DAILY_CLOSE', 'ACCOUNT_RESET') NOT NULL,
    changed_by_staff_id BIGINT NULL,
    terminal_id INT NULL,
    old_status ENUM('ANWESEND', 'ABWESEND', 'BETRIEB', 'BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT') NULL,
    new_status ENUM('ANWESEND', 'ABWESEND', 'BETRIEB', 'BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT') NULL,
    occurred_at DATETIME NOT NULL,
    unexcused_minutes_delta INT NOT NULL DEFAULT 0,
    excused_minutes_delta INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_attendance_audit_student FOREIGN KEY (student_id) REFERENCES student(id),
    CONSTRAINT fk_attendance_audit_attendance FOREIGN KEY (attendance_id) REFERENCES attendance(id),
    CONSTRAINT fk_attendance_audit_deletion_date FOREIGN KEY (deletion_date_id) REFERENCES deletion_date(id),
    CONSTRAINT fk_attendance_audit_staff FOREIGN KEY (changed_by_staff_id) REFERENCES staff(id),
    CONSTRAINT fk_attendance_audit_terminal FOREIGN KEY (terminal_id) REFERENCES terminal(terminal_id),
    CONSTRAINT chk_attendance_audit_status_pair CHECK (
        (old_status IS NULL AND new_status IS NULL)
        OR (old_status IS NOT NULL AND new_status IS NOT NULL AND old_status <> new_status)
    ),
    CONSTRAINT chk_attendance_audit_actor CHECK (
        (event_type = 'SCAN' AND changed_by_staff_id IS NULL AND terminal_id IS NOT NULL)
        OR (event_type IN ('STATUS_CHANGE', 'ACCOUNT_RESET') AND changed_by_staff_id IS NOT NULL AND terminal_id IS NULL)
        OR (event_type = 'DAILY_CLOSE' AND changed_by_staff_id IS NULL AND terminal_id IS NULL)
    ),
    CONSTRAINT chk_attendance_audit_content CHECK (
        (event_type = 'ACCOUNT_RESET' AND attendance_id IS NULL AND old_status IS NULL AND new_status IS NULL)
        OR (event_type <> 'ACCOUNT_RESET' AND attendance_id IS NOT NULL AND (
            old_status IS NOT NULL OR unexcused_minutes_delta <> 0 OR excused_minutes_delta <> 0
        ))
    ),
    INDEX idx_attendance_audit_attendance (attendance_id),
    INDEX idx_attendance_audit_deletion_date (deletion_date_id),
    INDEX idx_attendance_audit_occurred_at (occurred_at)
);

CREATE TABLE raw_scan (
    scan_id CHAR(36) PRIMARY KEY,
    rfid_uid VARCHAR(64) NOT NULL,
    scanned_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_raw_scan_time CHECK (scanned_at <= created_at)
);
