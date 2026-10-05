package de.feswiesbaden.attendance.login;

import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {
  private final StaffLoginService staff;

  public LoginController(StaffLoginService staff) {
    this.staff = staff;
  }

  @GetMapping("/api/me")
  public StaffLoginService.AuthUser currentUser(Principal principal) {
    return staff.currentUser(principal.getName());
  }
}
