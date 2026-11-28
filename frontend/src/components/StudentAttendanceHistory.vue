<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import DatePicker from 'primevue/datepicker'
import InputText from 'primevue/inputtext'
import Tag from 'primevue/tag'
import GermanTimeInput from './GermanTimeInput.vue'
import {
  STATUS_HINTS,
  STATUS_LABELS,
  berlinTime,
  todayInBerlin,
  type AttendanceStatus
} from '@/api/attendance'
import { errorMessage, formatTime } from '@/api/planning'
import {
  formatCalendarWeek,
  timePattern,
  toCalendarDate,
  toIsoDate
} from '@/dateTime'
import { confirmAction } from '@/confirm'
import { showNotice } from '@/notice'
import {
  addManualAbsence,
  correctStatusPeriod,
  excuseOpenAbsences,
  excuseSchoolDay,
  loadAttendanceCalendar,
  loadAttendanceWeek,
  removeManualAbsence,
  type CalendarDay,
  type HistoryDay,
  type HistoryWeek
} from '@/api/attendanceHistory'

const props = defineProps<{
  studentId: number
  initialDate?: string
  revision?: number
}>()
const emit = defineEmits<{ corrected: [] }>()

type DayAction = 'minutes' | 'removeMinutes' | 'excuse' | 'status'

const displayDate = new Intl.DateTimeFormat('de-DE', {
  timeZone: 'UTC',
  weekday: 'short',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric'
})

function canSelectDay(entry: HistoryDay) {
  return (
    entry.editable ||
    entry.canBookMinutes ||
    entry.manualAbsenceMinutes > 0 ||
    entry.canCorrectStatus ||
    entry.canPlanStatus
  )
}

// shortcut: „heute“ gilt ab dem Laden; über Mitternacht offene Profile rechnen bis zum Neuladen mit dem Vortag
const today = toCalendarDate(todayInBerlin())!
const lastPreview = new Date(today)
const daysUntilSunday = (7 - today.getDay()) % 7
lastPreview.setDate(today.getDate() + daysUntilSunday + 14)

const selectedDate = ref<Date | null>(
  toCalendarDate(props.initialDate ?? '') ?? today
)
watch(
  () => props.initialDate,
  (initialDate) => {
    const requested = toCalendarDate(initialDate ?? '')
    if (requested) selectedDate.value = requested
  }
)
const week = ref<HistoryWeek>()
const loadingWeek = ref(false)
const saving = ref(false)
const weekError = ref('')
const saveError = ref<{ date: string; message: string }>()

const selectedDay = ref<string>()
const action = ref<DayAction>('minutes')
const minutes = ref('')
const note = ref('')
const status = ref('')
const wholeSchoolDay = ref(false)
const startsAt = ref('')
const endsAt = ref('')
const formHint = ref('')
const manualRequests = new Map<string, string>()
const day = computed(() =>
  week.value?.days.find(
    (entry) => entry.date === selectedDay.value && canSelectDay(entry)
  )
)
const expandedRows = computed(() =>
  day.value ? { [day.value.date]: true } : {}
)
// Wochenenden erscheinen nur, wenn dort Unterricht oder Einträge liegen
const visibleDays = computed(() =>
  (week.value?.days ?? []).filter((entry) => {
    const weekday = new Date(entry.date).getUTCDay()
    if (weekday !== 0 && weekday !== 6) return true
    return (
      canSelectDay(entry) ||
      entry.unexcusedMinutes !== null ||
      Boolean(entry.fullAbsence) ||
      Boolean(entry.excusals?.length) ||
      Boolean(entry.plannedStatuses?.length)
    )
  })
)
const isCurrentWeek = computed(() => {
  const now = toIsoDate(today)
  return Boolean(
    week.value && week.value.startsOn <= now && now <= week.value.endsOn
  )
})
const atPreviewLimit = computed(() =>
  Boolean(week.value && week.value.endsOn >= toIsoDate(lastPreview))
)

watch(
  () => day.value,
  (entry) => {
    if (!entry) return
    if (!availableActions(entry).includes(action.value))
      action.value = defaultAction(entry)
  }
)
const statuses = Object.entries(STATUS_LABELS).map(([value, label]) => ({
  value,
  label
}))

