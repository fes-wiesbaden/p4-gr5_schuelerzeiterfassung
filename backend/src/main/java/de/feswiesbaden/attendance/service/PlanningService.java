package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Slot;
import de.feswiesbaden.attendance.repository.PlanningOverviewRepository;
import de.feswiesbaden.attendance.repository.PlanningOverviewRepository.ClassBlockRow;
import de.feswiesbaden.attendance.repository.PlanningOverviewRepository.PeriodRow;
import de.feswiesbaden.attendance.repository.PlanningOverviewRepository.RoomRow;
import de.feswiesbaden.attendance.repository.PlanningOverviewRepository.SlotRow;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stellt Administratoren die vollständige Stunden- und Blockplanung als Übersicht bereit */
@Service
public class PlanningService {
  private final PlanningOverviewRepository overview;

  private final SchoolClassRepository classes;

  private final StaffAuthorization authorization;

  private final AttendanceAccountingService attendance;

  private final AttendanceAccountingRepository accounting;

  public PlanningService(
      PlanningOverviewRepository overview,
      SchoolClassRepository classes,
      StaffAuthorization authorization,
      AttendanceAccountingService attendance,
      AttendanceAccountingRepository accounting) {
    this.overview = overview;
    this.classes = classes;
    this.authorization = authorization;
    this.attendance = attendance;
    this.accounting = accounting;
  }

  /**
   * Liefert Stundenpläne mit Slots, Blockpläne mit Blöcken und zugeordneten Klassen sowie Räume und
   * Klassen. Das Löschdatum eines Blockplans wird wie beim Anlegen aus dessen Ende berechnet
   */
  @Transactional(readOnly = true)
  public Overview read() {
    var actor = authorization.requireAdministrator();
    var slotsByTimetable =
        overview.findSlots().stream().collect(Collectors.groupingBy(SlotRow::timetableId));
    var classesByBlock =
        overview.findClassBlocks().stream()
            .collect(
                Collectors.groupingBy(
                    ClassBlockRow::blockId,
                    Collectors.mapping(ClassBlockRow::classId, Collectors.toList())));
    Map<Long, List<BlockView>> blocksByPlan =
        overview.findBlocks().stream()
            .collect(
                Collectors.groupingBy(
                    block -> block.blockPlanId(),
                    Collectors.mapping(
                        block ->
                            new BlockView(
                                block.id(),
                                block.startsOn(),
                                block.endsOn(),
                                classesByBlock.getOrDefault(block.id(), List.of()).stream()
                                    .sorted()
                                    .toList()),
                        Collectors.toList())));
    return new Overview(
        overview.findTimetables().stream()
            .map(
                timetable ->
                    new TimetableView(
                        timetable.id(),
                        timetable.name(),
                        slotsByTimetable.getOrDefault(timetable.id(), List.of()),
                        overview.findPeriods(timetable.id())))
            .toList(),
        overview.findBlockPlans().stream()
            .map(
                plan ->
                    new BlockPlanView(
                        plan.id(),
                        plan.name(),
                        plan.startsOn(),
                        plan.endsOn(),
                        DeletionDateService.deletionDateFor(plan.endsOn()),
                        blocksByPlan.getOrDefault(plan.id(), List.of())))
            .toList(),
        overview.findRooms(),
        classes.findAccessible(actor.id(), true).stream()
            .map(
                schoolClass ->
                    new ClassPlanView(
                        schoolClass.id(),
                        schoolClass.classCode(),
                        schoolClass.blockPlanId(),
                        schoolClass.timetableId()))
            .toList());
  }

  /** Liefert die Unterrichtszeiten und Raumnummern einer zugänglichen Klasse. */
  @Transactional(readOnly = true)
  public List<WeekDay> readWeek(Long classId, LocalDate date) {
    var week = attendance.readClassWeek(classId, date);
    var periods = overview.findPeriods(week.timetableId());
    var rooms =
        overview.findRooms().stream().collect(Collectors.toMap(RoomRow::id, RoomRow::roomNumber));
    return week.days().entrySet().stream()
        .map(
            day ->
                new WeekDay(
                    day.getKey(),
                    day.getValue().stream().map(slot -> lesson(slot, rooms)).toList(),
                    periods))
        .toList();
  }

