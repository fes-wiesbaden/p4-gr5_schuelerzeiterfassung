package de.feswiesbaden.terminal.model;

/** Definiert feste Terminalanzeigen ohne Namen, Klassen oder Kartenkennungen */
public enum ScanState {
  READY("⌾", "Karte auflegen", "Halte deine Schulkarte an das Lesegerät."),
  PROCESSING("⧗", "Wird verarbeitet", "Der Scan wird an den Server geschickt."),
  QUEUED("⇄", "Lokal vorgemerkt", "Der Scan wird nachgereicht."),
  SUCCESS("✓", "Buchung erfolgreich", "Deine Anwesenheit wurde gespeichert."),
  ALREADY_RECEIVED("ⓘ", "Scan bereits verarbeitet", "Der Server kennt diesen Scan bereits."),
  ERROR("✕", "Nicht gebucht", "Bitte wende dich an deine Lehrkraft.");

  private final String symbol;

  private final String headline;

  private final String hint;

  ScanState(String symbol, String headline, String hint) {
    this.symbol = symbol;
    this.headline = headline;
    this.hint = hint;
  }

  /** Liefert das feste Symbol des Anzeigezustands */
  public String symbol() {
    return symbol;
  }

  /** Liefert die neutrale Überschrift des Anzeigezustands */
  public String headline() {
    return headline;
  }

  /** Liefert den festen Hinweistext des Anzeigezustands */
  public String hint() {
    return hint;
  }
}
