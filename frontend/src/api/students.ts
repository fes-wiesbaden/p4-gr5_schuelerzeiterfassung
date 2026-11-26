export interface Student {
  id: number
  firstName: string
  lastName: string
  birthDate: string
  classId: number
  excusedMinutes: number
  unexcusedMinutes: number
  excusedDays: number
  unexcusedDays: number
  excusedHours: number
  unexcusedHours: number
}

export interface StudentClass {
  id: number
  classCode: string
  classTeacherId: number
}

export function studentLabel(student: Student) {
  return `${student.lastName}, ${student.firstName}`
}

export function formatAbsenceTotals(days = 0, hours = 0) {
  return `${days} ${days === 1 ? 'Tag' : 'Tage'} · ${hours} ${hours === 1 ? 'Stunde' : 'Stunden'}`
}
