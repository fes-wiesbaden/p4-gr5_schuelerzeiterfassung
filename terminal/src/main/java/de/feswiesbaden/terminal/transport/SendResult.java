package de.feswiesbaden.terminal.transport;

/** Kennzeichnet die Antwort auf einen Scan oder die Wiederholung der Zustellung */
public record SendResult(Status status, String code) {

  /** Kennzeichnet eine endgültige Antwort oder eine noch offene Zustellung */
  public enum Status {
    ACCEPTED,
    ALREADY_RECEIVED,
    REJECTED,
    RETRY
  }

  /** Kennzeichnet die Zustellung als abgeschlossen und vom Sender als Erfolg eingeordnet */
  public static SendResult accepted() {
    return new SendResult(Status.ACCEPTED, null);
  }

  /** Der Server hat dieselbe Scan-ID bereits verarbeitet */
  public static SendResult alreadyReceived() {
    return new SendResult(Status.ALREADY_RECEIVED, null);
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
