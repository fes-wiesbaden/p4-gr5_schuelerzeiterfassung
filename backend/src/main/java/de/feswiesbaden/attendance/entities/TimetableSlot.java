package de.feswiesbaden.attendance.entities;

import de.feswiesbaden.attendance.enums.Weekday;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "timetable_slot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TimetableSlot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "timetable_id", nullable = false)
  private Timetable timetable;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "weekday", nullable = false)
  private Weekday weekday;

  @NotNull
  @Column(name = "start_time", nullable = false)
  private LocalTime startTime;

  @NotNull
  @Column(name = "end_time", nullable = false)
  private LocalTime endTime;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "changed_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime changedAt;
}
