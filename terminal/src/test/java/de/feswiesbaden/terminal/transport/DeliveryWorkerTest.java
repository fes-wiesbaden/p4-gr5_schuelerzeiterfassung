package de.feswiesbaden.terminal.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import de.feswiesbaden.terminal.model.Json;
import de.feswiesbaden.terminal.model.Scan;
import de.feswiesbaden.terminal.queue.ScanQueue;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeliveryWorkerTest {
  @Test
  void retriesTheOldestPersistedScanBeforeSendingANewOne(@TempDir Path directory) throws Exception {
    var tls = tls(directory);
    var requests = new CopyOnWriteArrayList<String>();
    var connected = new AtomicBoolean(false);
    var failedAttempt = new CountDownLatch(1);
    var delivered = new CountDownLatch(2);
    var server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(tls));
    server.createContext(
        "/scans",
        exchange -> {
          try (exchange) {
            var scan = Json.mapper().readValue(exchange.getRequestBody(), Scan.class);
            requests.add(scan.scanId());
            exchange.sendResponseHeaders(connected.get() ? 200 : 503, -1);
          }
        });
    server.start();
    try {
      var queuePath = directory.resolve("queue.jsonl");
      var oldScan = Scan.of("TEST-UID-OLD", 9001);
      new ScanQueue(queuePath).add(oldScan);
      var queue = new ScanQueue(queuePath);
      var sender =
          new ScanSender("https://localhost:" + server.getAddress().getPort() + "/scans", tls);
      try (var worker =
          new DeliveryWorker(
              queue,
              sender,
              (scan, result) -> {
                if (result.isFinal()) {
                  delivered.countDown();
                } else {
                  failedAttempt.countDown();
                }
              })) {
        worker.deliverNow();
        assertTrue(failedAttempt.await(15, TimeUnit.SECONDS));
        assertEquals(List.of(oldScan), queue.pending());
        var freshScan = Scan.of("TEST-UID-NEW", 9001);
        queue.add(freshScan);
        connected.set(true);
        worker.deliverNow();
        assertTrue(delivered.await(15, TimeUnit.SECONDS));
        assertEquals(List.of(oldScan.scanId(), oldScan.scanId(), freshScan.scanId()), requests);
        assertEquals(0, queue.size());
      }
    } finally {
      server.stop(0);
    }
  }

  private SSLContext tls(Path directory) throws Exception {
    var storePath = directory.resolve("server.p12");
    var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    var process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-storetype",
                "PKCS12",
                "-keystore",
                storePath.toString(),
                "-storepass",
                "test-password")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertTrue(process.waitFor(15, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
    var keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(storePath)) {
      keys.load(input, "test-password".toCharArray());
    }
    var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "test-password".toCharArray());
    var trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("test", keys.getCertificate("test"));
    var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trust);
    var context = SSLContext.getInstance("TLS");
    context.init(managers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    return context;
  }
}
