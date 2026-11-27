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
import de.feswiesbaden.attendance.repository.RawScanRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository.ClassPlan;
import de.feswiesbaden.attendance.repository.StudentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
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

  private final RawScanRepository rawScans;

  private final TerminalIdentityService terminalIdentity;

  private final Clock clock;

  private final TransactionTemplate transactions;

  public AttendanceAccountingService(
      AttendanceAccountingRepository accounting,
      SchoolClassRepository classes,
      StudentRepository students,
      DeletionDateService deletionDates,
      StaffAuthorization authorization,
      RawScanRepository rawScans,
      TerminalIdentityService terminalIdentity,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.accounting = accounting;
    this.classes = classes;
    this.students = students;
    this.deletionDates = deletionDates;
    this.authorization = authorization;
    this.rawScans = rawScans;
    this.terminalIdentity = terminalIdentity;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactions);
  }

  /**
   * Prüft die Terminalzuordnung und speichert Rohscan und Fachänderungen atomar. Neue Scans buchen
   * nur im zugeordneten Raum und Block aus ABWESEND oder BETRIEBLICH_ENTSCHULDIGT. Überlappende
   * Slots am Tag des Scans, ein durch eine andere Klasse zur selben Zeit belegter Raum oder eine
   * Blockzuordnung zu einem fremden Plan gelten als Datenproblem und lehnen den Scan neutral ab
   */
  @Transactional
  public ScanResult recordScan(String verifiedSubject, RawScanDTO scan, Instant receivedAt) {
    long roomId = terminalIdentity.resolveRoom(verifiedSubject, scan.terminalNumber());
    var capturedAt = scan.capturedAt();
    var at = LocalDateTime.ofInstant(capturedAt.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
    var received =
        LocalDateTime.ofInstant(receivedAt.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
    if (!rawScans.insertIfNew(scan.scanId(), scan.rfidUid(), at, received)) {
      return ScanResult.DUPLICATE;
    }
    if (capturedAt.getNano() != 0 || capturedAt.isAfter(receivedAt)) {
      return ScanResult.REJECTED;
    }

    var studentId = students.findIdByRfidUid(scan.rfidUid()).orElse(null);
    if (studentId == null) {
      return ScanResult.REJECTED;
    }
    var berlinTime = capturedAt.atZone(BERLIN).toLocalTime();
    Day day;
    try {
      day = lockDay(studentId, berlinDate(at));
    } catch (InvalidClassBlockAssignmentException dataProblem) {
      // Ein Datenproblem, das der Scan nicht lösen kann. Der Rohscan bleibt gespeichert und das
      // Terminal wiederholt ihn nicht. Technische Fehler rollen dagegen zurück, damit das Terminal
      // den Scan erneut senden kann
      return ScanResult.REJECTED;
    }
    if (day == null) {
      return ScanResult.REJECTED;
    }
    var slot =
        day.slots().stream()
            .filter(
                candidate ->
                    candidate.roomId() == roomId
                        && !berlinTime.isBefore(candidate.startsAt())
                        && berlinTime.isBefore(candidate.endsAt()))
            .findFirst()
            .orElse(null);
    if (slot == null
        || hasOverlappingSlots(day.slots())
        || accounting.roomDoubleBooked(
            day.plan().classId(), roomId, slot.startsAt(), slot.endsAt(), day.date())) {
      return ScanResult.REJECTED;
    }

    var before = statusAt(day.audits(), at);
    if (before != AttendanceStatus.ABWESEND
        && before != AttendanceStatus.BETRIEBLICH_ENTSCHULDIGT) {
      return ScanResult.REJECTED;
    }
    reconcile(
        day,
        new Change(
            AuditEventType.SCAN,
            before,
            AttendanceStatus.ANWESEND,
            at,
            null,
            scan.terminalNumber()));
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

  /** Liefert den Tagesstatus und die unentschuldigten Fehlminuten einer zugänglichen Klasse */
  @Transactional(readOnly = true)
  public List<DailyStudent> readDay(Long classId, LocalDate date) {
    authorization.requireClassAccess(classId, authorization.currentStaff());
    var now = now();
    var today = berlinDate(now);
    if (date == null || date.isAfter(today)) {
      throw new IllegalArgumentException("INVALID_ATTENDANCE_DATE");
    }
    var plan =
        classes
            .findPlan(classId, false)
            .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"));
    var slots = readDaySlots(plan, date, now);
    var audits = accounting.findDayAudits(classId, date, today);
    List<DailyStudent> result = new ArrayList<>();
    for (var student : students.findAttendanceStudents(classId)) {
      var day =
          readStudentDay(
              date, now, slots, audits.getOrDefault(student.id(), List.of()), student.createdAt());
      if (day != null) {
        result.add(
            new DailyStudent(
                student.id(),
                student.firstName(),
                student.lastName(),
                day.status(),
                day.unexcusedMinutes()));
      }
    }
    return result;
  }

  /**
   * Liest die Minutenkonten und Tagesanwesenheiten seit Beginn des laufenden Berliner Blocks. Eine
   * leere Schülerliste wählt die gesamte Klasse aus.
   */
  @Transactional(readOnly = true)
  public List<StudentExport> readExport(Map<Long, List<Long>> selection) {
    var actor = authorization.currentStaff();
    var accessible = classes.findAccessible(actor.id(), actor.administrator());
    var accessibleIds = accessible.stream().map(c -> c.id()).collect(Collectors.toSet());
    if (!accessibleIds.containsAll(selection.keySet())) {
      throw new AccessDeniedException("CLASS_ACCESS_DENIED");
    }
    var now = now();
    var today = berlinDate(now);
    List<StudentExport> result = new ArrayList<>();
    for (var schoolClass : accessible) {
      var selected = selection.get(schoolClass.id());
      if (selected == null) {
        continue;
      }
      var classId = schoolClass.id();
      var plan =
          classes
              .findPlan(classId, false)
              .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"));
      var periods = accounting.findPeriods(classId);
      requirePlanPeriods(plan, periods);
      var start =
          periods.stream()
              .filter(
                  period -> !today.isBefore(period.startsOn()) && !today.isAfter(period.endsOn()))
              .map(Period::startsOn)
              .min(LocalDate::compareTo)
              .orElseThrow(() -> new IllegalArgumentException("NO_ACTIVE_EXPORT_BLOCK"));
      var ids = Set.copyOf(selected);
      var roster =
          students.findAttendanceStudents(classId).stream()
              .filter(student -> ids.isEmpty() || ids.contains(student.id()))
              .toList();
      if (!ids.isEmpty() && ids.size() != roster.size()) {
        throw new IllegalArgumentException("INVALID_EXPORT_SELECTION");
      }
      var slots = accounting.findSlots(plan.timetableId());
      Map<Long, List<ExportDay>> days = new HashMap<>();
      for (var date = start; !date.isAfter(today); date = date.plusDays(1)) {
        var daySlots = slotsFor(plan, periods, slots, date);
        var audits = accounting.findDayAudits(classId, date, today);
        for (var student : roster) {
          var day =
              readStudentDay(
                  date,
                  now,
                  daySlots,
                  audits.getOrDefault(student.id(), List.of()),
                  student.createdAt());
          if (day != null) {
            days.computeIfAbsent(student.id(), id -> new ArrayList<>()).add(day);
          }
        }
      }
      for (var student : students.findByClassId(classId)) {
        if (ids.isEmpty() || ids.contains(student.id())) {
          result.add(
              new StudentExport(
                  schoolClass.classCode(),
                  student.firstName(),
                  student.lastName(),
                  student.unexcusedMinutes(),
                  student.excusedMinutes(),
                  days.getOrDefault(student.id(), List.of())));
        }
      }
    }
    return result;
  }

  /** Berechnet beide Tagesminutenwerte oder liest die Buchungen eines historischen Tags. */
  private static ExportDay readStudentDay(
      LocalDate date,
      LocalDateTime now,
      List<Slot> slots,
      List<Audit> audits,
      LocalDateTime createdAt) {
    boolean planned = !slots.isEmpty() && !date.isBefore(berlinDate(createdAt));
    if (!planned && audits.isEmpty()) {
      return null;
    }
    var status = statusAt(audits, now);
    if (!planned) {
      return new ExportDay(
          date,
          status,
          audits.stream().mapToLong(Audit::unexcusedDelta).sum(),
          audits.stream().mapToLong(Audit::excusedDelta).sum(),
          null);
    }
    var contribution = calculate(date, slots, audits, now);
    return new ExportDay(
        date,
        status,
        contribution.unexcused(),
        contribution.excused(),
        fullAbsenceStatus(date, now, slots, audits, contribution));
  }

  /** Ordnet einen vollständig versäumten und beendeten Unterrichtstag einem Fehltagszähler zu. */
  private static AttendanceStatus fullAbsenceStatus(
      LocalDate date,
      LocalDateTime now,
      List<Slot> slots,
      List<Audit> audits,
      Contribution contribution) {
    if (hasOverlappingSlots(slots)
        || date.atTime(slots.getLast().endsAt())
            .atZone(BERLIN)
            .toInstant()
            .isAfter(now.toInstant(ZoneOffset.UTC))
        || audits.stream().anyMatch(audit -> audit.status() == AttendanceStatus.ANWESEND)) {
      return null;
    }
    long teachingSeconds =
        slots.stream()
            .mapToLong(slot -> Duration.between(slot.startsAt(), slot.endsAt()).getSeconds())
            .sum();
    if (contribution.unexcusedSeconds() + contribution.excusedSeconds() != teachingSeconds) {
      return null;
    }
    return contribution.unexcusedSeconds() > 0
        ? AttendanceStatus.ABWESEND
        : AttendanceStatus.ENTSCHULDIGT;
  }

  /** Enthält Status, Minuten und die Einstufung als ganzer Fehltag. */
  public record ExportDay(
      LocalDate date,
      AttendanceStatus status,
      long unexcusedMinutes,
      long excusedMinutes,
      AttendanceStatus fullAbsence) {}

  /** Enthält die Minutenkonten und Blockanwesenheiten eines Schülers. */
  public record StudentExport(
      String classCode,
      String firstName,
      String lastName,
      int unexcusedMinutes,
      int excusedMinutes,
      List<ExportDay> days) {

    /** Zählt ganze Fehltage im exportierten Blockzeitraum. */
    public long countFullAbsences(AttendanceStatus status) {
      return days.stream().filter(day -> day.fullAbsence() == status).count();
    }
  }

  /**
   * Ermittelt die zugängliche Klasse, deren Slot am heutigen Berliner Tag gerade läuft. Ohne
   * laufenden Slot gilt die Klasse mit dem nächsten heutigen Slot, sonst keine Klasse. Haben
   * mehrere Klassen gleichzeitig Unterricht, etwa bei Administratoren, gewinnt die niedrigste
   * Klassen-ID. Klassen mit planfremder Blockzuordnung werden protokolliert und übersprungen
   */
  @Transactional(readOnly = true)
  public CurrentClass findCurrentClass() {
    var actor = authorization.currentStaff();
    var now = now();
    var berlin = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN);
    var today = berlin.toLocalDate();
    var time = berlin.toLocalTime();
    var serverTime = now.toInstant(ZoneOffset.UTC);
    Long nextClassId = null;
    LocalTime nextStart = null;
    for (var schoolClass : classes.findAccessible(actor.id(), actor.administrator())) {
      var plan = classes.findPlan(schoolClass.id(), false).orElse(null);
      if (plan == null) {
        continue;
      }
      List<Slot> slots;
      try {
        slots = readDaySlots(plan, today, now);
      } catch (InvalidClassBlockAssignmentException dataProblem) {
        LOG.warn("CURRENT_CLASS_SKIPPED_INVALID_BLOCK_ASSIGNMENT classId={}", schoolClass.id());
        continue;
      }
      for (var slot : slots) {
        if (!time.isBefore(slot.startsAt()) && time.isBefore(slot.endsAt())) {
          return new CurrentClass(schoolClass.id(), serverTime);
        }
        if (slot.startsAt().isAfter(time)
            && (nextStart == null || slot.startsAt().isBefore(nextStart))) {
          nextStart = slot.startsAt();
          nextClassId = schoolClass.id();
        }
      }
    }
    return new CurrentClass(nextClassId, serverTime);
  }

  /**
   * Enthält die vorausgewählte Klasse oder null und die Serverzeit, an der die Ansicht Tag und
   * Uhrzeit unabhängig von der Browseruhr ausrichtet
   */
  public record CurrentClass(Long classId, Instant serverTime) {}

  /** Liefert geplante Slots des Tages, solange die Aufbewahrungsfrist des Plans läuft */
  private List<Slot> readDaySlots(ClassPlan plan, LocalDate date, LocalDateTime now) {
    if (!retained(plan, now) || date.isBefore(plan.startsOn()) || date.isAfter(plan.endsOn())) {
      return List.of();
    }
    var periods = accounting.findPeriods(plan.classId(), false);
    requirePlanPeriods(plan, periods);
    return slotsFor(plan, periods, accounting.findSlots(plan.timetableId(), false), date);
  }

  /** Enthält sichtbare Schülerdaten mit Tagesstatus und unentschuldigten Fehlminuten des Tages */
  public record DailyStudent(
      Long studentId,
      String firstName,
      String lastName,
      AttendanceStatus status,
      long unexcusedMinutes) {}

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
      throw new InvalidClassBlockAssignmentException();
    }
  }

  /** Liefert die Slots des Wochentags innerhalb des Plans und eines zugeordneten Blocks */
  private static List<Slot> slotsFor(
      ClassPlan plan, List<Period> periods, List<Slot> slots, LocalDate date) {
    if (date.isBefore(plan.startsOn()) || date.isAfter(plan.endsOn()) || !active(periods, date)) {
      return List.of();
    }
    return slots.stream().filter(slot -> slot.occursOn(date)).toList();
  }

  /**
   * Prüft, ob sich Slots des Tages überschneiden. Erwartet nach Beginn sortierte Slots, daher
   * genügt der Vergleich mit dem Vorgänger. Direkt anschließende Slots überschneiden sich nicht
   */
  private static boolean hasOverlappingSlots(List<Slot> daySlots) {
    for (int i = 1; i < daySlots.size(); i++) {
      if (daySlots.get(i).startsAt().isBefore(daySlots.get(i - 1).endsAt())) {
        return true;
      }
    }
    return false;
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
    return new Contribution(unexcused, excused);
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

  /** Hält die Dauer je Konto für die Fehltagsprüfung sekundengenau. */
  record Contribution(long unexcusedSeconds, long excusedSeconds) {
    long unexcused() {
      return unexcusedSeconds / 60;
    }

    long excused() {
      return excusedSeconds / 60;
    }
  }

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
      Integer terminalId) {}

  /**
   * Enthält die resultierenden Fehlminutenkonten und gibt an, ob ein Änderungsprotokoll geschrieben
   * wurde
   */
  private record Booking(Account account, boolean changed) {}
}
