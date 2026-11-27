package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.repository.LogRepository.AuditEntry;
import de.feswiesbaden.attendance.repository.LogRepository.LogQuery;
import de.feswiesbaden.attendance.repository.LogRepository.Page;
import de.feswiesbaden.attendance.repository.LogRepository.RawScanEntry;
import de.feswiesbaden.attendance.service.LogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liefert Rohscans und Änderungsprotokoll gefiltert, sortiert und seitenweise. Ungültige Parameter
 * beantwortet StaffApiErrors mit INVALID_REQUEST
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {
  private final LogService logs;

  public LogController(LogService logs) {
    this.logs = logs;
  }

  /** Rohscans enthalten Kartenkennungen und werden deshalb nicht im Browser zwischengespeichert */
  @GetMapping("/raw-scans")
  public ResponseEntity<Page<RawScanEntry>> rawScans(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(required = false) @Size(max = 100) String search,
      @RequestParam(defaultValue = "scannedAt")
          @Pattern(regexp = "scannedAt|rfidUid|student|classCode")
          String sort,
      @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
    var query = new LogQuery(from, to, search, sort, direction.equals("asc"), page, size);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(logs.rawScans(query));
  }

  /** Änderungsprotokoll, optional auf einen Ereignistyp beschränkt */
  @GetMapping("/audits")
  public ResponseEntity<Page<AuditEntry>> audits(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(required = false) AuditEventType eventType,
      @RequestParam(required = false) @Size(max = 100) String search,
      @RequestParam(defaultValue = "occurredAt")
          @Pattern(regexp = "occurredAt|eventType|student|actor")
          String sort,
      @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
    var query = new LogQuery(from, to, search, sort, direction.equals("asc"), page, size);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(logs.audits(query, eventType));
  }
}
