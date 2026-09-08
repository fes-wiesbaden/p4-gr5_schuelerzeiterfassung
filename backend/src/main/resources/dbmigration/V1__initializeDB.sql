CREATE TABLE staff (
    staff_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(30) NOT NULL,
    last_name VARCHAR(30) NOT NULL,
    username VARCHAR(40) NOT NULL UNIQUE,
    password_hash VARCHAR(60) NOT NULL,
    role ENUM('LEHRKRAFT', 'ADMINISTRATOR') NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE class (
    class_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_code VARCHAR(30) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    class_teacher_id BIGINT	NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_class_staff
        FOREIGN KEY (class_teacher_id)
        REFERENCES staff(id)
);

CREATE TABLE student (
    student_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(30) NOT NULL,
    last_name VARCHAR(30) NOT NULL,
    birth_date DATE NOT NULL,
    class_id BIGINT NOT NULL,
    rfid_uid VARCHAR(64) UNIQUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_student_class
        FOREIGN KEY (class_id)
        REFERENCES class(class_id)
);

CREATE TABLE staff_class (
    staff_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    PRIMARY KEY (staff_id, class_id),
    CONSTRAINT fk_staff_class_staff
        FOREIGN KEY (staff_id)
        REFERENCES staff(staff_id),
    CONSTRAINT fk_staff_class_class
        FOREIGN KEY (class_id)
        REFERENCES class(class_id)
);

CREATE TABLE room (
    room_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_number VARCHAR(10) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE terminal (
    terminal_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    terminal_number INT NOT NULL UNIQUE,
    room_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_terminal_room
        FOREIGN KEY (room_id)
        REFERENCES room(room_id)
);

CREATE TABLE timetable (
    timetable_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL CHECK (valid_to > valid_from),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE timetable_slot (
    timetable_slot_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    timetable_id BIGINT	NOT NULL,
    room_id BIGINT NOT NULL,
    weekday ENUM('MONTAG', 'DIENSTAG', 'MITTWOCH', 'DONNERSTAG', 'FREITAG')	NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL CHECK (end_time > start_time),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_timetable_slot_timetable
        FOREIGN KEY (timetable_id)
        REFERENCES timetable(timetable_id),
    CONSTRAINT fk_timetable_slot_room
        FOREIGN KEY (room_id)
        REFERENCES room(room_id)
);

CREATE TABLE block_plan (
    block_plan_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    program_name VARCHAR(150) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL CHECK (ends_on > starts_on),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE block_assignment (
    block_assignment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    block_plan_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    timetable_id BIGINT	not null,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL CHECK (ends_on > starts_on),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_block_assignment_block_plan
        FOREIGN KEY (block_plan_id)
        REFERENCES block_plan(block_plan_id),
    CONSTRAINT fk_block_assignment_class
        FOREIGN KEY (class_id)
        REFERENCES class(class_id),
    CONSTRAINT fk_block_assignment_timetable
        FOREIGN KEY (timetable_id)
        REFERENCES timetable(timetable_id)
);

CREATE TABLE teaching_unit (
    teaching_unit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    block_assignment_id	BIGINT NOT NULL,
    unit_date DATE NOT NULL,
    planned_start DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_teaching_unit_block_assignment
        FOREIGN KEY (block_assignment_id)
        REFERENCES block_assignment(block_assignment_id)
);

CREATE TABLE attendance (
    attendance_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    teaching_unit_id BIGINT NOT NULL,
    status ENUM('ANWESEND', 'ABWESEND', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT', 'BETRIEB') NOT NULL,
    first_scanned_at DATETIME NOT NULL,
    lateness_minutes INT NOT NULL DEFAULT 0 CHECK (lateness_minutes >= 0),
    CONSTRAINT fk_attendance_student
        FOREIGN KEY (student_id)
        REFERENCES student(student_id),
    CONSTRAINT fk_attendance_teaching_unit
        FOREIGN KEY (teaching_unit_id)
        REFERENCES teaching_unit(teaching_unit_id)
);

CREATE TABLE attendance_audit (
    attendance_audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attendance_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    old_status ENUM('ANWESEND', 'ABWESEND', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT', 'BETRIEB') NOT NULL,
    new_status ENUM('ANWESEND', 'ABWESEND', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT', 'BETRIEB') NOT NULL,
    unexcused_minutes_delta INTEGER NOT NULL,
    excused_minutes_delta INTEGER NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_attendance_audit_attendance
        FOREIGN KEY (attendance_id)
        REFERENCES attendance(attendance_id),
    CONSTRAINT fk_attendance_audit_staff
        FOREIGN KEY (staff_id)
        REFERENCES staff(staff_id)
);

CREATE TABLE raw_scan (
    raw_scan_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    terminal_number INTEGER NOT NULL,
    rfid_uid VARCHAR(64) NOT NULL,
    scanned_at DATETIME NOT NULL,
    received_at DATETIME NOT NULL
)