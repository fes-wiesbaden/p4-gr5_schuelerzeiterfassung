ALTER TABLE timetable_slot
    ADD COLUMN learning_field VARCHAR(100) NULL,
    ADD COLUMN teacher_id BIGINT NULL,
    ADD CONSTRAINT fk_timetable_slot_teacher FOREIGN KEY (teacher_id) REFERENCES staff(id) ON DELETE SET NULL;
