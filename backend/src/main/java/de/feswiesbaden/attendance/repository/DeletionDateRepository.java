package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.DeletionDate;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeletionDateRepository {
  private final JdbcTemplate jdbc;

  private final EntityManager entityManager;

  public DeletionDateRepository(JdbcTemplate jdbc, EntityManager entityManager) {
    this.jdbc = jdbc;
    this.entityManager = entityManager;
  }

  public DeletionDate getOrCreate(LocalDate deleteAfter) {
    jdbc.update(
        "INSERT INTO deletion_date (delete_after) VALUES (?)"
            + " ON DUPLICATE KEY UPDATE delete_after = deletion_date.delete_after",
        deleteAfter);
    // Sperrende Abfrage sieht parallele Einfügungen trotz bestehendem REPEATABLE-READ-Snapshot.
    return (DeletionDate)
        entityManager
            .createNativeQuery(
                "SELECT id, delete_after FROM deletion_date WHERE delete_after = :date FOR UPDATE",
                DeletionDate.class)
            .setParameter("date", deleteAfter)
            .getSingleResult();
  }
}
