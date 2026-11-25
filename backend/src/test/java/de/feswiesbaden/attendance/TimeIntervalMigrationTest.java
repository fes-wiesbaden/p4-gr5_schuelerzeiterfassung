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

/** Prüft die Migration der Datenbankregeln für Zeiträume mit vorhandenen Daten */
@Testcontainers
class TimeIntervalMigrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  private static final List<String> TABLES =
      List.of("block_assignment", "block_plan", "timetable_slot");

  private DriverManagerDataSource dataSource;

  private JdbcTemplate jdbc;

  /** Erstellt den Datenbankstand vor der Migration und lädt die Beispieldaten */
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

  /** Prüft den Erhalt gültiger Zeiträume einschließlich gleicher Grenzen bei der Migration */
  @Test
  void upgradePreservesOrderedAndEqualIntervals() {
    jdbc.update("UPDATE block_assignment SET ends_on = starts_on WHERE id = 1");
    jdbc.update("UPDATE block_plan SET ends_on = starts_on WHERE id = 1");
    jdbc.update("UPDATE timetable_slot SET end_time = start_time WHERE id = 1");
    var before = TABLES.stream().map(this::rowsOf).toList();

    Flyway.configure().dataSource(dataSource).load().migrate();

    assertThat(TABLES.stream().map(this::rowsOf).toList()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_slot"
                    + " WHERE valid_from IS NOT NULL OR valid_until IS NOT NULL OR series_id IS NOT NULL",
                Integer.class))
        .isZero();
  }

  /** Prüft den Abbruch bei vertauschten Zeitgrenzen und die Fortsetzung nach deren Korrektur */
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

    // Nur der Test korrigiert die ungültigen Beispieldaten. Die Migration fügt
    // Datenbankregeln hinzu und korrigiert keine bestehenden Fachdatensätze
    String correction =
        table.equals("timetable_slot") ? "end_time = start_time" : "ends_on = starts_on";
    jdbc.update("UPDATE " + table + " SET " + correction + " WHERE id = 1");
    var corrected = TABLES.stream().map(this::rowsOf).toList();

    upgrade.repair();
    upgrade.migrate();

    assertThat(TABLES.stream().map(this::rowsOf).toList()).isEqualTo(corrected);
  }

  /** Liest die Spalten des Ausgangsschemas vor und nach der Migration */
  private List<Map<String, Object>> rowsOf(String table) {
    String columns =
        table.equals("timetable_slot")
            ? "id, timetable_id, room_id, weekday, start_time, end_time, created_at, changed_at"
            : "*";
    return jdbc.queryForList("SELECT " + columns + " FROM " + table + " ORDER BY id");
  }
}
