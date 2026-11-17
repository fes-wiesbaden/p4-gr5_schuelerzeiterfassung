package de.feswiesbaden.attendance.login;

import de.feswiesbaden.attendance.entities.Staff;
import de.feswiesbaden.attendance.enums.Role;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Lädt Mitarbeiterkonten für die Anmeldung und die Nutzeranzeige */
@Service
public class StaffLoginService implements UserDetailsService {
  private final StaffRepository staff;

  public StaffLoginService(StaffRepository staff) {
    this.staff = staff;
  }

  /** Lädt Passwort-Hash und Rolle für die Prüfung durch Spring Security */
  @Override
  public UserDetails loadUserByUsername(String username) {
    Staff account = find(username);
    return User.withUsername(account.getUsername())
        .password(account.getPasswordHash())
        .roles(account.getRole().name())
        .build();
  }

  /** Liefert den Anzeigenamen und die für das Frontend vorgesehene Rolle */
  public AuthUser currentUser(String username) {
    Staff account = find(username);
    return new AuthUser(
        account.getFirstName() + " " + account.getLastName(),
        account.getRole() == Role.ADMINISTRATOR ? "admin" : "lehrkraft");
  }

  /** Sucht das Konto zum Nutzernamen und meldet fehlende Konten mit einem neutralen Fehler */
  private Staff find(String username) {
    return staff
        .findByUsername(username)
        .orElseThrow(() -> new UsernameNotFoundException("LOGIN_FAILED"));
  }

  public record AuthUser(String name, String role) {}
}
