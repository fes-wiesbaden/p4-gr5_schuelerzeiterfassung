package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TimeIntervalMigrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  private static final List<String> TABLES =
      List.of("block_assignment", "block_plan", "timetable_slot");

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;

  @BeforeEach
  void loadVersionTwoWithExistingData() {
    dataSource =
        new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    Flyway baseline =
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).target("2").load();
    baseline.clean();
    baseline.migrate();
    new ResourceDatabasePopulator(new ClassPathResource("fixtures/attendance.sql"))
        .execute(dataSource);
    jdbc = new JdbcTemplate(dataSource);
  }

  @Test
  void upgradePreservesOrderedAndEqualIntervals() {
    jdbc.update("UPDATE block_assignment SET ends_on = starts_on WHERE id = 1");
    jdbc.update("UPDATE block_plan SET ends_on = starts_on WHERE id = 1");
    jdbc.update("UPDATE timetable_slot SET end_time = start_time WHERE id = 1");
    var before = TABLES.stream().map(this::rowsOf).toList();

    Flyway.configure().dataSource(dataSource).load().migrate();

    assertThat(TABLES.stream().map(this::rowsOf).toList()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"block_assignment", "block_plan", "timetable_slot"})
  void upgradeRejectsReversedIntervalsAndResumesAfterCorrection(String table) {
    String invalidInterval =
        table.equals("timetable_slot")
            ? "start_time = '09:00:00', end_time = '07:30:00'"
            : "starts_on = '2026-12-31', ends_on = '2026-09-01'";
    jdbc.update("UPDATE " + table + " SET " + invalidInterval + " WHERE id = 1");
    var before = TABLES.stream().map(this::rowsOf).toList();

    Flyway upgrade = Flyway.configure().dataSource(dataSource).load();
    assertThatThrownBy(upgrade::migrate)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("chk_" + table + "_interval");

    assertThat(TABLES.stream().map(this::rowsOf).toList()).isEqualTo(before);

    // Korrektur nur synthetischer Testdaten; die Migration selbst verändert keine Daten.
    String correction =
        table.equals("timetable_slot") ? "end_time = start_time" : "ends_on = starts_on";
    jdbc.update("UPDATE " + table + " SET " + correction + " WHERE id = 1");
    var corrected = TABLES.stream().map(this::rowsOf).toList();

    upgrade.repair();
    upgrade.migrate();

    assertThat(TABLES.stream().map(this::rowsOf).toList()).isEqualTo(corrected);
  }

  private List<Map<String, Object>> rowsOf(String table) {
    return jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id");
  }
}
