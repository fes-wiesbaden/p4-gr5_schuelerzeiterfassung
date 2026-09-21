package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "teacher_class")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TeacherClass {

  @EmbeddedId private TeacherClassId id;

  @NotNull
  @MapsId("staffId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "staff_id", nullable = false)
  private Staff staff;

  @NotNull
  @MapsId("schoolClassId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "school_class_id", nullable = false)
  private SchoolClass schoolClass;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "changed_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime changedAt;
}
