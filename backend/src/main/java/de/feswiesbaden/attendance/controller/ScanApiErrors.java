package de.feswiesbaden.attendance.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Übersetzt Identitäts- und Anfragefehler der Terminal-Scan-API in feste, neutrale Fehlercodes */
@RestControllerAdvice(assignableTypes = AttendanceAccountingController.class)
class ScanApiErrors {
  /**
   * Zertifikat, Terminal-ID oder Zuordnung stimmen nicht; derselbe neutrale Code wie eine fachliche
   * Ablehnung, damit das Terminal keinen Unterschied zeigen kann
   */
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Map<String, String>> identityRejected() {
    return error(HttpStatus.FORBIDDEN);
  }

  /** Nicht lesbares JSON erhält denselben neutralen Code statt Validierungsdetails */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> invalidRequest() {
    return error(HttpStatus.BAD_REQUEST);
  }

  private ResponseEntity<Map<String, String>> error(HttpStatus status) {
    return ResponseEntity.status(status).body(Map.of("code", "SCAN_REJECTED"));
  }
}
