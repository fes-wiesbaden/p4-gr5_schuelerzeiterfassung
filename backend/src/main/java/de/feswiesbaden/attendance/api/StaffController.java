package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.repository.StudentRepository.RfidAssignment;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.CalendarDay;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.CurrentClass;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.DailyStudent;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.HistoryWeek;
import de.feswiesbaden.attendance.service.RfidUidService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import de.feswiesbaden.attendance.service.SchoolClassService.ClassView;
import de.feswiesbaden.attendance.service.StudentService;
import de.feswiesbaden.attendance.service.StudentService.StudentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** Stellt Klassenverwaltung und Tagesanwesenheit für angemeldete Mitarbeiter bereit */
@RestController
@RequestMapping("/api")
public class StaffController {
  private final SchoolClassService classes;

  private final StudentService students;

  private final AttendanceAccountingService attendance;

  private final RfidUidService rfidUids;

  /** Übernimmt die Services für Klassen, Schüler und Anwesenheit */
  public StaffController(
      SchoolClassService classes,
      StudentService students,
      AttendanceAccountingService attendance,
      RfidUidService rfidUids) {
    this.classes = classes;
    this.students = students;
    this.attendance = attendance;
    this.rfidUids = rfidUids;
  }

  /** Liefert die für den angemeldeten Mitarbeiter sichtbaren Klassen */
  @GetMapping("/classes")
  public List<ClassView> listClasses() {
    return classes.list();
  }

  /** Legt eine Klasse an und gibt ihre ID zurück */
  @PostMapping("/classes")
  @ResponseStatus(HttpStatus.CREATED)
  public Created createClass(@RequestBody ClassRequest request) {
    return new Created(
        classes.create(
            request.classCode(),
            request.classTeacherId(),
            request.blockPlanId(),
            request.timetableId()));
  }

  /** Liefert die Schüler einer zugänglichen Klasse */
  @GetMapping("/classes/{classId}/students")
  public List<StudentView> listStudents(@PathVariable Long classId) {
    return students.list(classId);
  }

  /** Legt einen Schüler in der angegebenen Klasse an und gibt seine ID zurück */
  @PostMapping("/classes/{classId}/students")
  @ResponseStatus(HttpStatus.CREATED)
  public Created createStudent(@PathVariable Long classId, @RequestBody StudentRequest request) {
    return new Created(
        students.create(classId, request.firstName(), request.lastName(), request.birthDate()));
  }

  /** Liefert alle zugänglichen Schüler für die klassenübergreifende Liste */
  @GetMapping("/students")
  public List<StudentView> listAccessibleStudents() {
    return students.listAccessible();
  }

  /** Liefert Schülerstammdaten und die beiden aktuellen Fehlminutenkonten */
  @GetMapping("/students/{studentId}")
  public StudentView getStudent(@PathVariable Long studentId) {
    return students.get(studentId);
  }

