package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.util.function.Consumer;

/** Beschreibt das Einlesen von Scans aus einer austauschbaren Quelle */
public interface ScanSource extends AutoCloseable {

  /** Startet den Leser und übergibt erkannte Scans an den Empfänger */
  void start(Consumer<Reading> onScan);

  /** Beendet das Einlesen weiterer Scans */
  @Override
  void close();
}
