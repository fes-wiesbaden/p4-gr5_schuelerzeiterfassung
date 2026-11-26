package de.feswiesbaden.attendance.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MasterDataRepository {
  private final JdbcTemplate jdbc;

  public MasterDataRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> teachers() {
    return jdbc.queryForList(
        "SELECT id,first_name AS firstName,last_name AS lastName,username FROM staff "
            + "WHERE role='LEHRKRAFT' ORDER BY last_name,first_name");
  }

  public List<Map<String, Object>> rooms() {
    return jdbc.queryForList(
        """
        SELECT r.id, r.room_number AS roomNumber, t.terminal_id AS terminalId
        FROM room r LEFT JOIN terminal t ON t.room_id = r.id
        ORDER BY r.room_number
        """);
  }

  public void saveRoom(Long id, String number) {
    if (id == null) {
      jdbc.update("INSERT INTO room(room_number) VALUES (?)", number.strip());
    } else {
      requireRow(jdbc.update("UPDATE room SET room_number=? WHERE id=?", number.strip(), id));
    }
  }

  public void saveTeacher(Long id, String first, String last, String username, String password) {
    if (id == null) {
      jdbc.update(
          "INSERT INTO staff(first_name,last_name,username,password_hash,role) VALUES (?,?,?,?,'LEHRKRAFT')",
          first.strip(),
          last.strip(),
          username.strip(),
          password);
    } else {
      requireRow(
          jdbc.update(
              "UPDATE staff SET first_name=?,last_name=? WHERE id=? AND role='LEHRKRAFT'",
              first.strip(),
              last.strip(),
              id));
    }
  }

  public void deleteRoom(Long id) {
    requireRow(jdbc.update("DELETE FROM room WHERE id=?", id));
  }

  public void deleteTeacher(Long id) {
    requireRow(jdbc.update("DELETE FROM staff WHERE id=? AND role='LEHRKRAFT'", id));
  }

  private void requireRow(int count) {
    if (count == 0) {
      throw new IllegalArgumentException("MASTER_DATA_NOT_FOUND");
    }
  }
}