  /** Liefert Lehrkräften Kartenzuordnungen ohne Zwischenspeicherung im Browser */
  @GetMapping("/students/rfid-uids")
  public ResponseEntity<List<RfidAssignment>> listRfidAssignments() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rfidUids.listForTeacher());
  }

  /** Ändert Namen und Geburtsdatum eines Schülers */
  @PutMapping("/students/{studentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateStudent(@PathVariable Long studentId, @RequestBody StudentRequest request) {
    students.update(studentId, request.firstName(), request.lastName(), request.birthDate());
  }

  /** Weist dem Schüler eine neue Karte zu oder ersetzt die bisherige Karte */
  @PutMapping("/students/{studentId}/rfid-uid")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void assignRfidUid(@PathVariable Long studentId, @RequestBody RfidUidRequest request) {
    rfidUids.assign(studentId, request.uid());
  }

  /** Entfernt die Kartenzuordnung des Schülers */
  @DeleteMapping("/students/{studentId}/rfid-uid")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeRfidUid(@PathVariable Long studentId) {
    rfidUids.remove(studentId);
  }

  /** Löscht einen Schüler, sofern keine Anwesenheiten oder Audits auf ihn verweisen */
  @DeleteMapping("/students/{studentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteStudent(@PathVariable Long studentId) {
    students.delete(studentId);
  }

  /** Liefert den Tagesstatus und die bis jetzt angefallenen unentschuldigten Fehlminuten */
  @GetMapping("/classes/{classId}/attendance")
  public List<DailyStudent> readDay(
      @PathVariable Long classId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return attendance.readDay(classId, date);
  }

  /** Liefert die Anwesenheitshistorie eines Schülers für die Woche um das gewählte Datum */
  @GetMapping("/students/{studentId}/attendance/week")
  public HistoryWeek readWeek(
      @PathVariable Long studentId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return attendance.readWeek(studentId, date);
  }

  /** Entschuldigt alle noch offenen Unterrichtsfehlzeiten des ausgewählten Tags */
  @PostMapping("/students/{studentId}/attendance/{date}/open-absence-excuses")
  public CorrectionResult excuseOpenAbsences(
      @PathVariable Long studentId,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return new CorrectionResult(attendance.excuseOpenAbsences(studentId, date));
  }

  /** Bucht zusätzliche unentschuldigte Minuten auf dem Berliner Tag */
  @PostMapping("/students/{studentId}/attendance/{date}/manual-absence-minutes")
  public CorrectionResult addManualAbsence(
      @PathVariable Long studentId,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Valid @RequestBody ManualAbsenceRequest request) {
    return new CorrectionResult(
        attendance.addManualAbsence(
            studentId,
            date,
            request.minutes().intValueExact(),
            request.requestId(),
            request.note()));
  }

  /** Nimmt alle händisch erfassten Fehlminuten des Berliner Tags zurück */
  @DeleteMapping("/students/{studentId}/attendance/{date}/manual-absence-minutes")
  public CorrectionResult removeManualAbsence(
      @PathVariable Long studentId,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return new CorrectionResult(attendance.removeManualAbsence(studentId, date));
  }

  /** Liefert je Kalendertag des Zeitraums Unterrichts- und Fehlzeitkennzeichen des Schülers */
  @GetMapping("/students/{studentId}/attendance/calendar")
  public List<CalendarDay> readCalendar(
      @PathVariable Long studentId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return attendance.readCalendar(studentId, from, to);
  }

  /** Entschuldigt den gesamten geplanten Unterrichtstag */
  @PostMapping("/students/{studentId}/attendance/{date}/school-day-excuses")
  public CorrectionResult excuseSchoolDay(
      @PathVariable Long studentId,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return new CorrectionResult(attendance.excuseSchoolDay(studentId, date));
  }

  /** Überschreibt den Status nur im angegebenen Berliner Zeitabschnitt */
  @PostMapping("/students/{studentId}/attendance/{date}/status-periods")
  public CorrectionResult correctStatusPeriod(
      @PathVariable Long studentId,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Valid @RequestBody StatusPeriodRequest request) {
    return new CorrectionResult(
        attendance.correctStatusPeriod(
            studentId, date, status(request.status()), request.startsAt(), request.endsAt()));
  }

  /** Liefert die nach Stundenplan vorausgewählte Klasse und die aktuelle Serverzeit */
  @GetMapping("/attendance/current-class")
  public CurrentClass currentClass() {
    return attendance.findCurrentClass();
  }

  /** Korrigiert den Status ab dem angegebenen Zeitpunkt im Berliner Kalendertag */
  @PostMapping("/students/{studentId}/attendance/{date}/status-corrections")
  public CorrectionResult correctStatus(
      @PathVariable Long studentId,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Valid @RequestBody CorrectionRequest request) {
    return new CorrectionResult(
        attendance.correctStatus(studentId, date, status(request.status()), request.occurredAt()));
  }

  private static AttendanceStatus status(String value) {
    try {
      return AttendanceStatus.valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("INVALID_ATTENDANCE_STATUS");
    }
  }

  /** Setzt die Fehlminutenkonten der Klasse für den erwarteten Blockplan zurück */
  @PostMapping("/classes/{classId}/account-resets")
  public ResetResult resetAccounts(
      @PathVariable Long classId, @Valid @RequestBody ResetRequest request) {
    return new ResetResult(classes.resetAccounts(classId, request.expectedBlockPlanId()));
  }

  /** Enthält Klassenkürzel, Klassenleitung und die zugehörigen Pläne */
  public record ClassRequest(
      String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {}

  /** Enthält die änderbaren Stammdaten eines Schülers */
  public record StudentRequest(String firstName, String lastName, LocalDate birthDate) {}

  /** Enthält ausschließlich die neu zuzuweisende Kartenkennung */
  public record RfidUidRequest(String uid) {
    /** Hält die UID aus der Textdarstellung für technische Logs heraus */
    @Override
    public String toString() {
      return "RfidUidRequest";
    }
  }

  /** Enthält den gewünschten Status und den Zeitpunkt, ab dem er gelten soll */
  public record CorrectionRequest(@NotBlank String status, @NotNull Instant occurredAt) {}

  /**
   * Enthält den Tagesbetrag, die ID zur Wiederholung desselben Buchungsauftrags und den
   * Pflichtvermerk
   */
  public record ManualAbsenceRequest(
      @NotNull @Positive @Max(Integer.MAX_VALUE) @Digits(integer = 10, fraction = 0)
          BigDecimal minutes,
      @NotNull UUID requestId,
      String note) {}

  /** Enthält den neuen Status und die lokalen Berliner Grenzen des Zeitabschnitts */
  public record StatusPeriodRequest(
      @NotBlank String status, @NotNull LocalTime startsAt, @NotNull LocalTime endsAt) {}

  /** Enthält den Blockplan, den der Administrator beim Auslösen des Resets ausgewählt hat */
  public record ResetRequest(@NotNull @Positive Long expectedBlockPlanId) {}

  /** Enthält die ID des angelegten Datensatzes */
  public record Created(Long id) {}

  /** Zeigt an, ob die Korrektur einen Statuswechsel oder eine Kontobuchung ausgelöst hat */
  public record CorrectionResult(boolean changed) {}

  /** Enthält die Anzahl der Schüler, deren Konten zurückgesetzt wurden */
  public record ResetResult(int resetCount) {}
}
