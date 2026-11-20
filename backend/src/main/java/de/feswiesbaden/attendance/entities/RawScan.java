package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Speichert Kartenkennung und UTC-Zeitpunkte eines Rohscans unter seiner eindeutigen Scan-ID. Ein
 * Rohscan belegt den Empfang, unabhängig von einer fachlichen Anwesenheitsbuchung
 */
@Entity
@Table(name = "raw_scan")
@Immutable
@Getter
@Setter
@NoArgsConstructor
public class RawScan {

  public RawScan(UUID scanId, UUID rfidUid, Long terminalId, LocalDateTime scannedAt) {
    this.scanId = scanId;
    this.rfidUid = rfidUid;
    this.terminalId = terminalId;
    this.scannedAt = scannedAt;
  }

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "scan_id", length = 36, nullable = false)
  private UUID scanId;

  @NotNull
  @Column(name = "rfid_uid", length = 64, nullable = false)
  private UUID rfidUid;

  @NotNull
  @Column(name = "terminal_id", nullable = false)
  private Long terminalId;

  @NotNull
  @Column(name = "scanned_at", nullable = false)
  private LocalDateTime scannedAt;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;
}
