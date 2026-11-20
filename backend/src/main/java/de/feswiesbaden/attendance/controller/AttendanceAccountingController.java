package de.feswiesbaden.attendance.controller;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scans")
public class AttendanceAccountingController {

  private final AttendanceAccountingService attendanceAccountingService;

  public AttendanceAccountingController(AttendanceAccountingService attendanceAccountingService) {
    this.attendanceAccountingService = attendanceAccountingService;
  }

  @PostMapping
  public ResponseEntity registerScan(@RequestBody RawScanDTO scan) {
    return switch (attendanceAccountingService.recordScan(scan)) {
      case SUCCESS -> ResponseEntity.status(HttpStatus.NO_CONTENT).build();
      case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).build();
      case FORBIDDEN -> ResponseEntity.status(HttpStatus.LOCKED).build();
      default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    };
  }
}
