package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.feswiesbaden.attendance.repository.TerminalRepository;
import de.feswiesbaden.attendance.service.TerminalIdentityService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

/** Prüft, welche Terminalnummern und Zertifikatsnamen der Service ablehnt */
class TerminalIdentityServiceTest {
  private final TerminalRepository terminals = mock(TerminalRepository.class);

  private final TerminalIdentityService identity = new TerminalIdentityService(terminals);

  /** Prüft die Ablehnung von Zertifikatsnamen mit führender Null oder einem zusätzlichen CN */
  @ParameterizedTest
  @ValueSource(strings = {"CN=terminal-09001", "CN=terminal-9001,CN=terminal-9002"})
  void rejectsNonCanonicalIdentity(String subject) {
    rejects(subject, 9001);
  }

  /** Prüft, dass Terminalnummern kleiner als eins abgelehnt werden */
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsInvalidTerminalNumbers(int terminalId) {
    rejects("CN=terminal-" + terminalId, terminalId);
  }

  /** Prüft den Fehlercode und stellt sicher, dass keine Raumabfrage erfolgt */
  private void rejects(String subject, int terminalId) {
    assertThatThrownBy(() -> identity.resolveRoom(subject, terminalId))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("SCAN_REJECTED");
    verifyNoInteractions(terminals);
  }
}
