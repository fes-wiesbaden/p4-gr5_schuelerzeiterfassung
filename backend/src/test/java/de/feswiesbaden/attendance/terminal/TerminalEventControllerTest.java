package de.feswiesbaden.attendance.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Nur die Webschicht, damit der Test ohne Datenbank und ohne Docker läuft.
@WebMvcTest(TerminalEventController.class)
@Import({SecurityConfig.class, TerminalEventPublisher.class})
class TerminalEventControllerTest {
  @Autowired MockMvc mockMvc;
  @Autowired TerminalEventPublisher publisher;

  @Test
  void streamIsReachableWithoutLogin() throws Exception {
    mockMvc
        .perform(get("/api/terminals/3/events"))
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted());
  }

  @Test
  void sendsProcessedScanToTheMatchingTerminal() throws Exception {
    MvcResult stream = mockMvc.perform(get("/api/terminals/3/events")).andReturn();

    publisher.publish(3, ScanResult.VERARBEITET);

    String body = stream.getResponse().getContentAsString();
    assertThat(body).contains("event:scan").contains("{\"result\":\"VERARBEITET\"}");
  }

  @Test
  void sendsRejectedScanToTheMatchingTerminal() throws Exception {
    MvcResult stream = mockMvc.perform(get("/api/terminals/3/events")).andReturn();

    publisher.publish(3, ScanResult.ABGELEHNT);

    assertThat(stream.getResponse().getContentAsString()).contains("{\"result\":\"ABGELEHNT\"}");
  }

  @Test
  void doesNotSendToAnotherTerminal() throws Exception {
    MvcResult stream = mockMvc.perform(get("/api/terminals/3/events")).andReturn();

    publisher.publish(4, ScanResult.VERARBEITET);

    assertThat(stream.getResponse().getContentAsString()).doesNotContain("scan");
  }

  @Test
  void sendsNothingBesidesTheResult() throws Exception {
    MvcResult stream = mockMvc.perform(get("/api/terminals/3/events")).andReturn();

    publisher.publish(3, ScanResult.ABGELEHNT);

    assertThat(stream.getResponse().getContentAsString())
        .contains("data:{\"result\":\"ABGELEHNT\"}")
        .doesNotContain("rfid")
        .doesNotContain("student")
        .doesNotContain("reason");
  }
}
