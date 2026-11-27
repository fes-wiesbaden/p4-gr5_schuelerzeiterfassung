package de.feswiesbaden.attendance.service;

import static de.feswiesbaden.attendance.service.AttendanceAccountingService.BERLIN;

import de.feswiesbaden.attendance.enums.Weekday;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import de.feswiesbaden.attendance.repository.TimetableRepository;
import de.feswiesbaden.attendance.repository.TimetableRepository.SlotVersion;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Verwaltet Stundenpläne und ihre Unterrichtszeiten (Slots) */
@Service
public class TimetableService {
  private final TimetableRepository timetables;

  private final SchoolClassRepository classes;

  private final StaffAuthorization authorization;

  private final Clock clock;

  public TimetableService(
      TimetableRepository timetables,
      SchoolClassRepository classes,
      StaffAuthorization authorization,
      Clock clock) {
    this.timetables = timetables;
    this.classes = classes;
    this.authorization = authorization;
    this.clock = clock;
  }

  /** Prüft Administratorrechte und den Namen und liefert die ID des angelegten Stundenplans */
  @Transactional
  public Long create(String name) {
    authorization.requireAdministrator();
    if (name == null || name.isBlank() || name.strip().length() > 150) {
      throw new IllegalArgumentException("INVALID_TIMETABLE");
    }
    return timetables.insertTimetable(name.strip());
  }

  /**
   * Legt als Administrator einen neuen Stundenplan mit allen Unterrichtszeiten in einer Transaktion
   * an. Bei einem Fehler entsteht nichts; die Ausnahme nennt die Position der ersten fehlerhaften
   * Unterrichtszeit.
   *
   * <p>Mit classId und validFrom erhält eine bestehende Klasse den neuen Plan ab diesem Tag. Der
   * neue Stundenplan übernimmt dazu alle bisherigen Slotfassungen der Klasse als Kopie, begrenzt
   * auf die Zeit vor validFrom, und die neuen Unterrichtszeiten gelten erst ab validFrom. So
   * rechnen und zeigen alle früheren Tage unverändert den alten Plan, ohne dass die Abrechnung den
   * Stundenplan je Datum auflösen muss. Mit validUntil gelten die neuen Unterrichtszeiten nur bis
   * einschließlich dieses Tages; danach setzen Kopien des bisherigen Plans wieder ein. Ohne classId
   * begrenzen validFrom und validUntil nur die neuen Unterrichtszeiten. Der Aufrufer hält die
   * Sperre der Klasse
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Long importTimetable(
      String name,
      List<ImportSlot> slots,
      Long classId,
      LocalDate validFrom,
      LocalDate validUntil) {
    authorization.requireAdministrator();
    if (name == null || name.isBlank() || name.strip().length() > 150) {
      throw new TimetableImportException("INVALID_TIMETABLE", null);
    }
    if (slots == null || slots.isEmpty() || slots.size() > 80) {
      throw new TimetableImportException("INVALID_TIMETABLE_SLOT", null);
    }
    if (validUntil != null && (validFrom == null || validUntil.isBefore(validFrom))) {
      throw new TimetableImportException("INVALID_TIMETABLE_VALID_FROM", null);
    }
    Long timetableId = timetables.insertTimetable(name.strip());
    var now = LocalDateTime.ofInstant(clock.instant(), BERLIN);
    // valid_until ist exklusiv, die Angabe im Auftrag einschließlich
    var until = validUntil == null ? null : validUntil.plusDays(1);
    Long previous = null;
    if (classId != null) {
      previous =
          classes
              .findPlan(classId, false)
              .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"))
              .timetableId();
      timetables.copyHistory(previous, timetableId, validFrom);
      // Vor dem Einfügen, damit die Raumprüfung die Blöcke der Klasse berücksichtigt
      classes.updateTimetable(classId, timetableId);
    }
    for (int index = 0; index < slots.size(); index++) {
      var slot = slots.get(index);
      try {
        if (slot == null) {
          throw new IllegalArgumentException("INVALID_TIMETABLE_SLOT");
        }
        validateSlot(timetableId, slot.weekday(), slot.startsAt(), slot.endsAt(), slot.roomId());
        insertSlot(
            timetableId,
            slot.weekday(),
            slot.startsAt(),
            slot.endsAt(),
            slot.roomId(),
            validFrom == null ? nextDate(slot.weekday(), slot.startsAt(), now) : validFrom,
            until,
            null,
            validDetails(slot.subject(), slot.teacherId()));
      } catch (IllegalArgumentException | IllegalStateException invalid) {
        throw new TimetableImportException(invalid.getMessage(), index);
      }
    }
    if (previous != null && until != null) {
      // Erst nach den neuen Slots, da die Überschneidungsprüfung sonst die Fortsetzung träfe
      timetables.copyContinuation(previous, timetableId, until);
    }
    return timetableId;
  }

  /** Eine zu importierende Unterrichtszeit mit optionalem Fach und optionaler Lehrkraft */
  public record ImportSlot(
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId,
      String subject,
      Long teacherId) {}

