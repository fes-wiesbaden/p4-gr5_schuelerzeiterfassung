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

/** Verwaltet berechtigte Klassenzugriffe, Klassenanlage, Kontenresets und Planwechsel */
@Service
public class SchoolClassService {
  private final SchoolClassRepository classes;

  private final StaffAuthorization authorization;

  private final AttendanceAccountingRepository accounting;

  private final AttendanceAccountingService verification;

  private final DeletionDateService deletionDates;

  private final RetentionService retention;

  private final Clock clock;

  public SchoolClassService(
      SchoolClassRepository classes,
      StaffAuthorization authorization,
      AttendanceAccountingRepository accounting,
      AttendanceAccountingService verification,
      DeletionDateService deletionDates,
      RetentionService retention,
      Clock clock) {
    this.classes = classes;
    this.authorization = authorization;
    this.accounting = accounting;
    this.verification = verification;
    this.deletionDates = deletionDates;
    this.retention = retention;
    this.clock = clock;
  }

  /** Liefert die für den angemeldeten Mitarbeiter zugänglichen Klassen */
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

  /** Prüft die Klassendaten und weist anlegende Lehrkräfte selbst als Klassenleitung zu */
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

  /**
   * Setzt als Administrator beide Fehlminutenkonten aller Klassenschüler zurück. Offene
   * Tagesabrechnungen werden vorher nachgeholt. Noch laufende oder spätere heutige Slots verhindern
   * den Reset. Jeder Schüler erhält ein ACCOUNT_RESET-Protokoll, auch bei leeren Konten. Der
   * Rückgabewert ist die Zahl der zurückgesetzten Schüler. Eine leere Klasse liefert 0
   */
  @Transactional
  public int resetAccounts(Long classId, Long expectedPlanId) {
    var actor = authorization.requireAdministrator();
    return resetAccounts(lockExpectedPlan(classId, expectedPlanId), actor.id(), false);
  }

  private int resetAccounts(ClassPlan plan, Long actorId, boolean requireStudents) {
    var deadline = deletionDates.getOrCreate(plan.endsOn());
    var accounts = accounting.lockAccounts(plan.classId());
    if (accounts.isEmpty()) {
      if (requireStudents) {
        throw new IllegalStateException("CLASS_HAS_NO_STUDENTS");
      }
      return 0;
    }
    var now =
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    verification.requireNoRemainingSlots(plan, now);
    if (verification.completeDays(plan, accounts, deadline.getId(), now) > 0) {
      accounts = accounting.lockAccounts(plan.classId());
    }
    verification.requireFullyAccounted(plan, accounts, deadline.getId(), now);
    for (var account : accounts) {
      accounting.reset(account, actorId, deadline.getId(), now);
    }
    var today = AttendanceAccountingService.berlinDate(now);
    if (!today.isBefore(deadline.getDeleteAfter())) {
      retention.deleteDueAccountResets(plan.classId(), deadline.getId(), today);
    }
    return accounts.size();
  }

  /**
   * Setzt als Administrator oder zuständige Klassenleitung nach Ende des bisherigen Plans und
   * seiner Blöcke die Konten zurück und aktiviert den Folgeplan in derselben Transaktion
   */
  @Transactional
  public void changeBlockPlan(Long classId, Long expectedPlanId, Long nextPlanId) {
    var actor = authorization.currentStaff();
    if (!actor.administrator()) {
      authorization.requireClassManagement(classId, actor);
    }
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
    resetAccounts(plan, actor.id(), true);
    classes.replacePlan(classId, nextPlanId);
  }

  /**
   * Sperrt Klasse und aktuellen Blockplan und vergleicht dessen ID mit der vom Aufrufer erwarteten
   * ID
   */
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

  /** Enthält die sichtbaren Klassendaten mit Klassenleitung und Planverweisen */
  public record ClassView(
      Long id, String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {}
}
