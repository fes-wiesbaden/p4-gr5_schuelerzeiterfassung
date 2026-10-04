package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.config.RetentionJobs;
import de.feswiesbaden.attendance.service.RetentionService;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.CronExpression;

@ExtendWith(OutputCaptureExtension.class)
class RetentionJobsTest {
  @Test
  void registersJobsWithoutRunningOnStartupAndCanDisableScheduling() {
    RetentionService retention = mock(RetentionService.class);
    ApplicationContextRunner context =
        new ApplicationContextRunner()
            .withUserConfiguration(RetentionJobs.class)
            .withBean(RetentionService.class, () -> retention);
    context.run(
        application -> {
          assertThat(application).hasSingleBean(RetentionJobs.class);
          assertThat(
                  application
                      .getBean(ScheduledAnnotationBeanPostProcessor.class)
                      .getScheduledTasks())
              .hasSize(2);
          verifyNoInteractions(retention);
        });
    context
        .withPropertyValues("attendance.retention.scheduling.enabled=false")
        .run(application -> assertThat(application).doesNotHaveBean(RetentionJobs.class));
  }

  @ParameterizedTest
  @CsvSource({
    "2027-06-29T21:59:59Z,2027-06-29T22:00:00Z",
    "2027-03-27T23:00:00Z,2027-03-28T22:00:00Z",
    "2027-10-30T22:00:00Z,2027-10-31T23:00:00Z"
  })
  void historyRunsAtBerlinMidnightAcrossDst(Instant previous, Instant expected) throws Exception {
    assertNextRun("deleteHistory", previous, expected);
  }

  @Test
  void rawScansRunOnTheNextFullUtcHour() throws Exception {
    assertNextRun(
        "deleteRawScans",
        Instant.parse("2027-10-31T00:59:59Z"),
        Instant.parse("2027-10-31T01:00:00Z"));
  }

  @Test
  void failedJobLogsOnlyTechnicalCodeAndOtherJobStillWorks(CapturedOutput output) {
    RetentionService retention = mock(RetentionService.class);
    when(retention.deleteHistory()).thenThrow(new IllegalStateException("04A3B2C1"));
    when(retention.deleteRawScans()).thenReturn(2);
    RetentionJobs jobs = new RetentionJobs(retention);
    jobs.deleteHistory();
    jobs.deleteRawScans();
    verify(retention).deleteHistory();
    verify(retention).deleteRawScans();
    assertThat(output.getAll())
        .contains("HISTORY_RETENTION_FAILED", "RAW_SCAN_RETENTION deleted=2")
        .doesNotContain("04A3B2C1");
  }

  private void assertNextRun(String method, Instant previous, Instant expected) throws Exception {
    Scheduled schedule = RetentionJobs.class.getMethod(method).getAnnotation(Scheduled.class);
    var next =
        CronExpression.parse(schedule.cron()).next(previous.atZone(ZoneId.of(schedule.zone())));
    assertThat(next).isNotNull();
    assertThat(next.toInstant()).isEqualTo(expected);
  }
}
