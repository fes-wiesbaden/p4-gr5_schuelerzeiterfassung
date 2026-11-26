<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import DatePicker from 'primevue/datepicker'
import InputText from 'primevue/inputtext'
import Tag from 'primevue/tag'
import {
  addManualAbsence,
  correctStatusPeriod,
  excuseOpenAbsences,
  excuseSchoolDay,
  loadAttendanceWeek,
  type HistoryDay,
  type HistoryWeek
} from '@/api/attendanceHistory'

const props = defineProps<{ studentId: number }>()
const emit = defineEmits<{ corrected: [] }>()

const berlinDate = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Europe/Berlin',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
})
const displayDate = new Intl.DateTimeFormat('de-DE', {
  timeZone: 'UTC',
  weekday: 'short',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric'
})
const berlinClock = new Intl.DateTimeFormat('de-DE', {
  timeZone: 'Europe/Berlin',
  hourCycle: 'h23',
  hour: '2-digit',
  minute: '2-digit'
})

function canSelectDay(entry: HistoryDay) {
  return entry.editable || entry.canCorrectStatus || entry.canPlanStatus
}

const today = new Date(`${berlinDate.format(new Date())}T12:00:00`)
const lastPreview = new Date(today)
const daysUntilSunday = (7 - today.getDay()) % 7
lastPreview.setDate(today.getDate() + daysUntilSunday + 14)

const selectedDate = ref<Date | null>(today)
const week = ref<HistoryWeek>()
const loadingWeek = ref(false)
const saving = ref(false)
const weekError = ref('')
const saveError = ref<{ date: string; message: string }>()

const selectedDay = ref<string>()
const action = ref<'minutes' | 'excuse' | 'status'>('minutes')
const minutes = ref('')
const status = ref('BETRIEBLICH_ENTSCHULDIGT')
const wholeSchoolDay = ref(false)
const startsAt = ref('')
const endsAt = ref('')
const manualRequests = new Map<string, string>()
const day = computed(() =>
  week.value?.days.find(
    (entry) => entry.date === selectedDay.value && canSelectDay(entry)
  )
)
watch(
  () => day.value?.canExcuseOpenAbsences,
  (canExcuse) => {
    if (!canExcuse && action.value === 'excuse') action.value = 'minutes'
  }
)
const statuses = [
  { value: 'ANWESEND', label: 'Anwesend' },
  { value: 'ABWESEND', label: 'Abwesend' },
  { value: 'ENTSCHULDIGT', label: 'Entschuldigt' },
  { value: 'MIT_ATTEST_ENTSCHULDIGT', label: 'Mit Attest entschuldigt' },
  { value: 'BETRIEBLICH_ENTSCHULDIGT', label: 'Betrieblich entschuldigt' },
  { value: 'BETRIEB', label: 'Betrieb' }
]

const futureStatuses = new Set(['ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT'])
const planningStatus = computed(() => {
  if (!day.value?.canPlanStatus) return false
  if (!day.value.canCorrectStatus) return true
  if (wholeSchoolDay.value) return false
  return startsAt.value > berlinClock.format(new Date())
})
const invalidStatusTime = computed(
  () =>
    action.value === 'status' &&
    !wholeSchoolDay.value &&
    startsAt.value &&
    endsAt.value &&
    startsAt.value >= endsAt.value
)
const submitLabel = computed(() => {
  if (action.value === 'minutes') return 'Hinzufügen'
  if (action.value === 'excuse') return 'Entschuldigen'
  if (wholeSchoolDay.value) {
    return planningStatus.value
      ? 'Schultag vormerken'
      : 'Schultag entschuldigen'
  }
  return planningStatus.value ? 'Status vormerken' : 'Status speichern'
})
const statusOptions = computed(() =>
  planningStatus.value
    ? statuses.filter((item) => futureStatuses.has(item.value))
    : statuses
)

watch(planningStatus, (planning) => {
  if (planning && !futureStatuses.has(status.value)) {
    status.value = 'BETRIEBLICH_ENTSCHULDIGT'
  }
})

function statusLabel(value: string) {
  return statuses.find((item) => item.value === value)?.label ?? value
}

function formatPlannedTime(time: string) {
  return time.replace(/^(\d{2}:\d{2}):00$/, '$1')
}

function selectDay(entry: HistoryDay) {
  if (!canSelectDay(entry) || saving.value || selectedDay.value === entry.date)
    return
  selectedDay.value = entry.date
  action.value = entry.editable ? 'minutes' : 'status'
  status.value = 'BETRIEBLICH_ENTSCHULDIGT'
  minutes.value = ''
  wholeSchoolDay.value = false
  startsAt.value = ''
  endsAt.value = ''
  saveError.value = undefined
}

