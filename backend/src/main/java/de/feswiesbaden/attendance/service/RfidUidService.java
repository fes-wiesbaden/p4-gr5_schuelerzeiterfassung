package de.feswiesbaden.attendance.service;

import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RfidUidService {
  private static final Pattern UID = Pattern.compile("(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})");

  private final JdbcTemplate jdbc;

  private final AdminAuthorization authorization;

  public RfidUidService(JdbcTemplate jdbc, AdminAuthorization authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  @Transactional
  public void assign(Long staffId, Long studentId, String uid) {
    authorization.requireAdministrator(staffId);
    if (uid == null || !UID.matcher(uid).matches()) {
      throw new IllegalArgumentException("INVALID_RFID_UID");
    }
    update(studentId, uid);
  }

  @Transactional
  public void remove(Long staffId, Long studentId) {
    authorization.requireAdministrator(staffId);
    update(studentId, null);
  }

  private void update(Long studentId, String uid) {
    try {
      if (jdbc.update("UPDATE student SET rfid_uid = ? WHERE id = ?", uid, studentId) == 0) {
        throw new IllegalArgumentException("STUDENT_NOT_FOUND");
      }
    } catch (DuplicateKeyException conflict) {
      throw new IllegalArgumentException("RFID_UID_ALREADY_ASSIGNED");
    } catch (DataAccessException failure) {
      throw new IllegalStateException("RFID_UID_UPDATE_FAILED");
    }
  }
}
