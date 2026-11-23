package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.BlockAssignment;
import de.feswiesbaden.attendance.entities.BlockPlan;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Speichert neue Blockpläne und ihre Blockzeiträume (Blockzuordnungen) */
@Repository
public class BlockPlanRepository {
  private final EntityManager entityManager;

  private final JdbcTemplate jdbc;

  public BlockPlanRepository(EntityManager entityManager, JdbcTemplate jdbc) {
    this.entityManager = entityManager;
    this.jdbc = jdbc;
  }

  /** Fügt den Blockplan in den aktuellen Persistenzkontext ein */
  public void insert(BlockPlan plan) {
    entityManager.persist(plan);
  }

  /** Prüft, ob der Blockplan existiert */
  public boolean exists(Long blockPlanId) {
    return !jdbc.queryForList("SELECT id FROM block_plan WHERE id = ?", Long.class, blockPlanId)
        .isEmpty();
  }

  /** Prüft, ob der Blockplan bereits einen zeitlich überlappenden Blockzeitraum enthält */
  public boolean hasOverlappingBlockAssignment(
      Long blockPlanId, LocalDate startsOn, LocalDate endsOn) {
    return !jdbc.queryForList(
            "SELECT id FROM block_assignment WHERE block_plan_id = ?"
                + " AND starts_on <= ? AND ends_on >= ?",
            Long.class,
            blockPlanId,
            endsOn,
            startsOn)
        .isEmpty();
  }

  /** Legt einen Blockzeitraum im Blockplan an und liefert dessen erzeugte ID */
  public Long insertBlockAssignment(Long blockPlanId, LocalDate startsOn, LocalDate endsOn) {
    BlockAssignment assignment = new BlockAssignment();
    assignment.setBlockPlan(entityManager.getReference(BlockPlan.class, blockPlanId));
    assignment.setStartsOn(startsOn);
    assignment.setEndsOn(endsOn);
    entityManager.persist(assignment);
    return assignment.getId();
  }
}
