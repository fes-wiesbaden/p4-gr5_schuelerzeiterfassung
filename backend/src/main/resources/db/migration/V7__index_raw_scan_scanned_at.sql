-- Die Logs-Ansicht filtert und sortiert Rohscans nach dem Scanzeitpunkt
CREATE INDEX idx_raw_scan_scanned_at ON raw_scan (scanned_at);
