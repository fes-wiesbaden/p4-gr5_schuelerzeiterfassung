package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import de.feswiesbaden.attendance.repository.StaffAccessRepository;
import de.feswiesbaden.attendance.repository.StaffAccessRepository.StaffIdentity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class StaffAuthorization {
  private final StaffAccessRepository staff;

  private final SchoolClassRepository classes;

  StaffAuthorization(StaffAccessRepository staff, SchoolClassRepository classes) {
    this.staff = staff;
    this.classes = classes;
  }

  StaffIdentity currentStaff() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      throw new AccessDeniedException("STAFF_REQUIRED");
    }
    return staff
        .findByUsername(authentication.getName())
        .orElseThrow(() -> new AccessDeniedException("STAFF_REQUIRED"));
  }

  void requireClassAccess(Long classId) {
    var actor = currentStaff();
    if (!classes.isAccessible(classId, actor.id(), actor.administrator())) {
      throw new AccessDeniedException("CLASS_ACCESS_DENIED");
    }
  }

  StaffIdentity requireAdministrator() {
    var actor = currentStaff();
    if (!actor.administrator()) {
      throw new AccessDeniedException("ADMINISTRATOR_REQUIRED");
    }
    return actor;
  }

  void requireStudentManagement(Long classId) {
    var actor = currentStaff();
    var teacher = classes.lockClassTeacher(classId);
    if (teacher.isEmpty() || (!actor.administrator() && !teacher.get().equals(actor.id()))) {
      throw new AccessDeniedException("STUDENT_MANAGEMENT_DENIED");
    }
  }
}