function availableActions(entry: HistoryDay): DayAction[] {
  const actions: DayAction[] = []
  if (entry.canExcuseOpenAbsences) actions.push('excuse')
  if (entry.canCorrectStatus || entry.canPlanStatus) actions.push('status')
  if (entry.canBookMinutes) actions.push('minutes')
  // Auch an einem ganz ausgefallenen Tag lassen sich Minuten wieder entfernen
  if (entry.manualAbsenceMinutes > 0) actions.push('removeMinutes')
  return actions.length ? actions : ['status']
}

function defaultAction(entry: HistoryDay): DayAction {
  return availableActions(entry)[0] ?? 'status'
}

const actionLabels: Record<DayAction, string> = {
  excuse: 'Offene Fehlzeiten entschuldigen',
  status: 'Status für einen Zeitraum eintragen',
  minutes: 'Fehlminuten hinzufügen',
  removeMinutes: 'Hinzugefügte Fehlminuten entfernen'
}

// Obergrenze laut Backend: geplanter Unterricht abzüglich bereits erfasster Fehlminuten
const minutesLimit = computed(() => day.value?.manualAbsenceLimit)

const futureStatuses = new Set(['ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT'])
const planningStatus = computed(() => {
  if (!day.value?.canPlanStatus) return false
  if (!day.value.canCorrectStatus) return true
  if (wholeSchoolDay.value) return false
  return startsAt.value > berlinTime(new Date())
})
// Das Formular prüft selbst (novalidate), damit jeder Fehler hier erklärt wird
const clock = new RegExp(`^${timePattern}$`)
const validTimes = computed(
  () => clock.test(startsAt.value) && clock.test(endsAt.value)
)
const invalidStatusTime = computed(() =>
  Boolean(
    action.value === 'status' &&
    !wholeSchoolDay.value &&
    validTimes.value &&
    startsAt.value >= endsAt.value
  )
)
// Ein Zeitraum darf nicht von der Vergangenheit in die Zukunft reichen
const crossesNow = computed(() => {
  if (action.value !== 'status' || wholeSchoolDay.value) return ''
  if (day.value?.date !== toIsoDate(today)) return ''
  const now = berlinTime(new Date())
  return startsAt.value &&
    endsAt.value &&
    startsAt.value <= now &&
    now < endsAt.value
    ? now
    : ''
})
const submitLabel = computed(() => {
  if (action.value === 'minutes') return 'Fehlminuten hinzufügen'
  if (action.value === 'removeMinutes') return 'Fehlminuten entfernen'
  if (action.value === 'excuse') return 'Entschuldigen'
  if (wholeSchoolDay.value) {
    return planningStatus.value
      ? 'Schultag vormerken'
      : 'Schultag entschuldigen'
  }
  return planningStatus.value ? 'Status vormerken' : 'Status speichern'
})
const submitSeverity = computed(() => {
  if (action.value === 'removeMinutes') return 'danger'
  if (action.value === 'excuse') return 'success'
  return action.value === 'status' && wholeSchoolDay.value
    ? 'success'
    : undefined
})
const statusOptions = computed(() =>
  statuses.filter(
    ({ value }) =>
      (!planningStatus.value || futureStatuses.has(value)) &&
      (value !== 'ABWESEND' ||
        !day.value?.wasPresent ||
        day.value.canMarkAbsent)
  )
)

// Passt der gewählte Status nicht mehr zum Zeitraum, wird er geleert und
// nicht stillschweigend durch einen anderen ersetzt
watch(statusOptions, (options) => {
  if (status.value && !options.some(({ value }) => value === status.value)) {
    const previous = statusLabel(status.value)
    status.value = ''
    formHint.value = `„${previous}“ ist für diesen Zeitraum nicht möglich. Für künftige Zeiten kannst du „Entschuldigt“ oder „Betrieblich entschuldigt“ vormerken.`
  }
})

function statusLabel(value: string) {
  return STATUS_LABELS[value as AttendanceStatus] ?? value
}

function statusHint(value: string) {
  return STATUS_HINTS[value as AttendanceStatus] ?? ''
}

