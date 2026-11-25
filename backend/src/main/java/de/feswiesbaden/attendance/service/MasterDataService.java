package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.MasterDataRepository;
import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

  public MasterDataService(
      MasterDataRepository data,
      SchoolClassRepository classes,
      StaffAuthorization auth,
      PasswordEncoder passwords) {
    this.data = data;
    this.classes = classes;
    this.auth = auth;
    this.passwords = passwords;
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

  public void saveTeacher(Long id, String first, String last, String username, String password) {
    auth.requireAdministrator();
    if (id == null) {
      if (password == null
          || password.isBlank()
          || password.getBytes(StandardCharsets.UTF_8).length > 72) {
        throw new IllegalArgumentException("INVALID_TEACHER_PASSWORD");
      }
      password = passwords.encode(password);
    }
    data.saveTeacher(id, first, last, username, password);
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
