package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "school_class")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SchoolClass {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @NotNull
  @Column(name = "class_code", length = 30, nullable = false, unique = true)
  private String classCode;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "class_teacher_id", nullable = false)
  private Staff classTeacher;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "block_plan_id", nullable = false)
  private BlockPlan blockPlan;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "timetable_id", nullable = false)
  private Timetable timetable;

  @ManyToMany
  @JoinTable(
      name = "class_block_assignment",
      joinColumns = @JoinColumn(name = "school_class_id"),
      inverseJoinColumns = @JoinColumn(name = "block_assignment_id"))
  private Set<BlockAssignment> blockAssignments = new HashSet<>();

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "changed_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime changedAt;
}
