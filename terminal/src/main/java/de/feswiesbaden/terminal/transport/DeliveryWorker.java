package de.feswiesbaden.terminal.transport;

import de.feswiesbaden.terminal.model.Scan;
import de.feswiesbaden.terminal.queue.ScanQueue;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

// Eigener Thread, damit die Oberfläche bei langsamer Verbindung nicht einfriert.
public final class DeliveryWorker implements AutoCloseable {
  private static final int INTERVAL_SECONDS = 5;

  private final ScanQueue queue;

  private final ScanSender sender;

  private final BiConsumer<Scan, SendResult> onResult;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          auftrag -> {
            Thread t = new Thread(auftrag, "delivery-worker");
            t.setDaemon(true);
            return t;
          });

  public DeliveryWorker(ScanQueue queue, ScanSender sender, BiConsumer<Scan, SendResult> onResult) {
    this.queue = queue;
    this.sender = sender;
    this.onResult = onResult;
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(this::drain, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
  }

  public void deliverNow() {
    scheduler.execute(this::drain);
  }

  private void drain() {
    try {
      for (Scan scan : List.copyOf(queue.pending())) {
        SendResult ergebnis = sender.send(scan);

        if (ergebnis.isFinal()) {
          queue.remove(scan.scanId());
        }

        onResult.accept(scan, ergebnis);

        if (!ergebnis.isFinal()) {
          return;
        }
      }
    } catch (IOException pufferKaputt) {
      System.err.println("Warteschlange nicht lesbar: " + pufferKaputt.getMessage());
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
