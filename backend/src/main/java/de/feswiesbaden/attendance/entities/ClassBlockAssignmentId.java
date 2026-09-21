package de.feswiesbaden.attendance.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class ClassBlockAssignmentId implements Serializable {

  @Column(name = "school_class_id")
  private Long schoolClassId;

  @Column(name = "block_assignment_id")
  private Long blockAssignmentId;
}