function selectDay(entry: HistoryDay) {
  if (!canSelectDay(entry) || saving.value) return
  if (selectedDay.value === entry.date) {
    selectedDay.value = undefined
    return
  }
  selectedDay.value = entry.date
  action.value = defaultAction(entry)
  status.value = ''
  minutes.value = ''
  note.value = ''
  wholeSchoolDay.value = false
  startsAt.value = ''
  endsAt.value = ''
  formHint.value = ''
  saveError.value = undefined
}

function shiftWeek(days: number) {
  const date = new Date(selectedDate.value ?? today)
  date.setDate(date.getDate() + days)
  selectedDate.value =
    toIsoDate(date) > toIsoDate(lastPreview) ? lastPreview : date
}

function showCurrentWeek() {
  selectedDate.value = new Date(today)
}

function noPlannedLessons(entry: HistoryDay) {
  return (
    entry.plannedMinutes === 0 &&
    !entry.plannedStatuses?.length &&
    !entry.excusals?.length
  )
}

function formatDate(date: string) {
  return displayDate.format(new Date(date))
}

function formatMinutes(minutes: number | null) {
  return minutes === null ? '—' : `${minutes} Min.`
}

function formatTotal(day: HistoryDay) {
  if (day.fullAbsence) return '1 Tag'
  if (day.unexcusedMinutes === null || day.excusedMinutes === null) return '—'
  return formatMinutes(day.unexcusedMinutes + day.excusedMinutes)
}

function levelLabel(day: HistoryDay) {
  return day.absenceLevel === 'FULL'
    ? 'Ganz unentschuldigt'
    : 'Teilweise unentschuldigt'
}

function rowClass(entry: HistoryDay) {
  const tone =
    entry.absenceLevel === 'FULL'
      ? 'history-full'
      : entry.absenceLevel === 'PARTIAL'
        ? 'history-partial'
        : ''
  return {
    [tone]: Boolean(tone),
    'history-editable': canSelectDay(entry),
    'history-selected': entry.date === selectedDay.value
  }
}

// Kalendermarkierungen: Unterrichtstage, Tage mit Fehlzeiten, blockfreie Tage
const calendarDays = ref(new Map<string, CalendarDay>())
const loadedMonths = new Set<string>()

async function loadCalendarMonth(year: number, month: number) {
  const key = `${props.studentId}/${year}-${month}`
  if (loadedMonths.has(key)) return
  loadedMonths.add(key)
  const from = new Date(year, month - 2, 1)
  const to = new Date(year, month + 1, 0)
  try {
    const days = await loadAttendanceCalendar(
      props.studentId,
      toIsoDate(from),
      toIsoDate(to)
    )
    const next = new Map(calendarDays.value)
    for (const entry of days) next.set(entry.date, entry)
    calendarDays.value = next
  } catch {
    // Ohne Markierungen bleibt der Kalender voll bedienbar
    loadedMonths.delete(key)
  }
}

function refreshCalendar() {
  loadedMonths.clear()
  const date = selectedDate.value ?? today
  void loadCalendarMonth(date.getFullYear(), date.getMonth() + 1)
}

function calendarClass(date: { year: number; month: number; day: number }) {
  const entry = calendarDays.value.get(
    toIsoDate(new Date(date.year, date.month, date.day))
  )
  if (!entry) return ''
  if (entry.absent) return 'calendar-day calendar-day--absent'
  if (entry.lessonDay) return 'calendar-day calendar-day--lesson'
  return 'calendar-day calendar-day--free'
}

function calendarTitle(date: { year: number; month: number; day: number }) {
  const tone = calendarClass(date)
  if (tone.includes('absent')) return 'Fehlminuten vorhanden'
  if (tone.includes('lesson')) return 'Unterricht'
  if (tone.includes('free')) return 'Kein Unterrichtsblock'
  return undefined
}

watch(
  [() => props.studentId, selectedDate, () => props.revision],
  async ([id, date], previous, onCleanup) => {
    let current = true
    onCleanup(() => (current = false))
    week.value = undefined
    selectedDay.value = undefined
    saveError.value = undefined
    weekError.value = ''
    loadingWeek.value = true
    if (date === null) {
      loadingWeek.value = false
      return
    }
    if (previous?.[0] !== id || previous?.[2] !== props.revision)
      loadedMonths.clear()
    void loadCalendarMonth(date.getFullYear(), date.getMonth() + 1)
    try {
      const result = await loadAttendanceWeek(id, toIsoDate(date))
      if (current) week.value = result
    } catch {
      if (current) weekError.value = 'Die Woche konnte nicht geladen werden.'
    } finally {
      if (current) loadingWeek.value = false
    }
  },
  { immediate: true }
)

