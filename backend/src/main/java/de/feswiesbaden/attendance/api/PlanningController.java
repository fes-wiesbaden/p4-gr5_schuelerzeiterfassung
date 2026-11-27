package de.feswiesbaden.attendance.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import de.feswiesbaden.attendance.api.StaffController.Created;
import de.feswiesbaden.attendance.enums.Weekday;
import de.feswiesbaden.attendance.service.BlockPlanService;
import de.feswiesbaden.attendance.service.PlanningService;
import de.feswiesbaden.attendance.service.PlanningService.CancellationOutcome;
import de.feswiesbaden.attendance.service.PlanningService.ClassSchedule;
import de.feswiesbaden.attendance.service.PlanningService.Overview;
import de.feswiesbaden.attendance.service.PlanningService.WeekDay;
import de.feswiesbaden.attendance.service.SchoolClassService;
import de.feswiesbaden.attendance.service.TimetableImportService;
import de.feswiesbaden.attendance.service.TimetableImportService.ClassTarget;
import de.feswiesbaden.attendance.service.TimetableImportService.Imported;
import de.feswiesbaden.attendance.service.TimetableService;
import de.feswiesbaden.attendance.service.TimetableService.ImportSlot;
import de.feswiesbaden.attendance.service.TimetableService.SlotDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stellt Administratoren die Stunden- und Blockplanung bereit. Den Planwechsel einer Klasse darf
 * auch deren Klassenleitung auslösen
 */
@RestController
@RequestMapping("/api/planning")
public class PlanningController {
  private final PlanningService planning;

  private final TimetableService timetables;

  private final BlockPlanService blockPlans;

  private final SchoolClassService classes;

  private final TimetableImportService imports;

  /** Übernimmt die Services für Übersicht, Stundenpläne, Blockpläne und Klassen */
  public PlanningController(
      PlanningService planning,
      TimetableService timetables,
      BlockPlanService blockPlans,
      SchoolClassService classes,
      TimetableImportService imports) {
    this.planning = planning;
    this.timetables = timetables;
    this.blockPlans = blockPlans;
    this.classes = classes;
    this.imports = imports;
  }

  /** Liefert Stundenpläne, Blockpläne, Räume und Klassen für Administratoren */
  @GetMapping
  public Overview overview() {
    return planning.read();
  }

  /** Liefert die Kalenderwoche einer zugänglichen Klasse. */
  @GetMapping("/classes/{classId}/week")
  public List<WeekDay> week(
      @PathVariable Long classId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return planning.readWeek(classId, date);
  }

  /** Liefert Schuljahr und Unterrichtsblöcke einer zugänglichen Klasse. */
  @GetMapping("/classes/{classId}/schedule")
  public ClassSchedule schedule(@PathVariable Long classId) {
    return planning.readSchedule(classId);
  }

  /** Markiert eine Unterrichtszeit oder ohne Uhrzeit den ganzen Tag als ausgefallen */
  @PostMapping("/classes/{classId}/lesson-cancellations")
  public CancellationOutcome cancelLessons(
      @PathVariable Long classId, @Valid @RequestBody CancellationRequest request) {
    return planning.cancelLessons(classId, request.date(), request.startsAt());
  }

  /** Nimmt den Ausfall einer Unterrichtszeit oder ohne Uhrzeit des ganzen Tages zurück */
  @DeleteMapping("/classes/{classId}/lesson-cancellations")
  public CancellationResult restoreLessons(
      @PathVariable Long classId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
          LocalTime startsAt) {
    return new CancellationResult(planning.restoreLessons(classId, date, startsAt));
  }

  /** Legt einen leeren Stundenplan an */
  @PostMapping("/timetables")
  @ResponseStatus(HttpStatus.CREATED)
  public Created createTimetable(@RequestBody NameRequest request) {
    return new Created(timetables.create(request.name()));
  }

