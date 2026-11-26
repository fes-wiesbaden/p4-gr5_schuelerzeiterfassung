import { loadData, postJson } from './client'

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
  requestId: string
) {
  return postJson(
    `/api/students/${studentId}/attendance/${date}/manual-absence-minutes`,
    { minutes, requestId }
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
