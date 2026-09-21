package de.feswiesbaden.attendance.entities;

import de.feswiesbaden.attendance.enums.Role;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "staff")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Staff {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @NotNull
  @Column(name = "first_name", length = 100, nullable = false)
  private String firstName;

  @NotNull
  @Column(name = "last_name", length = 100, nullable = false)
  private String lastName;

  @NotNull
  @Column(name = "username", length = 120, nullable = false, unique = true)
  private String username;

  @NotNull
  @Column(name = "password_hash", length = 60, nullable = false)
  private String passwordHash;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false)
  private Role role;

  @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "changed_at", insertable = false, updatable = false, nullable = false)
  private LocalDateTime changedAt;
}
