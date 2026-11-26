export interface Student {
  id: number
  firstName: string
  lastName: string
  birthDate: string
  classId: number
  excusedMinutes: number
  unexcusedMinutes: number
}

export interface StudentClass {
  id: number
  classCode: string
  classTeacherId: number
}

export function studentLabel(student: Student) {
  return `${student.lastName}, ${student.firstName}`
}
