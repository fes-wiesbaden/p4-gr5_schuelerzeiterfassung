package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.entities.BlockPlan;
import de.feswiesbaden.attendance.repository.BlockPlanRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Legt Blockpläne mit dem zugehörigen Löschdatum an */
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

  /**
   * Prüft Administratorrechte, Name und Zeitraum und liefert die ID des angelegten Blockplans. Das
   * berechnete Löschdatum wird in derselben Transaktion separat angelegt oder wiederverwendet. Der
   * Blockplan speichert keinen Verweis darauf
   */
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

  /**
   * Fügt dem Blockplan einen Blockzeitraum hinzu, sofern der Plan existiert, Beginn vor oder am
   * Ende liegt und sich der Zeitraum nicht mit einem bereits vorhandenen Block desselben Plans
   * überlappt. Blöcke eines Blockplans dürfen sich nicht überlappen
   */
  @Transactional
  public Long addBlockAssignment(Long blockPlanId, LocalDate startsOn, LocalDate endsOn) {
    authorization.requireAdministrator();
    if (blockPlanId == null
        || startsOn == null
        || endsOn == null
        || startsOn.isAfter(endsOn)
        || !plans.exists(blockPlanId)) {
      throw new IllegalArgumentException("INVALID_BLOCK_ASSIGNMENT");
    }
    if (plans.hasOverlappingBlockAssignment(blockPlanId, startsOn, endsOn)) {
      throw new IllegalArgumentException("OVERLAPPING_BLOCK_ASSIGNMENT");
    }
    return plans.insertBlockAssignment(blockPlanId, startsOn, endsOn);
  }
}
