package de.feswiesbaden.attendance.login;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class StaffSessionFilter extends OncePerRequestFilter {
  private final StaffRepository staff;

  public StaffSessionFilter(StaffRepository staff) {
    this.staff = staff;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      if (!(authentication.getPrincipal() instanceof StaffPrincipal principal)
          || staff.findById(principal.id()).isEmpty()) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
          session.invalidate();
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
