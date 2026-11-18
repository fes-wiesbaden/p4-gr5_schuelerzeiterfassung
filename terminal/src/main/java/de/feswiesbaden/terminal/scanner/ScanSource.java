package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.util.function.Consumer;

/** Beschreibt das Einlesen von Scans aus einer austauschbaren Quelle */
public interface ScanSource extends AutoCloseable {

  /** Startet den Leser und übergibt erkannte Scans an den Empfänger */
  void start(Consumer<Reading> onScan);

  /**
   * Fordert das Beenden des Lesers an. Die konkrete Quelle bestimmt, wann ein blockierendes Lesen
   * endet
   */
  @Override
  void close();
}
