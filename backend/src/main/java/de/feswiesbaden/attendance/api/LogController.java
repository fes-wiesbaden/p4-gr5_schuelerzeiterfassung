package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.repository.LogRepository.AuditEntry;
import de.feswiesbaden.attendance.repository.LogRepository.LogQuery;
import de.feswiesbaden.attendance.repository.LogRepository.Page;
import de.feswiesbaden.attendance.repository.LogRepository.RawScanEntry;
import de.feswiesbaden.attendance.service.LogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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

  /** Gemeinsame Filter- und Seitenparameter beider Logs, fehlende Werte erhalten Standardwerte */
  public record LogParams(
      @NotNull Instant from,
      @NotNull Instant to,
      @Size(max = 100) String search,
      @Pattern(regexp = "asc|desc") String direction,
      @Min(0) Integer page,
      @Min(1) @Max(100) Integer size) {
    public LogParams {
      direction = direction == null ? "desc" : direction;
      page = page == null ? 0 : page;
      size = size == null ? 25 : size;
    }

    LogQuery toQuery(String sort) {
      return new LogQuery(from, to, search, sort, direction.equals("asc"), page, size);
    }

    /** Der Suchbegriff kann eine UID sein und bleibt deshalb aus technischen Logs heraus */
    @Override
    public String toString() {
      return "LogParams[page=" + page + "]";
    }
  }

  /** Rohscans enthalten Kartenkennungen und werden deshalb nicht im Browser zwischengespeichert */
  @GetMapping("/raw-scans")
  public ResponseEntity<Page<RawScanEntry>> rawScans(
      @Valid @ModelAttribute LogParams params,
      @RequestParam(defaultValue = "scannedAt")
          @Pattern(regexp = "scannedAt|rfidUid|student|classCode")
          String sort) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(logs.rawScans(params.toQuery(sort)));
  }

  /** Änderungsprotokoll, optional auf einen Ereignistyp beschränkt */
  @GetMapping("/audits")
  public ResponseEntity<Page<AuditEntry>> audits(
      @Valid @ModelAttribute LogParams params,
      @RequestParam(required = false) AuditEventType eventType,
      @RequestParam(defaultValue = "occurredAt")
          @Pattern(regexp = "occurredAt|eventType|student|actor")
          String sort) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(logs.audits(params.toQuery(sort), eventType));
  }
}
