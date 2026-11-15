package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.StudentRepository;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RfidUidService {
  private static final Pattern UID = Pattern.compile("(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})");

  private final StudentRepository students;

  private final StaffAuthorization authorization;

  public RfidUidService(StudentRepository students, StaffAuthorization authorization) {
    this.students = students;
    this.authorization = authorization;
  }

  @Transactional
  public void assign(Long studentId, String uid) {
    authorization.requireAdministrator();
    if (uid == null || !UID.matcher(uid).matches()) {
      throw new IllegalArgumentException("INVALID_RFID_UID");
    }
    update(studentId, uid);
  }

  @Transactional
  public void remove(Long studentId) {
    authorization.requireAdministrator();
    update(studentId, null);
  }

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
