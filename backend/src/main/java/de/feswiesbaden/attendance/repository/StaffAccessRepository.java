package de.feswiesbaden.attendance.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StaffAccessRepository {
  private final JdbcTemplate jdbc;

  public StaffAccessRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<StaffIdentity> findByUsername(String username) {
    return jdbc
        .query(
            "SELECT id, role FROM staff WHERE username = ?",
            (row, index) ->
                new StaffIdentity(row.getLong("id"), "ADMINISTRATOR".equals(row.getString("role"))),
            username)
        .stream()
        .findFirst();
  }

  public record StaffIdentity(Long id, boolean administrator) {}
}
