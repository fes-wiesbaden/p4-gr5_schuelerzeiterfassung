package de.feswiesbaden.attendance.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Übersetzt bekannte Fehler der Mitarbeiter-API in HTTP-Status und feste Fehlercodes */
@RestControllerAdvice(assignableTypes = {StaffController.class, MasterDataController.class})
class StaffApiErrors {
  /** Meldet ungültige Anfragen ohne Eingabewerte in der Antwort */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<Map<String, String>> invalidRequest() {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
  }

  /** Verwendet dieselbe Antwort für fremde und unbekannte Datensätze */
  @ExceptionHandler({
    IllegalArgumentException.class,
    IllegalStateException.class,
    AccessDeniedException.class
  })
  ResponseEntity<Map<String, String>> serviceError(RuntimeException exception) {
    // Sobald weitere Fehlerfälle dazukommen, soll die HTTP-Zuordnung typisierte Fehlercodes aus
    // einem Enum verwenden
    String code = String.valueOf(exception.getMessage());
    return switch (code) {
      case "CLASS_ACCESS_DENIED", "CLASS_NOT_FOUND", "STUDENT_NOT_FOUND" ->
          error(HttpStatus.NOT_FOUND, "NOT_FOUND");
      case "ADMINISTRATOR_REQUIRED",
              "CLASS_MANAGEMENT_DENIED",
              "CLASS_TEACHER_ASSIGNMENT_DENIED",
              "STAFF_REQUIRED" ->
          error(HttpStatus.FORBIDDEN, code);
      case "CLASS_CODE_ALREADY_EXISTS",
              "STUDENT_HAS_HISTORY",
              "CLASS_PLAN_CHANGED",
              "CLASS_HAS_REMAINING_SLOTS",
              "CLASS_NOT_FULLY_ACCOUNTED",
              "ATTENDANCE_DAY_LOCKED_OR_UNPLANNED" ->
          error(HttpStatus.CONFLICT, code);
      case "INVALID_CLASS",
              "INVALID_STUDENT",
              "INVALID_STATUS_TIME",
              "INVALID_ATTENDANCE_DATE",
              "INVALID_ATTENDANCE_STATUS" ->
          error(HttpStatus.BAD_REQUEST, code);
      default -> throw exception;
    };
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<Map<String, String>> conflict() {
    return error(HttpStatus.CONFLICT, "MASTER_DATA_CONFLICT");
  }

  /** Erstellt die Antwort mit einem maschinenlesbaren Fehlercode */
  private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).body(Map.of("code", code));
  }
}
