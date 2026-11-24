package de.feswiesbaden.attendance.login;

import de.feswiesbaden.attendance.entities.Staff;
import de.feswiesbaden.attendance.enums.Role;
import org.springframework.security.core.Authentication;
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
    var user =
        User.withUsername(account.getUsername())
            .password(account.getPasswordHash())
            .roles(account.getRole().name())
            .build();
    return new StaffPrincipal(
        account.getId(), user.getUsername(), user.getPassword(), user.getAuthorities());
  }

  /** Liefert Vor- und Nachnamen sowie die Frontend-Rolle admin oder lehrkraft */
  public AuthUser currentUser(Authentication authentication) {
    StaffPrincipal principal = (StaffPrincipal) authentication.getPrincipal();
    Staff account =
        staff
            .findById(principal.id())
            .orElseThrow(() -> new UsernameNotFoundException("LOGIN_FAILED"));
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
