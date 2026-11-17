package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.repository.TerminalRepository;
import de.feswiesbaden.attendance.service.TerminalIdentityService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

class TerminalIdentityServiceTest {
  private static final String TOKEN = "a".repeat(64);

  private final TerminalRepository terminals = mock(TerminalRepository.class);

  private final TerminalIdentityService identity = new TerminalIdentityService(terminals, TOKEN);

  @Test
  void returnsTheStoredRoom() {
    when(terminals.findRoomId(9001)).thenReturn(Optional.of(7L));
    assertThat(identity.resolveRoom(request(TOKEN, "SUCCESS", "CN=terminal-9001"), 9001))
        .isEqualTo(7L);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"wrong", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
  void rejectsMissingOrWrongProxyToken(String token) {
    rejects(request(token, "SUCCESS", "CN=terminal-9001"), 9001);
    verifyNoInteractions(terminals);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"NONE", "FAILED:certificate has expired"})
  void rejectsUnverifiedCertificates(String verification) {
    rejects(request(TOKEN, verification, "CN=terminal-9001"), 9001);
    verifyNoInteractions(terminals);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"CN=terminal-9002", "CN=terminal-09001", "CN=terminal-9001,CN=terminal-9002"})
  void rejectsMissingOrDifferentIdentity(String subject) {
    rejects(request(TOKEN, "SUCCESS", subject), 9001);
    verifyNoInteractions(terminals);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsInvalidTerminalNumbers(int terminalId) {
    rejects(request(TOKEN, "SUCCESS", "CN=terminal-" + terminalId), terminalId);
    verifyNoInteractions(terminals);
  }

  @Test
  void rejectsUnknownTerminal() {
    when(terminals.findRoomId(9001)).thenReturn(Optional.empty());
    rejects(request(TOKEN, "SUCCESS", "CN=terminal-9001"), 9001);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "short", "changeit"})
  void rejectsRequestsWhenProxyTokenIsNotConfigured(String token) {
    var unconfigured = new TerminalIdentityService(terminals, token);
    assertThatThrownBy(
            () -> unconfigured.resolveRoom(request(token, "SUCCESS", "CN=terminal-9001"), 9001))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("SCAN_REJECTED");
    verifyNoInteractions(terminals);
  }

  private void rejects(MockHttpServletRequest request, int terminalId) {
    assertThatThrownBy(() -> identity.resolveRoom(request, terminalId))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("SCAN_REJECTED");
  }

  private MockHttpServletRequest request(String token, String verification, String subject) {
    var request = new MockHttpServletRequest();
    if (token != null) {
      request.addHeader("X-Terminal-Proxy-Token", token);
    }
    if (verification != null) {
      request.addHeader("X-Terminal-Client-Verify", verification);
    }
    if (subject != null) {
      request.addHeader("X-Terminal-Client-Subject", subject);
    }
    return request;
  }
}
