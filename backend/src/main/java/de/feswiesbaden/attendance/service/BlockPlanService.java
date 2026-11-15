package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.entities.BlockPlan;
import de.feswiesbaden.attendance.repository.BlockPlanRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockPlanService {
  private final BlockPlanRepository plans;

  private final StaffAuthorization authorization;

  private final DeletionDateService deletionDates;

  public BlockPlanService(
      BlockPlanRepository plans,
      StaffAuthorization authorization,
      DeletionDateService deletionDates) {
    this.plans = plans;
    this.authorization = authorization;
    this.deletionDates = deletionDates;
  }

  @Transactional
  public Long create(String name, LocalDate startsOn, LocalDate endsOn) {
    authorization.requireAdministrator();
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
    plans.insert(plan);
    deletionDates.getOrCreate(endsOn);
    return plan.getId();
  }
}
