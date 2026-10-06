package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.entities.DeletionDate;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletionDateService {
  private final JdbcTemplate jdbc;

  private final EntityManager entityManager;

  public DeletionDateService(JdbcTemplate jdbc, EntityManager entityManager) {
    this.jdbc = jdbc;
    this.entityManager = entityManager;
  }

  @Transactional
  public DeletionDate getOrCreate(LocalDate endsOn) {
    LocalDate deleteAfter = endsOn.plusMonths(6);
    jdbc.update(
        "INSERT INTO deletion_date (delete_after) VALUES (?)"
            + " ON DUPLICATE KEY UPDATE delete_after = deletion_date.delete_after",
        deleteAfter);
    // sperrende Abfrage sieht parallele Einfügungen trotz bestehendem REPEATABLE-READ-Snapshot
    return (DeletionDate)
        entityManager
            .createNativeQuery(
                "SELECT id, delete_after FROM deletion_date WHERE delete_after = :date FOR UPDATE",
                DeletionDate.class)
            .setParameter("date", deleteAfter)
            .getSingleResult();
  }
}
