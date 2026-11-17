package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.DeletionDate;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Legt Löschdaten an oder verwendet vorhandene Datensätze mit demselben Datum wieder */
@Repository
public class DeletionDateRepository {
  private final JdbcTemplate jdbc;

  private final EntityManager entityManager;

  public DeletionDateRepository(JdbcTemplate jdbc, EntityManager entityManager) {
    this.jdbc = jdbc;
    this.entityManager = entityManager;
  }

  /** Legt das Löschdatum bei Bedarf an und lädt die zugehörige Frist mit Schreibsperre */
  public DeletionDate getOrCreate(LocalDate deleteAfter) {
    jdbc.update(
        "INSERT INTO deletion_date (delete_after) VALUES (?)"
            + " ON DUPLICATE KEY UPDATE delete_after = deletion_date.delete_after",
        deleteAfter);
    // Die sperrende Abfrage liest den aktuellen Datenstand statt eines älteren Transaktionsabbilds.
    // So wird auch ein parallel angelegtes Löschdatum gefunden (REPEATABLE READ)
    return (DeletionDate)
        entityManager
            .createNativeQuery(
                "SELECT id, delete_after FROM deletion_date WHERE delete_after = :date FOR UPDATE",
                DeletionDate.class)
            .setParameter("date", deleteAfter)
            .getSingleResult();
  }
}
