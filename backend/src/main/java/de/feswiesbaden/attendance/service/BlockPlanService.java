package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.entities.BlockPlan;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockPlanService {
  private final EntityManager entityManager;

  private final AdminAuthorization authorization;

  private final DeletionDateService deletionDates;

  public BlockPlanService(
      EntityManager entityManager,
      AdminAuthorization authorization,
      DeletionDateService deletionDates) {
    this.entityManager = entityManager;
    this.authorization = authorization;
    this.deletionDates = deletionDates;
  }

  @Transactional
  public Long create(Long staffId, String name, LocalDate startsOn, LocalDate endsOn) {
    authorization.requireAdministrator(staffId);
    if (name == null
        || name.isBlank()
        || name.strip().length() > 150
        || startsOn == null
        || endsOn == null
        || startsOn.isAfter(endsOn)) {
      throw new IllegalArgumentException("INVALID_BLOCK_PLAN");
    }
    BlockPlan plan = new BlockPlan();
    plan.setName(name.strip());
    plan.setStartsOn(startsOn);
    plan.setEndsOn(endsOn);
    entityManager.persist(plan);
    deletionDates.getOrCreate(endsOn);
    return plan.getId();
  }
}
