package de.feswiesbaden.attendance.repository;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Speichert Rohscans in der Transaktion des aufrufenden Dienstes */
@Repository
public class RawScanRepository {
  private final JdbcTemplate jdbc;

  public RawScanRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Fügt einen Rohscan ein und meldet eine bereits gespeicherte Scan-ID */
  public boolean insertIfNew(
      UUID scanId, String rfidUid, LocalDateTime capturedAt, LocalDateTime receivedAt) {
    try {
      jdbc.update(
          "INSERT INTO raw_scan (scan_id, rfid_uid, scanned_at, created_at) VALUES (?, ?, ?, ?)",
          scanId.toString(),
          rfidUid,
          capturedAt,
          receivedAt);
      return true;
    } catch (DuplicateKeyException alreadyReceived) {
      return false;
    }
  }
}
