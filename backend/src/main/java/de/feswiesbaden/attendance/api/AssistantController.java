package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.Excusal;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.ExcusalImpact;
import de.feswiesbaden.attendance.service.CodexAssistantService;
import de.feswiesbaden.attendance.service.CodexAssistantService.Message;
import de.feswiesbaden.attendance.service.CodexAssistantService.Reply;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/** Liefert Assistentenvorschläge und verarbeitet ausdrücklich bestätigte Entschuldigungen */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
  private static final String PREVIEW_ATTRIBUTE = AssistantController.class.getName() + ".preview";

  private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(5);

  private final CodexAssistantService codex;

  private final AttendanceAccountingService attendance;

  private final Clock clock;

  public AssistantController(
      CodexAssistantService codex, AttendanceAccountingService attendance, Clock clock) {
    this.codex = codex;
    this.attendance = attendance;
    this.clock = clock;
  }

  /** Liefert die Verfügbarkeit der Demo für die angemeldete Weboberfläche */
  @GetMapping
  public Availability availability() {
    return new Availability(codex.demoEnabled());
  }

  /** Verarbeitet die Nachrichten der Demo und liefert ausschließlich einen Vorschlag */
  @PostMapping("/messages")
  public Reply message(@Valid @RequestBody ChatPrompt prompt) {
    return codex.suggest(prompt.messages(), prompt.page());
  }

  /** Berechnet die Minuten und bindet das Ergebnis für fünf Minuten an die Websitzung */
  @PostMapping("/excusals/preview")
  public ExcusalPreview preview(
      @Valid @RequestBody Excusal request,
      @AuthenticationPrincipal StaffPrincipal staff,
      HttpSession session) {
    synchronized (WebUtils.getSessionMutex(session)) {
      session.removeAttribute(PREVIEW_ATTRIBUTE);
      var impact = attendance.previewExcusal(request);
      if (!impact.hasEffect()) {
        return new ExcusalPreview(null, impact);
      }
      var id = UUID.randomUUID();
      session.setAttribute(
          PREVIEW_ATTRIBUTE,
          new PendingExcusal(
              id, staff.id(), request, impact, clock.instant().plus(PREVIEW_LIFETIME), false));
      return new ExcusalPreview(id, impact);
    }
  }

  /** Verbucht die aktuelle Vorschau und liefert bei Wiederholung dasselbe Ergebnis */
  @PostMapping("/excusals/{id}/confirm")
  public ExcusalImpact confirm(
      @PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal staff, HttpSession session) {
    synchronized (WebUtils.getSessionMutex(session)) {
      if (!(session.getAttribute(PREVIEW_ATTRIBUTE) instanceof PendingExcusal pending)
          || !pending.id().equals(id)
          || !pending.staffId().equals(staff.id())
          || !pending.expiresAt().isAfter(clock.instant())) {
        throw new IllegalStateException("EXCUSAL_PREVIEW_EXPIRED");
      }
      if (pending.confirmed()) {
        return pending.impact();
      }
      var result = attendance.confirmExcusal(pending.request(), pending.impact());
      session.setAttribute(
          PREVIEW_ATTRIBUTE,
          new PendingExcusal(id, staff.id(), pending.request(), result, pending.expiresAt(), true));
      return result;
    }
  }

  /** Beschreibt den Schalter für den Codex-Demobetrieb */
  public record Availability(boolean demoEnabled) {}

  /** Enthält die aktuelle Frage und höchstens zwei vorherige Nachrichtenpaare */
  public record ChatPrompt(
      @NotEmpty @Size(max = 5) List<@NotNull @Valid Message> messages,
      @Pattern(regexp = "[a-z-]{1,35}") String page) {}

  /** Enthält die Auswirkungen und bei buchbarer Vorschau deren Sitzungskennung */
  public record ExcusalPreview(UUID id, ExcusalImpact impact) {}

  private record PendingExcusal(
      UUID id,
      Long staffId,
      Excusal request,
      ExcusalImpact impact,
      Instant expiresAt,
      boolean confirmed) {}
}
