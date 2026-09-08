package de.feswiesbaden.terminal.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.feswiesbaden.terminal.model.Scan;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLContext;

public final class ScanSender {
  // Wenn der Server keinen Code mitschickt, nehmen wir den Sammelcode aus #37.
  private static final String FALLBACK_CODE = "SCAN_REJECTED";

  // Der Scan wurde schon einmal angenommen. Gebucht ist gebucht, also zeigen
  // wir das dem Schüler als Erfolg (Issue #26).
  private static final String ALREADY_RECEIVED = "SCAN_ALREADY_RECEIVED";

  private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  private final HttpClient client;

  private final URI endpoint;

  public ScanSender(String serverUrl, SSLContext sslContext) {
    this.endpoint = URI.create(serverUrl);

    HttpClient.Builder bauer = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
    if (sslContext != null) {
      bauer.sslContext(sslContext);
    }
    this.client = bauer.build();
  }

  public SendResult send(Scan scan) {
    try {
      HttpRequest anfrage =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body(scan)))
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

  private String body(Scan scan) throws IOException {
    ObjectNode k = json.createObjectNode();
    k.put("scanId", scan.scanId());
    k.put("rfidUid", scan.rfidUid());
    k.put("terminalNumber", scan.terminalNumber());
    k.put("capturedAt", scan.capturedAt().toString());
    return json.writeValueAsString(k);
  }

  String readCode(String antwortkoerper) {
    if (antwortkoerper == null || antwortkoerper.isBlank()) {
      return FALLBACK_CODE;
    }
    try {
      JsonNode k = json.readTree(antwortkoerper);
      JsonNode code = k.get("code");
      return code == null || code.asText().isBlank() ? FALLBACK_CODE : code.asText();
    } catch (IOException kaputt) {
      return FALLBACK_CODE;
    }
  }

  // 2xx heißt gebucht. 4xx ist eine endgültige Absage, danach fliegt der Scan
  // aus der Warteschlange. Bei 5xx ist der Server kaputt, nicht der Scan, also
  // später nochmal versuchen.
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
