package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.repository.LogRepository.AuditEntry;
import de.feswiesbaden.attendance.repository.LogRepository.Page;
import de.feswiesbaden.attendance.repository.LogRepository.RawScanEntry;
import de.feswiesbaden.attendance.service.LogService;
import de.feswiesbaden.attendance.service.LogService.LogQuery;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Liefert Rohscans und Änderungsprotokoll gefiltert, sortiert und seitenweise */
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
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "scannedAt") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(logs.rawScans(new LogQuery(from, to, search, sort, direction, page, size)));
  }

  /** Änderungsprotokoll, optional auf einen Ereignistyp beschränkt */
  @GetMapping("/audits")
  public ResponseEntity<Page<AuditEntry>> audits(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "occurredAt") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(logs.audits(new LogQuery(from, to, search, sort, direction, page, size), eventType));
  }
}
