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
   * Fügt einen Slot hinzu, sofern Stundenplan und Raum existieren, Beginn vor Ende liegt, sich der
   * Slot nicht mit einem vorhandenen Slot desselben Wochentags überlappt und keine Klasse mit
   * diesem Stundenplan dadurch einen Raum belegt, den eine gleichzeitig aktive Klasse nutzt. Der
   * Raumkonflikt wird nach dem Einfügen geprüft; eine Ablehnung rollt den Slot zurück
   */
  // SERIALIZABLE, damit parallele Planungsänderungen die Konfliktprüfung nicht umgehen
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Long addSlot(
      Long timetableId, Weekday weekday, LocalTime startsAt, LocalTime endsAt, Long roomId) {
    authorization.requireAdministrator();
    validateSlot(timetableId, weekday, startsAt, endsAt, roomId);
    var now = LocalDateTime.ofInstant(clock.instant(), BERLIN);
    return insertSlot(
        timetableId, weekday, startsAt, endsAt, roomId, nextDate(weekday, startsAt, now), null);
  }

  /** Ersetzt den Slot ab einem noch nicht begonnenen Termin und liefert dessen Datum */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public LocalDate updateSlot(
      Long timetableId,
      Long slotId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId) {
    authorization.requireAdministrator();
    var old = lockSlot(timetableId, slotId);
    validateSlot(timetableId, weekday, startsAt, endsAt, roomId);
    var now = LocalDateTime.ofInstant(clock.instant(), BERLIN);
    var oldDate = nextDate(old.weekday(), old.startsAt(), now);
    var newDate = nextDate(weekday, startsAt, now);
    var week = monday(oldDate.isAfter(newDate) ? oldDate : newDate);
    if (old.validFrom() != null && week.isBefore(monday(old.validFrom()))) {
      week = monday(old.validFrom());
    }
    endSlot(old, week);
    var from = week.plusDays(weekday.ordinal());
    insertSlot(timetableId, weekday, startsAt, endsAt, roomId, from, old.seriesId());
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

  private Long insertSlot(
      Long timetableId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId,
      LocalDate from,
      Long seriesId) {
    if (timetables.hasOverlappingSlot(timetableId, weekday, startsAt, endsAt, from)) {
      throw new IllegalArgumentException("OVERLAPPING_TIMETABLE_SLOT");
    }
    Long slotId =
        timetables.insertSlot(timetableId, roomId, weekday, startsAt, endsAt, from, seriesId);
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
