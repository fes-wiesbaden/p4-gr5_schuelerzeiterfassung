package de.feswiesbaden.attendance.terminal;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class TerminalEventPublisher {
  private static final long THIRTY_MINUTES = 30 * 60 * 1000L;

  private record OpenStream(int terminalNumber, SseEmitter emitter) {}

  // CopyOnWriteArrayList, weil sich Anzeigen an- und abmelden können, während
  // gerade gesendet wird.
  private final List<OpenStream> streams = new CopyOnWriteArrayList<>();

  public SseEmitter subscribe(int terminalNumber) {
    // Nach dem Zeitlimit verbindet sich der Browser von allein neu.
    SseEmitter emitter = new SseEmitter(THIRTY_MINUTES);
    OpenStream stream = new OpenStream(terminalNumber, emitter);
    streams.add(stream);

    emitter.onCompletion(() -> streams.remove(stream));
    emitter.onTimeout(() -> streams.remove(stream));
    emitter.onError(error -> streams.remove(stream));

    return emitter;
  }

  // Ruft später die Scanverarbeitung auf (#26, #30). Es geht nur das Ergebnis
  // raus, keine Namen, Klassen oder Gründe.
  public void publish(int terminalNumber, ScanResult result) {
    for (OpenStream stream : streams) {
      if (stream.terminalNumber() != terminalNumber) {
        continue;
      }

      try {
        stream
            .emitter()
            .send(
                SseEmitter.event()
                    .name("scan")
                    .data(Map.of("result", result.name()), MediaType.APPLICATION_JSON));
      } catch (IOException closed) {
        streams.remove(stream);
      }
    }
  }
}
