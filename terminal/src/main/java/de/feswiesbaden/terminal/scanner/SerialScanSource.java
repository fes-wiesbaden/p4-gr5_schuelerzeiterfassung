package de.feswiesbaden.terminal.scanner;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class SerialScanSource implements ScanSource {
  // So lange haelt die Anwendung den ESP32 im Reset, bevor sie ihn starten laesst.
  private static final long RESET_PULSE_MS = 150;

  // Wartezeit einer einzelnen Leseoperation. Laeuft sie ab, wird einfach
  // weitergelesen; sie begrenzt nur, wie lange ein Lesevorgang haengt.
  private static final int READ_TIMEOUT_MS = 1000;

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
    // Ohne Timeout meldet der Datenstrom sein Ende, sobald einmal nichts
    // anliegt. Deshalb eine Wartezeit setzen und den Ablauf unten abfangen.
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, READ_TIMEOUT_MS, 0);

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

  // Das Oeffnen des Ports laesst den ESP32 im Reset haengen, er sendet dann
  // nichts mehr. DTR liegt am Bootmodus-Pin, RTS am Reset. Erst beide definiert
  // setzen, dann faehrt das Board hoch und meldet sich.
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
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {

      while (running) {
        try {
          String zeile = in.readLine();
          if (zeile == null) {
            break;
          }
          SerialLine.parse(zeile).ifPresent(onScan);
        } catch (SerialPortTimeoutException wartezeitAbgelaufen) {
          // Zwischen zwei Karten liegt fast immer eine Pause. Das ist der
          // Normalfall und kein Grund, das Lesen aufzugeben.
          continue;
        }
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
