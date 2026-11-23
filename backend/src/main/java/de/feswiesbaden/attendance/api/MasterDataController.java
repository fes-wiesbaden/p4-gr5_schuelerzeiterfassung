package de.feswiesbaden.attendance.api;

import de.feswiesbaden.attendance.service.MasterDataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MasterDataController {
  private final MasterDataService data;

  public MasterDataController(MasterDataService data) {
    this.data = data;
  }

  public record RoomRequest(@NotBlank @Size(max = 30) String roomNumber) {}

  public record TeacherRequest(
      @NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String lastName,
      @NotBlank @Size(max = 120) String username,
      String password) {}

  public record ClassUpdate(
      @NotBlank @Size(max = 30) String classCode, @NotNull Long classTeacherId) {}

  @GetMapping("/class-options")
  public Map<String, Object> options() {
    return data.options();
  }

  @GetMapping("/rooms")
  public List<Map<String, Object>> rooms() {
    return data.rooms();
  }

  @PostMapping("/rooms")
  @ResponseStatus(HttpStatus.CREATED)
  public void createRoom(@Valid @RequestBody RoomRequest r) {
    data.saveRoom(null, r.roomNumber());
  }

  @PutMapping("/rooms/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateRoom(@PathVariable Long id, @Valid @RequestBody RoomRequest r) {
    data.saveRoom(id, r.roomNumber());
  }

  @DeleteMapping("/rooms/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRoom(@PathVariable Long id) {
    data.deleteRoom(id);
  }

  @GetMapping("/teachers")
  public List<Map<String, Object>> teachers() {
    return data.teachers();
  }

  @PostMapping("/teachers")
  @ResponseStatus(HttpStatus.CREATED)
  public void createTeacher(@Valid @RequestBody TeacherRequest r) {
    data.saveTeacher(null, r.firstName(), r.lastName(), r.username(), r.password());
  }

  @PutMapping("/teachers/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateTeacher(@PathVariable Long id, @Valid @RequestBody TeacherRequest r) {
    data.saveTeacher(id, r.firstName(), r.lastName(), r.username(), r.password());
  }

  @DeleteMapping("/teachers/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteTeacher(@PathVariable Long id) {
    data.deleteTeacher(id);
  }

  @PutMapping("/classes/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateClass(@PathVariable Long id, @Valid @RequestBody ClassUpdate r) {
    data.updateClass(id, r.classCode(), r.classTeacherId());
  }

  @DeleteMapping("/classes/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteClass(@PathVariable Long id) {
    data.deleteClass(id);
  }
}
