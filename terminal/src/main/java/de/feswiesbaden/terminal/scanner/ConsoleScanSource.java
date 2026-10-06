package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

// Ersatz für den ESP32 ohne Hardware. Bewusst über die Konsole statt über einen
// Knopf: die Oberfläche darf laut #37 keine Bedienelemente haben.
public final class ConsoleScanSource implements ScanSource {
  private volatile boolean running;

  @Override
  public void start(Consumer<Reading> onScan) {
    running = true;
    Thread leser = new Thread(() -> read(onScan), "console-reader");
    leser.setDaemon(true);
    leser.start();

    System.out.println(
        "[TODO] Kein serieller Port eingetragen. Scans hier eintippen, zum Beispiel:");
    System.out.println("[TEST] {\"rfidUid\":\"04A3B2C1\",\"terminalNumber\":23102003}");
  }

  private void read(Consumer<Reading> onScan) {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

      String zeile;
      while (running && (zeile = in.readLine()) != null) {
        SerialLine.parse(zeile).ifPresent(onScan);
      }
    } catch (IOException ende) {
      // Konsole zu, nichts weiter zu tun.
    }
  }

  @Override
  public void close() {
    running = false;
  }
}
