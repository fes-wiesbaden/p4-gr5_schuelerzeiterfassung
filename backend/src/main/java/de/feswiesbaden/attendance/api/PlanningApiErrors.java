package de.feswiesbaden.attendance.api;

import java.util.Map;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Übersetzt Fehler der Planungs-API in HTTP-Status und feste Fehlercodes. Getrennt von
 * StaffApiErrors, bis die dortige Umstellung aus #82 gemergt ist
 */
@RestControllerAdvice(assignableTypes = PlanningController.class)
class PlanningApiErrors {
  /** Meldet ungültige Anfragen ohne Eingabewerte in der Antwort */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Map<String, String>> invalidRequest() {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
  }

  /** Meldet eine wegen gleichzeitiger Planungsänderung abgebrochene Transaktion */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  ResponseEntity<Map<String, String>> concurrentChange() {
    return error(HttpStatus.CONFLICT, "CONCURRENT_PLANNING_CHANGE");
  }

  /** Ordnet die Fehlercodes der Planungsservices einem HTTP-Status zu */
  @ExceptionHandler({
    IllegalArgumentException.class,
    IllegalStateException.class,
    AccessDeniedException.class
  })
  ResponseEntity<Map<String, String>> serviceError(RuntimeException exception) {
    String code = String.valueOf(exception.getMessage());
    return switch (code) {
      case "CLASS_ACCESS_DENIED",
              "CLASS_NOT_FOUND",
              "BLOCK_ASSIGNMENT_NOT_FOUND",
              "BLOCK_PLAN_NOT_FOUND" ->
          error(HttpStatus.NOT_FOUND, "NOT_FOUND");
      case "ADMINISTRATOR_REQUIRED", "CLASS_MANAGEMENT_DENIED", "STAFF_REQUIRED" ->
          error(HttpStatus.FORBIDDEN, code);
      case "OVERLAPPING_TIMETABLE_SLOT",
              "OVERLAPPING_BLOCK_ASSIGNMENT",
              "BLOCK_ASSIGNMENT_BELONGS_TO_ANOTHER_PLAN",
              "BLOCK_ALREADY_ASSIGNED",
              "ROOM_ALREADY_ASSIGNED",
              "TIMETABLE_REUSE_NOT_ALLOWED",
              "CLASS_PLAN_CHANGED",
              "CURRENT_PLAN_NOT_FINISHED",
              "NEXT_PLAN_OVERLAPS_CURRENT_PLAN",
              "NEXT_PLAN_FINISHED",
              "CLASS_HAS_NO_STUDENTS",
              "CLASS_HAS_REMAINING_SLOTS",
              "CLASS_NOT_FULLY_ACCOUNTED" ->
          error(HttpStatus.CONFLICT, code);
      case "INVALID_TIMETABLE",
              "INVALID_TIMETABLE_SLOT",
              "INVALID_BLOCK_PLAN",
              "INVALID_BLOCK_ASSIGNMENT" ->
          error(HttpStatus.BAD_REQUEST, code);
      default -> throw exception;
    };
  }

  /** Erstellt die Antwort mit einem maschinenlesbaren Fehlercode */
  private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).body(Map.of("code", code));
  }
}
