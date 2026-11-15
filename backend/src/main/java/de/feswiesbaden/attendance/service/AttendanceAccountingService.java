package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Account;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Audit;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Period;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Slot;
import de.feswiesbaden.attendance.repository.SchoolClassRepository.ClassPlan;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AttendanceAccountingService {
  static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  private final AttendanceAccountingRepository accounting;

  public AttendanceAccountingService(AttendanceAccountingRepository accounting) {
    this.accounting = accounting;
  }

  // Aufruf in der Klassentransaktion, nachdem Klasse, Löschfrist und Schüler gesperrt wurden.
  void requireFullyAccounted(ClassPlan plan, List<Account> accounts, LocalDateTime now) {
    var berlinNow = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN);
    var periods = accounting.findPeriods(plan.classId());
    var slots = accounting.findSlots(plan.timetableId());
    if (periods.stream().anyMatch(period -> !period.planId().equals(plan.planId()))) {
      throw new IllegalStateException("INVALID_CLASS_BLOCK_ASSIGNMENT");
    }
    LocalDate today = berlinNow.toLocalDate();
    if (accounts.stream().anyMatch(account -> account.unexcused() < 0 || account.excused() < 0)) {
      throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
    }
    if (!today.isBefore(plan.startsOn())
        && !today.isAfter(plan.endsOn())
        && active(periods, today)
        && slots.stream()
            .anyMatch(
                slot ->
                    slot.weekday() == today.getDayOfWeek()
                        && slot.endsAt().isAfter(berlinNow.toLocalTime()))) {
      throw new IllegalStateException("CLASS_HAS_REMAINING_SLOTS");
    }
    if (!today.isBefore(plan.endsOn().plusMonths(6))) {
      return;
    }
    for (Account account : accounts) {
      var attendances = accounting.findAttendances(account.id());
      var audits = accounting.findAudits(account.id());
      if (audits.stream().anyMatch(audit -> audit.occurredAt().isAfter(now))) {
        throw new IllegalStateException("INVALID_ATTENDANCE_HISTORY");
      }
      var reset =
          audits.stream()
              .filter(audit -> audit.type() == AuditEventType.ACCOUNT_RESET)
              .max(Comparator.comparing(Audit::id));
      long resetId = reset.map(Audit::id).orElse(0L);
      LocalDate resetDay = reset.map(audit -> berlinDate(audit.occurredAt())).orElse(LocalDate.MIN);
      var attendanceIds = attendances.stream().map(day -> day.id()).collect(Collectors.toSet());
      if (audits.stream()
          .anyMatch(
              audit -> audit.id() > resetId && !attendanceIds.contains(audit.attendanceId()))) {
        throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
      }
      long auditedUnexcused =
          audits.stream()
              .filter(audit -> audit.id() > resetId)
              .mapToLong(Audit::unexcusedDelta)
              .sum();
      long auditedExcused =
          audits.stream()
              .filter(audit -> audit.id() > resetId)
              .mapToLong(Audit::excusedDelta)
              .sum();
      if (account.unexcused() != auditedUnexcused || account.excused() != auditedExcused) {
        throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
      }
      var byDate = attendances.stream().collect(Collectors.toMap(day -> day.date(), day -> day));
      var byAttendance =
          audits.stream()
              .filter(audit -> audit.attendanceId() != null)
              .collect(Collectors.groupingBy(Audit::attendanceId));
      for (var attendance : attendances) {
        if (!attendance.date().isAfter(resetDay)
            && byAttendance.getOrDefault(attendance.id(), List.of()).stream()
                .anyMatch(audit -> audit.id() > resetId)) {
          throw new IllegalStateException("RESET_DAY_LOCKED");
        }
      }
      LocalDate start = plan.startsOn();
      LocalDate joined = berlinDate(account.createdAt());
      if (joined.isAfter(start)) {
        start = joined;
      }
      if (!resetDay.isBefore(start)) {
        start = resetDay.plusDays(1);
      }
      for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
        var weekday = date.getDayOfWeek();
        var daySlots = slots.stream().filter(slot -> slot.weekday() == weekday).toList();
        if (date.isAfter(plan.endsOn()) || !active(periods, date)) {
          daySlots = List.of();
        }
        var attendance = byDate.get(date);
        if (attendance == null) {
          if (daySlots.isEmpty()) {
            continue;
          }
          throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
        }
        var dayAudits = byAttendance.getOrDefault(attendance.id(), List.of());
        var target = calculate(date, daySlots, dayAudits);
        var lastStatus =
            dayAudits.stream()
                .map(Audit::status)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(AttendanceStatus.ABWESEND);
        if (dayAudits.isEmpty()
            || attendance.status() != lastStatus
            || target.unexcused() != dayAudits.stream().mapToLong(Audit::unexcusedDelta).sum()
            || target.excused() != dayAudits.stream().mapToLong(Audit::excusedDelta).sum()) {
          throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
        }
      }
    }
  }

  static Contribution calculate(LocalDate date, List<Slot> slots, List<Audit> audits) {
    var events =
        audits.stream()
            .filter(audit -> audit.status() != null)
            .sorted(Comparator.comparing(Audit::occurredAt).thenComparing(Audit::id))
            .toList();
    if (events.stream().anyMatch(event -> !berlinDate(event.occurredAt()).equals(date))) {
      throw new IllegalStateException("INVALID_ATTENDANCE_HISTORY");
    }
    long unexcused = 0;
    long excused = 0;
    for (Slot slot : slots) {
      var cursor = date.atTime(slot.startsAt()).atZone(BERLIN).toInstant();
      var end = date.atTime(slot.endsAt()).atZone(BERLIN).toInstant();
      var status = AttendanceStatus.ABWESEND;
      for (Audit event : events) {
        var at = event.occurredAt().toInstant(ZoneOffset.UTC);
        if (!at.isAfter(cursor)) {
          status = event.status();
        } else if (at.isBefore(end)) {
          long nanos = Duration.between(cursor, at).toNanos();
          if (status == AttendanceStatus.ABWESEND) {
            unexcused += nanos;
          } else if (excused(status)) {
            excused += nanos;
          }
          cursor = at;
          status = event.status();
        }
      }
      long nanos = Duration.between(cursor, end).toNanos();
      if (status == AttendanceStatus.ABWESEND) {
        unexcused += nanos;
      } else if (excused(status)) {
        excused += nanos;
      }
    }
    long minute = Duration.ofMinutes(1).toNanos();
    return new Contribution(unexcused / minute, excused / minute);
  }

  void requireFinalReset(
      ClassPlan plan, List<Account> accounts, Long deletionDateId, LocalDateTime now) {
    if (!berlinDate(now).isBefore(plan.endsOn().plusMonths(6))) {
      return;
    }
    var latestEnrollment =
        accounts.stream().map(Account::createdAt).max(Comparator.naturalOrder()).orElseThrow();
    for (Account account : accounts) {
      boolean reset =
          accounting.findAudits(account.id()).stream()
              .anyMatch(
                  audit ->
                      audit.type() == AuditEventType.ACCOUNT_RESET
                          && audit.attendanceId() == null
                          && audit.deletionDateId().equals(deletionDateId)
                          && berlinDate(audit.occurredAt()).isAfter(plan.endsOn())
                          && !audit.occurredAt().isBefore(latestEnrollment)
                          && !audit.occurredAt().isAfter(now));
      if (!reset) {
        throw new IllegalStateException("FINAL_CLASS_RESET_REQUIRED");
      }
    }
  }

  private static boolean excused(AttendanceStatus status) {
    return status == AttendanceStatus.ENTSCHULDIGT
        || status == AttendanceStatus.MIT_ATTEST_ENTSCHULDIGT
        || status == AttendanceStatus.BETRIEBLICH_ENTSCHULDIGT;
  }

  private static boolean active(List<Period> periods, LocalDate date) {
    return periods.stream()
        .anyMatch(period -> !date.isBefore(period.startsOn()) && !date.isAfter(period.endsOn()));
  }

  static LocalDate berlinDate(LocalDateTime utc) {
    return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN).toLocalDate();
  }

  record Contribution(long unexcused, long excused) {}
}
