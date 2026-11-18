package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.feswiesbaden.attendance.config.TerminalProxyAuthentication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/** Prüft, welche Proxy-Header die Authentifizierung ablehnt */
class TerminalProxyAuthenticationTest {
  private static final String TOKEN = "a".repeat(64);

  private static final String SUBJECT = "CN=terminal-9001";

  private final TerminalProxyAuthentication authentication = new TerminalProxyAuthentication(TOKEN);

  /** Prüft, dass ein leerer oder zu kurzer konfigurierter Schlüssel abgelehnt wird */
  @ParameterizedTest
  @ValueSource(strings = {"", "short"})
  void rejectsMatchingTokenWhenConfigurationIsInvalid(String token) {
    rejects(new TerminalProxyAuthentication(token), request(token, "SUCCESS", SUBJECT));
  }

  /** Prüft die Ablehnung bei fehlendem oder falschem Proxy-Schlüssel */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
  void rejectsMissingOrWrongProxyToken(String token) {
    rejects(authentication, request(token, "SUCCESS", SUBJECT));
  }

  /** Prüft die Ablehnung bei fehlender oder fehlgeschlagener Zertifikatsprüfung */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"NONE", "FAILED:certificate has expired"})
  void rejectsUnverifiedCertificates(String verification) {
    rejects(authentication, request(TOKEN, verification, SUBJECT));
  }

  /** Prüft die Ablehnung ohne brauchbaren Zertifikatsnamen */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void rejectsMissingOrBlankCertificateSubject(String subject) {
    rejects(authentication, request(TOKEN, "SUCCESS", subject));
  }

  /** Prüft den neutralen Fehlercode bei einer Ablehnung */
  private void rejects(TerminalProxyAuthentication adapter, MockHttpServletRequest request) {
    assertThatThrownBy(() -> adapter.verifiedSubject(request))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("SCAN_REJECTED");
  }

  /** Erstellt eine Testanfrage mit den angegebenen Proxy-Headern */
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
