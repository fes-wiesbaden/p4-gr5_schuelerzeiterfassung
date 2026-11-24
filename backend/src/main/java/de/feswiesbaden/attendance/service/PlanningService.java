package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.enums.Weekday;
import de.feswiesbaden.attendance.repository.PlanningOverviewRepository;
import de.feswiesbaden.attendance.repository.PlanningOverviewRepository.ClassBlockRow;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import java.time.LocalDate;
import java.time.LocalTime;
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

  public PlanningService(
      PlanningOverviewRepository overview,
      SchoolClassRepository classes,
      StaffAuthorization authorization) {
    this.overview = overview;
    this.classes = classes;
    this.authorization = authorization;
  }

  /**
   * Liefert Stundenpläne mit Slots, Blockpläne mit Blöcken und zugeordneten Klassen sowie Räume und
   * Klassen. Das Löschdatum eines Blockplans wird wie beim Anlegen aus dessen Ende berechnet
   */
  @Transactional(readOnly = true)
  public Overview read() {
    var actor = authorization.requireAdministrator();
    var slotsByTimetable =
        overview.findSlots().stream()
            .collect(
                Collectors.groupingBy(
                    slot -> slot.timetableId(),
                    Collectors.mapping(
                        slot ->
                            new SlotView(
                                slot.id(),
                                slot.weekday(),
                                slot.startsAt(),
                                slot.endsAt(),
                                slot.roomId(),
                                slot.roomNumber()),
                        Collectors.toList())));
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
                        slotsByTimetable.getOrDefault(timetable.id(), List.of())))
            .toList(),
        overview.findBlockPlans().stream()
            .map(
                plan ->
                    new BlockPlanView(
                        plan.id(),
                        plan.name(),
                        plan.startsOn(),
                        plan.endsOn(),
                        DeletionDateService.deleteAfter(plan.endsOn()),
                        blocksByPlan.getOrDefault(plan.id(), List.of())))
            .toList(),
        overview.findRooms().stream().map(room -> new RoomView(room.id(), room.name())).toList(),
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

  /** Enthält alle Daten der Planungsansicht */
  public record Overview(
      List<TimetableView> timetables,
      List<BlockPlanView> blockPlans,
      List<RoomView> rooms,
      List<ClassPlanView> classes) {}

  /** Enthält einen Stundenplan mit seinen Slots */
  public record TimetableView(Long id, String name, List<SlotView> slots) {}

  /** Enthält Wochentag, Uhrzeiten und Raum eines Slots */
  public record SlotView(
      Long id,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId,
      String roomNumber) {}

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

  /** Enthält ID und Raumnummer */
  public record RoomView(Long id, String roomNumber) {}

  /** Enthält eine Klasse mit ihrem aktuellen Block- und Stundenplan */
  public record ClassPlanView(Long id, String classCode, Long blockPlanId, Long timetableId) {}
}
