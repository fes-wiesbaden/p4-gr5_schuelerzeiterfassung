package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "raw_scan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RawScan {

    @Id
    @Column(name = "scan_id", length = 36, nullable = false)
    private String scanId;

    @NotNull
    @Column(name = "rfid_uid", length = 64, nullable = false)
    private String rfidUid;

    @NotNull
    @Column(name = "terminal_number", nullable = false)
    private Integer terminalNumber;

    @NotNull
    @Column(name = "scanned_at", nullable = false)
    private LocalDateTime scannedAt;

    @Column(
            name = "created_at",
            insertable = false,
            updatable = false,
            nullable = false
    )
    private LocalDateTime createdAt;
}