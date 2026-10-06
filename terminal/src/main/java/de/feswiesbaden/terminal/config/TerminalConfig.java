package de.feswiesbaden.terminal.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record TerminalConfig(
    String serverUrl,
    String serialPort,
    int baudRate,
    Path queueFile,
    Path clientKeystore,
    String keystorePassword,
    Path serverTruststore,
    String truststorePassword) {

  private static final String DEFAULT_URL = "https://127.0.0.1:8444/api/scans";

  public static TerminalConfig load(Path file) {
    Properties werte = new Properties();

    if (Files.isReadable(file)) {
      try (InputStream in = Files.newInputStream(file)) {
        werte.load(in);
      } catch (IOException lesefehler) {
        System.err.println("terminal.properties nicht lesbar: " + lesefehler.getMessage());
      }
    }

    String keystore = werte.getProperty("client.keystore", "").trim();
    String truststore = werte.getProperty("server.truststore", "").trim();

    return new TerminalConfig(
        werte.getProperty("server.url", DEFAULT_URL),
        // Der Port darf auch aus einer Umgebungsvariablen kommen (Issue #29).
        umgebungOderDatei("TERMINAL_SERIAL_PORT", werte.getProperty("serial.port", "")),
        Integer.parseInt(werte.getProperty("serial.baud", "115200")),
        Path.of(werte.getProperty("queue.file", "scan-puffer.jsonl")),
        keystore.isEmpty() ? null : Path.of(keystore),
        werte.getProperty("client.keystore.password", ""),
        truststore.isEmpty() ? null : Path.of(truststore),
        werte.getProperty("server.truststore.password", ""));
  }

  private static String umgebungOderDatei(String variable, String ausDatei) {
    String ausUmgebung = System.getenv(variable);
    return ausUmgebung == null || ausUmgebung.isBlank() ? ausDatei.trim() : ausUmgebung.trim();
  }

  public boolean usesConsole() {
    return serialPort.isEmpty();
  }
}
