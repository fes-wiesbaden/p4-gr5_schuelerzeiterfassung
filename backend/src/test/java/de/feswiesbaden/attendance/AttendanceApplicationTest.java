package de.feswiesbaden.attendance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class AttendanceApplicationTest {
  @Test
  void applicationHasAnEntrypoint() {
    assertDoesNotThrow(() -> AttendanceApplication.class.getDeclaredMethod("main", String[].class));
  }
}