// Bekannte Fehlercodes erklärt das gemeinsame Wörterbuch, sonst der HTTP-Status
function failureText(
  code: string | undefined,
  status: number,
  planning: boolean
) {
  const known = errorMessage(code)
  if (known !== errorMessage(undefined)) return known
  if (status === 409)
    return 'Der Tag wurde inzwischen geändert oder ist gesperrt. Lade die Seite neu und versuch es erneut.'
  if (planning && status === 400)
    return 'Wähle einen künftigen Zeitraum mit Unterricht.'
  if (status === 403) return 'Dafür fehlt dir die Berechtigung.'
  return 'Die Änderung konnte nicht gespeichert werden. Prüfe Zeitraum und Status.'
}

async function submitDay() {
  const entry = day.value
  const date = selectedDate.value
  if (!entry || date === null || saving.value) return
  const id = props.studentId
  const planning = action.value === 'status' && planningStatus.value
  formHint.value = ''
  let send: () => Promise<Response>
  let requestKey: string | undefined
  let success = 'Änderung gespeichert.'
  if (action.value === 'minutes') {
    if (!entry.canBookMinutes) return
    const count = Number(minutes.value)
    const limit = minutesLimit.value
    if (!Number.isInteger(count) || count < 1) {
      formHint.value = 'Gib die Fehlminuten als ganze Zahl ab 1 ein.'
      return
    }
    const reason = note.value.trim()
    if (!reason) {
      formHint.value =
        'Trag einen kurzen Vermerk ein, warum du die Fehlminuten ergänzt – zum Beispiel „nach der 4. Stunde gegangen“.'
      return
    }
    if (limit !== undefined && count > limit) {
      formHint.value =
        limit > 0
          ? `Höchstens ${limit} Min. möglich – mehr Unterricht ist an diesem Tag nach den bereits erfassten Fehlminuten nicht offen.`
          : 'Für diesen Tag sind bereits alle Unterrichtsminuten als Fehlminuten erfasst.'
      return
    }
    const amount = count === 1 ? '1 Fehlminute' : `${count} Fehlminuten`
    if (
      !(await confirmAction({
        title: `${amount} hinzufügen?`,
        message: `Am ${formatDate(entry.date)} ${count === 1 ? 'wird 1 unentschuldigte Fehlminute' : `werden ${count} unentschuldigte Fehlminuten`} zusätzlich eingetragen.\nVermerk: ${reason}\nDu kannst sie später wieder entfernen.`,
        confirmLabel: 'Hinzufügen'
      }))
    )
      return
    const key = `${id}/${entry.date}/${count}/${reason}`
    const requestId = manualRequests.get(key) ?? crypto.randomUUID()
    manualRequests.set(key, requestId)
    requestKey = key
    success = `${amount} am ${formatDate(entry.date)} hinzugefügt.`
    send = () => addManualAbsence(id, entry.date, count, requestId, reason)
  } else if (action.value === 'removeMinutes') {
    if (!entry.manualAbsenceMinutes) return
    if (
      !(await confirmAction({
        title: 'Hinzugefügte Fehlminuten entfernen?',
        message: `Die ${entry.manualAbsenceMinutes} von Hand eingetragenen Fehlminuten am ${formatDate(entry.date)} werden entfernt. Automatisch erfasste Fehlzeiten bleiben bestehen.`,
        confirmLabel: 'Entfernen',
        danger: true
      }))
    )
      return
    success = `Hinzugefügte Fehlminuten am ${formatDate(entry.date)} entfernt.`
    send = () => removeManualAbsence(id, entry.date)
  } else if (action.value === 'excuse') {
    if (!entry.canExcuseOpenAbsences) return
    success = `Offene Fehlzeiten am ${formatDate(entry.date)} entschuldigt.`
    send = () => excuseOpenAbsences(id, entry.date)
  } else if (wholeSchoolDay.value) {
    if (
      !(await confirmAction({
        title: planning
          ? 'Ganzen Schultag vormerken?'
          : 'Ganzen Schultag entschuldigen?',
        message: `Der ganze Schultag am ${formatDate(entry.date)} wird ${planning ? 'als entschuldigt vorgemerkt' : 'entschuldigt'}.`,
        confirmLabel: planning ? 'Vormerken' : 'Entschuldigen'
      }))
    )
      return
    success = planning
      ? `Schultag ${formatDate(entry.date)} vorgemerkt.`
      : `Schultag ${formatDate(entry.date)} entschuldigt.`
    send = () => excuseSchoolDay(id, entry.date)
  } else {
    if (!status.value) {
      formHint.value = 'Wähle einen Status.'
      return
    }
    if (!validTimes.value) {
      formHint.value = 'Trag Von und Bis als Uhrzeit im Format HH:MM ein.'
      return
    }
    if (invalidStatusTime.value) return
    if (crossesNow.value) return
    const chosen = status.value
    success = planning
      ? `${statusLabel(chosen)} für ${startsAt.value}–${endsAt.value} Uhr vorgemerkt.`
      : `${statusLabel(chosen)} für ${startsAt.value}–${endsAt.value} Uhr gespeichert.`
    send = () =>
      correctStatusPeriod(id, entry.date, chosen, startsAt.value, endsAt.value)
  }

  saving.value = true
  saveError.value = undefined
  const fail = (message: string) => {
    saveError.value = { date: entry.date, message }
  }
  try {
    const response = await send()
    const problem: { code?: string; changed?: boolean } = await response
      .clone()
      .json()
      .catch(() => ({}))
    if (!response.ok) {
      fail(failureText(problem.code, response.status, planning))
      // Nach einem Konflikt zeigt die neu geladene Woche den aktuellen Stand
      if (response.status !== 409) return
    } else {
      if (requestKey) manualRequests.delete(requestKey)
      minutes.value = ''
      note.value = ''
      showNotice(
        problem.changed === false
          ? 'Keine Änderung nötig – der Tag war bereits so eingetragen.'
          : success
      )
      refreshCalendar()
      if (!planning) emit('corrected')
    }
    try {
      week.value = await loadAttendanceWeek(id, toIsoDate(date))
    } catch {
      fail('Die Woche konnte nicht aktualisiert werden.')
    }
  } catch {
    fail('Die Änderung konnte nicht bestätigt werden. Prüfe die Verbindung.')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="attendance-history">
    <h2>Anwesenheitshistorie</h2>
    <div class="history-toolbar">
      <div class="week-filter">
        <label for="history-date">Woche</label>
        <div class="week-navigation">
          <Button
            icon="pi pi-chevron-left"
            aria-label="Vorherige Woche"
            severity="secondary"
            :disabled="saving"
            @click="shiftWeek(-7)"
          />
          <DatePicker
            v-model="selectedDate"
            input-id="history-date"
            date-format="dd.mm.yy"
            :max-date="lastPreview"
            :manual-input="false"
            :disabled="saving"
            show-icon
            show-week
            @month-change="loadCalendarMonth($event.year, $event.month)"
          >
            <template #date="{ date }">
              <span :class="calendarClass(date)" :title="calendarTitle(date)">
                {{ date.day }}
              </span>
            </template>
            <template #footer>
              <ul class="calendar-legend" aria-label="Legende">
                <li>
                  <span class="calendar-day calendar-day--lesson" />Unterricht
                </li>
                <li>
                  <span class="calendar-day calendar-day--absent" />Fehlminuten
                </li>
                <li>
                  <span class="calendar-day calendar-day--free" />Kein
                  Unterrichtsblock
                </li>
              </ul>
            </template>
          </DatePicker>
          <Button
            icon="pi pi-chevron-right"
            aria-label="Nächste Woche"
            severity="secondary"
            :disabled="saving || !week || atPreviewLimit"
            @click="shiftWeek(7)"
          />
          <Button
            v-if="!isCurrentWeek"
            label="Diese Woche"
            text
            :disabled="saving"
            @click="showCurrentWeek"
          />
        </div>
        <p v-if="atPreviewLimit" class="week-hint">
          Vormerkungen sind höchstens zwei Wochen im Voraus möglich.
        </p>
      </div>
      <p class="history-help">
        <i class="pi pi-info-circle" aria-hidden="true" />
        Über „Korrigieren“ entschuldigst du Fehlzeiten, trägst einen Status für
        einen Zeitraum ein oder ergänzt Fehlminuten.
      </p>
    </div>

    <PageError
      v-if="saveError"
      :message="`${formatDate(saveError.date)}: ${saveError.message}`"
    />
    <PageError v-if="weekError" :message="weekError" />
    <p v-else-if="loadingWeek" role="status">Tagesdaten werden geladen …</p>
    <template v-if="week">
      <h3 id="history-week">
        {{ formatCalendarWeek(week.startsOn) }} ·
        {{ formatDate(week.startsOn) }} bis {{ formatDate(week.endsOn) }}
      </h3>
      <div class="history-table">
        <DataTable
          :value="visibleDays"
          data-key="date"
          :row-class="rowClass"
          :expanded-rows="expandedRows"
          :table-props="{ 'aria-labelledby': 'history-week' }"
        >
          <Column header="Tag">
            <template #body="{ data }">
              <time :datetime="data.date" class="day-name">{{
                formatDate(data.date)
              }}</time>
              <Tag
                v-if="data.absenceLevel"
                class="day-status"
                :severity="data.absenceLevel === 'FULL' ? 'danger' : 'warn'"
                :value="levelLabel(data)"
              />
              <Tag
                v-if="data.interim"
                class="day-status"
                severity="info"
                value="Läuft noch · Zwischenstand"
              />
              <small
                v-for="planned in data.plannedStatuses"
                :key="planned.startsAt"
                class="planned-status"
              >
                Vorgemerkt {{ formatTime(planned.startsAt) }}–{{
                  formatTime(planned.endsAt)
                }}
                Uhr ·
                {{ statusLabel(planned.status) }}
              </small>
              <small
                v-for="(excusal, index) in data.excusals"
                :key="index"
                class="planned-status"
              >
                {{ statusLabel(excusal.status) }} ·
                {{ formatTime(excusal.startsAt) }}–{{
                  formatTime(excusal.endsAt)
                }}
                Uhr
                <span v-if="excusal.reason" class="excusal-reason">{{
                  excusal.reason
                }}</span>
              </small>
              <small v-if="noPlannedLessons(data)" class="planned-status">
                Kein Unterricht geplant
              </small>
            </template>
          </Column>
          <Column header="Fehlzeit gesamt">
            <template #body="{ data }">{{ formatTotal(data) }}</template>
          </Column>
          <Column header="Entschuldigt">
            <template #body="{ data }">
              <span>{{
                data.fullAbsence
                  ? data.fullAbsence === 'ENTSCHULDIGT'
                    ? '1 Tag'
                    : '—'
                  : formatMinutes(data.excusedMinutes)
              }}</span>
            </template>
          </Column>
          <Column header="Unentschuldigt">
            <template #body="{ data }">
              <span>{{
                data.fullAbsence
                  ? data.fullAbsence === 'ABWESEND'
                    ? '1 Tag'
                    : '—'
                  : formatMinutes(data.unexcusedMinutes)
              }}</span>
              <small
                v-if="data.fullAbsence === 'ABWESEND' && data.excusedMinutes"
                class="manual-minutes"
              >
                ganzer Tag, weil
                {{ formatMinutes(data.unexcusedMinutes) }} offen sind ({{
                  formatMinutes(data.excusedMinutes)
                }}
                entschuldigt)
              </small>
              <small v-if="data.manualAbsenceMinutes" class="manual-minutes">
                davon von Hand hinzugefügt:
                {{ formatMinutes(data.manualAbsenceMinutes) }}
              </small>
              <small
                v-for="(entry, index) in data.manualAbsenceEntries ?? []"
                :key="index"
                class="manual-minutes manual-note"
              >
                {{ formatMinutes(entry.minutes) }} ·
                {{ entry.note || 'ohne Vermerk' }}
              </small>
            </template>
          </Column>
          <Column header="Zu spät gekommen">
            <template #body="{ data }">
              {{
                data.didNotArrive
                  ? 'Nicht erschienen'
                  : formatMinutes(data.lateMinutes)
              }}
            </template>
          </Column>
          <Column header="Korrektur" class="history-action-column">
            <template #body="{ data }">
              <button
                v-if="canSelectDay(data)"
                type="button"
                class="day-select"
                :aria-expanded="selectedDay === data.date"
                :aria-label="`${formatDate(data.date)} korrigieren`"
                :disabled="saving"
                @click="selectDay(data)"
              >
                <i
                  class="pi"
                  :class="selectedDay === data.date ? 'pi-times' : 'pi-pencil'"
                  aria-hidden="true"
                />
                {{ selectedDay === data.date ? 'Schließen' : 'Korrigieren' }}
              </button>
            </template>
          </Column>
          <template #expansion="{ data }">
            <form
              v-if="day && day.date === data.date"
              class="history-actions"
              novalidate
              @submit.prevent="submitDay"
            >
              <fieldset :disabled="saving">
                <legend>{{ formatDate(day.date) }} korrigieren</legend>
                <label>
                  Was möchtest du tun?
                  <select v-model="action">
                    <option
                      v-for="item in availableActions(day)"
                      :key="item"
                      :value="item"
                    >
                      {{ actionLabels[item] }}
                    </option>
                  </select>
                </label>
                <label v-if="action === 'minutes'">
                  Fehlminuten
                  <InputText
                    v-model="minutes"
                    type="number"
                    min="1"
                    :max="minutesLimit"
                    step="1"
                    inputmode="numeric"
                  />
                </label>
                <label v-if="action === 'minutes'" class="minutes-note">
                  Vermerk
                  <InputText
                    v-model="note"
                    maxlength="200"
                    placeholder="z. B. nach der 4. Stunde gegangen"
                  />
                </label>
                <label v-if="action === 'status'" class="whole-school-day">
                  <Checkbox
                    v-model="wholeSchoolDay"
                    binary
                    input-id="whole-school-day"
                  />
                  <span>Ganzen Schultag entschuldigen</span>
                </label>
                <template v-if="action === 'status' && !wholeSchoolDay">
                  <label>
                    Status
                    <select v-model="status">
                      <option value="" disabled>Status wählen</option>
                      <option
                        v-for="item in statusOptions"
                        :key="item.value"
                        :value="item.value"
                      >
                        {{ item.label }}
                      </option>
                    </select>
                  </label>
                  <label>
                    <span>Von</span>
                    <GermanTimeInput v-model="startsAt" label="Von" required />
                  </label>
                  <label>
                    <span>Bis</span>
                    <GermanTimeInput v-model="endsAt" label="Bis" required />
                  </label>
                </template>
                <Button
                  type="submit"
                  :label="submitLabel"
                  :severity="submitSeverity"
                  :loading="saving"
                />
                <Button
                  type="button"
                  label="Abbrechen"
                  text
                  @click="selectedDay = undefined"
                />
              </fieldset>
              <p v-if="action === 'minutes'" class="form-note">
                Für Fehlzeiten, die das Terminal nicht erfasst hat – etwa wenn
                jemand früher gegangen ist.
                <template v-if="minutesLimit !== undefined">
                  Höchstens {{ minutesLimit }} Min. – so viel Unterricht ist an
                  diesem Tag nach den bereits erfassten Fehlminuten noch offen.
                </template>
              </p>
              <p v-else-if="action === 'removeMinutes'" class="form-note">
                Entfernt die {{ day.manualAbsenceMinutes }} von Hand
                hinzugefügten Fehlminuten dieses Tages.
              </p>
              <p v-else-if="action === 'excuse'" class="form-note">
                Entschuldigt alle noch unentschuldigten Fehlminuten dieses
                Tages.
              </p>
              <p
                v-else-if="action === 'status' && !wholeSchoolDay && status"
                class="form-note"
              >
                {{ statusLabel(status) }}: {{ statusHint(status) }}
                <template v-if="planningStatus">
                  Der Zeitraum liegt in der Zukunft und wird vorgemerkt.
                </template>
              </p>
              <p v-if="invalidStatusTime" class="form-alert" role="alert">
                Die Endzeit muss nach der Startzeit liegen.
              </p>
              <p v-else-if="crossesNow" class="form-alert" role="alert">
                Der Zeitraum reicht über die aktuelle Uhrzeit ({{
                  crossesNow
                }}
                Uhr) hinaus. Trage zuerst den Teil bis {{ crossesNow }} Uhr ein
                und merke den Rest danach als eigenen Zeitraum vor.
              </p>
              <p v-else-if="formHint" class="form-alert" role="alert">
                {{ formHint }}
              </p>
            </form>
          </template>
        </DataTable>
      </div>
    </template>
  </section>
</template>

<style scoped>
.history-toolbar,
.history-actions fieldset {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 0.5rem 1rem;
}

.attendance-history {
  min-width: 0;
}

.attendance-history h2 {
  margin: 0 0 12px;
  font-size: 1.35rem;
}

.history-toolbar {
  align-items: start;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.history-actions {
  min-width: 0;
  padding: 4px 0 6px;
  white-space: normal;
}

.history-help {
  flex: 1 1 18rem;
  margin: 0;
  align-self: center;
  color: var(--muted);
  font-size: 0.92rem;
  line-height: 1.4;
}

.week-hint,
.form-note {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 0.88rem;
  font-weight: 400;
  white-space: normal;
}

.form-alert {
  margin: 8px 0 0;
  padding: 8px 10px;
  border: 1px solid var(--danger-line);
  border-radius: 8px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 0.9rem;
  white-space: normal;
}

.history-actions .minutes-note {
  flex: 1 1 16rem;
}

.history-actions .minutes-note :deep(input) {
  width: 100%;
  max-width: 26rem;
}

.manual-note {
  max-width: 20rem;
  white-space: normal;
  overflow-wrap: anywhere;
  font-style: italic;
}

.history-actions select {
  min-height: 44px;
  max-width: 100%;
}

.history-actions fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}

.history-actions legend {
  margin-bottom: 6px;
  font-weight: 650;
}

.history-actions label {
  display: grid;
  gap: 0.25rem;
}

.history-actions .whole-school-day {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 44px;
}

.history-actions :deep(input:not([type='checkbox'])) {
  font: inherit;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 0.5rem 0.65rem;
  min-height: 44px;
  max-width: 8rem;
}

.day-select {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0.35rem 0.7rem;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  color: var(--accent-ink);
  font: inherit;
  font-size: 0.88rem;
  font-weight: 600;
  cursor: pointer;
}

.day-select:hover {
  background: var(--accent-soft);
}

.day-name {
  font-weight: 600;
}

.calendar-day {
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  border-radius: 50%;
}

.calendar-day--lesson {
  background: #dbe9fb;
  color: #17406e;
}

.calendar-day--absent {
  background: #ffd9a8;
  color: #6b3a00;
  font-weight: 700;
}

.calendar-day--free {
  color: #9a93a6;
}

.calendar-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin: 0;
  padding: 8px 4px 0;
  list-style: none;
  font-size: 0.8rem;
  color: var(--muted);
}

.calendar-legend li {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.calendar-legend .calendar-day {
  width: 0.9rem;
  height: 0.9rem;
  border: 1px solid var(--line);
}

.manual-minutes,
.planned-status {
  display: block;
}

.history-table :deep(.p-datatable-row-expansion > td) {
  background: #faf8fd;
  white-space: normal;
}

.history-table :deep(.history-selected > td) {
  background: var(--accent-soft);
}

.week-navigation {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.week-filter {
  display: grid;
  gap: 0.375rem;
  font-weight: 600;
}

.week-navigation :deep(.p-datepicker-input) {
  max-width: 8rem;
}

.history-table {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius);
}

.history-table :deep(td) {
  white-space: nowrap;
}

.history-table :deep(td:first-child) {
  min-width: 10rem;
  max-width: 15rem;
  white-space: normal;
}

.history-table :deep(.history-full) {
  --history-row-background: #fff0ef;
}

.history-table :deep(.history-partial) {
  --history-row-background: #fff5e7;
}

.history-table :deep(.history-full > td),
.history-table :deep(.history-partial > td) {
  background: var(--history-row-background);
}

.history-table :deep(.history-editable:hover > td),
.history-table :deep(.history-editable:focus-within > td) {
  background: color-mix(
    in srgb,
    var(--accent) 11%,
    var(--history-row-background, white)
  );
}

.day-status {
  display: block;
  width: fit-content;
  margin-top: 0.25rem;
}

h3 {
  margin-top: 1rem;
  font-size: 1.125rem;
}
.excusal-reason {
  display: block;
  overflow-wrap: anywhere;
}
</style>
