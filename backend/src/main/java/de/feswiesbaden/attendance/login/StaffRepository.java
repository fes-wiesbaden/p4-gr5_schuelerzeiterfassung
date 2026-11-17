package de.feswiesbaden.attendance.login;

import de.feswiesbaden.attendance.entities.Staff;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Lädt Mitarbeiterkonten für die Anmeldung anhand des Nutzernamens */
public interface StaffRepository extends Repository<Staff, Long> {
  /** Sucht das Mitarbeiterkonto anhand des Nutzernamens */
  Optional<Staff> findByUsername(String username);
}
