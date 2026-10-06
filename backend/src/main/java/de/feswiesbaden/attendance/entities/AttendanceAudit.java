package de.feswiesbaden.attendance.entities;

import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.AuditEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "attendance_audit")
@Immutable
@Getter
@Setter
@NoArgsConstructor
public class AttendanceAudit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "student_id", nullable = false)
  private Student student;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "attendance_id")
  private Attendance attendance;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "deletion_date_id", nullable = false)
  private DeletionDate deletionDate;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false)
  private AuditEventType eventType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "changed_by_staff_id")
  private Staff changedByStaff;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "terminal_id")
  private Terminal terminal;

  @Enumerated(EnumType.STRING)
  @Column(name = "old_status")
  private AttendanceStatus oldStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_status")
  private AttendanceStatus newStatus;

  @NotNull
  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  @NotNull
  @Column(name = "unexcused_minutes_delta", nullable = false)
  private Integer unexcusedMinutesDelta;

  @NotNull
  @Column(name = "excused_minutes_delta", nullable = false)
  private Integer excusedMinutesDelta;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;
}