  /** Legt einen neuen Stundenplan samt Unterrichtszeiten in einem Schritt an */
  @PostMapping("/timetables/import")
  public Imported importTimetable(@RequestBody ImportRequest request) {
    return imports.importTimetable(request.name(), request.slots(), request.schoolClass());
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
            request.roomId(),
            request.subject(),
            request.teacherId()));
  }

  /** Ändert einen Slot ab einem zukünftigen Termin */
  @PutMapping("/timetables/{timetableId}/slots/{slotId}")
  public SlotChange updateSlot(
      @PathVariable Long timetableId, @PathVariable Long slotId, @RequestBody SlotRequest request) {
    return new SlotChange(
        timetables.updateSlot(
            timetableId,
            slotId,
            request.weekday(),
            request.startsAt(),
            request.endsAt(),
            request.roomId(),
            new SlotDetails(
                !request.subjectGiven(),
                request.subject(),
                !request.teacherGiven(),
                request.teacherId())));
  }

  /** Löscht die zukünftigen Termine des Slots */
  @DeleteMapping("/timetables/{timetableId}/slots/{slotId}")
  public SlotChange deleteSlot(@PathVariable Long timetableId, @PathVariable Long slotId) {
    return new SlotChange(timetables.deleteSlot(timetableId, slotId));
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

  /** Enthält das Datum und optional die Anfangszeit der ausfallenden Unterrichtszeit */
  public record CancellationRequest(@NotNull LocalDate date, LocalTime startsAt) {}

  /** Zeigt an, ob sich der Unterrichtsausfall geändert hat */
  public record CancellationResult(boolean changed) {}

  /**
   * Enthält Namen und Unterrichtszeiten eines neu anzulegenden Stundenplans sowie optional die
   * Klasse, die ihn erhält
   */
  public record ImportRequest(String name, List<ImportSlot> slots, ClassTarget schoolClass) {}

  /** Enthält den Namen eines Stundenplans */
  public record NameRequest(String name) {}

  /**
   * Enthält Wochentag, Beginn, Ende und Raum eines Slots sowie optional Fach und Lehrkraft. Die
   * Klasse merkt sich, ob subject und teacherId in der Anfrage vorkamen: Fehlen sie beim Ändern,
   * bleiben die bisherigen Werte erhalten, ein ausdrückliches null entfernt sie
   */
  public static final class SlotRequest {
    @JsonProperty private Weekday weekday;

    @JsonProperty private LocalTime startsAt;

    @JsonProperty private LocalTime endsAt;

    @JsonProperty private Long roomId;

    private String subject;

    private boolean subjectGiven;

    private Long teacherId;

    private boolean teacherGiven;

    public Weekday weekday() {
      return weekday;
    }

    public LocalTime startsAt() {
      return startsAt;
    }

    public LocalTime endsAt() {
      return endsAt;
    }

    public Long roomId() {
      return roomId;
    }

    public String subject() {
      return subject;
    }

    public Long teacherId() {
      return teacherId;
    }

    public boolean subjectGiven() {
      return subjectGiven;
    }

    public boolean teacherGiven() {
      return teacherGiven;
    }

    @JsonSetter("subject")
    void setSubject(String value) {
      subject = value;
      subjectGiven = true;
    }

    @JsonSetter("teacherId")
    void setTeacherId(Long value) {
      teacherId = value;
      teacherGiven = true;
    }
  }

  /** Enthält das Datum des ersten geänderten oder gelöschten Termins */
  public record SlotChange(LocalDate effectiveOn) {}

  /** Enthält Namen, Beginn und Ende eines Blockplans */
  public record BlockPlanRequest(String name, LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält Beginn und Ende eines Blocks */
  public record PeriodRequest(LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält den zuzuordnenden Block */
  public record BlockRequest(@NotNull @Positive Long blockId) {}

  /** Enthält den erwarteten aktuellen und den gewünschten nächsten Blockplan */
  public record PlanChangeRequest(
      @NotNull @Positive Long expectedBlockPlanId, @NotNull @Positive Long nextBlockPlanId) {}
}
