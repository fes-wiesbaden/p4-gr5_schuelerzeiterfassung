package de.feswiesbaden.attendance.service;

import static de.feswiesbaden.attendance.service.AttendanceAccountingService.BERLIN;

import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Audit;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Slot;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.AbsenceLevel;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.ExcusalView;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.HistoryDay;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.PlannedStatusView;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class AttendanceHistory {
  private AttendanceHistory() {}

  static HistoryDay describe(
      LocalDate date,
      List<Slot> slots,
      List<Audit> audits,
      LocalDateTime now,
      boolean editable,
      boolean canCorrectStatus,
      List<PlannedStatusView> preview,
      AttendanceStatus storedFullAbsence,
      List<ExcusalView> excusals) {
    var presence = firstPresence(audits);
    long manual = AttendanceAccountingService.manualAbsenceMinutes(audits);
    if (slots.isEmpty()) {
      if (audits.isEmpty()) {
        return new HistoryDay(
            date, null, null, null, false, null, false, false, 0, false, false, List.of(), null,
            false, false, excusals);
      }
      long unexcused = audits.stream().mapToLong(Audit::unexcusedDelta).sum();
      long excused = audits.stream().mapToLong(Audit::excusedDelta).sum();
      return new HistoryDay(
          date,
          unexcused,
          excused,
          null,
          presence == null && unexcused + excused > 0,
          null,
          false,
          false,
          manual,
          false,
          false,
          List.of(),
          storedFullAbsence,
          AttendanceAccountingService.wasPresent(audits),
          false,
          excusals);
    }

    var teaching = teachingWindows(date, slots);
    var minutes = AttendanceAccountingService.calculate(date, slots, audits, now);
    long plannedSeconds = teaching.stream().mapToLong(Window::seconds).sum();
    long unexcusedSeconds = minutes.unexcusedSeconds();
    var level =
        unexcusedSeconds == 0 && manual == 0
            ? null
            : unexcusedSeconds == plannedSeconds ? AbsenceLevel.FULL : AbsenceLevel.PARTIAL;
    Long lateMinutes =
        presence == null
            ? null
            : lessonsBefore(teaching, presence.occurredAt().toInstant(ZoneOffset.UTC)) / 60;
    return new HistoryDay(
        date,
        minutes.unexcused() + manual,
        minutes.excused(),
        lateMinutes,
        presence == null,
        level,
        editable && unexcusedSeconds > 0,
        editable,
        manual,
        canCorrectStatus,
        false,
        preview,
        AttendanceAccountingService.fullAbsenceStatus(date, slots, audits, minutes, now),
        AttendanceAccountingService.wasPresent(audits),
        editable || (canCorrectStatus && AttendanceAccountingService.wasPresent(audits)),
        excusals);
  }

  static List<OpenAbsence> openAbsences(LocalDate date, List<Slot> slots, List<Audit> audits) {
    return openAbsences(teachingWindows(date, slots), audits);
  }

  private static List<Window> teachingWindows(LocalDate date, List<Slot> slots) {
    return slots.stream()
        .map(
            slot ->
                new Window(
                    date.atTime(slot.startsAt()).atZone(BERLIN).toInstant(),
                    date.atTime(slot.endsAt()).atZone(BERLIN).toInstant()))
        .toList();
  }

  private static List<OpenAbsence> openAbsences(List<Window> teaching, List<Audit> audits) {
    var events =
        audits.stream()
            .filter(audit -> audit.status() != null)
            .sorted(Comparator.comparing(Audit::occurredAt).thenComparing(Audit::id))
            .toList();
    var result = new ArrayList<OpenAbsence>();
    Instant from = teaching.getFirst().start();
    var status = AttendanceStatus.ABWESEND;
    for (int i = 0; i < events.size(); i++) {
      var event = events.get(i);
      // Bei gleicher Uhrzeit ist nur der letzte Status wirksam.
      if (i + 1 < events.size() && event.occurredAt().equals(events.get(i + 1).occurredAt())) {
        continue;
      }
      var at = event.occurredAt().toInstant(ZoneOffset.UTC);
      if (status == AttendanceStatus.ABWESEND) {
        addAbsence(teaching, from, at, result);
      }
      from = at;
      status = event.status();
    }
    if (status == AttendanceStatus.ABWESEND) {
      addAbsence(teaching, from, teaching.getLast().end(), result);
    }
    return result;
  }

  private static void addAbsence(
      List<Window> teaching, Instant from, Instant until, List<OpenAbsence> result) {
    Instant start = null;
    long seconds = 0;
    for (Window slot : teaching) {
      var overlapStart = slot.start().isAfter(from) ? slot.start() : from;
      var overlapEnd = slot.end().isBefore(until) ? slot.end() : until;
      if (overlapStart.isBefore(overlapEnd)) {
        if (start == null) {
          start = overlapStart;
        }
        seconds += Duration.between(overlapStart, overlapEnd).toSeconds();
      }
    }
    if (start != null) {
      result.add(new OpenAbsence(start, seconds));
    }
  }

  private static Audit firstPresence(List<Audit> audits) {
    return audits.stream()
        .filter(
            audit ->
                audit.status() == AttendanceStatus.ANWESEND
                    || audit.status() == AttendanceStatus.BETRIEB)
        .min(Comparator.comparing(Audit::occurredAt).thenComparing(Audit::id))
        .orElse(null);
  }

  private static long lessonsBefore(List<Window> teaching, Instant time) {
    return teaching.stream()
        .filter(slot -> slot.start().isBefore(time))
        .mapToLong(
            slot ->
                Duration.between(slot.start(), slot.end().isBefore(time) ? slot.end() : time)
                    .toSeconds())
        .sum();
  }

  record OpenAbsence(Instant startsAt, long seconds) {}

  private record Window(Instant start, Instant end) {
    long seconds() {
      return Duration.between(start, end).toSeconds();
    }
  }
}
