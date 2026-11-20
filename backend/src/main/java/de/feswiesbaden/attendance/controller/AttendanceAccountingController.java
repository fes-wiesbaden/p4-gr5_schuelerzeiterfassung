package de.feswiesbaden.attendance.controller;

import de.feswiesbaden.attendance.config.TerminalProxyAuthentication;
import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.TerminalIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nimmt Scans vom JavaFX-Terminal entgegen. Die Identitaet kommt ausschliesslich ueber die von
 * nginx per mTLS bestaetigten Header, nicht ueber eine Staff-Session
 */
@RestController
@RequestMapping("/api/scans")
public class AttendanceAccountingController {

  private final AttendanceAccountingService attendanceAccountingService;

  private final TerminalProxyAuthentication proxyAuthentication;

  private final TerminalIdentityService identity;

  public AttendanceAccountingController(
      AttendanceAccountingService attendanceAccountingService,
      TerminalProxyAuthentication proxyAuthentication,
      TerminalIdentityService identity) {
    this.attendanceAccountingService = attendanceAccountingService;
    this.proxyAuthentication = proxyAuthentication;
    this.identity = identity;
  }

  /**
   * Prueft zuerst Zertifikat und Terminal-ID gegen die Datenbank, bevor der Scan fachlich
   * verarbeitet wird. Eine abweichende oder gefaelschte Identitaet erreicht die Fachlogik gar nicht
   * erst
   */
  @PostMapping
  public ResponseEntity<?> registerScan(HttpServletRequest request, @RequestBody RawScanDTO scan) {
    identity.resolveRoom(
        proxyAuthentication.verifiedSubject(request), scan.terminalId().intValue());
    return switch (attendanceAccountingService.recordScan(scan)) {
      case SUCCESS -> ResponseEntity.noContent().build();
      case DUPLICATE ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SCAN_ALREADY_RECEIVED"));
      case FORBIDDEN ->
          ResponseEntity.status(HttpStatus.LOCKED).body(Map.of("code", "SCAN_REJECTED"));
      case ERROR ->
          ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "SCAN_REJECTED"));
    };
  }
}