  /**
   * Fügt einen Slot hinzu, sofern Stundenplan und Raum existieren, Beginn vor Ende liegt, sich der
   * Slot nicht mit einem vorhandenen Slot desselben Wochentags überlappt und keine Klasse mit
   * diesem Stundenplan dadurch einen Raum belegt, den eine gleichzeitig aktive Klasse nutzt. Der
   * Raumkonflikt wird nach dem Einfügen geprüft; eine Ablehnung rollt den Slot zurück. Fach und
   * Lehrkraft sind optional
   */
  // SERIALIZABLE, damit parallele Planungsänderungen die Konfliktprüfung nicht umgehen
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Long addSlot(
      Long timetableId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId,
      String subject,
      Long teacherId) {
    authorization.requireAdministrator();
    validateSlot(timetableId, weekday, startsAt, endsAt, roomId);
    var details = validDetails(subject, teacherId);
    var now = LocalDateTime.ofInstant(clock.instant(), BERLIN);
    return insertSlot(
        timetableId,
        weekday,
        startsAt,
        endsAt,
        roomId,
        nextDate(weekday, startsAt, now),
        null,
        null,
        details);
  }

  /**
   * Ersetzt den Slot ab einem noch nicht begonnenen Termin und liefert dessen Datum. Die neue
   * Fassung übernimmt Fach und Lehrkraft der bisherigen Fassung, soweit die Angaben sie nicht
   * ausdrücklich ändern
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public LocalDate updateSlot(
      Long timetableId,
      Long slotId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId,
      SlotDetails changes) {
    authorization.requireAdministrator();
    var old = lockSlot(timetableId, slotId);
    validateSlot(timetableId, weekday, startsAt, endsAt, roomId);
    // Eine unverändert übernommene Lehrkraft wird nicht erneut geprüft
    var details =
        new SlotDetails(
            false,
            changes.keepSubject() ? old.subject() : validSubject(changes.subject()),
            false,
            changes.keepTeacher() ? old.teacherId() : validTeacher(changes.teacherId()));
    var now = LocalDateTime.ofInstant(clock.instant(), BERLIN);
    var oldDate = nextDate(old.weekday(), old.startsAt(), now);
    var newDate = nextDate(weekday, startsAt, now);
    var week = monday(oldDate.isAfter(newDate) ? oldDate : newDate);
    if (old.validFrom() != null && week.isBefore(monday(old.validFrom()))) {
      week = monday(old.validFrom());
    }
    endSlot(old, week);
    var from = week.plusDays(weekday.ordinal());
    insertSlot(timetableId, weekday, startsAt, endsAt, roomId, from, null, old.seriesId(), details);
    return from;
  }

  /** Entfernt nur zukünftige Termine und liefert das Datum des ersten ausfallenden Termins */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public LocalDate deleteSlot(Long timetableId, Long slotId) {
    authorization.requireAdministrator();
    var current = lockSlot(timetableId, slotId);
    var now = LocalDateTime.ofInstant(clock.instant(), BERLIN);
    var until = nextDate(current, now);
    var firstCancelled = until;
    for (var previous :
        timetables.lockPredecessors(timetableId, current.seriesId(), now.toLocalDate())) {
      var next = nextDate(previous, now);
      if (next.isBefore(previous.validUntil())) {
        endSlot(previous, next);
        if (next.isBefore(firstCancelled)) {
          firstCancelled = next;
        }
      }
    }
    endSlot(current, until);
    return firstCancelled;
  }

  private void endSlot(SlotVersion slot, LocalDate until) {
    if (slot.validFrom() != null && !slot.validFrom().isBefore(until)) {
      timetables.deleteSlot(slot.id());
    } else {
      timetables.setSlotValidUntil(slot.id(), until);
    }
  }

