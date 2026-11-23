package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.enums.Weekday;
import de.feswiesbaden.attendance.repository.TimetableRepository;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verwaltet die Anlage von Stundenplänen und ihren Unterrichtszeiten (Slots) */
@Service
public class TimetableService {
  private final TimetableRepository timetables;

  private final StaffAuthorization authorization;

  public TimetableService(TimetableRepository timetables, StaffAuthorization authorization) {
    this.timetables = timetables;
    this.authorization = authorization;
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
   * Fügt einen Slot hinzu, sofern Stundenplan und Raum existieren, Beginn vor Ende liegt und sich
   * der Slot nicht mit einem bereits vorhandenen Slot desselben Wochentags überlappt
   */
  @Transactional
  public Long addSlot(
      Long timetableId, Weekday weekday, LocalTime startsAt, LocalTime endsAt, Long roomId) {
    authorization.requireAdministrator();
    if (timetableId == null
        || weekday == null
        || startsAt == null
        || endsAt == null
        || !startsAt.isBefore(endsAt)
        || roomId == null
        || !timetables.timetableExists(timetableId)
        || !timetables.roomExists(roomId)) {
      throw new IllegalArgumentException("INVALID_TIMETABLE_SLOT");
    }
    if (timetables.hasOverlappingSlot(timetableId, weekday, startsAt, endsAt)) {
      throw new IllegalArgumentException("OVERLAPPING_TIMETABLE_SLOT");
    }
    return timetables.insertSlot(timetableId, roomId, weekday, startsAt, endsAt);
  }
}
