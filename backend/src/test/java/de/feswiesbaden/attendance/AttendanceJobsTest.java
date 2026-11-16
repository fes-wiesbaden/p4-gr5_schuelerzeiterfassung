package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.config.AttendanceJobs;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.CronExpression;

@ExtendWith(OutputCaptureExtension.class)
class AttendanceJobsTest {
  @Test
  void registersNightJobAndRunsAtApplicationReadyIndependentlyOfRetentionScheduling() {
    var accounting = mock(AttendanceAccountingService.class);
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(AttendanceJobs.class)
            .withBean(AttendanceAccountingService.class, () -> accounting)
            .withPropertyValues("attendance.retention.scheduling.enabled=false");
    runner.run(
        context -> {
          assertThat(
                  context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
              .hasSize(1);
          verifyNoInteractions(accounting);
          context.publishEvent(
              new ApplicationReadyEvent(
                  new SpringApplication(),
                  new String[0],
                  context.getSourceApplicationContext(),
                  Duration.ZERO));
          verify(accounting).closeDueDays();
        });
    runner
        .withPropertyValues("attendance.accounting.scheduling.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(AttendanceJobs.class));
  }

  @ParameterizedTest
  @CsvSource({
    "2027-06-29T22:00:00Z,2027-06-29T22:15:00Z",
    "2027-03-27T23:15:00Z,2027-03-28T22:15:00Z",
    "2027-10-30T22:15:00Z,2027-10-31T23:15:00Z"
  })
  void runsAtBerlinQuarterPastMidnightAcrossDst(Instant previous, Instant expected)
      throws Exception {
    var schedule = AttendanceJobs.class.getMethod("closeDays").getAnnotation(Scheduled.class);
    var next =
        CronExpression.parse(schedule.cron()).next(previous.atZone(ZoneId.of(schedule.zone())));
    assertThat(next).isNotNull();
    assertThat(next.toInstant()).isEqualTo(expected);
  }

  @Test
  void logsOnlyTechnicalFailureAndAllowsTheNextRun(CapturedOutput output) {
    var accounting = mock(AttendanceAccountingService.class);
    when(accounting.closeDueDays())
        .thenThrow(new IllegalStateException("TEST-UID-PRIVATE"))
        .thenReturn(3);
    var jobs = new AttendanceJobs(accounting);
    jobs.closeDays();
    jobs.closeDays();
    verify(accounting, times(2)).closeDueDays();
    assertThat(output.getAll())
        .contains("DAILY_CLOSE_FAILED", "DAILY_CLOSE changedDays=3")
        .doesNotContain("TEST-UID-PRIVATE");
  }
}