  private SlotVersion lockSlot(Long timetableId, Long slotId) {
    return timetables
        .lockSlot(timetableId, slotId)
        .orElseThrow(() -> new IllegalArgumentException("TIMETABLE_SLOT_NOT_FOUND"));
  }

  private static LocalDate nextDate(Weekday weekday, LocalTime startsAt, LocalDateTime now) {
    var date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(weekday.dayOfWeek()));
    return date.atTime(startsAt).isAfter(now) ? date : date.plusWeeks(1);
  }

  private static LocalDate nextDate(SlotVersion slot, LocalDateTime now) {
    var next = nextDate(slot.weekday(), slot.startsAt(), now);
    return slot.validFrom() != null && next.isBefore(slot.validFrom()) ? slot.validFrom() : next;
  }

  private static LocalDate monday(LocalDate date) {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  private void validateSlot(
      Long timetableId, Weekday weekday, LocalTime startsAt, LocalTime endsAt, Long roomId) {
    if (timetableId == null
        || weekday == null
        || startsAt == null
        || endsAt == null
        || !startsAt.isBefore(endsAt)
        || roomId == null
        || !classes.hasTimetable(timetableId)
        || !timetables.roomExists(roomId)) {
      throw new IllegalArgumentException("INVALID_TIMETABLE_SLOT");
    }
  }

  private SlotDetails validDetails(String subject, Long teacherId) {
    return new SlotDetails(false, validSubject(subject), false, validTeacher(teacherId));
  }

  /** Kürzt das Fach, behandelt eine leere Angabe als fehlend und begrenzt es auf 80 Zeichen */
  private static String validSubject(String subject) {
    if (subject == null || subject.isBlank()) {
      return null;
    }
    if (subject.strip().length() > 80) {
      throw new IllegalArgumentException("INVALID_TIMETABLE_SLOT");
    }
    return subject.strip();
  }

  /** Verlangt für eine angegebene Lehrkraft einen Mitarbeiter mit der Rolle LEHRKRAFT */
  private Long validTeacher(Long teacherId) {
    if (teacherId != null && !classes.hasTeacher(teacherId)) {
      throw new IllegalArgumentException("INVALID_TIMETABLE_SLOT");
    }
    return teacherId;
  }

  /**
   * Enthält Fach und Lehrkraft eines Slots. Bei keepSubject oder keepTeacher gilt beim Ändern der
   * jeweilige Wert der bisherigen Fassung weiter
   */
  public record SlotDetails(
      boolean keepSubject, String subject, boolean keepTeacher, Long teacherId) {}

  private Long insertSlot(
      Long timetableId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId,
      LocalDate from,
      LocalDate until,
      Long seriesId,
      SlotDetails details) {
    if (timetables.hasOverlappingSlot(timetableId, weekday, startsAt, endsAt, from)) {
      throw new IllegalArgumentException("OVERLAPPING_TIMETABLE_SLOT");
    }
    Long slotId =
        timetables.insertSlot(
            timetableId,
            roomId,
            weekday,
            startsAt,
            endsAt,
            from,
            seriesId,
            details.subject(),
            details.teacherId());
    if (until != null) {
      // Vor der Raumprüfung, damit ein befristeter Slot nur seinen Zeitraum belegt
      timetables.setSlotValidUntil(slotId, until);
    }
    for (var period : classes.findPeriodsByTimetable(timetableId)) {
      requireRoomAvailable(
          period.classId(), timetableId, period.startsOn(), period.endsOn(), slotId);
    }
    return slotId;
  }

  /** Lehnt die Belegung ab, wenn im gemeinsamen Zeitraum ein Termin am Slot-Wochentag liegt */
  void requireRoomAvailable(
      Long classId, Long timetableId, LocalDate startsOn, LocalDate endsOn, Long slotId) {
    for (var overlap :
        timetables.findRoomOverlaps(classId, timetableId, startsOn, endsOn, slotId)) {
      var firstDate =
          overlap.startsOn().with(TemporalAdjusters.nextOrSame(overlap.weekday().dayOfWeek()));
      if (!firstDate.isAfter(overlap.endsOn())) {
        throw new IllegalStateException("ROOM_ALREADY_ASSIGNED");
      }
    }
  }
}
