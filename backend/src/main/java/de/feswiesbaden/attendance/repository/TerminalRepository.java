package de.feswiesbaden.attendance.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Liest Terminalzuordnungen aus der Datenbank */
@Repository
public class TerminalRepository {
  private final JdbcTemplate jdbc;

  /** Bindet den Datenbankzugriff für die Raumabfrage ein */
  public TerminalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Liest und sperrt die Raumzuordnung eines gespeicherten Terminals */
  public Optional<Long> findRoomId(int terminalId) {
    return jdbc
        .queryForList(
            "SELECT room_id FROM terminal WHERE terminal_id = ? FOR SHARE", Long.class, terminalId)
        .stream()
        .findFirst();
  }
}
