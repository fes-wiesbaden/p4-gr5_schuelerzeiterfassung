package de.feswiesbaden.attendance.api;

import java.util.Map;
import org.springframework.dao.PessimisticLockingFailureException;
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
@RestControllerAdvice(
    assignableTypes = {
      StaffController.class,
      MasterDataController.class,
      PlanningController.class,
      AssistantController.class
    })
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

  /** Meldet eine wegen gleichzeitiger Änderung abgebrochene Transaktion */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  ResponseEntity<Map<String, String>> concurrentChange() {
    return error(HttpStatus.CONFLICT, "CONCURRENT_PLANNING_CHANGE");
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
      case "MASTER_DATA_NOT_FOUND",
              "CLASS_ACCESS_DENIED",
              "CLASS_NOT_FOUND",
              "STUDENT_NOT_FOUND",
              "BLOCK_ASSIGNMENT_NOT_FOUND",
              "BLOCK_PLAN_NOT_FOUND",
              "TIMETABLE_SLOT_NOT_FOUND" ->
          error(HttpStatus.NOT_FOUND, "NOT_FOUND");
      case "ADMINISTRATOR_REQUIRED",
              "TEACHER_REQUIRED",
              "CLASS_MANAGEMENT_DENIED",
              "CLASS_TEACHER_ASSIGNMENT_DENIED",
              "STAFF_REQUIRED" ->
          error(HttpStatus.FORBIDDEN, code);
      case "CLASS_HAS_STUDENTS",
              "CLASS_CODE_ALREADY_EXISTS",
              "RFID_UID_ALREADY_ASSIGNED",
              "STUDENT_HAS_HISTORY",
              "CLASS_PLAN_CHANGED",
              "CLASS_HAS_REMAINING_SLOTS",
              "CLASS_NOT_FULLY_ACCOUNTED",
              "OVERLAPPING_TIMETABLE_SLOT",
              "OVERLAPPING_BLOCK_ASSIGNMENT",
              "BLOCK_ASSIGNMENT_BELONGS_TO_ANOTHER_PLAN",
              "BLOCK_ALREADY_ASSIGNED",
              "ROOM_ALREADY_ASSIGNED",
              "TIMETABLE_REUSE_NOT_ALLOWED",
              "CURRENT_PLAN_NOT_FINISHED",
              "NEXT_PLAN_OVERLAPS_CURRENT_PLAN",
              "NEXT_PLAN_FINISHED",
              "CLASS_HAS_NO_STUDENTS",
              "ATTENDANCE_DAY_LOCKED_OR_UNPLANNED",
              "ATTENDANCE_BOOKING_CHANGED",
              "EXCUSAL_PREVIEW_CHANGED",
              "EXCUSAL_PREVIEW_EXPIRED",
              "ATTENDANCE_ALREADY_PRESENT",
              "OVERLAPPING_PLANNED_ATTENDANCE_STATUS" ->
          error(HttpStatus.CONFLICT, code);
      case "INVALID_TEACHER_PASSWORD",
              "INVALID_CLASS",
              "INVALID_RFID_UID",
              "INVALID_STUDENT",
              "INVALID_TIMETABLE",
              "INVALID_TIMETABLE_SLOT",
              "INVALID_BLOCK_PLAN",
              "INVALID_BLOCK_ASSIGNMENT",
              "INVALID_ASSISTANT_MESSAGE",
              "INVALID_EXCUSAL_TIME",
              "INVALID_STATUS_TIME",
              "INVALID_ATTENDANCE_DATE",
              "INVALID_MANUAL_ABSENCE_MINUTES",
              "INVALID_PLANNED_ATTENDANCE_DATE",
              "INVALID_PLANNED_ATTENDANCE_STATUS",
              "INVALID_PLANNED_ATTENDANCE_TIME",
              "INVALID_ATTENDANCE_STATUS" ->
          error(HttpStatus.BAD_REQUEST, code);
      case "ASSISTANT_BUSY" -> error(HttpStatus.TOO_MANY_REQUESTS, code);
      case "ASSISTANT_UNAVAILABLE" -> error(HttpStatus.SERVICE_UNAVAILABLE, code);
      case "RFID_UID_UPDATE_FAILED" -> error(HttpStatus.INTERNAL_SERVER_ERROR, code);
      default -> throw exception;
    };
  }

  /** Erstellt die Antwort mit einem maschinenlesbaren Fehlercode */
  private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).body(Map.of("code", code));
  }
}
