package de.feswiesbaden.terminal.transport;

/** Unterscheidet erfolgreiche, abgelehnte und erneut zu versuchende Zustellungen */
public record SendResult(Status status, String code) {

  /** Kennzeichnet eine endgültige Antwort oder eine noch offene Zustellung */
  public enum Status {
    ACCEPTED,
    REJECTED,
    RETRY
  }

  /** Kennzeichnet die Zustellung als abgeschlossen und vom Sender als Erfolg eingeordnet */
  public static SendResult accepted() {
    return new SendResult(Status.ACCEPTED, null);
  }

  /** Kennzeichnet eine endgültige Ablehnung mit dem übergebenen Antwortcode */
  public static SendResult rejected(String code) {
    return new SendResult(Status.REJECTED, code);
  }

  /** Kennzeichnet die Zustellung für einen weiteren Versuch */
  public static SendResult retry() {
    return new SendResult(Status.RETRY, null);
  }

  /** Prüft, ob der Scan nach der Antwort aus der Warteschlange entfernt werden kann */
  public boolean isFinal() {
    return status != Status.RETRY;
  }
}
