package de.feswiesbaden.attendance.login;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class StaffPrincipal extends User {
  private final Long id;

  public StaffPrincipal(
      Long id,
      String username,
      String password,
      Collection<? extends GrantedAuthority> authorities) {
    super(username, password, authorities);
    this.id = id;
  }

  public Long id() {
    return id;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StaffPrincipal staff && id.equals(staff.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
