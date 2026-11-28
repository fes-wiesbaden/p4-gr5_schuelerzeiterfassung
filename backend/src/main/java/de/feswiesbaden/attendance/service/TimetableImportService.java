package de.feswiesbaden.attendance.service;

import static de.feswiesbaden.attendance.service.AttendanceAccountingService.BERLIN;

import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import de.feswiesbaden.attendance.service.TimetableService.ImportSlot;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legt einen geprüften Stundenplan-Entwurf in einem Schritt an und verknüpft ihn auf Wunsch mit
 * einer bestehenden oder neuen Klasse. Nur Administratoren dürfen importieren
 */
@Service
public class TimetableImportService {
  private final TimetableService timetables;

  private final SchoolClassService schoolClasses;

  private final SchoolClassRepository classes;

  private final StaffAuthorization authorization;

  private final Clock clock;

  public TimetableImportService(
      TimetableService timetables,
      SchoolClassService schoolClasses,
      SchoolClassRepository classes,
      StaffAuthorization authorization,
      Clock clock) {
    this.timetables = timetables;
    this.schoolClasses = schoolClasses;
    this.classes = classes;
    this.authorization = authorization;
    this.clock = clock;
  }

  /**
   * Legt Stundenplan, Unterrichtszeiten und gegebenenfalls die Klasse atomar an. Eine bestehende
   * Klasse erhält den Plan ab einem künftigen Tag; frühere Tage behalten ihren bisherigen Plan
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Imported importTimetable(String name, List<ImportSlot> slots, ClassTarget target) {
    authorization.requireAdministrator();
    if (target == null) {
      return new Imported(timetables.importTimetable(name, slots, null, null, null), null);
    }
    if (target.id() != null) {
      var today = clock.instant().atZone(BERLIN).toLocalDate();
      if (target.validFrom() == null || !target.validFrom().isAfter(today)) {
        throw new TimetableImportException("INVALID_TIMETABLE_VALID_FROM", null);
      }
      // Sperrt die Klasse gegen gleichzeitige Abrechnung, Scans und Planänderungen
      if (classes.lockPlan(target.id()).isEmpty()) {
        throw new IllegalArgumentException("CLASS_NOT_FOUND");
      }
      return new Imported(
          timetables.importTimetable(
              name, slots, target.id(), target.validFrom(), target.validUntil()),
          target.id());
    }
    // Bei einer neuen Klasse ist außerhalb eines angegebenen Zeitraums kein Unterricht geplant
    Long timetableId =
        timetables.importTimetable(name, slots, null, target.validFrom(), target.validUntil());
    try {
      return new Imported(
          timetableId,
          schoolClasses.create(
              target.classCode(), target.classTeacherId(), target.blockPlanId(), timetableId));
    } catch (IllegalArgumentException | IllegalStateException | AccessDeniedException invalid) {
      throw new TimetableImportException(invalid.getMessage(), null);
    }
  }

  /**
   * Beschreibt die Klasse des Imports: mit id und validFrom eine bestehende Klasse, sonst eine neu
   * anzulegende Klasse mit Kürzel, Klassenleitung und Blockplan
   */
  public record ClassTarget(
      Long id,
      LocalDate validFrom,
      LocalDate validUntil,
      String classCode,
      Long classTeacherId,
      Long blockPlanId) {}

  /** Enthält die ID des neuen Stundenplans und der verknüpften Klasse oder null */
  public record Imported(Long id, Long classId) {}
}
