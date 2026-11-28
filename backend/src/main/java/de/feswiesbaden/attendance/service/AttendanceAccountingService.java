package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.enums.ScanResult;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Account;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Audit;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Cancellation;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.DayAttendance;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.FullDay;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.ManualAbsence;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Period;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.PlannedStatus;
import de.feswiesbaden.attendance.repository.AttendanceAccountingRepository.Slot;
import de.feswiesbaden.attendance.repository.RawScanRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository.ClassPlan;
import de.feswiesbaden.attendance.repository.StaffAccessRepository.StaffIdentity;
import de.feswiesbaden.attendance.repository.StudentRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
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

  /** Ab dieser Berliner Uhrzeit zählt ein Scan auch vor Beginn der nächsten Stunde */
  private static final LocalTime EARLIEST_CHECK_IN = LocalTime.of(7, 30);

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
   * nur im zugeordneten Raum und Block aus ABWESEND oder BETRIEBLICH_ENTSCHULDIGT. Vor einer
   * Stunde, in Pausen und während ausgefallener Stunden zählt der Scan ab 07:30 Uhr für die nächste
   * stattfindende Stunde des Tages. Überlappende Slots am Tag des Scans, ein durch eine andere
   * Klasse zur selben Zeit belegter Raum oder eine Blockzuordnung zu einem fremden Plan gelten als
   * Datenproblem und lehnen den Scan neutral ab
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
      day = lockDay(studentId, berlinDate(at), false);
    } catch (InvalidClassBlockAssignmentException dataProblem) {
      // Ein Datenproblem, das der Scan nicht lösen kann. Der Rohscan bleibt gespeichert und das
      // Terminal wiederholt ihn nicht. Technische Fehler rollen dagegen zurück, damit das Terminal
      // den Scan erneut senden kann
      return ScanResult.REJECTED;
    }
    if (day == null) {
      return ScanResult.REJECTED;
    }
    // Läuft gerade eine stattfindende Stunde, zählt der Scan nur in deren Raum. Außerhalb der
    // Stunden gilt er ab 07:30 Uhr für die nächste stattfindende Stunde und deren Raum
    var slot =
        day.slots().stream()
            .filter(
                candidate ->
                    !berlinTime.isBefore(candidate.startsAt())
                        && berlinTime.isBefore(candidate.endsAt()))
            .findFirst()
            .orElseGet(
                () ->
                    berlinTime.isBefore(EARLIEST_CHECK_IN)
                        ? null
                        : day.slots().stream()
                            .filter(candidate -> candidate.startsAt().isAfter(berlinTime))
                            .findFirst()
                            .orElse(null));
    if (slot == null
        || slot.roomId() != roomId
        || hasOverlappingSlots(day.slots())
        || accounting.roomDoubleBooked(
            day.plan().classId(), roomId, slot.startsAt(), slot.endsAt(), day.date())) {
      return ScanResult.REJECTED;
    }

    var before = statusAt(day.timeline(), at);
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
    requireStudentClassAccess(studentId, actor);
    if (status == null
        || !validTime(occurredAt)
        || !occurredAt.atZone(BERLIN).toLocalDate().equals(date)) {
      throw new IllegalArgumentException("INVALID_STATUS_TIME");
    }
    var day = lockDay(studentId, date, false);
    if (day == null) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    return applyStatusChange(day, status, occurredAt, actor.id());
  }

  /** Entschuldigt die noch offenen Unterrichtsfehlzeiten des gewählten Berliner Tags */
  @Transactional
  public boolean excuseOpenAbsences(Long studentId, LocalDate date) {
    var actor = authorization.currentStaff();
    requireStudentClassAccess(studentId, actor);
    var day = lockHistoryDay(studentId, date);
    if (calculate(date, day.slots(), day.timeline(), day.now()).unexcusedSeconds() == 0) {
      return false;
    }
    var changes =
        AttendanceHistory.openAbsences(date, day.slots(), day.timeline()).stream()
            .map(
                absence ->
                    new Change(
                        AuditEventType.STATUS_CHANGE,
                        AttendanceStatus.ABWESEND,
                        AttendanceStatus.ENTSCHULDIGT,
                        LocalDateTime.ofInstant(absence.startsAt(), ZoneOffset.UTC),
                        actor.id(),
                        null))
            .toList();
    return !changes.isEmpty() && reconcile(day, changes, null).changed();
  }

  /**
   * Bucht zusätzliche unentschuldigte Minuten mit einem Pflichtvermerk ohne Änderung des
   * Statusverlaufs
   */
  @Transactional
  public boolean addManualAbsence(
      Long studentId, LocalDate date, int minutes, UUID requestId, String note) {
    var actor = authorization.currentStaff();
    requireStudentClassAccess(studentId, actor);
    if (minutes < 1 || requestId == null) {
      throw new IllegalArgumentException("INVALID_MANUAL_ABSENCE_MINUTES");
    }
    if (note == null || note.isBlank() || note.strip().length() > 200) {
      throw new IllegalArgumentException("INVALID_MANUAL_ABSENCE_NOTE");
    }
    var day = lockEditableDay(studentId, date);
    // Nach Unterrichtsende ist der Tag buchbar, vorher nur nach erfasster Anwesenheit
    if (!lessonsOver(date, day.slots(), day.now())
        && !(date.equals(berlinDate(day.now()))
            && wasPresent(day.audits())
            && teachingTime(date, day.slots().getFirst().startsAt()).isBefore(day.now()))) {
      throw new IllegalArgumentException("INVALID_ATTENDANCE_DATE");
    }
    if (day.attendance() != null) {
      var recorded = accounting.findManualBooking(day.attendance().id(), requestId);
      if (recorded.isPresent()) {
        if (recorded.get() != minutes) {
          throw new IllegalArgumentException("ATTENDANCE_BOOKING_CHANGED");
        }
        return false;
      }
    }
    if (minutes > manualAbsenceLimit(date, day.slots(), day.timeline(), day.now())) {
      throw new IllegalArgumentException("MANUAL_ABSENCE_EXCEEDS_SCHOOL_DAY");
    }
    return reconcile(
            day,
            List.of(manualAbsenceChange(date, actor.id())),
            new ManualBooking(minutes, requestId, note.strip()))
        .changed();
  }

  /**
   * Nimmt alle händisch erfassten Fehlminuten des Berliner Tags zurück. Die bisherigen Buchungen
   * bleiben im Protokoll erhalten; eine Gegenbuchung gleicht sie auf den Konten und in der
   * Tageszählung aus. Liefert false, wenn der Tag keine händischen Fehlminuten enthält
   */
  @Transactional
  public boolean removeManualAbsence(Long studentId, LocalDate date) {
    var actor = authorization.currentStaff();
    requireStudentClassAccess(studentId, actor);
    // Auch nach einem Ausfall des ganzen Tages bleiben die Minuten so entfernbar
    var day = lockEditableDay(studentId, date, true);
    long recorded = manualAbsenceMinutes(day.audits());
    if (recorded <= 0) {
      return false;
    }
    return reconcile(
            day,
            List.of(manualAbsenceChange(date, actor.id())),
            new ManualBooking(Math.toIntExact(-recorded), null, null))
        .changed();
  }

  private static Change manualAbsenceChange(LocalDate date, Long staffId) {
    var datedAt = LocalDateTime.ofInstant(date.atStartOfDay(BERLIN).toInstant(), ZoneOffset.UTC);
    return new Change(AuditEventType.MANUAL_ABSENCE_MINUTES, null, null, datedAt, staffId, null);
  }

  /**
   * Liefert die noch zulässigen händischen Minuten: geplante Unterrichtsminuten abzüglich der bis
   * now berechneten und der bereits händisch gebuchten Fehlminuten des Tages
   */
  static long manualAbsenceLimit(
      LocalDate date, List<Slot> slots, List<Audit> timeline, LocalDateTime now) {
    var booked = calculate(date, slots, timeline, now);
    return Math.max(
        0,
        plannedMinutes(slots)
            - booked.unexcused()
            - booked.excused()
            - manualAbsenceMinutes(timeline));
  }

  /** Summiert die geplanten Unterrichtsminuten der Slots eines Tages ohne Pausen */
  static long plannedMinutes(List<Slot> slots) {
    return slots.stream()
            .mapToLong(slot -> Duration.between(slot.startsAt(), slot.endsAt()).getSeconds())
            .sum()
        / 60;
  }

  /** Ändert den Status im halboffenen Zeitraum und belässt die übrigen Statusereignisse */
  @Transactional
  public boolean correctStatusPeriod(
      Long studentId, LocalDate date, AttendanceStatus status, LocalTime start, LocalTime end) {
    var actor = authorization.currentStaff();
    requireStudentClassAccess(studentId, actor);
    var from = berlinTime(date, start, "INVALID_STATUS_TIME");
    var until = berlinTime(date, end, "INVALID_STATUS_TIME");
    if (status == null || !from.isBefore(until)) {
      throw new IllegalArgumentException("INVALID_STATUS_TIME");
    }
    var now = now();
    if (from.isAfter(now)) {
      return planStatusPeriod(new PlannedStatus(studentId, date, from, until, status), actor.id());
    }
    if (until.isAfter(now)) {
      throw new IllegalArgumentException("INVALID_STATUS_TIME");
    }
    var day = lockEditableDay(studentId, date);
    requireAllowedStatus(day, status);
    var changes = periodChanges(day.timeline(), status, from, until, actor.id(), true);
    return !changes.isEmpty() && reconcile(day, changes, null).changed();
  }

  /** Entschuldigt die Unterrichtszeit des gesamten Schultags gemäß Stundenplan */
  @Transactional
  public boolean excuseSchoolDay(Long studentId, LocalDate date) {
    var actor = authorization.currentStaff();
    requireStudentClassAccess(studentId, actor);
    if (date.isAfter(lastPreviewDate(berlinDate(now())))) {
      throw new IllegalArgumentException("INVALID_PLANNED_ATTENDANCE_DATE");
    }

    var day = lockEditableDay(studentId, date);
    var from = teachingTime(date, day.slots().getFirst().startsAt());
    var until = teachingTime(date, day.slots().getLast().endsAt());
    var status = AttendanceStatus.ENTSCHULDIGT;
    if (from.isAfter(day.now())) {
      return savePlannedStatus(
          day, new PlannedStatus(studentId, date, from, until, status), actor.id());
    }
    if (!until.isAfter(day.now())) {
      var changes = periodChanges(day.timeline(), status, from, until, actor.id(), true);
      return !changes.isEmpty() && reconcile(day, changes, null).changed();
    }

    // Die laufende Entschuldigung gilt weiter, ohne ein Audit in der Zukunft anzulegen.
    if (day.plannedStatuses().stream()
        .anyMatch(
            planned ->
                planned.status() != status
                    && planned.start().isBefore(until)
                    && planned.end().isAfter(day.now()))) {
      throw new IllegalArgumentException("OVERLAPPING_PLANNED_ATTENDANCE_STATUS");
    }
    var changes = periodChanges(day.timeline(), status, from, day.now(), actor.id(), false);
    var current = statusAt(projectChanges(day, changes), day.now());
    addStatusChange(changes, current, status, day.now(), actor.id());
    return !changes.isEmpty() && reconcile(day, changes, null).changed();
  }

  private boolean planStatusPeriod(PlannedStatus planned, Long staffId) {
    if (planned.status() != AttendanceStatus.ENTSCHULDIGT
        && planned.status() != AttendanceStatus.BETRIEBLICH_ENTSCHULDIGT) {
      throw new IllegalArgumentException("INVALID_PLANNED_ATTENDANCE_STATUS");
    }
    var now = now();
    if (!planned.start().isAfter(now) || planned.date().isAfter(lastPreviewDate(berlinDate(now)))) {
      throw new IllegalArgumentException("INVALID_PLANNED_ATTENDANCE_DATE");
    }
    var day = lockDay(planned.studentId(), planned.date(), false);
    if (day == null || hasOverlappingSlots(day.slots())) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    return savePlannedStatus(day, planned, staffId);
  }

  private boolean savePlannedStatus(Day day, PlannedStatus planned, Long staffId) {
    if (!planned.start().isAfter(day.now())) {
      throw new IllegalArgumentException("INVALID_PLANNED_ATTENDANCE_DATE");
    }
    boolean scheduledLesson =
        day.slots().stream()
            .anyMatch(
                slot ->
                    teachingTime(day.date(), slot.startsAt()).isBefore(planned.end())
                        && teachingTime(day.date(), slot.endsAt()).isAfter(planned.start()));
    if (!scheduledLesson) {
      throw new IllegalArgumentException("INVALID_PLANNED_ATTENDANCE_TIME");
    }
    if (day.plannedStatuses().contains(planned)) {
      return false;
    }
    if (day.plannedStatuses().stream()
        .anyMatch(
            previous ->
                previous.start().isBefore(planned.end())
                    && previous.end().isAfter(planned.start()))) {
      throw new IllegalArgumentException("OVERLAPPING_PLANNED_ATTENDANCE_STATUS");
    }
    accounting.insertPlannedStatus(planned, staffId, day.deletionDateId(), day.now());
    return true;
  }

  private static LocalDate lastPreviewDate(LocalDate today) {
    return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusWeeks(2);
  }

  private static LocalDateTime teachingTime(LocalDate date, LocalTime time) {
    return LocalDateTime.ofInstant(date.atTime(time).atZone(BERLIN).toInstant(), ZoneOffset.UTC);
  }

  private static List<Change> periodChanges(
      List<Audit> audits,
      AttendanceStatus status,
      LocalDateTime from,
      LocalDateTime until,
      Long staffId,
      boolean restoreAfterPeriod) {
    var original = new TreeMap<LocalDateTime, AttendanceStatus>();
    audits.stream()
        .filter(audit -> audit.status() != null)
        .sorted(Comparator.comparing(Audit::id))
        .forEach(audit -> original.put(audit.occurredAt(), audit.status()));
    var changes = new ArrayList<Change>();
    addStatusChange(changes, statusAt(audits, from), status, from, staffId);
    original
        .subMap(from, false, until, !restoreAfterPeriod)
        .forEach((time, before) -> addStatusChange(changes, before, status, time, staffId));
    if (restoreAfterPeriod && !changes.isEmpty() && !original.containsKey(until)) {
      addStatusChange(changes, status, statusAt(audits, until), until, staffId);
    }
    return changes;
  }

  private static void addStatusChange(
      List<Change> changes,
      AttendanceStatus oldStatus,
      AttendanceStatus newStatus,
      LocalDateTime at,
      Long staffId) {
    if (oldStatus != newStatus) {
      changes.add(
          new Change(AuditEventType.STATUS_CHANGE, oldStatus, newStatus, at, staffId, null));
    }
  }

  /** Wandelt eine eindeutige, sekundengenaue Berliner Uhrzeit in UTC um, sonst gilt der Code */
  private static LocalDateTime berlinTime(LocalDate date, LocalTime time, String invalidCode) {
    if (time == null || time.getNano() != 0) {
      throw new IllegalArgumentException(invalidCode);
    }
    var local = date.atTime(time);
    var offsets = BERLIN.getRules().getValidOffsets(local);
    if (offsets.size() != 1) {
      throw new IllegalArgumentException(invalidCode);
    }
    return LocalDateTime.ofInstant(local.toInstant(offsets.getFirst()), ZoneOffset.UTC);
  }

  /** Sperrt einen Tag, dessen stattfindender Unterricht bereits vollständig beendet ist */
  private Day lockHistoryDay(Long studentId, LocalDate date) {
    if (date.isAfter(berlinDate(now()))) {
      throw new IllegalArgumentException("INVALID_ATTENDANCE_DATE");
    }
    var day = lockEditableDay(studentId, date);
    if (!lessonsOver(day.date(), day.slots(), day.now())) {
      throw new IllegalArgumentException("INVALID_ATTENDANCE_DATE");
    }
    return day;
  }

  /** Prüft, ob die letzte stattfindende Stunde des Tages bis now beendet ist */
  private static boolean lessonsOver(LocalDate date, List<Slot> slots, LocalDateTime now) {
    return !slots.isEmpty() && !teachingTime(date, slots.getLast().endsAt()).isAfter(now);
  }

  private Day lockEditableDay(Long studentId, LocalDate date) {
    return lockEditableDay(studentId, date, false);
  }

  /** Wie oben; mit withoutLessons auch für einen Tag, dessen Unterricht ganz ausgefallen ist */
  private Day lockEditableDay(Long studentId, LocalDate date, boolean withoutLessons) {
    var day = lockDay(studentId, date, withoutLessons);
    if (day == null || hasOverlappingSlots(day.slots())) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    return day;
  }

  private Long requireStudentClassAccess(Long studentId, StaffIdentity actor) {
    var classId =
        students
            .findClassId(studentId, false)
            .orElseThrow(() -> new IllegalArgumentException("STUDENT_NOT_FOUND"));
    authorization.requireClassAccess(classId, actor);
    return classId;
  }

  private boolean applyStatusChange(
      Day day, AttendanceStatus status, Instant occurredAt, Long staffId) {
    requireAllowedStatus(day, status);
    var at = LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
    var changes = new ArrayList<Change>();
    if (status == AttendanceStatus.ABWESEND) {
      // Rücknahme eines Fehlklicks: Jede händisch gesetzte Anwesenheit ab dem neuen Zeitpunkt wird
      // zu ihrem eigenen Zeitpunkt überschrieben. Frühere Anwesenheit bleibt bestehen
      day.audits().stream()
          .filter(
              audit ->
                  manualPresence(audit)
                      && !audit.occurredAt().isBefore(at)
                      && !retracted(audit, day.audits()))
          .map(Audit::occurredAt)
          .distinct()
          .sorted()
          .forEach(
              time ->
                  addStatusChange(changes, statusAt(day.audits(), time), status, time, staffId));
    }
    addStatusChange(changes, statusAt(projectChanges(day, changes), at), status, at, staffId);
    if (changes.isEmpty()) {
      // Ohne Statuswechsel gleicht der Abgleich wie bisher nur offene Minuten aus
      changes.add(new Change(AuditEventType.STATUS_CHANGE, status, status, at, staffId, null));
    }
    return reconcile(day, changes, null).changed();
  }

  private static void requireAllowedStatus(Day day, AttendanceStatus status) {
    if (status == AttendanceStatus.ABWESEND && !canMarkAbsent(day.audits())) {
      throw new IllegalArgumentException("ATTENDANCE_ALREADY_PRESENT");
    }
  }

  /**
   * ABWESEND bleibt als Korrektur zulässig, solange kein RFID-Scan des Tages vorliegt. Eine nur
   * händisch gesetzte Anwesenheit lässt sich damit zurücknehmen
   */
  static boolean canMarkAbsent(List<Audit> audits) {
    return audits.stream().noneMatch(audit -> audit.type() == AuditEventType.SCAN);
  }

  /** Prüft, ob am Tag eine nicht zurückgenommene Anwesenheit erfasst ist */
  static boolean wasPresent(List<Audit> audits) {
    return audits.stream()
        .anyMatch(
            audit -> audit.status() == AttendanceStatus.ANWESEND && !retracted(audit, audits));
  }

  private static boolean manualPresence(Audit audit) {
    return audit.type() == AuditEventType.STATUS_CHANGE
        && audit.status() == AttendanceStatus.ANWESEND;
  }

  /**
   * Eine händisch gesetzte Anwesenheit gilt als zurückgenommen, wenn eine spätere Korrektur sie zum
   * selben Zeitpunkt mit ABWESEND überschreibt. Scans lassen sich nicht zurücknehmen
   */
  static boolean retracted(Audit presence, List<Audit> audits) {
    return manualPresence(presence)
        && audits.stream()
            .anyMatch(
                later ->
                    later.status() == AttendanceStatus.ABWESEND
                        && later.id() != null
                        && presence.id() != null
                        && later.id() > presence.id()
                        && later.occurredAt().equals(presence.occurredAt()));
  }

  /** Berechnet die Auswirkungen einer Entschuldigung ohne Buchung. */
  @Transactional(readOnly = true)
  public ExcusalImpact previewExcusal(Excusal request) {
    return excusalImpact(excusalBookings(request, authorization.currentStaff(), false));
  }

  /** Prüft Vorschau und Rechte erneut und bucht alle bestätigten Schultage gemeinsam. */
  @Transactional
  public ExcusalImpact confirmExcusal(Excusal request, ExcusalImpact expected) {
    var bookings = excusalBookings(request, authorization.currentStaff(), true);
    var current = excusalImpact(bookings);
    if (!current.equals(expected) || !current.hasEffect()) {
      throw new IllegalStateException("EXCUSAL_PREVIEW_CHANGED");
    }
    var account = bookings.getFirst().day().account();
    for (var booking : bookings) {
      account = reconcile(booking.day().withAccount(account), booking.change()).account();
    }
    return current;
  }

  /** Zeitfenster oder ganztägige Entschuldigung mit optionalem Grund in Berliner Ortszeit. */
  public record Excusal(
      @NotNull @Positive Long classId,
      @NotNull @Positive Long studentId,
      @NotNull LocalDate date,
      LocalDate endDate,
      LocalTime startsAt,
      LocalTime endsAt,
      AttendanceStatus status,
      @Size(max = 250) String reason) {}

  public record ExcusalImpact(long unexcusedRemoved, long excusedAdded, List<ExcusalDay> days) {
    public boolean hasEffect() {
      return !days.isEmpty();
    }
  }

  public record ExcusalDay(
      LocalDate date,
      boolean pending,
      AttendanceStatus before,
      AttendanceStatus after,
      long unexcusedRemoved,
      long excusedAdded) {}

  private record ExcusalBooking(Day day, Change change, ExcusalDay impact) {}

  private List<ExcusalBooking> excusalBookings(Excusal request, StaffIdentity actor, boolean lock) {
    authorization.requireClassAccess(request.classId(), actor);
    if (!request.classId().equals(requireStudentClassAccess(request.studentId(), actor))) {
      throw new IllegalArgumentException("STUDENT_NOT_FOUND");
    }
    var plan =
        classes
            .findPlan(request.classId(), lock)
            .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"));
    var endDate = request.endDate() == null ? request.date() : request.endDate();
    var status = request.status() == null ? AttendanceStatus.ENTSCHULDIGT : request.status();
    boolean timed = request.startsAt() != null || request.endsAt() != null;
    if (!excused(status)
        || endDate.isBefore(request.date())
        || (!endDate.equals(request.date())
            && (timed || status != AttendanceStatus.MIT_ATTEST_ENTSCHULDIGT))
        || (timed && (request.startsAt() == null || request.endsAt() == null))) {
      throw new IllegalArgumentException("INVALID_EXCUSAL_TIME");
    }
    if (request.date().isBefore(plan.startsOn()) || endDate.isAfter(plan.endsOn())) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    var periods = accounting.findPeriods(request.classId(), lock);
    requirePlanPeriods(plan, periods);
    var slots = accounting.findSlots(plan.timetableId(), lock);
    var cancellations = accounting.findCancellations(request.classId());
    var now = now();
    if (!retained(plan, now)) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    var deadlineId =
        lock
            ? deletionDates.getOrCreate(plan.endsOn()).getId()
            : deletionDates.findId(plan.endsOn());
    var account = accounting.findAccount(request.studentId(), request.classId(), lock).orElse(null);
    now = now();
    if (account == null || !retained(plan, now)) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    var history = history(plan, account, deadlineId, now, lock);
    var planned =
        plannedByDate(request.studentId(), request.date(), endDate, berlinDate(now), lock);
    var bookings = new ArrayList<ExcusalBooking>();
    boolean scheduled = false;
    for (var date = request.date(); !date.isAfter(endDate); date = date.plusDays(1)) {
      var daySlots = slotsFor(plan, periods, slots, cancellations, date);
      if (daySlots.isEmpty()) {
        continue;
      }
      scheduled = true;
      if (date.isBefore(berlinDate(account.createdAt()))
          || !date.isAfter(history.resetDay())
          || hasOverlappingSlots(daySlots)) {
        throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
      }
      var attendance = history.attendances().get(date);
      var day =
          new Day(
              plan,
              account,
              date,
              deadlineId,
              daySlots,
              attendance,
              history.dayAudits(attendance),
              planned.getOrDefault(date, List.of()),
              now);
      var start =
          timed
              ? berlinTime(date, request.startsAt(), "INVALID_EXCUSAL_TIME")
              : teachingTime(date, day.slots().getFirst().startsAt());
      var end =
          timed
              ? berlinTime(date, request.endsAt(), "INVALID_EXCUSAL_TIME")
              : teachingTime(date, day.slots().getLast().endsAt());
      if (!start.isBefore(end) || (timed && end.isAfter(day.now()))) {
        throw new IllegalArgumentException("INVALID_EXCUSAL_TIME");
      }
      var reason =
          request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
      var change =
          new Change(
              AuditEventType.TIMED_EXCUSAL,
              null,
              null,
              start,
              actor.id(),
              null,
              new ExcusalWindow(end, status, reason));
      boolean pending = end.isAfter(day.now());
      var through = pending ? end : day.now();
      var before = calculate(date, day.slots(), day.timeline(), through);
      var afterTimeline =
          includePlannedStatuses(projectChanges(day, List.of(change)), day.plannedStatuses());
      var after = calculate(date, day.slots(), afterTimeline, through);
      if (before.unexcusedSeconds() > after.unexcusedSeconds()) {
        bookings.add(
            new ExcusalBooking(
                day,
                change,
                new ExcusalDay(
                    date,
                    pending,
                    fullDay(day, day.timeline(), before, through).status(),
                    fullDay(day, afterTimeline, after, through).status(),
                    before.unexcused() - after.unexcused(),
                    after.excused() - before.excused())));
      }
    }
    if (!scheduled) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    return bookings;
  }

  private static ExcusalImpact excusalImpact(List<ExcusalBooking> bookings) {
    var days = bookings.stream().map(ExcusalBooking::impact).toList();
    return new ExcusalImpact(
        days.stream().mapToLong(ExcusalDay::unexcusedRemoved).sum(),
        days.stream().mapToLong(ExcusalDay::excusedAdded).sum(),
        days);
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
    var plan = readPlan(classId);
    var slots = daySlots(plan, now).apply(date);
    var audits = accounting.findDayAudits(classId, date, today);
    var plannedStatuses =
        accounting.findClassPlannedStatuses(classId, date, today).stream()
            .collect(Collectors.groupingBy(PlannedStatus::studentId));
    List<DailyStudent> result = new ArrayList<>();
    for (var student : students.findAttendanceStudents(classId)) {
      var dayAudits = audits.getOrDefault(student.id(), List.of());
      boolean planned = !slots.isEmpty() && !date.isBefore(berlinDate(student.createdAt()));
      if (!planned && dayAudits.isEmpty()) {
        continue;
      }
      var timeline =
          includePlannedStatuses(dayAudits, plannedStatuses.getOrDefault(student.id(), List.of()));
      var contribution = planned ? calculate(date, slots, timeline, now) : null;
      var full = planned ? fullAbsenceStatus(date, slots, timeline, contribution, now) : null;
      long minutes =
          planned
              ? contribution.unexcused() + manualAbsenceMinutes(dayAudits)
              : dayAudits.stream().mapToLong(Audit::unexcusedDelta).sum();
      var status = statusAt(timeline, now);
      boolean present = wasPresent(dayAudits);
      if (status == AttendanceStatus.ABWESEND) {
        status =
            present
                ? AttendanceStatus.ANWESEND
                : displayedExcusal(timeline, slots, date, now, full);
      }
      result.add(
          new DailyStudent(
              student.id(),
              student.firstName(),
              student.lastName(),
              status,
              minutes,
              present,
              full,
              manualAbsenceMinutes(dayAudits),
              planned
                  ? contribution.excused()
                  : dayAudits.stream().mapToLong(Audit::excusedDelta).sum(),
              planned && canMarkAbsent(dayAudits),
              student.birthDate()));
    }
    return result;
  }

  private static AttendanceStatus displayedExcusal(
      List<Audit> timeline,
      List<Slot> slots,
      LocalDate date,
      LocalDateTime now,
      AttendanceStatus full) {
    var endOfLessons = slots.isEmpty() ? now : teachingTime(date, slots.getLast().endsAt());
    var at =
        full == AttendanceStatus.ENTSCHULDIGT && !endOfLessons.isAfter(now)
            ? endOfLessons.minusSeconds(1)
            : now;
    return timeline.stream()
        .filter(
            audit ->
                audit.excusalEndsAt() != null
                    && !audit.occurredAt().isAfter(at)
                    && audit.excusalEndsAt().isAfter(at))
        .max(Comparator.comparing(Audit::id))
        .map(
            audit ->
                audit.excusalStatus() == null
                    ? AttendanceStatus.ENTSCHULDIGT
                    : audit.excusalStatus())
        // Eine ganztägige Statuskorrektur endet mit der letzten Stunde; sie bleibt danach sichtbar
        .orElseGet(
            () -> {
              var status = statusAt(timeline, at);
              return excused(status) ? status : AttendanceStatus.ABWESEND;
            });
  }

  /** Liefert vergangene Berliner Tagesdaten der Woche eines autorisierten Schülers */
  @Transactional(readOnly = true)
  public HistoryWeek readWeek(Long studentId, LocalDate date) {
    var classId = requireStudentClassAccess(studentId, authorization.currentStaff());
    var now = now();
    var today = berlinDate(now);
    if (date.isAfter(lastPreviewDate(today))) {
      throw new IllegalArgumentException("INVALID_ATTENDANCE_DATE");
    }
    var joined = joinedOn(studentId, classId);
    var plan = readPlan(classId);
    var weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    var weekEnd = weekStart.plusDays(6);
    var lessons = daySlots(plan, now);
    var audits = accounting.findStudentDayAudits(studentId, weekStart, weekEnd, today);
    var fullAbsences = accounting.findFullAbsences(studentId, weekStart, weekEnd, today);
    var plannedStatuses = plannedByDate(studentId, weekStart, weekEnd, today, false);
    var manualEntries = accounting.findManualEntries(studentId, weekStart, weekEnd, today);
    var resetDay =
        accounting
            .latestResetAt(studentId)
            .map(AttendanceAccountingService::berlinDate)
            .orElse(LocalDate.MIN);
    var days = new ArrayList<HistoryDay>();
    for (var day = weekStart; !day.isAfter(weekEnd); day = day.plusDays(1)) {
      var dayPlans = plannedStatuses.getOrDefault(day, List.of());
      var dayAudits = audits.getOrDefault(day, List.of());
      var excusals =
          dayAudits.stream()
              .filter(audit -> audit.excusalEndsAt() != null)
              .sorted(Comparator.comparing(Audit::occurredAt))
              .map(ExcusalView::from)
              .toList();
      var slots = day.isBefore(joined) ? List.<Slot>of() : lessons.apply(day);
      if (hasOverlappingSlots(slots)) {
        slots = List.of();
      }
      var dayDate = day;
      boolean past = day.isBefore(today);
      boolean elapsedToday =
          day.equals(today)
              && slots.stream()
                  .anyMatch(slot -> teachingTime(dayDate, slot.startsAt()).isBefore(now));
      boolean lessonsRemaining =
          slots.stream().anyMatch(slot -> teachingTime(dayDate, slot.endsAt()).isAfter(now));
      boolean unlocked = day.isAfter(resetDay);
      // Nach der letzten Stunde ist der heutige Tag wie ein vergangener bearbeitbar
      boolean finished = past || (day.equals(today) && lessonsOver(day, slots, now));
      boolean canCorrectStatus = unlocked && !slots.isEmpty() && (past || elapsedToday);
      boolean canPlanStatus = unlocked && !past && lessonsRemaining;
      var preview =
          past
              ? List.<PlannedStatusView>of()
              : dayPlans.stream().map(PlannedStatusView::from).toList();
      if (past || elapsedToday) {
        // Ein noch laufender Unterrichtstag liefert den Zwischenstand bis jetzt
        var timeline = slots.isEmpty() ? dayAudits : includePlannedStatuses(dayAudits, dayPlans);
        days.add(
            AttendanceHistory.describe(
                day,
                slots,
                timeline,
                now,
                finished && unlocked,
                canCorrectStatus,
                canPlanStatus,
                elapsedToday && lessonsRemaining,
                preview,
                fullAbsences.get(day),
                excusals,
                effectiveManualEntries(manualEntries.getOrDefault(day, List.of()))));
      } else {
        days.add(
            new HistoryDay(
                day,
                null,
                null,
                null,
                false,
                null,
                false,
                false,
                manualAbsenceMinutes(dayAudits),
                canCorrectStatus,
                canPlanStatus,
                preview,
                null,
                wasPresent(dayAudits),
                unlocked && elapsedToday && wasPresent(dayAudits),
                excusals,
                plannedMinutes(slots),
                false,
                false,
                effectiveManualEntries(manualEntries.getOrDefault(day, List.of())),
                0));
      }
    }
    return new HistoryWeek(weekStart, weekEnd, days);
  }

  /**
   * Liefert je Kalendertag, ob Unterricht geplant ist und ob Fehlminuten angefallen sind. Der
   * Zeitraum umfasst höchstens 93 Tage. Künftige Tage gelten nie als Fehltag, der heutige Tag zählt
   * mit dem Zwischenstand bis jetzt
   */
  @Transactional(readOnly = true)
  public List<CalendarDay> readCalendar(Long studentId, LocalDate from, LocalDate to) {
    var classId = requireStudentClassAccess(studentId, authorization.currentStaff());
    if (from == null
        || to == null
        || to.isBefore(from)
        || ChronoUnit.DAYS.between(from, to) >= 93) {
      throw new IllegalArgumentException("INVALID_ATTENDANCE_DATE");
    }
    var now = now();
    var today = berlinDate(now);
    var joined = joinedOn(studentId, classId);
    var plan = readPlan(classId);
    var lessons = daySlots(plan, now);
    var audits = accounting.findStudentDayAudits(studentId, from, to, today);
    var plannedStatuses = plannedByDate(studentId, from, to, today, false);
    var days = new ArrayList<CalendarDay>();
    for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
      var daySlots = lessons.apply(date);
      var dayAudits = audits.getOrDefault(date, List.of());
      boolean absent = false;
      if (!date.isAfter(today)) {
        if (daySlots.isEmpty() || hasOverlappingSlots(daySlots) || date.isBefore(joined)) {
          absent =
              dayAudits.stream()
                      .mapToLong(audit -> (long) audit.unexcusedDelta() + audit.excusedDelta())
                      .sum()
                  > 0;
        } else {
          var timeline =
              includePlannedStatuses(dayAudits, plannedStatuses.getOrDefault(date, List.of()));
          var minutes = calculate(date, daySlots, timeline, now);
          absent = minutes.unexcused() + minutes.excused() + manualAbsenceMinutes(dayAudits) > 0;
        }
      }
      days.add(new CalendarDay(date, !daySlots.isEmpty(), absent));
    }
    return days;
  }

  /** Liefert den Berliner Tag der Schüleranlage in der angegebenen Klasse */
  private LocalDate joinedOn(Long studentId, Long classId) {
    return students
        .findCreatedAt(studentId, classId)
        .map(AttendanceAccountingService::berlinDate)
        .orElseThrow(() -> new IllegalArgumentException("STUDENT_NOT_FOUND"));
  }

  /** Liest den aktuellen Plan der Klasse ohne Sperre */
  private ClassPlan readPlan(Long classId) {
    return classes
        .findPlan(classId, false)
        .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"));
  }

  /** Ein Kalendertag des Schülerprofils mit Unterrichts- und Fehlzeitkennzeichen */
  public record CalendarDay(LocalDate date, boolean lessonDay, boolean absent) {}

  public record HistoryWeek(LocalDate startsOn, LocalDate endsOn, List<HistoryDay> days) {}

  public record HistoryDay(
      LocalDate date,
      Long unexcusedMinutes,
      Long excusedMinutes,
      Long lateMinutes,
      boolean didNotArrive,
      AbsenceLevel absenceLevel,
      boolean canExcuseOpenAbsences,
      boolean editable,
      long manualAbsenceMinutes,
      boolean canCorrectStatus,
      boolean canPlanStatus,
      List<PlannedStatusView> plannedStatuses,
      AttendanceStatus fullAbsence,
      boolean wasPresent,
      boolean canBookMinutes,
      List<ExcusalView> excusals,
      long plannedMinutes,
      boolean interim,
      boolean canMarkAbsent,
      List<ManualAbsenceEntry> manualAbsenceEntries,
      long manualAbsenceLimit) {
    /** Ein Tag ohne Unterricht und ohne Buchungen */
    static HistoryDay empty(
        LocalDate date, List<ExcusalView> excusals, List<ManualAbsenceEntry> entries) {
      return new HistoryDay(
          date, null, null, null, false, null, false, false, 0, false, false, List.of(), null,
          false, false, excusals, 0, false, false, entries, 0);
    }
  }

  /** Eine wirksame händische Fehlminutenbuchung mit ihrem Vermerk; Altbestand hat keinen */
  public record ManualAbsenceEntry(long minutes, String note) {}

  /** Behält nur die Buchungen nach der letzten Rücknahme des Tages */
  private static List<ManualAbsenceEntry> effectiveManualEntries(List<ManualAbsenceEntry> entries) {
    var effective = new ArrayList<ManualAbsenceEntry>();
    for (var entry : entries) {
      if (entry.minutes() < 0) {
        effective.clear();
      } else if (entry.minutes() > 0) {
        effective.add(entry);
      }
    }
    return effective;
  }

  /** Nennt die Schüler der Klasse mit wirksamen händischen Fehlminuten an diesem Tag */
  @Transactional(readOnly = true)
  public List<ManualAbsence> findManualAbsences(Long classId, LocalDate date) {
    authorization.requireClassAccess(classId, authorization.currentStaff());
    return accounting.findManualAbsences(classId, date, berlinDate(now()));
  }

  public record ExcusalView(
      LocalTime startsAt, LocalTime endsAt, AttendanceStatus status, String reason) {
    private static ExcusalView from(Audit audit) {
      return new ExcusalView(
          audit.occurredAt().toInstant(ZoneOffset.UTC).atZone(BERLIN).toLocalTime(),
          audit.excusalEndsAt().toInstant(ZoneOffset.UTC).atZone(BERLIN).toLocalTime(),
          audit.excusalStatus() == null ? AttendanceStatus.ENTSCHULDIGT : audit.excusalStatus(),
          audit.excusalReason());
    }
  }

  public record PlannedStatusView(LocalTime startsAt, LocalTime endsAt, AttendanceStatus status) {
    private static PlannedStatusView from(PlannedStatus planned) {
      return new PlannedStatusView(
          planned.start().atZone(ZoneOffset.UTC).withZoneSameInstant(BERLIN).toLocalTime(),
          planned.end().atZone(ZoneOffset.UTC).withZoneSameInstant(BERLIN).toLocalTime(),
          planned.status());
    }
  }

  private Map<LocalDate, List<PlannedStatus>> plannedByDate(
      Long studentId, LocalDate from, LocalDate through, LocalDate today, boolean lock) {
    return accounting.findPlannedStatuses(studentId, from, through, today, lock).stream()
        .collect(Collectors.groupingBy(PlannedStatus::date));
  }

  public enum AbsenceLevel {
    FULL,
    PARTIAL
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
    var accessible =
        classes.findAccessible(actor.id(), actor.administrator()).stream()
            .sorted(Comparator.comparing(SchoolClassRepository.ClassData::id))
            .toList();
    for (var schoolClass : accessible) {
      var plan = classes.findPlan(schoolClass.id(), false).orElse(null);
      if (plan == null) {
        continue;
      }
      List<Slot> slots;
      try {
        slots = daySlots(plan, now).apply(today);
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

  /** Liefert die gültigen Unterrichtszeiten der gewählten Berliner Schulwoche. */
  @Transactional(readOnly = true)
  public ClassWeek readClassWeek(Long classId, LocalDate date) {
    authorization.requireClassAccess(classId, authorization.currentStaff());
    var plan = readPlan(classId);
    var monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    var days = new TreeMap<LocalDate, List<Slot>>();
    var periods = accounting.findPeriods(classId, false);
    requirePlanPeriods(plan, periods);
    var slots = accounting.findSlots(plan.timetableId(), false);
    var cancellations = accounting.findCancellations(classId);
    var cancelled = new TreeMap<LocalDate, List<LocalTime>>();
    for (int index = 0; index < 5; index++) {
      var day = monday.plusDays(index);
      var daySlots = scheduledSlots(plan, periods, slots, day);
      days.put(day, daySlots);
      cancelled.put(
          day,
          daySlots.stream()
              .filter(slot -> cancellations.stream().anyMatch(known -> cancels(known, day, slot)))
              .map(Slot::startsAt)
              .toList());
    }
    return new ClassWeek(plan.timetableId(), days, cancelled);
  }

  /**
   * Enthält die Unterrichtszeiten der Woche einschließlich ausgefallener Stunden. cancelled nennt
   * je Tag die Anfangszeiten der ausgefallenen Unterrichtszeiten
   */
  public record ClassWeek(
      Long timetableId,
      Map<LocalDate, List<Slot>> days,
      Map<LocalDate, List<LocalTime>> cancelled) {}

  /**
   * Markiert die Unterrichtszeit eines Tages, die um startsAt beginnt, als ausgefallen. Ohne
   * startsAt fallen alle Unterrichtszeiten des Tages aus. Bereits gebuchte Fehlminuten der Klasse
   * werden für diesen Tag neu abgerechnet. Liefert false, wenn alles schon ausgefallen war
   */
  @Transactional
  public boolean cancelLessons(Long classId, LocalDate date, LocalTime startsAt) {
    return changeCancellations(classId, date, startsAt, true);
  }

  /** Nimmt den Unterrichtsausfall zurück und rechnet den Tag der Klasse neu ab */
  @Transactional
  public boolean restoreLessons(Long classId, LocalDate date, LocalTime startsAt) {
    return changeCancellations(classId, date, startsAt, false);
  }

  private boolean changeCancellations(
      Long classId, LocalDate date, LocalTime startsAt, boolean cancel) {
    var actor = authorization.currentStaff();
    authorization.requireClassAccess(classId, actor);
    if (date == null) {
      throw new IllegalArgumentException("INVALID_LESSON_CANCELLATION");
    }
    var plan =
        classes
            .lockPlan(classId)
            .orElseThrow(() -> new IllegalArgumentException("CLASS_NOT_FOUND"));
    var now = now();
    var periods = accounting.findPeriods(classId, true);
    requirePlanPeriods(plan, periods);
    var slots = accounting.findSlots(plan.timetableId(), true);
    var targets =
        scheduledSlots(plan, periods, slots, date).stream()
            .filter(slot -> startsAt == null || slot.startsAt().equals(startsAt))
            .toList();
    // Ein Ausfall gilt für jede Stunde, mit der sich seine gespeicherte Zeitspanne überschneidet
    var recorded =
        accounting.findCancellations(classId).stream()
            .filter(
                cancellation ->
                    cancellation.date().equals(date)
                        && (startsAt == null
                            || cancellation.startsAt().equals(startsAt)
                            || targets.stream()
                                .anyMatch(slot -> cancels(cancellation, date, slot))))
            .toList();
    List<Slot> added = List.of();
    if (cancel) {
      if (targets.isEmpty()) {
        throw new IllegalArgumentException("INVALID_LESSON_CANCELLATION");
      }
      added =
          targets.stream()
              .filter(slot -> recorded.stream().noneMatch(known -> cancels(known, date, slot)))
              .toList();
      if (added.isEmpty()) {
        return false;
      }
    } else if (recorded.isEmpty()) {
      return false;
    }
    // Abgelaufene Aufbewahrung, ein anderer Blockplan oder ein Kontenreset sperren den Tag
    if (!retained(plan, now) || date.isBefore(plan.startsOn()) || date.isAfter(plan.endsOn())) {
      throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    }
    var deadlineId = deletionDates.getOrCreate(plan.endsOn()).getId();
    var accounts = accounting.lockAccounts(classId);
    var histories = new ArrayList<History>();
    for (var account : accounts) {
      var history = history(plan, account, deadlineId, now);
      if (!date.isAfter(history.resetDay())) {
        throw new IllegalArgumentException("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
      }
      histories.add(history);
    }
    if (cancel) {
      for (var slot : added) {
        accounting.insertCancellation(
            classId, date, slot.startsAt(), slot.endsAt(), actor.id(), now);
      }
    } else {
      for (var cancellation : recorded) {
        accounting.deleteCancellation(classId, date, cancellation.startsAt());
      }
    }
    var today = berlinDate(now);
    if (date.isAfter(today)) {
      return true;
    }
    var daySlots = slotsFor(plan, periods, slots, accounting.findCancellations(classId), date);
    // Ein ganz ausgefallener Tag kann beim Tagesabschluss ohne Buchung geblieben sein.
    boolean bookMissingAttendance = !cancel && lessonsOver(date, daySlots, now);
    for (int index = 0; index < accounts.size(); index++) {
      var account = accounts.get(index);
      var history = histories.get(index);
      var attendance = history.attendances().get(date);
      if (attendance == null
          && (!bookMissingAttendance || date.isBefore(berlinDate(account.createdAt())))) {
        continue;
      }
      reconcile(
          new Day(
              plan,
              account,
              date,
              deadlineId,
              daySlots,
              attendance,
              history.dayAudits(attendance),
              accounting.findPlannedStatuses(account.id(), date, date, today, true),
              now),
          new Change(AuditEventType.LESSON_CANCELLATION, null, null, now, actor.id(), null));
    }
    return true;
  }

  /**
   * Lädt Blöcke, Slots und Ausfälle der Klasse einmal und liefert danach je Tag die stattfindenden
   * Slots. Nach Ablauf der Aufbewahrungsfrist des Plans gibt es keine
   */
  private Function<LocalDate, List<Slot>> daySlots(ClassPlan plan, LocalDateTime now) {
    if (!retained(plan, now)) {
      return date -> List.of();
    }
    var periods = accounting.findPeriods(plan.classId(), false);
    requirePlanPeriods(plan, periods);
    var slots = accounting.findSlots(plan.timetableId(), false);
    var cancellations = accounting.findCancellations(plan.classId());
    return date -> slotsFor(plan, periods, slots, cancellations, date);
  }

  /** Enthält sichtbare Schülerdaten mit Tagesstatus und unentschuldigten Fehlminuten des Tages */
  public record DailyStudent(
      Long studentId,
      String firstName,
      String lastName,
      AttendanceStatus status,
      long unexcusedMinutes,
      boolean wasPresent,
      AttendanceStatus fullAbsence,
      long manualAbsenceMinutes,
      long excusedMinutes,
      boolean canMarkAbsent,
      LocalDate birthDate) {}

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
    var periods = accounting.findPeriods(plan.classId(), true);
    requirePlanPeriods(plan, periods);
    var slots = accounting.findSlots(plan.timetableId(), true);
    var cancellations = accounting.findCancellations(plan.classId());
    int changed = 0;
    for (Account original : accounts) {
      var history = history(plan, original, deletionDateId, now);
      var account = original;
      var start = firstAccountingDate(plan, account, history.resetDay());
      var today = berlinDate(now);
      var end = plan.endsOn().isBefore(today) ? plan.endsOn() : today;
      var planned = plannedByDate(account.id(), start, end, today, true);
      for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
        var daySlots = slotsFor(plan, periods, slots, cancellations, date);
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
                planned.getOrDefault(date, List.of()),
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
   * beziehungsweise am oder vor dem letzten Berliner Resettag. Mit withoutLessons genügt ein Tag im
   * Plan, dessen Unterricht vollständig ausgefallen ist
   */
  private Day lockDay(Long studentId, LocalDate date, boolean withoutLessons) {
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
    var account = accounting.findAccount(studentId, classId, true).orElse(null);
    var now = now();
    if (account == null || !retained(plan, now) || date.isBefore(berlinDate(account.createdAt()))) {
      return null;
    }
    var history = history(plan, account, deadline.getId(), now);
    if (!date.isAfter(history.resetDay())) {
      return null;
    }
    var periods = accounting.findPeriods(classId, true);
    requirePlanPeriods(plan, periods);
    var slots =
        slotsFor(
            plan,
            periods,
            accounting.findSlots(plan.timetableId(), true),
            accounting.findCancellations(classId),
            date);
    if (slots.isEmpty() && !(withoutLessons && active(periods, date))) {
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
        accounting.findPlannedStatuses(studentId, date, date, berlinDate(now), true),
        now);
  }

  /**
   * Berechnet beide Fehlminutensummen des Tages und zieht die bereits protokollierten Summen ab.
   * Nur diese Differenzen werden auf die Konten gebucht. Ein Statuswechsel wird auch ohne
   * Minutenänderung protokolliert. Ohne Status- oder Minutenänderung entsteht keine Buchung
   */
  private Booking reconcile(Day day, Change change) {
    return reconcile(day, List.of(change), null);
  }

  private Booking reconcile(Day day, List<Change> changes, ManualBooking manual) {
    var events = projectChanges(day, changes);
    var timeline = includePlannedStatuses(events, day.plannedStatuses());
    var target = calculate(day.date(), day.slots(), timeline, day.now());
    var previous = day.attendance() == null ? FullDay.NONE : day.attendance().fullDay();
    var full =
        manual == null
            ? fullDay(day, timeline, target, day.now())
                .includingManualMinutes(manualAbsenceMinutes(day.audits()))
            : previous.includingManualMinutes(manual.minutes());
    int unexcusedDelta;
    int excusedDelta;
    if (manual == null) {
      unexcusedDelta =
          Math.toIntExact(
              target.unexcused()
                  + manualAbsenceMinutes(day.audits())
                  - day.audits().stream().mapToLong(Audit::unexcusedDelta).sum());
      excusedDelta =
          Math.toIntExact(
              target.excused() - day.audits().stream().mapToLong(Audit::excusedDelta).sum());
    } else {
      unexcusedDelta = manual.minutes();
      excusedDelta = 0;
    }
    if (events.size() == day.audits().size() && unexcusedDelta == 0 && excusedDelta == 0) {
      if (full.equals(previous)) {
        return new Booking(day.account(), false);
      }
      if (day.attendance() != null) {
        accounting.updateFullDay(day.account().id(), day.attendance().id(), previous, full);
        return new Booking(day.account(), false);
      }
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
    for (var change : changes) {
      boolean last = change == changes.getLast();
      var entry =
          audit(
              day, change, null, attendanceId, last ? unexcusedDelta : 0, last ? excusedDelta : 0);
      accounting.insertAudit(
          account.id(),
          attendanceId,
          entry,
          change.staffId(),
          change.terminalId(),
          day.now(),
          manual == null ? null : manual.requestId(),
          manual == null ? null : manual.note());
    }
    accounting.updateTotals(account, attendanceId, status, day.now());
    if (!full.equals(previous)) {
      accounting.updateFullDay(account.id(), attendanceId, previous, full);
    }
    return new Booking(account, true);
  }

  private static FullDay fullDay(
      Day day, List<Audit> timeline, Contribution contribution, LocalDateTime through) {
    var status = fullAbsenceStatus(day.date(), day.slots(), timeline, contribution, through);
    return status == null
        ? FullDay.NONE
        : new FullDay(
            status,
            Math.toIntExact(contribution.unexcused()),
            Math.toIntExact(contribution.excused()));
  }

  static AttendanceStatus fullAbsenceStatus(
      LocalDate date,
      List<Slot> slots,
      List<Audit> timeline,
      Contribution contribution,
      LocalDateTime through) {
    if (slots.isEmpty()
        || hasOverlappingSlots(slots)
        || teachingTime(date, slots.getLast().endsAt()).isAfter(through)
        || wasPresent(timeline)) {
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

  private static List<Audit> projectChanges(Day day, List<Change> changes) {
    var events = new ArrayList<>(day.audits());
    for (var change : changes) {
      if (change.before() != change.after() || change.excusal() != null) {
        events.add(audit(day, change, Long.MAX_VALUE, null, 0, 0));
      }
    }
    return events;
  }

  static long manualAbsenceMinutes(List<Audit> audits) {
    return audits.stream()
        .filter(audit -> audit.type() == AuditEventType.MANUAL_ABSENCE_MINUTES)
        .mapToLong(Audit::unexcusedDelta)
        .sum();
  }

  private static Audit audit(
      Day day, Change change, Long id, Long attendanceId, int unexcusedDelta, int excusedDelta) {
    boolean statusChange = change.before() != change.after();
    return new Audit(
        id,
        attendanceId,
        day.deletionDateId(),
        change.type(),
        change.at(),
        change.excusal() == null ? null : change.excusal().end(),
        change.excusal() == null ? null : change.excusal().status(),
        change.excusal() == null ? null : change.excusal().reason(),
        statusChange ? change.after() : null,
        unexcusedDelta,
        excusedDelta,
        statusChange ? change.before() : null);
  }

  private record ManualBooking(int minutes, UUID requestId, String note) {}

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

  /**
   * Liefert die stattfindenden Slots des Tages. Ausgefallene Unterrichtszeiten zählen nicht als
   * geplanter Unterricht. Ein Ausfall gilt für jeden Slot des Datums, mit dem sich seine
   * gespeicherte Zeitspanne überschneidet, auch nach einer späteren Verschiebung der Stunde
   */
  private static List<Slot> slotsFor(
      ClassPlan plan,
      List<Period> periods,
      List<Slot> slots,
      List<Cancellation> cancellations,
      LocalDate date) {
    return scheduledSlots(plan, periods, slots, date).stream()
        .filter(
            slot ->
                cancellations.stream().noneMatch(cancellation -> cancels(cancellation, date, slot)))
        .toList();
  }

  /** Prüft, ob der Ausfall am Datum zeitlich in den Slot fällt */
  private static boolean cancels(Cancellation cancellation, LocalDate date, Slot slot) {
    return cancellation.date().equals(date)
        && cancellation.startsAt().isBefore(slot.endsAt())
        && cancellation.endsAt().isAfter(slot.startsAt());
  }

  /** Liefert die Slots des Wochentags innerhalb des Plans und eines zugeordneten Blocks */
  private static List<Slot> scheduledSlots(
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
    return history(plan, account, deletionDateId, now, true);
  }

  private History history(
      ClassPlan plan, Account account, Long deletionDateId, LocalDateTime now, boolean lock) {
    var attendances =
        accounting.findAttendances(account.id(), lock).stream()
            .filter(
                day -> !day.date().isBefore(plan.startsOn()) && !day.date().isAfter(plan.endsOn()))
            .toList();
    var attendanceIds = attendances.stream().map(DayAttendance::id).collect(Collectors.toSet());
    var audits =
        accounting.findAudits(account.id(), lock).stream()
            .filter(
                audit ->
                    audit.attendanceId() == null
                        ? deletionDateId != null && deletionDateId.equals(audit.deletionDateId())
                        : attendanceIds.contains(audit.attendanceId()))
            .toList();
    if (audits.stream()
        .anyMatch(
            audit ->
                audit.type() != AuditEventType.TIMED_EXCUSAL && audit.occurredAt().isAfter(now))) {
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
   * Blendet Vormerkungen in den Statusverlauf ein. Ein tatsächlich erfasstes Statusereignis ab
   * Beginn des Zeitraums übernimmt den weiteren Verlauf. Ohne solches Ereignis gilt nach Ablauf der
   * Vormerkung wieder der ursprüngliche Status.
   */
  private static List<Audit> includePlannedStatuses(
      List<Audit> audits, List<PlannedStatus> plannedStatuses) {
    if (plannedStatuses.isEmpty()) {
      return audits;
    }
    var timeline = new ArrayList<>(audits);
    for (var planned : plannedStatuses) {
      timeline.add(plannedEvent(planned.start(), planned.status(), Long.MIN_VALUE + 1));
      boolean superseded =
          audits.stream()
              .anyMatch(
                  audit ->
                      audit.status() != null
                          && !audit.occurredAt().isBefore(planned.start())
                          && audit.occurredAt().isBefore(planned.end()));
      if (!superseded) {
        timeline.add(plannedEvent(planned.end(), statusAt(audits, planned.end()), Long.MIN_VALUE));
      }
    }
    return timeline;
  }

  private static Audit plannedEvent(LocalDateTime at, AttendanceStatus status, long order) {
    // Nur für die Berechnung. Die Vormerkung selbst ist mit Akteur und Erstellungszeit gespeichert.
    return new Audit(order, null, null, AuditEventType.STATUS_CHANGE, at, status, 0, 0, null);
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
    var periods = accounting.findPeriods(plan.classId(), true);
    var slots = accounting.findSlots(plan.timetableId(), true);
    var cancellations = accounting.findCancellations(plan.classId());
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
      var planned = plannedByDate(account.id(), start, today, today, true);
      for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
        var daySlots = slotsFor(plan, periods, slots, cancellations, date);
        var attendance = history.attendances().get(date);
        var dayAudits = history.dayAudits(attendance);
        var timeline = includePlannedStatuses(dayAudits, planned.getOrDefault(date, List.of()));
        if (attendance == null) {
          var target = calculate(date, daySlots, timeline, now);
          if (target.unexcused() == 0 && target.excused() == 0) {
            continue;
          }
          throw new IllegalStateException("CLASS_NOT_FULLY_ACCOUNTED");
        }
        var target = calculate(date, daySlots, timeline, now);
        var lastStatus = statusAt(dayAudits, now);
        if (dayAudits.isEmpty()
            || attendance.status() != lastStatus
            || target.unexcused() + manualAbsenceMinutes(dayAudits)
                != dayAudits.stream().mapToLong(Audit::unexcusedDelta).sum()
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
    var periods = accounting.findPeriods(plan.classId(), true);
    requirePlanPeriods(plan, periods);
    var berlinNow = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(BERLIN);
    if (slotsFor(
            plan,
            periods,
            accounting.findSlots(plan.timetableId(), true),
            accounting.findCancellations(plan.classId()),
            berlinNow.toLocalDate())
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
    var excusals =
        audits.stream()
            .filter(audit -> audit.excusalEndsAt() != null)
            .sorted(Comparator.comparing(Audit::occurredAt))
            .toList();
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
            long covered = excusedSeconds(excusals, cursor, at);
            unexcused += seconds - covered;
            excused += covered;
          } else if (excused(status)) {
            excused += seconds;
          }
          cursor = at;
          status = event.status();
        }
      }
      long seconds = Duration.between(cursor, end).getSeconds();
      if (status == AttendanceStatus.ABWESEND) {
        long covered = excusedSeconds(excusals, cursor, end);
        unexcused += seconds - covered;
        excused += covered;
      } else if (excused(status)) {
        excused += seconds;
      }
    }
    return new Contribution(unexcused, excused);
  }

  /** Zählt die gemeinsame Abdeckung eines Abschnitts durch die sortierten Entschuldigungen */
  private static long excusedSeconds(List<Audit> excusals, Instant start, Instant end) {
    long covered = 0;
    var cursor = start;
    for (Audit excusal : excusals) {
      var from = excusal.occurredAt().toInstant(ZoneOffset.UTC);
      if (!from.isBefore(end)) {
        break;
      }
      var until = excusal.excusalEndsAt().toInstant(ZoneOffset.UTC);
      var overlapStart = from.isAfter(cursor) ? from : cursor;
      var overlapEnd = until.isBefore(end) ? until : end;
      if (overlapEnd.isAfter(overlapStart)) {
        covered += Duration.between(overlapStart, overlapEnd).getSeconds();
        cursor = overlapEnd;
      }
    }
    return covered;
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
      List<PlannedStatus> plannedStatuses,
      LocalDateTime now) {
    List<Audit> timeline() {
      return includePlannedStatuses(audits, plannedStatuses);
    }

    Day withAccount(Account updated) {
      return new Day(
          plan, updated, date, deletionDateId, slots, attendance, audits, plannedStatuses, now);
    }
  }

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
      Integer terminalId,
      ExcusalWindow excusal) {
    Change(
        AuditEventType type,
        AttendanceStatus before,
        AttendanceStatus after,
        LocalDateTime at,
        Long staffId,
        Integer terminalId) {
      this(type, before, after, at, staffId, terminalId, null);
    }
  }

  /**
   * Enthält die resultierenden Fehlminutenkonten und gibt an, ob ein Änderungsprotokoll geschrieben
   * wurde
   */
  private record ExcusalWindow(LocalDateTime end, AttendanceStatus status, String reason) {}

  private record Booking(Account account, boolean changed) {}
}
