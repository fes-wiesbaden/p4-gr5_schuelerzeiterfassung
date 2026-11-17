package de.feswiesbaden.attendance.login;

import de.feswiesbaden.attendance.entities.Staff;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface StaffRepository extends Repository<Staff, Long> {
  /** Sucht das Mitarbeiterkonto anhand des Nutzernamens */
  Optional<Staff> findByUsername(String username);
}
