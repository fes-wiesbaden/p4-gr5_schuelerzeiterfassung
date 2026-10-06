package de.feswiesbaden.terminal.model;

// Feste Texte, damit nie ein Name, eine Klasse oder eine UID am Terminal
// auftaucht (#37).
public enum ScanState {
  READY("⌾", "Karte auflegen", "Halte deine Schulkarte an das Lesegerät."),
  PROCESSING("⧗", "Wird verarbeitet", "Der Scan wird an den Server geschickt."),
  QUEUED("⇄", "Lokal vorgemerkt", "Keine Verbindung. Der Scan wird nachgereicht."),
  SUCCESS("✓", "Buchung erfolgreich", "Deine Anwesenheit wurde gespeichert."),
  ERROR("✕", "Nicht gebucht", "Bitte wende dich an deine Lehrkraft.");

  private final String symbol;

  private final String headline;

  private final String hint;

  ScanState(String symbol, String headline, String hint) {
    this.symbol = symbol;
    this.headline = headline;
    this.hint = hint;
  }

  public String symbol() {
    return symbol;
  }

  public String headline() {
    return headline;
  }

  public String hint() {
    return hint;
  }
}
