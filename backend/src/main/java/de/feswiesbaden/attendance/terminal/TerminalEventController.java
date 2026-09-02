package de.feswiesbaden.attendance.terminal;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class TerminalEventController {
  private final TerminalEventPublisher publisher;

  TerminalEventController(TerminalEventPublisher publisher) {
    this.publisher = publisher;
  }

  // Ob es die Terminalnummer wirklich gibt, wird noch nicht geprüft. Das kommt
  // mit #24. Eine unbekannte Nummer bekommt einfach nie ein Ereignis.
  @GetMapping(
      path = "/api/terminals/{terminalNumber}/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter events(@PathVariable int terminalNumber) {
    return publisher.subscribe(terminalNumber);
  }
}
