package de.feswiesbaden.attendance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prüft den nginx-Nachweis und erfasst die Eingangszeit vor dem Lesen des Scanauftrags */
final class TerminalScanFilter extends OncePerRequestFilter {
  private final TerminalProxyAuthentication proxy;

  private final Clock clock;

  private final ObjectMapper mapper;

  TerminalScanFilter(TerminalProxyAuthentication proxy, Clock clock, ObjectMapper mapper) {
    this.proxy = proxy;
    this.clock = clock;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var receivedAt = clock.instant();
    String subject;
    try {
      subject = proxy.verifiedSubject(request);
    } catch (AccessDeniedException denied) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(response.getOutputStream(), Map.of("code", "SCAN_REJECTED"));
      return;
    }
    request.setAttribute("verifiedTerminalSubject", subject);
    request.setAttribute("scanReceivedAt", receivedAt);
    chain.doFilter(request, response);
  }
}
