package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.BlockPlan;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class BlockPlanRepository {
  private final EntityManager entityManager;

  public BlockPlanRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public void insert(BlockPlan plan) {
    entityManager.persist(plan);
  }
}
