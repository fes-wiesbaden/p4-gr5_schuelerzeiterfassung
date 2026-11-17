package de.feswiesbaden.attendance.login;

import java.security.Principal;
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
  public StaffLoginService.AuthUser currentUser(Principal principal) {
    return staff.currentUser(principal.getName());
  }
}
