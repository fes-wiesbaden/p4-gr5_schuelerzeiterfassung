package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "block_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BlockPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long blockPlanId;

    @NotNull
    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @NotNull
    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @NotNull
    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

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