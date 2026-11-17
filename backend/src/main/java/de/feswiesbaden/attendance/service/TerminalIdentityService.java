package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.TerminalRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TerminalIdentityService {
  private final TerminalRepository terminals;

  private final String proxyToken;

  public TerminalIdentityService(
      TerminalRepository terminals,
      @Value("${attendance.terminal.proxy-token:}") String proxyToken) {
    this.terminals = terminals;
    this.proxyToken = proxyToken;
  }

  @Transactional(readOnly = true)
  public Long resolveRoom(HttpServletRequest request, int terminalId) {
    String receivedToken = request.getHeader("X-Terminal-Proxy-Token");
    // Ohne den lokalen Proxy-Schlüssel können wir den Headern nicht vertrauen.
    if (!proxyToken.matches("[0-9a-f]{64}")
        || receivedToken == null
        || !MessageDigest.isEqual(
            proxyToken.getBytes(StandardCharsets.UTF_8),
            receivedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new AccessDeniedException("SCAN_REJECTED");
    }

    if (terminalId <= 0
        || !"SUCCESS".equals(request.getHeader("X-Terminal-Client-Verify"))
        || !("CN=terminal-" + terminalId).equals(request.getHeader("X-Terminal-Client-Subject"))) {
      throw new AccessDeniedException("SCAN_REJECTED");
    }

    return terminals
        .findRoomId(terminalId)
        .orElseThrow(() -> new AccessDeniedException("SCAN_REJECTED"));
  }
}
