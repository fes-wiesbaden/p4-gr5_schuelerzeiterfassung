package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.BlockPlan;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/** Speichert neue Blockpläne in der Datenbank */
@Repository
public class BlockPlanRepository {
  private final EntityManager entityManager;

  public BlockPlanRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Fügt den Blockplan in den aktuellen Persistenzkontext ein */
  public void insert(BlockPlan plan) {
    entityManager.persist(plan);
  }
}
