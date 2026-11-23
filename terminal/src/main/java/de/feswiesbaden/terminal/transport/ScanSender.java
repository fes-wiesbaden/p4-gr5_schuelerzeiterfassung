package de.feswiesbaden.terminal.transport;

import com.fasterxml.jackson.databind.DeserializationFeature;
import de.feswiesbaden.terminal.model.Json;
import de.feswiesbaden.terminal.model.Scan;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import javax.net.ssl.SSLContext;

/** Sendet Scans an das Backend und ordnet dessen Antworten für die Zustellung ein */
public final class ScanSender {
  private static final String REJECTED = "SCAN_REJECTED";

  private static final String ALREADY_RECEIVED = "SCAN_ALREADY_RECEIVED";

  private final HttpClient client;

  private final URI endpoint;

  /**
   * Verlangt eine HTTPS-Adresse mit Host und einen TLS-Kontext und setzt fünf Sekunden
   * Verbindungsfrist
   */
  public ScanSender(String serverUrl, SSLContext sslContext) {
    this.endpoint = URI.create(serverUrl);
    if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
      throw new IllegalArgumentException("Terminal benötigt eine gültige HTTPS-Adresse.");
    }

    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .sslContext(Objects.requireNonNull(sslContext, "mTLS-Konfiguration fehlt."))
            .build();
  }

  /**
   * Sendet den Scan als JSON mit zehn Sekunden Anfragefrist. Ein-/Ausgabefehler und Unterbrechungen
   * liefern RETRY. Bei Unterbrechung bleibt das Interrupt-Flag gesetzt
   */
  public SendResult send(Scan scan) {
    try {
      HttpRequest anfrage =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(scan)))
              .build();

      HttpResponse<String> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofString());
      return interpret(antwort.statusCode(), antwort.body());

    } catch (IOException nichtErreichbar) {
      return SendResult.retry();
    } catch (InterruptedException unterbrochen) {
      Thread.currentThread().interrupt();
      return SendResult.retry();
    }
  }

  /**
   * Schließt die Zustellung bei den definierten Antworten von /api/scans ab. Sonstige Antworten
   * lassen den Scan für einen weiteren Versuch in der Warteschlange
   */
  static SendResult interpret(int status, String antwortkoerper) {
    return switch (status) {
      case 204 -> SendResult.accepted();
      case 400, 403 ->
          REJECTED.equals(readCode(antwortkoerper))
              ? SendResult.rejected(REJECTED)
              : SendResult.retry();
      case 409 ->
          ALREADY_RECEIVED.equals(readCode(antwortkoerper))
              ? SendResult.alreadyReceived()
              : SendResult.retry();
      default -> SendResult.retry();
    };
  }

  private static String readCode(String antwortkoerper) {
    if (antwortkoerper == null || antwortkoerper.isBlank()) {
      return null;
    }
    try {
      return Json.mapper()
          .reader()
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readTree(antwortkoerper)
          .path("code")
          .textValue();
    } catch (IOException kaputt) {
      return null;
    }
  }
}
