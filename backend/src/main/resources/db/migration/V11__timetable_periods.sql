CREATE TABLE timetable_period (
    timetable_id BIGINT NOT NULL,
    period_number SMALLINT UNSIGNED NOT NULL,
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    PRIMARY KEY (timetable_id, period_number),
    CONSTRAINT fk_timetable_period_timetable
        FOREIGN KEY (timetable_id) REFERENCES timetable(id) ON DELETE CASCADE,
    CONSTRAINT chk_timetable_period_times CHECK (starts_at < ends_at)
);
