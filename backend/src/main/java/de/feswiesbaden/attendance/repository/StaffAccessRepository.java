package de.feswiesbaden.attendance.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Liest Mitarbeiteridentität und aktuelle Administratorrolle für Rechteprüfungen */
@Repository
public class StaffAccessRepository {
  private final JdbcTemplate jdbc;

  public StaffAccessRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Sucht die gespeicherte Mitarbeiter-ID und aktuelle Administratorrolle zum Benutzernamen */
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

  public Optional<StaffIdentity> findById(Long id) {
    return jdbc
        .query(
            "SELECT id, role FROM staff WHERE id = ?",
            (row, index) ->
                new StaffIdentity(row.getLong("id"), "ADMINISTRATOR".equals(row.getString("role"))),
            id)
        .stream()
        .findFirst();
  }

  /** Enthält die Mitarbeiter-ID und die aktuelle Administratorberechtigung */
  public record StaffIdentity(Long id, boolean administrator) {}
}
