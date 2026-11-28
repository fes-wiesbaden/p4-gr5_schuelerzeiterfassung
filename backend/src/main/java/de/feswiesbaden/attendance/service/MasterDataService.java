package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.repository.MasterDataRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MasterDataService {

  private final MasterDataRepository data;

  private final SchoolClassRepository classes;

  private final StaffAuthorization auth;

  private final PasswordEncoder passwords;

  private final SessionRegistry sessions;

  public MasterDataService(
      MasterDataRepository data,
      SchoolClassRepository classes,
      StaffAuthorization auth,
      PasswordEncoder passwords,
      SessionRegistry sessions) {
    this.data = data;
    this.classes = classes;
    this.auth = auth;
    this.passwords = passwords;
    this.sessions = sessions;
  }

  public Map<String, Object> options() {
    auth.currentStaff();
    return classes.options();
  }

  public Long currentStaffId() {
    return auth.currentStaff().id();
  }

  public List<Map<String, Object>> teachers() {
    auth.requireAdministrator();
    return data.teachers();
  }

  public List<Map<String, Object>> rooms() {
    auth.requireAdministrator();
    return data.rooms();
  }

  public void saveRoom(Long id, String number) {
    auth.requireAdministrator();
    data.saveRoom(id, number);
  }

  /**
   * Legt eine Lehrkraft an oder ändert sie. Beim Ändern setzt ein angegebenes Passwort das
   * bisherige neu und beendet die Sitzungen der Lehrkraft; ohne Passwort bleibt es unverändert
   */
  public void saveTeacher(Long id, String first, String last, String username, String password) {
    auth.requireAdministrator();
    boolean newPassword = id == null || (password != null && !password.isBlank());
    if (newPassword
        && (password == null
            || password.isBlank()
            || password.getBytes(StandardCharsets.UTF_8).length > 72)) {
      throw new IllegalArgumentException("INVALID_TEACHER_PASSWORD");
    }
    data.saveTeacher(
        id,
        first == null ? "" : first,
        last,
        username,
        newPassword ? passwords.encode(password) : null);
    if (id != null && newPassword) {
      sessions.getAllPrincipals().stream()
          .filter(principal -> principal instanceof StaffPrincipal staff && id.equals(staff.id()))
          .flatMap(principal -> sessions.getAllSessions(principal, false).stream())
          .forEach(SessionInformation::expireNow);
    }
  }

  public void updateClass(Long id, String code, Long teacher) {
    auth.requireAdministrator();
    if (classes.updateClass(id, code, teacher) == 0) {
      throw new IllegalArgumentException("MASTER_DATA_NOT_FOUND");
    }
  }

  public void deleteRoom(Long id) {
    auth.requireAdministrator();
    data.deleteRoom(id);
  }

  public void deleteTeacher(Long id) {
    auth.requireAdministrator();
    data.deleteTeacher(id);
  }

  public void deleteClass(Long id) {
    auth.requireAdministrator();
    if (classes.lockClassTeacher(id).isEmpty()) {
      throw new IllegalArgumentException("MASTER_DATA_NOT_FOUND");
    }
    if (classes.hasStudents(id)) {
      throw new IllegalStateException("CLASS_HAS_STUDENTS");
    }
    classes.deleteEmptyClass(id);
  }
}
