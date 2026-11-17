package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.StudentRepository;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verwaltet die Zuordnung von RFID-Karten zu Schülern */
@Service
public class RfidUidService {
  private static final Pattern UID = Pattern.compile("(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})");

  private final StudentRepository students;

  private final StaffAuthorization authorization;

  public RfidUidService(StudentRepository students, StaffAuthorization authorization) {
    this.students = students;
    this.authorization = authorization;
  }

  /**
   * Verlangt Administratorrechte und eine UID aus 8, 14 oder 20 Hexzeichen in Großschreibung.
   * Ordnet die Kennung dem Schüler zu. Gehört sie bereits einem anderen Schüler, wird die Zuordnung
   * abgelehnt
   */
  @Transactional
  public void assign(Long studentId, String uid) {
    authorization.requireAdministrator();
    if (uid == null || !UID.matcher(uid).matches()) {
      throw new IllegalArgumentException("INVALID_RFID_UID");
    }
    update(studentId, uid);
  }

  /** Entfernt die Kartenzuordnung nach Prüfung der Administratorrechte */
  @Transactional
  public void remove(Long studentId) {
    authorization.requireAdministrator();
    update(studentId, null);
  }

  /** Speichert die Zuordnung und meldet Fehler ohne die UID offenzulegen */
  private void update(Long studentId, String uid) {
    try {
      if (students.updateUid(studentId, uid) == 0) {
        throw new IllegalArgumentException("STUDENT_NOT_FOUND");
      }
    } catch (DuplicateKeyException conflict) {
      throw new IllegalArgumentException("RFID_UID_ALREADY_ASSIGNED");
    } catch (DataAccessException failure) {
      throw new IllegalStateException("RFID_UID_UPDATE_FAILED");
    }
  }
}
