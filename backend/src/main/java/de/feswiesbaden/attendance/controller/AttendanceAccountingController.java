package de.feswiesbaden.attendance.controller;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nimmt die Scanaufträge des JavaFX-Terminals entgegen */
@RestController
@RequestMapping("/api/scans")
public class AttendanceAccountingController {
  private final AttendanceAccountingService accounting;

  public AttendanceAccountingController(AttendanceAccountingService accounting) {
    this.accounting = accounting;
  }

  /** Verarbeitet einen Scan und liefert den neutralen Zustellstatus */
  @PostMapping
  public ResponseEntity<Map<String, String>> registerScan(
      @RequestAttribute String verifiedTerminalSubject,
      @RequestAttribute Instant scanReceivedAt,
      @Valid @RequestBody RawScanDTO scan) {
    return switch (accounting.recordScan(verifiedTerminalSubject, scan, scanReceivedAt)) {
      case SUCCESS -> ResponseEntity.noContent().build();
      case DUPLICATE ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SCAN_ALREADY_RECEIVED"));
      case REJECTED -> ScanApiErrors.rejected(HttpStatus.BAD_REQUEST);
    };
  }
}
