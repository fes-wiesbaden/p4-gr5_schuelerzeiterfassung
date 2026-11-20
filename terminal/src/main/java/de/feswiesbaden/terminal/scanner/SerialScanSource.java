package de.feswiesbaden.terminal.scanner;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Liest Scanmeldungen des ESP32 über die serielle Verbindung ein */
public final class SerialScanSource implements ScanSource {
  private static final long RESET_PULSE_MS = 150;

  private static final int READ_TIMEOUT_MS = 1000;

  private final String portName;

  private final int baudRate;

  private SerialPort port;

  private volatile boolean running;

  public SerialScanSource(String portName, int baudRate) {
    this.portName = portName;
    this.baudRate = baudRate;
  }

  /** Öffnet den seriellen Port, sendet einen Resetimpuls und startet den Hintergrundleser */
  @Override
  public void start(Consumer<Reading> onScan) {
    port = SerialPort.getCommPort(portName);
    port.setBaudRate(baudRate);
    // Sobald Daten vorliegen, weiterverarbeiten. Ein Timeout erlaubt das Beenden bei Leerlauf
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);

    if (!port.openPort()) {
      throw new IllegalStateException(
          "Serieller Port "
              + portName
              + " lässt sich nicht öffnen. Hängt der ESP32 dran"
              + " und bist du in der Gruppe uucp beziehungsweise dialout?");
    }

    starteBoard();

    running = true;
    Thread leser = new Thread(() -> read(onScan), "serial-reader");
    leser.setDaemon(true);
    leser.start();
  }

  /** Löst den Reset über RTS und hält DTR frei, damit der ESP32 starten kann */
  private void starteBoard() {
    port.clearDTR();
    port.setRTS();
    try {
      Thread.sleep(RESET_PULSE_MS);
    } catch (InterruptedException unterbrochen) {
      Thread.currentThread().interrupt();
    }
    port.clearRTS();
  }

  private void read(Consumer<Reading> onScan) {
    try {
      readLines(port.getInputStream(), () -> running, onScan);
    } catch (IOException abbruch) {
      if (running) {
        System.err.println("Serielle Verbindung abgebrochen: " + abbruch.getMessage());
      }
    }
  }

  /** Hält angefangene UTF-8-Zeilen auch über einen Lesetimeout hinweg */
  static void readLines(InputStream input, BooleanSupplier running, Consumer<Reading> onScan)
      throws IOException {
    var serialInput =
        new FilterInputStream(input) {
          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            while (running.getAsBoolean()) {
              try {
                return super.read(buffer, offset, length);
              } catch (SerialPortTimeoutException pause) {
                // Unterhalb des Zeilenlesers weiterwarten, damit sein Puffer erhalten bleibt
              }
            }
            return -1;
          }
        };
    try (var reader =
        new BufferedReader(new InputStreamReader(serialInput, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null && running.getAsBoolean()) {
        SerialLine.parse(line).ifPresent(onScan);
      }
    }
  }

  /** Beendet den Leser und schließt den seriellen Port */
  @Override
  public void close() {
    running = false;
    if (port != null) {
      port.closePort();
    }
  }
}
