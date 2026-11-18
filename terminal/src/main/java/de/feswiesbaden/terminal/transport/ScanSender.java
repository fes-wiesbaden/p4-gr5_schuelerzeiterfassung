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
  // Unbekannte oder fehlende Antwortcodes werden durch einen festen, neutralen Code ersetzt
  private static final String FALLBACK_CODE = "SCAN_REJECTED";

  // Das Backend kennt diese Scan-ID bereits. Die Zustellung gilt dann als abgeschlossen
  // und wird wie ein Erfolg angezeigt. Der Code allein belegt keine Anwesenheitsbuchung
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
      return interpret(antwort.statusCode(), readCode(antwort.body()));

    } catch (IOException nichtErreichbar) {
      return SendResult.retry();
    } catch (InterruptedException unterbrochen) {
      Thread.currentThread().interrupt();
      return SendResult.retry();
    }
  }

  /**
   * Übernimmt aus dem JSON-Feld code ausschließlich SCAN_ALREADY_RECEIVED. Fehlende oder andere
   * Codes und unlesbares JSON ergeben den neutralen Code SCAN_REJECTED
   */
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

  /**
   * Wertet HTTP 2xx als ACCEPTED und HTTP 4xx als endgültige Ablehnung. Die Ausnahme
   * SCAN_ALREADY_RECEIVED gilt auch bei HTTP 4xx als ACCEPTED. Alle übrigen HTTP-Statuswerte
   * ergeben RETRY und lassen den Scan in der Warteschlange
   */
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
