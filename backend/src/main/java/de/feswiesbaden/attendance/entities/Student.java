package de.feswiesbaden.attendance.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "student_id")
    private Long studentId;

    @NotNull
    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @NotNull
    @Column(name = "last_name", length = 100, nullable = false)
    private String lastName;

    @NotNull
    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_class_id", nullable = false)
    private SchoolClass schoolClass;

    @Column(name = "rfid_uid", length = 64, unique = true)
    private String rfidUid;

    @NotNull
    @Min(0)
    @Column(
            name = "unexcused_minutes_account",
            nullable = false
    )
    private Integer unexcusedMinutesAccount;

    @NotNull
    @Min(0)
    @Column(
            name = "excused_minutes_account",
            nullable = false
    )
    private Integer excusedMinutesAccount;

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