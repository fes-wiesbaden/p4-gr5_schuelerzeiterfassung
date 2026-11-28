package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.service.AttendanceAccountingService.Excusal;
import de.feswiesbaden.attendance.service.AttendanceAccountingService.ExcusalImpact;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Verwaltet die befristete Vorschau und ihre einmalige Verbuchung je Sitzung */
@Service
public class ExcusalConfirmationService {
  private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(5);

  private final AttendanceAccountingService attendance;

  private final Clock clock;

  public ExcusalConfirmationService(AttendanceAccountingService attendance, Clock clock) {
    this.attendance = attendance;
    this.clock = clock;
  }

  /** Ersetzt die bisherige Vorschau und vergibt bei buchbaren Änderungen eine Kennung */
  public ExcusalPreview preview(PreviewState state, Long staffId, Excusal request) {
    state.pending = null;
    var impact = attendance.previewExcusal(request);
    if (!impact.hasEffect()) {
      return new ExcusalPreview(null, impact);
    }
    var id = UUID.randomUUID();
    state.pending =
        new PendingExcusal(
            id, staffId, request, impact, clock.instant().plus(PREVIEW_LIFETIME), false);
    return new ExcusalPreview(id, impact);
  }

  /** Prüft Kennung, Akteur und Ablauf und liefert bei Wiederholung das gespeicherte Ergebnis */
  public ExcusalImpact confirm(PreviewState state, Long staffId, UUID id) {
    var pending = state.pending;
    if (pending == null
        || !pending.id().equals(id)
        || !pending.staffId().equals(staffId)
        || !pending.expiresAt().isAfter(clock.instant())) {
      throw new IllegalStateException("EXCUSAL_PREVIEW_EXPIRED");
    }
    if (pending.confirmed()) {
      return pending.impact();
    }
    var result = attendance.confirmExcusal(pending.request(), pending.impact());
    state.pending =
        new PendingExcusal(id, staffId, pending.request(), result, pending.expiresAt(), true);
    return result;
  }

  /** Enthält die Auswirkungen und bei buchbarer Vorschau deren Kennung */
  public record ExcusalPreview(UUID id, ExcusalImpact impact) {}

  /** Sitzungszustand, dessen Zugriff der HTTP-Adapter mit dem Sitzungsmutex schützt */
  public static final class PreviewState {
    private PendingExcusal pending;
  }

  private record PendingExcusal(
      UUID id,
      Long staffId,
      Excusal request,
      ExcusalImpact impact,
      Instant expiresAt,
      boolean confirmed) {}
}
