package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "raw_scan")
@Immutable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RawScan {

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "scan_id", length = 36, nullable = false)
  private UUID scanId;

  @NotNull
  @Column(name = "rfid_uid", length = 64, nullable = false)
  private String rfidUid;

  @NotNull
  @Column(name = "scanned_at", nullable = false)
  private LocalDateTime scannedAt;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;
}
