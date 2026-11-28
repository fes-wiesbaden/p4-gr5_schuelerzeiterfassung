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
  // Fehlminuten ohne ganze Fehltage; aus ihnen entstehen die Zeugnisstunden
  excusedPartialMinutes?: number
  unexcusedPartialMinutes?: number
}

export interface StudentClass {
  id: number
  classCode: string
  classTeacherId: number
}

type Named = Pick<Student, 'firstName' | 'lastName'>

// „Nachname, Vorname“; ohne Vornamen nur der Nachname, ohne hängendes Komma
export function personLabel(person: Named) {
  return [person.lastName, person.firstName].filter(Boolean).join(', ')
}

export const studentLabel = personLabel

// Vergleichsform für Namen: ohne Groß-/Kleinschreibung und doppelte Leerzeichen
export function normalizeName(value = '') {
  return value
    .normalize()
    .trim()
    .replace(/\s+/g, ' ')
    .toLocaleLowerCase('de-DE')
}

// Suchform ohne Groß-/Kleinschreibung und Akzente: „ozdemir“ findet „Özdemir“
export function searchKey(value: string) {
  return value
    .toLocaleLowerCase('de')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
}

// Durchsuchbarer Text, der auch Umschreibungen findet: „krueger“ und „kruger“
// finden beide „Krüger“
export function searchableText(value: string) {
  const digraphs = value
    .toLocaleLowerCase('de')
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
  return `${searchKey(value)} ${searchKey(digraphs)}`
}

// Vergleichsform für Klassenkürzel: zusätzlich ohne Leerzeichen, „10/11 BE“ = „10/11be“
export function classCodeKey(value = '') {
  return normalizeName(value).replace(/ /g, '')
}

// Passt ein Name in der Form „Vorname Nachname“ oder „Nachname, Vorname“?
export function matchesName(student: Named, name: string) {
  const target = normalizeName(name)
  return [
    studentLabel(student),
    `${student.firstName} ${student.lastName}`
  ].some((candidate) => normalizeName(candidate) === target)
}

// Klasse eines eindeutig benannten Schülers, sonst undefined
export function classOfNamedStudent(
  students: Pick<Student, 'firstName' | 'lastName' | 'classId'>[],
  name: string
) {
  const matches = students.filter((student) => matchesName(student, name))
  return matches.length === 1 ? matches[0]!.classId : undefined
}

export function formatAbsenceTotals(days = 0, hours = 0) {
  return `${days} ${days === 1 ? 'Tag' : 'Tage'} · ${hours} ${hours === 1 ? 'Stunde' : 'Stunden'}`
}
