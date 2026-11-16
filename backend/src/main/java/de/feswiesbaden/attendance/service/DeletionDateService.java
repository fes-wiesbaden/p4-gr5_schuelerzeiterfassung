package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.entities.DeletionDate;
import de.feswiesbaden.attendance.repository.DeletionDateRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletionDateService {
  private final DeletionDateRepository deletionDates;

  public DeletionDateService(DeletionDateRepository deletionDates) {
    this.deletionDates = deletionDates;
  }

  @Transactional
  public DeletionDate getOrCreate(LocalDate endsOn) {
    return deletionDates.getOrCreate(endsOn.plusMonths(6));
  }
}
