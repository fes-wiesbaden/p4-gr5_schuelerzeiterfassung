package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "class_block_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ClassBlockAssignment {

  @EmbeddedId private ClassBlockAssignmentId id;

  @NotNull
  @MapsId("schoolClassId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "school_class_id", nullable = false)
  private SchoolClass schoolClass;

  @NotNull
  @MapsId("blockAssignmentId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "block_assignment_id", nullable = false)
  private BlockAssignment blockAssignment;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "changed_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime changedAt;
}
