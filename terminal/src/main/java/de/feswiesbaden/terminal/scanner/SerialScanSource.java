package de.feswiesbaden.terminal.scanner;

import com.fazecast.jSerialComm.SerialPort;
import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class SerialScanSource implements ScanSource {
  private final String portName;

  private final int baudRate;

  private SerialPort port;

  private volatile boolean running;

  public SerialScanSource(String portName, int baudRate) {
    this.portName = portName;
    this.baudRate = baudRate;
  }

  @Override
  public void start(Consumer<Reading> onScan) {
    port = SerialPort.getCommPort(portName);
    port.setBaudRate(baudRate);
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 0);

    if (!port.openPort()) {
      throw new IllegalStateException(
          "Serieller Port "
              + portName
              + " lässt sich nicht öffnen. Hängt der ESP32 dran"
              + " und bist du in der Gruppe uucp beziehungsweise dialout?");
    }

    running = true;
    Thread leser = new Thread(() -> read(onScan), "serial-reader");
    leser.setDaemon(true);
    leser.start();
  }

  private void read(Consumer<Reading> onScan) {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {

      String zeile;
      while (running && (zeile = in.readLine()) != null) {
        SerialLine.parse(zeile).ifPresent(onScan);
      }
    } catch (IOException abbruch) {
      if (running) {
        System.err.println("Serielle Verbindung abgebrochen: " + abbruch.getMessage());
      }
    }
  }

  @Override
  public void close() {
    running = false;
    if (port != null) {
      port.closePort();
    }
  }
}
