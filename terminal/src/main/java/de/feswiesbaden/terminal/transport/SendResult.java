package de.feswiesbaden.terminal.transport;

// Bei RETRY hat der Server nichts Endgültiges gesagt, dann bleibt der Scan in
// der Warteschlange liegen.
public record SendResult(Status status, String code) {

  public enum Status {
    ACCEPTED,
    REJECTED,
    RETRY
  }

  public static SendResult accepted() {
    return new SendResult(Status.ACCEPTED, null);
  }

  public static SendResult rejected(String code) {
    return new SendResult(Status.REJECTED, code);
  }

  public static SendResult retry() {
    return new SendResult(Status.RETRY, null);
  }

  public boolean isFinal() {
    return status != Status.RETRY;
  }
}
