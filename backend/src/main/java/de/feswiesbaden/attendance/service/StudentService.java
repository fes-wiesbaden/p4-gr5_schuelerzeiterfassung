package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.entities.Student;
import de.feswiesbaden.attendance.repository.StudentRepository;
import de.feswiesbaden.attendance.repository.StudentRepository.StudentData;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verwaltet Schülerdaten mit Klassenrechten und Schutz bestehender Historie */
@Service
public class StudentService {
  private final StudentRepository students;

  private final StaffAuthorization authorization;

  public StudentService(StudentRepository students, StaffAuthorization authorization) {
    this.students = students;
    this.authorization = authorization;
  }

  /** Liefert die Schüler einer zugänglichen Klasse ohne Kartenkennungen */
  @Transactional(readOnly = true)
  public List<StudentView> list(Long classId) {
    authorization.requireClassAccess(classId);
    return students.findByClassId(classId).stream().map(this::view).toList();
  }

  /** Liefert die Daten eines zugänglichen Schülers ohne Kartenkennung */
  @Transactional(readOnly = true)
  public StudentView get(Long studentId) {
    authorization.requireClassAccess(classOf(studentId));
    return view(students.findById(studentId));
  }

  /** Legt als Administrator oder Klassenlehrkraft einen Schüler mit leeren Minutenkonten an */
  @Transactional
  public Long create(Long classId, String firstName, String lastName, LocalDate birthDate) {
    authorization.requireStudentManagement(classId);
    validate(firstName, lastName, birthDate);
    Student student = new Student();
    student.setFirstName(firstName.strip());
    student.setLastName(lastName.strip());
    student.setBirthDate(birthDate);
    student.setUnexcusedMinutesAccount(0);
    student.setExcusedMinutesAccount(0);
    students.insert(student, classId);
    return student.getId();
  }

  /** Prüft Verwaltungsrechte und ändert Namen und Geburtsdatum des Schülers */
  @Transactional
  public void update(Long studentId, String firstName, String lastName, LocalDate birthDate) {
    lockManagedStudent(studentId);
    validate(firstName, lastName, birthDate);
    students.update(studentId, firstName.strip(), lastName.strip(), birthDate);
  }

  /**
   * Löscht den Schüler nach Prüfung der Verwaltungsrechte. Verweise aus Tagesanwesenheiten oder
   * Änderungsprotokollen verhindern die Löschung
   */
  @Transactional
  public void delete(Long studentId) {
    lockManagedStudent(studentId);
    try {
      students.delete(studentId);
    } catch (DataIntegrityViolationException history) {
      throw new IllegalStateException("STUDENT_HAS_HISTORY");
    }
  }

  /** Verlangt einen angemeldeten Mitarbeiter und ermittelt die Klasse eines vorhandenen Schülers */
  private Long classOf(Long studentId) {
    authorization.currentStaff();
    return students
        .findClassId(studentId, false)
        .orElseThrow(() -> new IllegalArgumentException("STUDENT_NOT_FOUND"));
  }

  /** Prüft Verwaltungsrechte und sperrt den Schüler bei unveränderter Klassenzuordnung */
  private void lockManagedStudent(Long studentId) {
    Long classId = classOf(studentId);
    authorization.requireStudentManagement(classId);
    if (students.findClassId(studentId, true).filter(classId::equals).isEmpty()) {
      throw new IllegalArgumentException("STUDENT_NOT_FOUND");
    }
  }

  /** Prüft Pflichtangaben und Längengrenzen der Schülerstammdaten */
  private void validate(String firstName, String lastName, LocalDate birthDate) {
    if (firstName == null
        || firstName.isBlank()
        || firstName.strip().length() > 100
        || lastName == null
        || lastName.isBlank()
        || lastName.strip().length() > 100
        || birthDate == null) {
      throw new IllegalArgumentException("INVALID_STUDENT");
    }
  }

  /** Erstellt aus den gelesenen Schülerdaten ein Antwortobjekt ohne Kartenkennung */
  private StudentView view(StudentData student) {
    return new StudentView(
        student.id(),
        student.firstName(),
        student.lastName(),
        student.birthDate(),
        student.classId(),
        student.unexcusedMinutes(),
        student.excusedMinutes());
  }

  /** Enthält sichtbare Schülerstammdaten, Klasse und getrennte Minutenkonten */
  public record StudentView(
      Long id,
      String firstName,
      String lastName,
      LocalDate birthDate,
      Long classId,
      int unexcusedMinutes,
      int excusedMinutes) {}
}
