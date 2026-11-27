import { deleteResource, loadData, postJson } from './client'

export interface HistoryDay {
  date: string
  unexcusedMinutes: number | null
  excusedMinutes: number | null
  lateMinutes: number | null
  didNotArrive: boolean
  absenceLevel: 'FULL' | 'PARTIAL' | null
  canExcuseOpenAbsences: boolean
  editable: boolean
  manualAbsenceMinutes: number
  canCorrectStatus: boolean
  canPlanStatus: boolean
  fullAbsence?: 'ABWESEND' | 'ENTSCHULDIGT' | null
  wasPresent?: boolean
  canBookMinutes?: boolean
  // Geplante Unterrichtsminuten des Tages
  plannedMinutes?: number
  // Noch zulässige händische Fehlminuten: geplanter Unterricht abzüglich der
  // bereits berechneten und händischen Fehlminuten
  manualAbsenceLimit?: number
  // Wirksame händische Einträge des Tages samt Vermerk
  manualAbsenceEntries?: { minutes: number; note: string | null }[]
  // Zwischenstand eines noch laufenden Unterrichtstags
  interim?: boolean
  // ABWESEND ist trotz erfasster Anwesenheit zulässig, wenn kein Scan vorliegt
  canMarkAbsent?: boolean
  excusals?: {
    startsAt: string
    endsAt: string
    status:
      'ENTSCHULDIGT' | 'BETRIEBLICH_ENTSCHULDIGT' | 'MIT_ATTEST_ENTSCHULDIGT'
    reason: string | null
  }[]
  plannedStatuses: {
    startsAt: string
    endsAt: string
    status: 'ENTSCHULDIGT' | 'BETRIEBLICH_ENTSCHULDIGT'
  }[]
}

export interface HistoryWeek {
  startsOn: string
  endsOn: string
  days: HistoryDay[]
}

export function loadAttendanceWeek(studentId: number, date: string) {
  return loadData<HistoryWeek>(
    `/api/students/${studentId}/attendance/week?date=${date}`
  )
}

export function excuseOpenAbsences(studentId: number, date: string) {
  return postJson(
    `/api/students/${studentId}/attendance/${date}/open-absence-excuses`,
    {}
  )
}

export function addManualAbsence(
  studentId: number,
  date: string,
  minutes: number,
  requestId: string,
  note: string
) {
  return postJson(
    `/api/students/${studentId}/attendance/${date}/manual-absence-minutes`,
    { minutes, requestId, note }
  )
}

export function excuseSchoolDay(studentId: number, date: string) {
  return postJson(
    `/api/students/${studentId}/attendance/${date}/school-day-excuses`,
    {}
  )
}

export function correctStatusPeriod(
  studentId: number,
  date: string,
  status: string,
  startsAt: string,
  endsAt: string
) {
  return postJson(
    `/api/students/${studentId}/attendance/${date}/status-periods`,
    { status, startsAt, endsAt }
  )
}

export function removeManualAbsence(studentId: number, date: string) {
  return deleteResource(
    `/api/students/${studentId}/attendance/${date}/manual-absence-minutes`
  )
}

export interface CalendarDay {
  date: string
  // Tag liegt in einem zugeordneten Unterrichtsblock und hat Unterricht
  lessonDay: boolean
  // An diesem Tag gibt es Fehlminuten
  absent: boolean
}

export function loadAttendanceCalendar(
  studentId: number,
  from: string,
  to: string
) {
  return loadData<CalendarDay[]>(
    `/api/students/${studentId}/attendance/calendar?from=${from}&to=${to}`
  )
}
