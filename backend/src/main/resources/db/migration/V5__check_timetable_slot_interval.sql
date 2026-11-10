ALTER TABLE timetable_slot
    ADD CONSTRAINT chk_timetable_slot_interval CHECK (start_time <= end_time);
