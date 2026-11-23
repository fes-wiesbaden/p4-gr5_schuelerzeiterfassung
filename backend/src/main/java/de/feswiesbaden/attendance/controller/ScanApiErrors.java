package de.feswiesbaden.attendance.controller;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Liefert neutrale Fehlerantworten für die Scan-API */
@RestControllerAdvice(assignableTypes = AttendanceAccountingController.class)
class ScanApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ScanApiErrors.class);

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Map<String, String>> identityRejected() {
    return rejected(HttpStatus.FORBIDDEN);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
  ResponseEntity<Map<String, String>> invalidRequest() {
    return rejected(HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Void> technicalFailure(RuntimeException failure) {
    LOG.error("SCAN_PROCESSING_FAILED type={}", failure.getClass().getSimpleName());
    return ResponseEntity.internalServerError().build();
  }

  static ResponseEntity<Map<String, String>> rejected(HttpStatus status) {
    return ResponseEntity.status(status).body(Map.of("code", "SCAN_REJECTED"));
  }
}
