package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.enums.ScanResult;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Account;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Audit;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.DayAttendance;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Period;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Slot;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository.ClassPlan;
import de.feswiesbaden.attendance.repository.StudentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Berechnet Fehlminuten aus geplanten Unterrichtszeiten (Slots) und dem zeitlichen Statusverlauf.
 * Scans, Statuskorrekturen und Tagesabschlüsse verwenden denselben Abgleich mit den bereits
 * protokollierten Buchungen, damit Minuten nicht doppelt gebucht werden
 */
@Service
public class AttendanceAccountingService {
  static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  private static final Logger LOG = LoggerFactory.getLogger(AttendanceAccountingService.class);

  private final AttendanceAccountingRepository accounting;

  private final SchoolClassRepository classes;

  private final StudentRepository students;

  private final DeletionDateService deletionDates;

  private final StaffAuthorization authorization;

  private final Clock clock;

  private final TransactionTemplate transactions;

  public AttendanceAccountingService(
      AttendanceAccountingRepository accounting,
      SchoolClassRepository classes,
      StudentRepository students,
      DeletionDateService deletionDates,
      StaffAuthorization authorization,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.accounting = accounting;
    this.classes = classes;
    this.students = students;
    this.deletionDates = deletionDates;
    this.authorization = authorization;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactions);
  }

  /**
   * Verarbeitet einen Scan nur in einem passenden Slot und zugeordneten Block. Nur ABWESEND und
   * BETRIEBLICH_ENTSCHULDIGT dürfen zu ANWESEND wechseln. Abgelehnte Scans ändern weder
   * Tagesanwesenheit noch Fehlminutenkonten. Der Rückgabewert true bedeutet, dass gebucht wurde.
   *
   * <p>Der Aufrufer muss zuvor Terminalidentität und Kartenkennung prüfen und den Rohscan in
   * derselben Transaktion speichern
   */
  @Transactional
  public ScanResult recordScan(RawScanDTO scan) {

    Optional<Long> studentIdOptional = students.findByUUID(scan.rfidUid());
    if(studentIdOptional.isEmpty()){
      return ScanResult.ERROR;
    }
    Long studentId = studentIdOptional.get();

    if (!validTime(Instant.from(scan.scannedAt()))) {
      return ScanResult.ERROR;
    }
    var at = LocalDateTime.ofInstant(Instant.from(scan.scannedAt()), ZoneOffset.UTC);
    var day = lockDay(studentId, berlinDate(at));
    if (day == null
        || !accounting.matchesTerminalSlot(
            day.plan().timetableId(),
            scan.terminalId(),
            day.date(),
            scan.scannedAt().atZone(BERLIN).toLocalTime())) {
      return ScanResult.FORBIDDEN;
    }
    var before = statusAt(day.audits(), at);
    if (before != AttendanceStatus.ABWESEND
        && before != AttendanceStatus.BETRIEBLICH_ENTSCHULDIGT) {
      return ScanResult.FORBIDDEN;
    }
    reconcile(
            day,
            new Change(
                AuditEventType.SCAN, before, AttendanceStatus.ANWESEND, at, null, scan.terminalId()));

    return ScanResult.SUCCESS;
  }

  /**
   * Korrigiert einen Status ab dem angegebenen Zeitpunkt im Berliner Kalendertag. Spätere
   * Statusereignisse bleiben wirksam. Prüft Klassenrechte, Tageszugehörigkeit und eine nicht
   * zukünftige, sekundengenaue Ereigniszeit. Liefert true, wenn ein Statuswechsel oder eine
   * Änderung der Fehlminuten gebucht wurde
   */
  @Transactional
  public boolean correctStatus(
      Long studentId, LocalDate date, AttendanceStatus status, Instant occurredAt) {
    var actor = authorization.currentStaff();
    var classId =
        students
            .findClassId(studentId, false)
            .orElseThrow(() -> new IllegalArgumentException("STUDENT_NOT_FOUND"));
    authorization.requireClassAccess(classId, actor);
    if (status == null
        || !validTime(occurredAt)
        || !occurredAt.atZone(BERLIN).toLocalDate().equals(date)) {
      throw new IllegalArgumentException("INVALID_STATUS_TIME");
    }
    var day = lockDay(studentId, date);
    if (day == null) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    var at = LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
    return reconcile(
            day,
            new Change(
                AuditEventType.STATUS_CHANGE,
                statusAt(day.audits(), at),
                status,
                at,
                actor.id(),
                null))
        .changed();
  }

  /**
   * Rechnet beendete geplante Unterrichtstage je Schüler ab, je Klasse in einer eigenen
   * Transaktion. Fehler einer Klasse verhindern die übrigen Klassen nicht. Der Rückgabewert zählt
   * Tagesanwesenheiten, für die eine neue Buchung entstanden ist
   */
  public int closeDueDays() {
    int changed = 0;
    for (Long classId : accounting.findClassesToClose(berlinDate(now()))) {
      try {
        changed +=
            transactions.execute(
                transaction -> {
                  var plan = classes.lockPlan(classId).orElse(null);
                  if (plan == null || !retained(plan, now())) {
                    return 0;
                  }
                  var deadline = deletionDates.getOrCreate(plan.endsOn());
                  var accounts = accounting.lockAccounts(classId);
                  return completeDays(plan, accounts, deadline.getId(), now());
                });
      } catch (RuntimeException failure) {
        LOG.error("DAILY_CLOSE_CLASS_FAILED");
      }
    }
    return changed;
  }

  /**
   * Rechnet geplante Tage ab, deren letzter Slot bereits beendet ist, und zählt neue
   * Tagesbuchungen. Der Aufrufer muss Klasse, Löschdatum und Schüler in derselben Transaktion
   * gesperrt haben
   */
  int completeDays(ClassPlan plan, List<Account> accounts, Long deletionDateId, LocalDateTime now) {
    if (!retained(plan, now)) {
      return 0;
    }
    var periods = accounting.findPeriods(plan.classId());
    requirePlanPeriods(plan, periods);
    var slots = accounting.findSlots(plan.timetableId());
    int changed = 0;
    for (Account original : accounts) {
      var history = history(plan, original, deletionDateId, now);
      var account = original;
      var start = firstAccountingDate(plan, account, history.resetDay());
      var today = berlinDate(now);
      var end = plan.endsOn().isBefore(today) ? plan.endsOn() : today;
      for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
        var daySlots = slotsFor(plan, periods, slots, date);
        if (daySlots.isEmpty()) {
          continue;
        }
        var lastEnd = date.atTime(daySlots.getLast().endsAt()).atZone(BERLIN).toInstant();
        if (lastEnd.isAfter(now.toInstant(ZoneOffset.UTC))) {
          continue;
        }
        var attendance = history.attendances().get(date);
        var day =
            new Day(
                plan,
                account,
                date,
                deletionDateId,
                daySlots,
                attendance,
                history.dayAudits(attendance),
                now);
        var booked =
            reconcile(
                day,
                new Change(
                    AuditEventType.DAILY_CLOSE,
                    null,
                    null,
                    LocalDateTime.ofInstant(lastEnd, ZoneOffset.UTC),
                    null,
                    null));
        account = booked.account();
        if (booked.changed()) {
          changed++;
        }
      }
    }
    return changed;
  }

  /**
   * Lädt und sperrt die Abrechnungsdaten eines geplanten Schülertags. Liefert null bei fehlendem
   * Schüler, ungeplantem Tag, abgelaufener Aufbewahrungsfrist oder einem Tag vor der Schüleranlage
   * beziehungsweise am oder vor dem letzten Berliner Resettag
   */
  private Day lockDay(Long studentId, LocalDate date) {
    var classId = students.findClassId(studentId, false).orElse(null);
    if (classId == null) {
      return null;
    }
    var plan = classes.lockPlan(classId).orElse(null);
    if (plan == null
        || !retained(plan, now())
        || date.isBefore(plan.startsOn())
        || date.isAfter(plan.endsOn())) {
      return null;
    }
    var deadline = deletionDates.getOrCreate(plan.endsOn());
    var account = accounting.lockAccount(studentId, classId).orElse(null);
    var now = now();
    if (account == null || !retained(plan, now) || date.isBefore(berlinDate(account.createdAt()))) {
      return null;
    }
    var history = history(plan, account, deadline.getId(), now);
    if (!date.isAfter(history.resetDay())) {
      return null;
    }
    var periods = accounting.findPeriods(classId);
    requirePlanPeriods(plan, periods);
    var slots = slotsFor(plan, periods, accounting.findSlots(plan.timetableId()), date);
    if (slots.isEmpty()) {
      return null;
    }
    var attendance = history.attendances().get(date);
    return new Day(
        plan,
        account,
        date,
        deadline.getId(),
        slots,
        attendance,
        history.dayAudits(attendance),
        now);
  }

  /**
   * Berechnet beide Fehlminutensummen des Tages und zieht die bereits protokollierten Summen ab.
   * Nur diese Differenzen werden auf die Konten gebucht. Ein Statuswechsel wird auch ohne
   * Minutenänderung protokolliert. Ohne Status- oder Minutenänderung entsteht keine Buchung
   */
  private Booking reconcile(Day day, Change change) {
    var events = new ArrayList<>(day.audits());
    boolean statusChange = change.before() != change.after();
    if (statusChange) {
      events.add(
          new Audit(
              Long.MAX_VALUE,
              null,
              day.deletionDateId(),
              change.type(),
              change.at(),
              change.after(),
              0,
              0,
              change.before()));
    }
    var target = calculate(day.date(), day.slots(), events, day.now());
    int unexcusedDelta =
        Math.toIntExact(
            target.unexcused() - day.audits().stream().mapToLong(Audit::unexcusedDelta).sum());
    int excusedDelta =
        Math.toIntExact(
            target.excused() - day.audits().stream().mapToLong(Audit::excusedDelta).sum());
    if (!statusChange && unexcusedDelta == 0 && excusedDelta == 0) {
      return new Booking(day.account(), false);
    }
    var account =
        new Account(
            day.account().id(),
            Math.addExact(day.account().unexcused(), unexcusedDelta),
            Math.addExact(day.account().excused(), excusedDelta),
            day.account().createdAt());
    if (account.unexcused() < 0 || account.excused() < 0) {
      throw new IllegalStateException("INVALID_MINUTE_ACCOUNT");
    }
    var status = statusAt(events, day.now());
    var attendanceId =
        day.attendance() == null
            ? accounting.insertAttendance(account.id(), day.date(), status, day.now())
            : day.attendance().id();
    var audit =
        new Audit(
            null,
            attendanceId,
            day.deletionDateId(),
            change.type(),
            change.at(),
            statusChange ? change.after() : null,
            unexcusedDelta,
            excusedDelta,
            statusChange ? change.before() : null);
    accounting.book(
        account, attendanceId, status, audit, change.staffId(), change.terminalId(), day.now());
    return new Booking(account, true);
  }

  /**
   * Prüft, ob ein Zeitpunkt angegeben ist, keine Sekundenbruchteile hat und nicht in der Zukunft
   * liegt
   */
  private boolean validTime(Instant time) {
    return time != null && time.getNano() == 0 && !time.isAfter(clock.instant());
  }

  /** Liefert die aktuelle UTC-Zeit auf ganze Sekunden gekürzt */
  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
  }

  /**
   * Prüft, ob der aktuelle Berliner Tag vor dem Löschdatum sechs Kalendermonate nach Planende liegt
   */
  private static boolean retained(ClassPlan plan, LocalDateTime now) {
    return berlinDate(now).isBefore(plan.endsOn().plusMonths(6));
  }

  /** Verlangt, dass alle zugeordneten Blöcke zum aktuellen Klassenplan gehören */
  private static void requirePlanPeriods(ClassPlan plan, List<Period> periods) {
    if (periods.stream().anyMatch(period -> !period.planId().equals(plan.planId()))) {
      throw new IllegalStateException("INVALID_CLASS_BLOCK_ASSIGNMENT");
    }
  }

  /** Liefert die Slots des Wochentags innerhalb des Plans und eines zugeordneten Blocks */
  private static List<Slot> slotsFor(
      ClassPlan plan, List<Period> periods, List<Slot> slots, LocalDate date) {
    if (date.isBefore(plan.startsOn()) || date.isAfter(plan.endsOn()) || !active(periods, date)) {
      return List.of();
    }
    return slots.stream().filter(slot -> slot.weekday() == date.getDayOfWeek()).toList();
  }

  /**
   * Ermittelt den späteren Tag aus Planbeginn und dem Berliner Datum der Anlage des
   * Schülerdatensatzes. Gab es einen Kontenreset, beginnt die Abrechnung frühestens am Folgetag des
   * Resets
   */
  private static LocalDate firstAccountingDate(
      ClassPlan plan, Account account, LocalDate resetDay) {
    var start = plan.startsOn();
    var joined = berlinDate(account.createdAt());
    if (joined.isAfter(start)) {
      start = joined;
    }
    if (!resetDay.isBefore(start)) {
      start = resetDay.plusDays(1);
    }
    return start;
  }

  /**
   * Prüft die Anwesenheiten und Protokolle des aktuellen Plans gegen die Konten. Der letzte Reset
   * dieses Plans sperrt seinen Berliner Tag und alle früheren Tage des Plans.
   */
  private History history(ClassPlan plan, Account account, Long deletionDateId, LocalDateTime now) {
    var attendances =
        accounting.findAttendances(account.id()).stream()
            .filter(
                day -> !day.date().isBefore(plan.startsOn()) && !day.date().isAfter(plan.endsOn()))
            .toList();
    var attendanceIds = attendances.stream().map(DayAttendance::id).collect(Collectors.toSet());
    var audits =
        accounting.findAudits(account.id()).stream()
            .filter(
                audit ->
                    audit.attendanceId() == null
                        ? deletionDateId.equals(audit.deletionDateId())
                        : attendanceIds.contains(audit.attendanceId()))
            .toList();
    if (audits.stream().anyMatch(audit -> audit.occurredAt().isAfter(now))) {
      throw new IllegalStateException("INVALID_ATTENDANCE_HISTORY");
    }
    if (audits.stream()
        .anyMatch(
            audit ->
                (audit.status() == null) != (audit.oldStatus() == null)
                    || (audit.status() != null && audit.status() == audit.oldStatus()))) {
      throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
    }
    var reset =
        audits.stream()
            .filter(audit -> audit.type() == AuditEventType.ACCOUNT_RESET)
            .max(Comparator.comparing(Audit::id));
    long resetId = reset.map(Audit::id).orElse(0L);
    LocalDate resetDay = reset.map(audit -> berlinDate(audit.occurredAt())).orElse(LocalDate.MIN);
    if (audits.stream()
        .anyMatch(audit -> audit.id() > resetId && !attendanceIds.contains(audit.attendanceId()))) {
      throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
    }
    long auditedUnexcused =
        audits.stream()
            .filter(audit -> audit.id() > resetId)
            .mapToLong(Audit::unexcusedDelta)
            .sum();
    long auditedExcused =
        audits.stream().filter(audit -> audit.id() > resetId).mapToLong(Audit::excusedDelta).sum();
    if (account.unexcused() < 0
        || account.excused() < 0
        || account.unexcused() != auditedUnexcused
        || account.excused() != auditedExcused) {
      throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
    }
    var byDate = attendances.stream().collect(Collectors.toMap(DayAttendance::date, day -> day));
    var byAttendance =
        audits.stream()
            .filter(audit -> audit.attendanceId() != null)
            .collect(Collectors.groupingBy(Audit::attendanceId));
    for (var attendance : attendances) {
      var dayAudits = byAttendance.getOrDefault(attendance.id(), List.of());
      if (!attendance.date().isAfter(resetDay)
          && dayAudits.stream().anyMatch(audit -> audit.id() > resetId)) {
        throw new IllegalStateException("RESET_DAY_LOCKED");
      }
      if (dayAudits.isEmpty() || attendance.status() != statusAt(dayAudits, now)) {
        throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
      }
    }
    return new History(byDate, byAttendance, resetDay);
  }

  /**
   * Liefert den letzten Status bis einschließlich des angegebenen UTC-Zeitpunkts. Bei gleicher
   * Ereigniszeit gewinnt die höhere Audit-ID. Ohne Statusereignis gilt ABWESEND
   */
  private static AttendanceStatus statusAt(List<Audit> audits, LocalDateTime at) {
    return audits.stream()
        .filter(audit -> audit.status() != null && !audit.occurredAt().isAfter(at))
        .max(Comparator.comparing(Audit::occurredAt).thenComparing(Audit::id))
        .map(Audit::status)
        .orElse(AttendanceStatus.ABWESEND);
  }

  /**
   * Prüft nach Ende der heutigen Slots die Konten und Tagesabrechnung. Nach Ablauf der
   * Aufbewahrungsfrist entfällt die Prüfung der Historie. Der Aufrufer hält die Sperren für Klasse,
   * Löschdatum und Schüler
   */
  void requireFullyAccounted(
      ClassPlan plan, List<Account> accounts, Long deletionDateId, LocalDateTime now) {
    var periods = accounting.findPeriods(plan.classId());
    var slots = accounting.findSlots(plan.timetableId());
    requirePlanPeriods(plan, periods);
    LocalDate today = berlinDate(now);
    if (accounts.stream().anyMatch(account -> account.unexcused() < 0 || account.excused() < 0)) {
      throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
    }
    if (!retained(plan, now)) {
      return;
    }
    for (Account account : accounts) {
      var history = history(plan, account, deletionDateId, now);
      var start = firstAccountingDate(plan, account, history.resetDay());
      for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
        var daySlots = slotsFor(plan, periods, slots, date);
        var attendance = history.attendances().get(date);
        if (attendance == null) {
          var target = calculate(date, daySlots, List.of(), now);
          if (target.unexcused() == 0 && target.excused() == 0) {
            continue;
          }
          throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
        }
        var dayAudits = history.dayAudits(attendance);
        var target = calculate(date, daySlots, dayAudits, now);
        var lastStatus = statusAt(dayAudits, now);
        if (dayAudits.isEmpty()
            || attendance.status() != lastStatus
            || target.unexcused() != dayAudits.stream().mapToLong(Audit::unexcusedDelta).sum()
            || target.excused() != dayAudits.stream().mapToLong(Audit::excusedDelta).sum()) {
          throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
        }
      }
    }
  }

  /**
   * Verweigert den Vorgang, solange ein geplanter Slot des heutigen Berliner Tages noch nicht
   * beendet ist
   */
  void requireNoRemainingSlots(ClassPlan plan, LocalDateTime now) {
    var periods = accounting.findPeriods(plan.classId());
    requirePlanPeriods(plan, periods);
    var berlinNow = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN);
    if (slotsFor(plan, periods, accounting.findSlots(plan.timetableId()), berlinNow.toLocalDate())
        .stream()
        .anyMatch(slot -> slot.endsAt().isAfter(berlinNow.toLocalTime()))) {
      throw new IllegalStateException("CLASS_HAS_REMAINING_SLOTS");
    }
  }

  /**
   * Berechnet Fehlminuten bis zum UTC-Zeitpunkt through für den Berliner Kalendertag date. Nur Zeit
   * innerhalb der Slots zählt. Pausen und spätere Slotanteile bleiben unberücksichtigt. ABWESEND
   * erhöht unentschuldigte Minuten, die drei Entschuldigungsstatus erhöhen entschuldigte Minuten.
   * ANWESEND und BETRIEB erzeugen keine Fehlminuten.
   *
   * <p>Die Sekunden werden je Konto über alle Slots summiert und erst danach auf ganze Minuten
   * abgerundet
   */
  static Contribution calculate(
      LocalDate date, List<Slot> slots, List<Audit> audits, LocalDateTime through) {
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
      if (end.isAfter(through.toInstant(ZoneOffset.UTC))) {
        end = through.toInstant(ZoneOffset.UTC);
      }
      if (!end.isAfter(cursor)) {
        continue;
      }
      var status = AttendanceStatus.ABWESEND;
      for (Audit event : events) {
        var at = event.occurredAt().toInstant(ZoneOffset.UTC);
        if (!at.isAfter(cursor)) {
          status = event.status();
        } else if (at.isBefore(end)) {
          long seconds = Duration.between(cursor, at).getSeconds();
          if (status == AttendanceStatus.ABWESEND) {
            unexcused += seconds;
          } else if (excused(status)) {
            excused += seconds;
          }
          cursor = at;
          status = event.status();
        }
      }
      long seconds = Duration.between(cursor, end).getSeconds();
      if (status == AttendanceStatus.ABWESEND) {
        unexcused += seconds;
      } else if (excused(status)) {
        excused += seconds;
      }
    }
    return new Contribution(unexcused / 60, excused / 60);
  }

  /** Prüft, ob der Status zum entschuldigten Minutenkonto beiträgt */
  private static boolean excused(AttendanceStatus status) {
    return status == AttendanceStatus.ENTSCHULDIGT
        || status == AttendanceStatus.MIT_ATTEST_ENTSCHULDIGT
        || status == AttendanceStatus.BETRIEBLICH_ENTSCHULDIGT;
  }

  /** Prüft, ob der Tag einschließlich der Grenzen in einem zugeordneten Block liegt */
  private static boolean active(List<Period> periods, LocalDate date) {
    return periods.stream()
        .anyMatch(period -> !date.isBefore(period.startsOn()) && !date.isAfter(period.endsOn()));
  }

  /** Wandelt eine UTC-Zeit in den Berliner Kalendertag um */
  static LocalDate berlinDate(LocalDateTime utc) {
    return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN).toLocalDate();
  }

  /** Enthält die berechneten unentschuldigten und entschuldigten Tagesminuten */
  record Contribution(long unexcused, long excused) {}

  /**
   * Enthält Tagesanwesenheiten, deren Änderungsprotokolle und den Berliner Tag des letzten
   * Kontenresets
   */
  private record History(
      Map<LocalDate, DayAttendance> attendances,
      Map<Long, List<Audit>> byAttendance,
      LocalDate resetDay) {
    /** Liefert die Protokolle einer Tagesanwesenheit oder eine leere Liste */
    List<Audit> dayAudits(DayAttendance attendance) {
      return attendance == null ? List.of() : byAttendance.getOrDefault(attendance.id(), List.of());
    }
  }

  /** Bündelt die gesperrten Abrechnungsdaten eines Schülers für einen Tag */
  private record Day(
      ClassPlan plan,
      Account account,
      LocalDate date,
      Long deletionDateId,
      List<Slot> slots,
      DayAttendance attendance,
      List<Audit> audits,
      LocalDateTime now) {}

  /**
   * Beschreibt das auslösende Ereignis mit vorherigem und neuem Status, UTC-Zeit und optionalem
   * Akteur. Bei reinen Minutenbuchungen können beide Statuswerte null sein
   */
  private record Change(
      AuditEventType type,
      AttendanceStatus before,
      AttendanceStatus after,
      LocalDateTime at,
      Long staffId,
      Long terminalId) {}

  /**
   * Enthält die resultierenden Fehlminutenkonten und gibt an, ob ein Änderungsprotokoll geschrieben
   * wurde
   */
  private record Booking(Account account, boolean changed) {}
}
