package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.enums.Weekday;
import de.feswiesbaden.attendance.service.BlockPlanService;
import de.feswiesbaden.attendance.service.PlanningService;
import de.feswiesbaden.attendance.service.PlanningService.Overview;
import de.feswiesbaden.attendance.service.SchoolClassService;
import de.feswiesbaden.attendance.service.TimetableService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Stellt die Stunden- und Blockplanung für Administratoren und Klassenleitungen bereit */
@RestController
@RequestMapping("/api/planning")
public class PlanningController {
  private final PlanningService planning;

  private final TimetableService timetables;

  private final BlockPlanService blockPlans;

  private final SchoolClassService classes;

  /** Übernimmt die Services für Übersicht, Stundenpläne, Blockpläne und Klassen */
  public PlanningController(
      PlanningService planning,
      TimetableService timetables,
      BlockPlanService blockPlans,
      SchoolClassService classes) {
    this.planning = planning;
    this.timetables = timetables;
    this.blockPlans = blockPlans;
    this.classes = classes;
  }

  /** Liefert Stundenpläne, Blockpläne, Räume und Klassen für Administratoren */
  @GetMapping
  public Overview overview() {
    return planning.read();
  }

  /** Legt einen leeren Stundenplan an */
  @PostMapping("/timetables")
  @ResponseStatus(HttpStatus.CREATED)
  public Created createTimetable(@RequestBody NameRequest request) {
    return new Created(timetables.create(request.name()));
  }

  /** Fügt dem Stundenplan einen nicht überlappenden Slot hinzu */
  @PostMapping("/timetables/{timetableId}/slots")
  @ResponseStatus(HttpStatus.CREATED)
  public Created addSlot(@PathVariable Long timetableId, @RequestBody SlotRequest request) {
    return new Created(
        timetables.addSlot(
            timetableId,
            request.weekday(),
            request.startsAt(),
            request.endsAt(),
            request.roomId()));
  }

  /** Legt einen Blockplan an. Das Löschdatum wird dabei berechnet und separat gespeichert */
  @PostMapping("/block-plans")
  @ResponseStatus(HttpStatus.CREATED)
  public Created createBlockPlan(@RequestBody BlockPlanRequest request) {
    return new Created(blockPlans.create(request.name(), request.startsOn(), request.endsOn()));
  }

  /** Fügt dem Blockplan einen nicht überlappenden Block hinzu */
  @PostMapping("/block-plans/{blockPlanId}/blocks")
  @ResponseStatus(HttpStatus.CREATED)
  public Created addBlock(@PathVariable Long blockPlanId, @RequestBody PeriodRequest request) {
    return new Created(
        blockPlans.addBlockAssignment(blockPlanId, request.startsOn(), request.endsOn()));
  }

  /** Ordnet der Klasse einen Block ihres aktuellen Blockplans zu */
  @PostMapping("/classes/{classId}/blocks")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void assignBlock(@PathVariable Long classId, @Valid @RequestBody BlockRequest request) {
    classes.assignBlock(classId, request.blockId());
  }

  /** Setzt nach Ende des aktuellen Blockplans die Konten zurück und aktiviert den Folgeplan */
  @PostMapping("/classes/{classId}/block-plan-change")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changeBlockPlan(
      @PathVariable Long classId, @Valid @RequestBody PlanChangeRequest request) {
    classes.changeBlockPlan(classId, request.expectedBlockPlanId(), request.nextBlockPlanId());
  }

  /** Enthält den Namen eines Stundenplans */
  public record NameRequest(String name) {}

  /** Enthält Wochentag, Beginn, Ende und Raum eines Slots */
  public record SlotRequest(Weekday weekday, LocalTime startsAt, LocalTime endsAt, Long roomId) {}

  /** Enthält Namen, Beginn und Ende eines Blockplans */
  public record BlockPlanRequest(String name, LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält Beginn und Ende eines Blocks */
  public record PeriodRequest(LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält den zuzuordnenden Block */
  public record BlockRequest(@NotNull @Positive Long blockId) {}

  /** Enthält den erwarteten aktuellen und den gewünschten nächsten Blockplan */
  public record PlanChangeRequest(
      @NotNull @Positive Long expectedBlockPlanId, @NotNull @Positive Long nextBlockPlanId) {}

  /** Enthält die ID des angelegten Datensatzes */
  public record Created(Long id) {}
}
