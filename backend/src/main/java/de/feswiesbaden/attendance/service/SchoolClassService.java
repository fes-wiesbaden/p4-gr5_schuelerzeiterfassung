package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository.ClassPlan;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchoolClassService {
  private final SchoolClassRepository classes;

  private final StaffAuthorization authorization;

  private final AttendanceAccountingRepository accounting;

  private final AttendanceAccountingService verification;

  private final DeletionDateService deletionDates;

  private final Clock clock;

  public SchoolClassService(
      SchoolClassRepository classes,
      StaffAuthorization authorization,
      AttendanceAccountingRepository accounting,
      AttendanceAccountingService verification,
      DeletionDateService deletionDates,
      Clock clock) {
    this.classes = classes;
    this.authorization = authorization;
    this.accounting = accounting;
    this.verification = verification;
    this.deletionDates = deletionDates;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ClassView> list() {
    var actor = authorization.currentStaff();
    return classes.findAccessible(actor.id(), actor.administrator()).stream()
        .map(
            schoolClass ->
                new ClassView(
                    schoolClass.id(),
                    schoolClass.classCode(),
                    schoolClass.classTeacherId(),
                    schoolClass.blockPlanId(),
                    schoolClass.timetableId()))
        .toList();
  }

  @Transactional
  public Long create(String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {
    var actor = authorization.currentStaff();
    if (!actor.administrator()) {
      if (classTeacherId != null && !classTeacherId.equals(actor.id())) {
        throw new AccessDeniedException("CLASS_TEACHER_ASSIGNMENT_DENIED");
      }
      classTeacherId = actor.id();
    }
    if (classCode == null
        || classCode.isBlank()
        || classCode.strip().length() > 30
        || !classes.hasTeacher(classTeacherId)
        || !classes.hasBlockPlan(blockPlanId)
        || !classes.hasTimetable(timetableId)) {
      throw new IllegalArgumentException("INVALID_CLASS");
    }
    try {
      return classes.insert(classCode.strip(), classTeacherId, blockPlanId, timetableId);
    } catch (DuplicateKeyException conflict) {
      throw new IllegalArgumentException("CLASS_CODE_ALREADY_EXISTS");
    }
  }

  @Transactional
  public int resetAccounts(Long classId, Long expectedPlanId) {
    var actor = authorization.requireAdministrator();
    var plan = lockExpectedPlan(classId, expectedPlanId);
    var deadline = deletionDates.getOrCreate(plan.endsOn());
    var accounts = accounting.lockAccounts(classId);
    var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    verification.requireNoRemainingSlots(plan, now);
    verification.completeDays(
        plan, accounts, deadline.getId(), now.truncatedTo(ChronoUnit.SECONDS));
    accounts = accounting.lockAccounts(classId);
    verification.requireFullyAccounted(plan, accounts, now);
    for (var account : accounts) {
      accounting.reset(account, actor.id(), deadline.getId(), now.truncatedTo(ChronoUnit.SECONDS));
    }
    return accounts.size();
  }

  @Transactional
  public void changeBlockPlan(Long classId, Long expectedPlanId, Long nextPlanId) {
    authorization.requireAdministrator();
    var plan = lockExpectedPlan(classId, expectedPlanId);
    var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var today = AttendanceAccountingService.berlinDate(now);
    if (!today.isAfter(plan.endsOn()) || classes.hasUnfinishedBlocks(plan.planId(), today)) {
      throw new IllegalStateException("CURRENT_PLAN_NOT_FINISHED");
    }
    var nextPlan =
        classes
            .lockPlanDates(nextPlanId)
            .orElseThrow(() -> new IllegalArgumentException("BLOCK_PLAN_NOT_FOUND"));
    if (!nextPlan.startsOn().isAfter(plan.endsOn())) {
      throw new IllegalArgumentException("NEXT_PLAN_OVERLAPS_CURRENT_PLAN");
    }
    if (nextPlan.endsOn().isBefore(today)) {
      throw new IllegalArgumentException("NEXT_PLAN_FINISHED");
    }
    var deadline = deletionDates.getOrCreate(plan.endsOn());
    var accounts = accounting.lockAccounts(classId);
    if (accounts.isEmpty()) {
      throw new IllegalStateException("CLASS_HAS_NO_STUDENTS");
    }
    if (accounts.stream().anyMatch(account -> account.unexcused() != 0 || account.excused() != 0)) {
      throw new IllegalStateException("CLASS_ACCOUNTS_NOT_EMPTY");
    }
    verification.requireFullyAccounted(plan, accounts, now);
    verification.requireFinalReset(plan, accounts, deadline.getId(), now);
    classes.replacePlan(classId, nextPlanId);
  }

  private ClassPlan lockExpectedPlan(Long classId, Long expectedPlanId) {
    var plan =
        classes
            .lockPlan(classId)
            .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"));
    if (!plan.planId().equals(expectedPlanId)) {
      throw new IllegalStateException("CLASS_PLAN_CHANGED");
    }
    return plan;
  }

  public record ClassView(
      Long id, String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {}
}
