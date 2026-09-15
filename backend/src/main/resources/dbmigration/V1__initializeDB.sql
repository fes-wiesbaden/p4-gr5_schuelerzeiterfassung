CREATE TABLE teacher (
                         teacher_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                         first_name VARCHAR(100) NOT NULL,
                         last_name VARCHAR(100) NOT NULL,
                         username VARCHAR(120) NOT NULL UNIQUE,
                         password_hash VARCHAR(60) NOT NULL,
                         role ENUM('LEHRKRAFT', 'ADMINISTRATOR') NOT NULL,
                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);


CREATE TABLE school_class (
                              school_class_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              class_code VARCHAR(30) NOT NULL,
                              school_year VARCHAR(9) NOT NULL,
                              class_teacher_id BIGINT NOT NULL,
                              block_plan_id BIGINT NOT NULL,
                              timetable_id BIGINT NOT NULL,
                              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                              CONSTRAINT uq_school_class_code_year UNIQUE (class_code, school_year),
                              CONSTRAINT fk_school_class_teacher FOREIGN KEY (class_teacher_id) REFERENCES teacher(teacher_id),
                              CONSTRAINT fk_school_class_block_plan FOREIGN KEY (block_plan_id) REFERENCES block_plan(block_plan_id),
                              CONSTRAINT fk_school_class_timetable FOREIGN KEY (timetable_id) REFERENCES timetable(timetable_id),
                              INDEX idx_school_class_block_plan (block_plan_id),
                              INDEX idx_school_class_timetable (timetable_id)
);


CREATE TABLE student (
                         student_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                         first_name VARCHAR(100) NOT NULL,
                         last_name VARCHAR(100) NOT NULL,
                         birth_date DATE NOT NULL,
                         school_class_id BIGINT NOT NULL,
                         rfid_uid VARCHAR(64) UNIQUE,
                         unexcused_minutes_account INT NOT NULL DEFAULT 0 CHECK (unexcused_minutes_account >= 0),
                         excused_minutes_account INT NOT NULL DEFAULT 0 CHECK (excused_minutes_account >= 0),
                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                         CONSTRAINT fk_student_class FOREIGN KEY (school_class_id) REFERENCES school_class(school_class_id)
);


CREATE TABLE teacher_class (
                               teacher_id BIGINT NOT NULL,
                               school_class_id BIGINT NOT NULL,
                               created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                               PRIMARY KEY (teacher_id, school_class_id),
                               CONSTRAINT fk_teacher_class_teacher FOREIGN KEY (teacher_id) REFERENCES teacher(teacher_id),
                               CONSTRAINT fk_teacher_class_class FOREIGN KEY (school_class_id) REFERENCES school_class(school_class_id)
);


CREATE TABLE room (
                      room_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      room_number VARCHAR(30) NOT NULL UNIQUE,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);


CREATE TABLE terminal (
                          terminal_id INT PRIMARY KEY,
                          room_id BIGINT NOT NULL UNIQUE,
                          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                          CONSTRAINT fk_terminal_room FOREIGN KEY (room_id) REFERENCES room(room_id)
);


CREATE TABLE timetable (
                           timetable_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                           name VARCHAR(150) NOT NULL,
                           created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);


CREATE TABLE timetable_slot (
                                timetable_slot_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                timetable_id BIGINT NOT NULL,
                                room_id BIGINT NOT NULL,
                                weekday ENUM('MONTAG', 'DIENSTAG', 'MITTWOCH', 'DONNERSTAG', 'FREITAG') NOT NULL,
                                start_time TIME NOT NULL,
                                end_time TIME NOT NULL,
                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                CONSTRAINT chk_timetable_slot_time CHECK (end_time > start_time),
                                CONSTRAINT fk_timetable_slot_timetable FOREIGN KEY (timetable_id) REFERENCES timetable(timetable_id),
                                CONSTRAINT fk_timetable_slot_room FOREIGN KEY (room_id) REFERENCES room(room_id),
                                INDEX idx_timetable_slot_timetable_weekday_time (timetable_id, weekday, start_time, end_time)
);


CREATE TABLE block_plan (
                            block_plan_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            name VARCHAR(150) NOT NULL,
                            starts_on DATE NOT NULL,
                            ends_on DATE NOT NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            CONSTRAINT chk_block_plan_dates CHECK (ends_on >= starts_on)
);


CREATE TABLE block_assignment (
                                  block_assignment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                  block_plan_id BIGINT NOT NULL,
                                  starts_on DATE NOT NULL,
                                  ends_on DATE NOT NULL,
                                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                  CONSTRAINT chk_block_assignment_dates CHECK (ends_on >= starts_on),
                                  CONSTRAINT fk_block_assignment_block_plan FOREIGN KEY (block_plan_id) REFERENCES block_plan(block_plan_id),
                                  INDEX idx_block_assignment_plan_dates (block_plan_id, starts_on, ends_on)
);


