package de.feswiesbaden.attendance.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
class AdminAuthorization {
  private final JdbcTemplate jdbc;

  AdminAuthorization(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void requireAdministrator(Long staffId) {
    if (staffId == null
        || !jdbc.queryForList("SELECT role FROM staff WHERE id = ?", String.class, staffId)
            .contains("ADMINISTRATOR")) {
      throw new AccessDeniedException("ADMINISTRATOR_REQUIRED");
    }
  }
}
