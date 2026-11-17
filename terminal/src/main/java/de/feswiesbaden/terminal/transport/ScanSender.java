package de.feswiesbaden.terminal.transport;

import com.fasterxml.jackson.databind.JsonNode;
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
  // Wenn der Server keinen Code mitschickt, nehmen wir den Sammelcode aus #37.
  private static final String FALLBACK_CODE = "SCAN_REJECTED";

  // Der Scan wurde schon einmal angenommen. Gebucht ist gebucht, also zeigen
  // wir das dem Schüler als Erfolg (Issue #26).
  private static final String ALREADY_RECEIVED = "SCAN_ALREADY_RECEIVED";

  private final HttpClient client;

  private final URI endpoint;

  /** Erstellt den HTTP-Client mit Verbindungsfrist und dem übergebenen TLS-Kontext */
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

  /** Sendet den Scan und erlaubt bei Verbindungsfehlern oder Unterbrechungen einen neuen Versuch */
  public SendResult send(Scan scan) {
    try {
      HttpRequest anfrage =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(scan)))
              .build();

      HttpResponse<String> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofString());
      return interpret(antwort.statusCode(), readCode(antwort.body()));

    } catch (IOException nichtErreichbar) {
      return SendResult.retry();
    } catch (InterruptedException unterbrochen) {
      Thread.currentThread().interrupt();
      return SendResult.retry();
    }
  }

  /** Liest den bekannten Duplikatcode und verwendet sonst den neutralen Fehlercode */
  static String readCode(String antwortkoerper) {
    if (antwortkoerper == null || antwortkoerper.isBlank()) {
      return FALLBACK_CODE;
    }
    try {
      JsonNode code = Json.mapper().readTree(antwortkoerper).get("code");
      return code != null && ALREADY_RECEIVED.equals(code.asText())
          ? ALREADY_RECEIVED
          : FALLBACK_CODE;
    } catch (IOException kaputt) {
      return FALLBACK_CODE;
    }
  }

  /** Wertet Erfolge und Ablehnungen aus und lässt andere Antworten erneut versuchen */
  static SendResult interpret(int status, String code) {
    if (status >= 200 && status < 300) {
      return SendResult.accepted();
    }
    if (status >= 400 && status < 500) {
      return ALREADY_RECEIVED.equals(code) ? SendResult.accepted() : SendResult.rejected(code);
    }
    return SendResult.retry();
  }
}
