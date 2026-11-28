package de.feswiesbaden.attendance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Prüft die Sperre pro Person und die Freigabe nach Fehlern ohne echten Codex-Prozess */
class CodexRunnerTest {
  private final CountDownLatch entered = new CountDownLatch(1);

  private final CountDownLatch release = new CountDownLatch(1);

  /** Hält den ersten Aufruf an, bis der Test ihn freigibt */
  private final CodexRunner runner =
      new CodexRunner(true, "") {
        @Override
        String execute(Request request) throws IOException, InterruptedException {
          if ("fehler".equals(request.prompt())) {
            throw new IOException("Prozess nicht startbar");
          }
          if ("warten".equals(request.prompt())) {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
          }
          return "{}";
        }
      };

  @Test
  void rejectsASecondRequestOfThePersonButServesOthersAndReleasesAfterwards() throws Exception {
    var first = CompletableFuture.supplyAsync(() -> runner.run(request(1L, "warten")));
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> runner.run(request(1L, "frage")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSISTANT_BUSY");
    assertThat(runner.run(request(2L, "frage"))).isEqualTo("{}");

    release.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("{}");
    assertThat(runner.run(request(1L, "frage"))).isEqualTo("{}");
  }

  @Test
  void reportsAFailedProcessAsUnavailableAndFreesThePerson() {
    assertThatThrownBy(() -> runner.run(request(1L, "fehler")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSISTANT_UNAVAILABLE");
    assertThat(runner.run(request(1L, "frage"))).isEqualTo("{}");
  }

  @Test
  void refusesRequestsWhenTheDemoIsOff() {
    assertThatThrownBy(() -> new CodexRunner(false, "").run(request(1L, "frage")))
        .hasMessage("ASSISTANT_UNAVAILABLE");
  }

  private static CodexRunner.Request request(Long staffId, String prompt) {
    return new CodexRunner.Request(staffId, prompt, "unused", null, 1, 1);
  }
}
