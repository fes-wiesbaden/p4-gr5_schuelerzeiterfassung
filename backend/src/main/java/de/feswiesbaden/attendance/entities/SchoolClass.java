package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "school_class",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"class_code", "school_year"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SchoolClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "school_class_id")
    private Long schoolClassId;

    @NotNull
    @Column(name = "class_code", length = 30, nullable = false)
    private String classCode;

    @NotNull
    @Column(name = "school_year", length = 9, nullable = false)
    private String schoolYear;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_teacher_id", nullable = false)
    private Teacher classTeacher;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "block_plan_id", nullable = false)
    private BlockPlan blockPlan;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "timetable_id", nullable = false)
    private Timetable timetable;

    @Column(
            name = "created_at",
            insertable = false,
            updatable = false,
            nullable = false
    )
    private LocalDateTime createdAt;

    @Column(
            name = "changed_at",
            insertable = false,
            updatable = false,
            nullable = false
    )
    private LocalDateTime changedAt;
}