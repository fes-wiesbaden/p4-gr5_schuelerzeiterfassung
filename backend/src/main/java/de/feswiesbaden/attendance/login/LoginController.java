package de.feswiesbaden.attendance.login;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stellt Anzeigenamen und Rolle für den angemeldeten Mitarbeiter bereit */
@RestController
public class LoginController {
  private final StaffLoginService staff;

  public LoginController(StaffLoginService staff) {
    this.staff = staff;
  }

  /** Liefert Name und Rolle des angemeldeten Nutzers */
  @GetMapping("/api/me")
  public StaffLoginService.AuthUser currentUser(Authentication principal) {
    return staff.currentUser(principal);
  }
}
