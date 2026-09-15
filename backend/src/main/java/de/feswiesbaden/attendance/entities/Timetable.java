package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "timetable")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Timetable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timetable_id")
    private Long timetableId;

    @NotNull
    @Column(name = "name", length = 150, nullable = false)
    private String name;

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