ALTER TABLE timetable_slot
    ADD COLUMN valid_from DATE NULL,
    ADD COLUMN valid_until DATE NULL,
    ADD COLUMN series_id BIGINT NULL,
    ADD CONSTRAINT chk_timetable_slot_validity
        CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_from < valid_until);