function dateString(date: Date) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function shiftWeek(days: number) {
  const date = new Date(selectedDate.value ?? today)
  date.setDate(date.getDate() + days)
  selectedDate.value =
    dateString(date) > dateString(lastPreview) ? lastPreview : date
}

function noPlannedLessons(entry: HistoryDay) {
  return (
    entry.date > dateString(today) &&
    !entry.canPlanStatus &&
    !entry.plannedStatuses?.length
  )
}

function formatDate(date: string) {
  return displayDate.format(new Date(date))
}

function formatMinutes(minutes: number | null) {
  return minutes === null ? '—' : `${minutes} min`
}

function formatTotal(day: HistoryDay) {
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

watch(
  [() => props.studentId, selectedDate],
  async ([id, date], _, onCleanup) => {
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
    try {
      const result = await loadAttendanceWeek(id, dateString(date))
      if (current) week.value = result
    } catch {
      if (current) weekError.value = 'Die Woche konnte nicht geladen werden.'
    } finally {
      if (current) loadingWeek.value = false
    }
  },
  { immediate: true }
)

async function submitDay() {
  const entry = day.value
  const date = selectedDate.value
  if (!entry || date === null || saving.value) return
  const id = props.studentId
  const planning = action.value === 'status' && planningStatus.value
  let send: () => Promise<Response>
  let requestKey: string | undefined
  if (action.value === 'minutes') {
    const count = Number(minutes.value)
    if (!Number.isInteger(count) || count < 1 || count > 2147483647) return
    if (
      !window.confirm(
        `${count} unentschuldigte Fehlminuten am ${formatDate(entry.date)} hinzufügen?`
      )
    )
      return
    const key = `${id}/${entry.date}/${count}`
    const requestId = manualRequests.get(key) ?? crypto.randomUUID()
    manualRequests.set(key, requestId)
    requestKey = key
    send = () => addManualAbsence(id, entry.date, count, requestId)
  } else if (action.value === 'excuse') {
    if (!entry.canExcuseOpenAbsences) return
    send = () => excuseOpenAbsences(id, entry.date)
  } else if (wholeSchoolDay.value) {
    send = () => excuseSchoolDay(id, entry.date)
  } else {
    if (!startsAt.value || !endsAt.value || startsAt.value >= endsAt.value)
      return
    send = () =>
      correctStatusPeriod(
        id,
        entry.date,
        status.value,
        startsAt.value,
        endsAt.value
      )
  }

  saving.value = true
  saveError.value = undefined
  const fail = (message: string) => {
    saveError.value = { date: entry.date, message }
  }
  try {
    const response = await send()
    if (response.status === 409) {
      const problem = await response.json().catch(() => ({}))
      fail(
        problem.code === 'OVERLAPPING_PLANNED_ATTENDANCE_STATUS'
          ? 'Für einen Teil dieses Zeitraums ist bereits ein Status vorgemerkt.'
          : 'Der Tag wurde geändert oder ist gesperrt.'
      )
    } else if (!response.ok) {
      fail(
        planning && response.status === 400
          ? 'Wähle einen künftigen Zeitraum mit Unterricht.'
          : 'Die Änderung konnte nicht gespeichert werden. Prüfe deine Eingaben.'
      )
      return
    } else {
      if (requestKey) {
        manualRequests.delete(requestKey)
        minutes.value = ''
      }
      if (!planning) emit('corrected')
    }
    try {
      week.value = await loadAttendanceWeek(id, dateString(date))
    } catch {
      fail('Die Woche konnte nicht aktualisiert werden.')
    }
  } catch {
    fail('Die Änderung konnte nicht bestätigt werden.')
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
          />
          <Button
            icon="pi pi-chevron-right"
            aria-label="Nächste Woche"
            severity="secondary"
            :disabled="
              saving || !week || week.endsOn >= dateString(lastPreview)
            "
            @click="shiftWeek(7)"
          />
        </div>
      </div>

      <form v-if="day" class="history-actions" @submit.prevent="submitDay">
        <fieldset :disabled="saving">
          <legend>{{ formatDate(day.date) }}</legend>
          <label>
            Aktion
            <select v-model="action">
              <option v-if="day.editable" value="minutes">
                Fehlminuten hinzufügen
              </option>
              <option v-if="day.canExcuseOpenAbsences" value="excuse">
                Offene Fehlzeiten entschuldigen
              </option>
              <option value="status">Status für Zeitraum ändern</option>
            </select>
          </label>
          <label v-if="action === 'minutes'">
            Fehlminuten
            <InputText
              v-model="minutes"
              type="number"
              min="1"
              max="2147483647"
              step="1"
              required
            />
          </label>
          <label v-if="action === 'status'" class="whole-school-day">
            <Checkbox v-model="wholeSchoolDay" binary />
            <span>Ganzen Schultag entschuldigen</span>
          </label>
          <template v-if="action === 'status' && !wholeSchoolDay">
            <label>
              Status
              <select v-model="status">
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
              <InputText v-model="startsAt" type="time" required />
            </label>
            <label>
              <span>Bis</span>
              <InputText v-model="endsAt" type="time" required />
            </label>
          </template>
          <Button
            type="submit"
            :label="submitLabel"
            :severity="
              action === 'excuse' || (action === 'status' && wholeSchoolDay)
                ? 'success'
                : undefined
            "
            :disabled="
              saving || (action === 'excuse' && !day.canExcuseOpenAbsences)
            "
          />
          <p v-if="invalidStatusTime" role="alert">
            Die Endzeit muss nach der Startzeit liegen.
          </p>
        </fieldset>
      </form>
    </div>

    <p v-if="weekError" role="alert">{{ weekError }}</p>
    <p v-else-if="loadingWeek" role="status">Tagesdaten werden geladen …</p>
    <template v-if="week">
      <h3 id="history-week">
        {{ formatDate(week.startsOn) }} bis {{ formatDate(week.endsOn) }}
      </h3>
      <div class="history-table">
        <DataTable
          :value="week.days"
          data-key="date"
          :row-class="rowClass"
          :table-props="{ 'aria-labelledby': 'history-week' }"
          @row-click="selectDay($event.data)"
        >
          <Column header="Tag">
            <template #body="{ data }">
              <button
                v-if="canSelectDay(data)"
                type="button"
                class="day-select"
                :aria-pressed="selectedDay === data.date"
                :disabled="saving"
                @click.stop="selectDay(data)"
              >
                <time :datetime="data.date">{{ formatDate(data.date) }}</time>
              </button>
              <time v-else :datetime="data.date">{{
                formatDate(data.date)
              }}</time>
              <Tag
                v-if="data.absenceLevel"
                class="day-status"
                :severity="data.absenceLevel === 'FULL' ? 'danger' : 'warn'"
                :value="levelLabel(data)"
              />
              <small
                v-for="planned in data.plannedStatuses"
                :key="planned.startsAt"
                class="planned-status"
              >
                Geplant {{ formatPlannedTime(planned.startsAt) }}–{{
                  formatPlannedTime(planned.endsAt)
                }}
                ·
                {{ statusLabel(planned.status) }}
              </small>
              <small v-if="noPlannedLessons(data)" class="planned-status">
                Kein Unterricht geplant
              </small>
              <p v-if="saveError?.date === data.date" role="alert">
                {{ saveError.message }}
              </p>
            </template>
          </Column>
          <Column header="Gesamt">
            <template #body="{ data }">{{ formatTotal(data) }}</template>
          </Column>
          <Column header="Entschuldigt">
            <template #body="{ data }">
              <span>{{ formatMinutes(data.excusedMinutes) }}</span>
            </template>
          </Column>
          <Column header="Unentschuldigt">
            <template #body="{ data }">
              <span>{{ formatMinutes(data.unexcusedMinutes) }}</span>
              <small v-if="data.manualAbsenceMinutes" class="manual-minutes">
                Davon manuell: {{ formatMinutes(data.manualAbsenceMinutes) }}
              </small>
            </template>
          </Column>
          <Column header="Verspätung">
            <template #body="{ data }">
              {{
                data.didNotArrive
                  ? 'Nicht erschienen'
                  : formatMinutes(data.lateMinutes)
              }}
            </template>
          </Column>
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

.history-toolbar {
  align-items: start;
}

.history-actions {
  flex: 1 1 29rem;
  min-width: 0;
}

.history-actions fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
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
  font: inherit;
  color: var(--accent);
  text-decoration: underline;
  background: transparent;
  cursor: pointer;
  border: 0;
  padding: 0;
}

.manual-minutes,
.planned-status {
  display: block;
}

.history-table :deep(.history-selected > td:first-child) {
  box-shadow: inset 3px 0 var(--accent);
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
}

.history-table :deep(td) {
  white-space: nowrap;
}

.history-table p[role='alert'] {
  margin-block: 0.375rem 0;
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

.history-table :deep(.history-editable) {
  cursor: pointer;
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
</style>
