package de.feswiesbaden.attendance.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TerminalRepository {
  private final JdbcTemplate jdbc;

  public TerminalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Long> findRoomId(int terminalId) {
    return jdbc
        .queryForList("SELECT room_id FROM terminal WHERE terminal_id = ?", Long.class, terminalId)
        .stream()
        .findFirst();
  }
}
