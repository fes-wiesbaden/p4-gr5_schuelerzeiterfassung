package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.SchoolClassRepository;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchoolClassService {
  private final SchoolClassRepository classes;

  private final StaffAuthorization authorization;

  public SchoolClassService(SchoolClassRepository classes, StaffAuthorization authorization) {
    this.classes = classes;
    this.authorization = authorization;
  }

  @Transactional(readOnly = true)
  public List<ClassView> list() {
    var actor = authorization.currentStaff();
    return classes.findAccessible(actor.id(), actor.administrator()).stream()
        .map(
            schoolClass ->
                new ClassView(
                    schoolClass.id(),
                    schoolClass.classCode(),
                    schoolClass.classTeacherId(),
                    schoolClass.blockPlanId(),
                    schoolClass.timetableId()))
        .toList();
  }

  @Transactional
  public Long create(String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {
    var actor = authorization.currentStaff();
    if (!actor.administrator()) {
      if (classTeacherId != null && !classTeacherId.equals(actor.id())) {
        throw new AccessDeniedException("CLASS_TEACHER_ASSIGNMENT_DENIED");
      }
      classTeacherId = actor.id();
    }
    if (classCode == null
        || classCode.isBlank()
        || classCode.strip().length() > 30
        || !classes.hasTeacher(classTeacherId)
        || !classes.hasBlockPlan(blockPlanId)
        || !classes.hasTimetable(timetableId)) {
      throw new IllegalArgumentException("INVALID_CLASS");
    }
    try {
      return classes.insert(classCode.strip(), classTeacherId, blockPlanId, timetableId);
    } catch (DuplicateKeyException conflict) {
      throw new IllegalArgumentException("CLASS_CODE_ALREADY_EXISTS");
    }
  }

  public record ClassView(
      Long id, String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {}
}
