package de.feswiesbaden.attendance.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class MasterDataService {
  private final JdbcTemplate jdbc;

  private final StaffAuthorization auth;

  private final PasswordEncoder passwords;

  private final SessionRegistry sessions;

  public MasterDataService(
      JdbcTemplate jdbc,
      StaffAuthorization auth,
      PasswordEncoder passwords,
      SessionRegistry sessions) {
    this.sessions = sessions;
    this.jdbc = jdbc;
    this.auth = auth;
    this.passwords = passwords;
  }

  public Map<String, Object> options() {
    var actor = auth.currentStaff();
    return Map.of(
        "staffId",
        actor.id(),
        "blockPlans",
        jdbc.queryForList("SELECT id,name FROM block_plan ORDER BY name"),
        "timetables",
        jdbc.queryForList("SELECT id,name FROM timetable ORDER BY name"));
  }

  public List<Map<String, Object>> teachers() {
    auth.requireAdministrator();
    return jdbc.queryForList(
        "SELECT id,first_name AS firstName,last_name AS lastName,username FROM staff "
            + "WHERE role='LEHRKRAFT' ORDER BY last_name,first_name");
  }

  public List<Map<String, Object>> rooms() {
    auth.requireAdministrator();
    return jdbc.queryForList("SELECT id,room_number AS roomNumber FROM room ORDER BY room_number");
  }

  public void saveRoom(Long id, String number) {
    auth.requireAdministrator();
    if (id == null) {
      jdbc.update("INSERT INTO room(room_number) VALUES (?)", number.strip());
    } else {
      requireRow(jdbc.update("UPDATE room SET room_number=? WHERE id=?", number.strip(), id));
    }
  }

  public void saveTeacher(Long id, String first, String last, String username, String password) {
    auth.requireAdministrator();
    if (id == null) {
      if (password == null
          || password.isBlank()
          || password.getBytes(StandardCharsets.UTF_8).length > 72) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
      }
      jdbc.update(
          "INSERT INTO staff(first_name,last_name,username,password_hash,role) VALUES (?,?,?,?,'LEHRKRAFT')",
          first.strip(),
          last.strip(),
          username.strip(),
          passwords.encode(password));
    } else {
      requireRow(
          jdbc.update(
              "UPDATE staff SET first_name=?,last_name=? WHERE id=? AND role='LEHRKRAFT' AND username=?",
              first.strip(),
              last.strip(),
              id,
              username));
    }
  }

  public void updateClass(Long id, String code, Long teacher) {
    auth.requireAdministrator();
    requireRow(
        jdbc.update(
            "UPDATE school_class SET class_code=?,class_teacher_id=? WHERE id=? "
                + "AND EXISTS (SELECT id FROM staff WHERE id=? AND role='LEHRKRAFT')",
            code.strip(),
            teacher,
            id,
            teacher));
  }

  public void deleteRoom(Long id) {
    auth.requireAdministrator();
    requireRow(jdbc.update("DELETE FROM room WHERE id=?", id));
  }

  public void deleteTeacher(Long id) {
    auth.requireAdministrator();
    var names =
        jdbc.queryForList(
            "SELECT username FROM staff WHERE id=? AND role='LEHRKRAFT' FOR UPDATE",
            String.class,
            id);
    requireRow(jdbc.update("DELETE FROM staff WHERE id=? AND role='LEHRKRAFT'", id));
    for (var principal : sessions.getAllPrincipals()) {
      if (principal instanceof UserDetails user && names.contains(user.getUsername())) {
        sessions.getAllSessions(user, false).forEach(session -> session.expireNow());
      }
    }
  }

  public void deleteClass(Long id) {
    auth.requireAdministrator();
    jdbc.update("DELETE FROM teacher_class WHERE school_class_id=?", id);
    requireRow(jdbc.update("DELETE FROM school_class WHERE id=?", id));
  }

  private void requireRow(int count) {
    if (count == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