  /** Liefert das Wochenraster und die zugeordneten Unterrichtsblöcke des Schuljahres. */
  @Transactional(readOnly = true)
  public ClassSchedule readSchedule(Long classId) {
    authorization.requireClassAccess(classId, authorization.currentStaff());
    var plan =
        classes
            .findPlan(classId, false)
            .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"));
    var schoolYear = schoolYearStartingIn(plan.startsOn());
    var schoolYearEnd = LocalDate.of(schoolYear + 1, Month.JULY, 31);
    var rooms =
        overview.findRooms().stream().collect(Collectors.toMap(RoomRow::id, RoomRow::roomNumber));
    var blocks =
        accounting.findPeriods(classId, false).stream()
            .filter(period -> period.planId().equals(plan.planId()))
            .map(period -> new TeachingBlock(period.startsOn(), period.endsOn()))
            .sorted(Comparator.comparing(TeachingBlock::startsOn))
            .toList();
    var lessonsByWeekday =
        accounting.findSlots(plan.timetableId(), false).stream()
            .filter(
                slot ->
                    (slot.validFrom() == null || !slot.validFrom().isAfter(schoolYearEnd))
                        && (slot.validUntil() == null || slot.validUntil().isAfter(schoolYearEnd)))
            .collect(
                Collectors.groupingBy(
                    Slot::weekday,
                    Collectors.mapping(slot -> lesson(slot, rooms), Collectors.toList())));
    var regularWeek =
        List.of(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)
            .stream()
            .map(day -> new TemplateDay(lessonsByWeekday.getOrDefault(day, List.of())))
            .toList();
    return new ClassSchedule(
        schoolYear, overview.findPeriods(plan.timetableId()), regularWeek, blocks);
  }

  private static int schoolYearStartingIn(LocalDate date) {
    return date.getMonthValue() < Month.AUGUST.getValue() ? date.getYear() - 1 : date.getYear();
  }

  private static Lesson lesson(Slot slot, Map<Long, String> rooms) {
    return new Lesson(slot.startsAt(), slot.endsAt(), rooms.get(slot.roomId()));
  }

  public record ClassSchedule(
      int schoolYear,
      List<PeriodRow> periods,
      List<TemplateDay> regularWeek,
      List<TeachingBlock> blocks) {}

  public record TeachingBlock(LocalDate startsOn, LocalDate endsOn) {}

  public record TemplateDay(List<Lesson> lessons) {}

  public record WeekDay(LocalDate date, List<Lesson> lessons, List<PeriodRow> periods) {}

  public record Lesson(LocalTime startsAt, LocalTime endsAt, String roomNumber) {}

  /** Enthält alle Daten der Planungsansicht */
  public record Overview(
      List<TimetableView> timetables,
      List<BlockPlanView> blockPlans,
      List<RoomRow> rooms,
      List<ClassPlanView> classes) {}

  /** Enthält einen Stundenplan mit seinen Slots */
  public record TimetableView(Long id, String name, List<SlotRow> slots, List<PeriodRow> periods) {}

  /** Enthält Zeitraum, berechnetes Löschdatum und Blöcke eines Blockplans */
  public record BlockPlanView(
      Long id,
      String name,
      LocalDate startsOn,
      LocalDate endsOn,
      LocalDate deleteAfter,
      List<BlockView> blocks) {}

  /** Enthält einen Blockzeitraum und die IDs der zugeordneten Klassen */
  public record BlockView(Long id, LocalDate startsOn, LocalDate endsOn, List<Long> classIds) {}

  /** Enthält eine Klasse mit ihrem aktuellen Block- und Stundenplan */
  public record ClassPlanView(Long id, String classCode, Long blockPlanId, Long timetableId) {}
}
