package de.feswiesbaden.attendance.config;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Prüft, ob die Terminalanfrage vom nginx-Proxy kommt */
@Component
public class TerminalProxyAuthentication {
  private final String proxyToken;

  /** Übernimmt den gemeinsamen Schlüssel von nginx und Spring */
  public TerminalProxyAuthentication(
      @Value("${attendance.terminal.proxy-token:}") String proxyToken) {
    this.proxyToken = proxyToken;
  }

  /** Prüft den Proxy-Schlüssel und gibt den von nginx bestätigten Zertifikatsnamen zurück */
  public String verifiedSubject(HttpServletRequest request) {
    String receivedToken = request.getHeader("X-Terminal-Proxy-Token");
    String subject = request.getHeader("X-Terminal-Client-Subject");
    if (!proxyToken.matches("[0-9a-f]{64}")
        || receivedToken == null
        || !MessageDigest.isEqual(
            proxyToken.getBytes(StandardCharsets.UTF_8),
            receivedToken.getBytes(StandardCharsets.UTF_8))
        || !"SUCCESS".equals(request.getHeader("X-Terminal-Client-Verify"))
        || subject == null
        || subject.isBlank()) {
      throw new AccessDeniedException("SCAN_REJECTED");
    }
    return subject;
  }
}
