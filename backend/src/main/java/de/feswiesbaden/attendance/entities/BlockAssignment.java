package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "block_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BlockAssignment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long blockAssignmentId;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "block_plan_id", nullable = false)
  private BlockPlan blockPlan;

  @NotNull
  @Column(name = "starts_on", nullable = false)
  private LocalDate startsOn;

  @NotNull
  @Column(name = "ends_on", nullable = false)
  private LocalDate endsOn;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "changed_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime changedAt;
}
