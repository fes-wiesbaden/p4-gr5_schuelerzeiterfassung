package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.Excusal;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.ExcusalImpact;
import de.feswiesbaden.attendance.service.CodexAssistantService;
import de.feswiesbaden.attendance.service.CodexAssistantService.Message;
import de.feswiesbaden.attendance.service.CodexAssistantService.Reply;
import de.feswiesbaden.attendance.service.ExcusalConfirmationService;
import de.feswiesbaden.attendance.service.ExcusalConfirmationService.ExcusalPreview;
import de.feswiesbaden.attendance.service.ExcusalConfirmationService.PreviewState;
import de.feswiesbaden.attendance.service.TimetableDraftService;
import de.feswiesbaden.attendance.service.TimetableDraftService.Draft;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.WebUtils;

/** Liefert Assistentenvorschläge und verarbeitet ausdrücklich bestätigte Entschuldigungen */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
  private static final String PREVIEW_ATTRIBUTE = AssistantController.class.getName() + ".preview";

  private final CodexAssistantService codex;

  private final ExcusalConfirmationService excusals;

  private final TimetableDraftService timetableDrafts;

  public AssistantController(
      CodexAssistantService codex,
      ExcusalConfirmationService excusals,
      TimetableDraftService timetableDrafts) {
    this.codex = codex;
    this.excusals = excusals;
    this.timetableDrafts = timetableDrafts;
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

  /**
   * Liest einen Stundenplan aus dem hochgeladenen Bild und liefert einen Entwurf. Gespeichert wird
   * erst nach Bestätigung über den Import der Planung. Rechte, Typ und Größe prüft der Service
   */
  @PostMapping("/timetable-drafts")
  public Draft timetableDraft(@RequestParam(name = "image", required = false) MultipartFile image)
      throws IOException {
    return timetableDrafts.read(image == null ? null : image.getBytes());
  }

  /** Berechnet die Minuten und bindet das Ergebnis für fünf Minuten an die Websitzung */
  @PostMapping("/excusals/preview")
  public ExcusalPreview preview(
      @Valid @RequestBody Excusal request,
      @AuthenticationPrincipal StaffPrincipal staff,
      HttpSession session) {
    synchronized (WebUtils.getSessionMutex(session)) {
      return excusals.preview(previewState(session), staff.id(), request);
    }
  }

  /** Verbucht die aktuelle Vorschau und liefert bei Wiederholung dasselbe Ergebnis */
  @PostMapping("/excusals/{id}/confirm")
  public ExcusalImpact confirm(
      @PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal staff, HttpSession session) {
    synchronized (WebUtils.getSessionMutex(session)) {
      return excusals.confirm(previewState(session), staff.id(), id);
    }
  }

  private static PreviewState previewState(HttpSession session) {
    if (session.getAttribute(PREVIEW_ATTRIBUTE) instanceof PreviewState state) {
      return state;
    }
    var state = new PreviewState();
    session.setAttribute(PREVIEW_ATTRIBUTE, state);
    return state;
  }

  /** Beschreibt den Schalter für den Codex-Demobetrieb */
  public record Availability(boolean demoEnabled) {}

  /** Enthält die aktuelle Frage und höchstens zwei vorherige Nachrichtenpaare */
  public record ChatPrompt(
      @NotEmpty @Size(max = 5) List<@NotNull @Valid Message> messages,
      @Pattern(regexp = "[a-z-]{1,35}") String page) {}
}
