package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.entities.RawScan;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Speichert neue Rohscans in der Datenbank */
@Repository
public class RawScanRepository {
  private final EntityManager entityManager;

  public RawScanRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Fügt den Rohscan in den aktuellen Persistenzkontext ein */
  public void insert(RawScanDTO scan) {
    entityManager.persist(
        new RawScan(
            scan.scanId(),
            scan.rfidUid(),
            scan.terminalId(),
            LocalDateTime.ofInstant(scan.scannedAt(), ZoneOffset.UTC)));
  }

  /** Prüft, ob eine Scan-ID schon vorhanden ist */
  public boolean checkIfExists(UUID id) {
    return entityManager.find(RawScan.class, id) != null;
  }
}