CREATE TABLE attendance (
                            attendance_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            student_id BIGINT NOT NULL,
                            school_class_id BIGINT NOT NULL,
                            attendance_date DATE NOT NULL,
                            block_assignment_id BIGINT NOT NULL,
                            status ENUM('ANWESEND', 'ABWESEND', 'BETRIEB', 'BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT') NOT NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            CONSTRAINT uq_attendance_student_date UNIQUE (student_id, attendance_date),
                            CONSTRAINT fk_attendance_student  FOREIGN KEY (student_id) REFERENCES student(student_id),
                            CONSTRAINT fk_attendance_school_class FOREIGN KEY (school_class_id) REFERENCES school_class(school_class_id),
                            CONSTRAINT fk_attendance_block_assignment FOREIGN KEY (block_assignment_id) REFERENCES block_assignment(block_assignment_id),
                            INDEX idx_attendance_block_assignment_date (block_assignment_id, attendance_date),
                            INDEX idx_attendance_school_class_date (school_class_id, attendance_date)
);


CREATE TABLE attendance_audit (
                                  attendance_audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                  student_id BIGINT NOT NULL,
                                  attendance_id BIGINT NULL,
                                  block_plan_id BIGINT NULL,
                                  source ENUM('SYSTEM', 'TEACHER', 'TERMINAL') NOT NULL,
                                  changed_by_teacher_id BIGINT NULL,
                                  terminal_id INT NULL,
                                  old_status ENUM('ANWESEND', 'ABWESEND', 'BETRIEB', 'BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT') NULL,
                                  new_status ENUM('ANWESEND', 'ABWESEND', 'BETRIEB', 'BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT') NULL,
                                  occurred_at DATETIME NOT NULL,
                                  unexcused_minutes_delta INT NOT NULL DEFAULT 0,
                                  excused_minutes_delta INT NOT NULL DEFAULT 0,
                                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  CONSTRAINT uq_attendance_audit_reset UNIQUE (student_id, block_plan_id),
                                  CONSTRAINT fk_attendance_audit_student FOREIGN KEY (student_id) REFERENCES student(student_id),
                                  CONSTRAINT fk_attendance_audit_attendance FOREIGN KEY (attendance_id) REFERENCES attendance(attendance_id),
                                  CONSTRAINT fk_attendance_audit_block_plan FOREIGN KEY (block_plan_id) REFERENCES block_plan(block_plan_id),
                                  CONSTRAINT fk_attendance_audit_teacher FOREIGN KEY (changed_by_teacher_id) REFERENCES teacher(teacher_id),
                                  CONSTRAINT fk_attendance_audit_terminal FOREIGN KEY (terminal_id) REFERENCES terminal(terminal_id),
                                  CONSTRAINT chk_attendance_audit_status_pair CHECK (
                                      (old_status IS NULL AND new_status IS NULL)
                                      OR
                                      (old_status IS NOT NULL AND new_status IS NOT NULL AND old_status <> new_status)),
                                  CONSTRAINT chk_attendance_audit_source_refs CHECK (
                                      (source = 'SYSTEM' AND changed_by_teacher_id IS NULL AND terminal_id IS NULL)
                                      OR
                                      (source = 'TEACHER' AND changed_by_teacher_id IS NOT NULL AND terminal_id IS NULL)
                                      OR (source = 'TERMINAL' AND changed_by_teacher_id IS NULL AND terminal_id IS NOT NULL)),

                                  CONSTRAINT chk_attendance_audit_normal_or_reset CHECK (
                                      (attendance_id IS NOT NULL AND block_plan_id IS NULL AND (
                                                  (old_status IS NOT NULL AND new_status IS NOT NULL)
                                                  OR unexcused_minutes_delta <> 0
                                                  OR excused_minutes_delta <> 0))
                                      OR
                                      (attendance_id IS NULL AND block_plan_id IS NOT NULL AND source = 'TEACHER' AND changed_by_teacher_id IS NOT NULL AND terminal_id IS NULL AND old_status IS NULL AND new_status IS NULL)),
                                  INDEX idx_attendance_audit_attendance (attendance_id),
                                  INDEX idx_attendance_audit_occurred_at (occurred_at)
);


CREATE TABLE raw_scan (
                          scan_id CHAR(36) PRIMARY KEY,
                          rfid_uid VARCHAR(64) NOT NULL,
                          terminal_number INT NOT NULL,
                          scanned_at DATETIME NOT NULL,
                          created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          CONSTRAINT fk_raw_scan_terminal FOREIGN KEY (terminal_number) REFERENCES terminal(terminal_id),
                          CONSTRAINT chk_raw_scan_time CHECK (scanned_at <= created_at)
);