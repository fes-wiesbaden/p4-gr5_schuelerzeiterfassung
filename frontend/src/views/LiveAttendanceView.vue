<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'

import {
  STATUS_HINTS,
  STATUS_LABELS,
  berlinDate,
  berlinTime,
  berlinInstant,
  loadClasses,
  todayInBerlin,
  type SchoolClass,
  type CurrentClass,
  type DailyStudent,
  type AttendanceStatus
} from '@/api/attendance'
import { loadData, postJson } from '@/api/client'
import GermanDateInput from '@/components/GermanDateInput.vue'
import GermanTimeInput from '@/components/GermanTimeInput.vue'
import { formatDate, timePattern, toCalendarDate, toIsoDate } from '@/dateTime'
import {
  expectOk,
  formatTime,
  loadClassWeek,
  type ClassWeekDay
} from '@/api/planning'
import { showNotice } from '@/notice'
import AppDataTable, {
  type DataTableColumn
} from '@/components/AppDataTable.vue'
import StatusBadge from '@/components/StatusBadge.vue'

type AttendanceRow = DailyStudent & { name: string }
type Draft = {
  status: AttendanceStatus
  time: string
  touched: boolean
  saving: boolean
}

const route = useRoute()
const today = ref(todayInBerlin())
const selectedDate = ref(today.value)
const displayedDate = ref('')
const selectedClass = ref<number>()
const classes = ref<SchoolClass[]>([])
const rows = ref<AttendanceRow[] | null>(null)
const classLoading = ref(true)
const classError = ref(false)
const dayLoading = ref(false)
const dayError = ref(false)
const lastUpdated = ref('')
const correctionError = ref('')
const drafts = reactive<Record<number, Draft>>({})
const validDate = computed(() =>
  Boolean(selectedDate.value && selectedDate.value <= today.value)
)
const clockTime = new RegExp(`^${timePattern}$`)
let clockOffset = 0

const columns: DataTableColumn<AttendanceRow>[] = [
  { field: 'name', header: 'Name' },
  { field: 'status', header: 'Status' },
  { field: 'unexcusedMinutes', header: 'Unentschuldigte Fehlzeit' },
  { field: 'studentId', header: 'Status ändern (gilt ab Uhrzeit)' }
]

const excusedStatuses: AttendanceStatus[] = [
  'ENTSCHULDIGT',
  'MIT_ATTEST_ENTSCHULDIGT',
  'BETRIEBLICH_ENTSCHULDIGT'
]

// Jede Person zählt in genau einer Gruppe, die Summe ergibt die Klassenstärke
const overview = computed(() => {
  const entries = rows.value ?? []
  const count = (...statuses: AttendanceStatus[]) =>
    entries.filter(({ status }) => statuses.includes(status)).length
  return {
    total: entries.length,
    present: count('ANWESEND'),
    absent: count('ABWESEND'),
    excused: count(...excusedStatuses),
    company: count('BETRIEB'),
    late: entries.filter(
      (entry) => entry.status === 'ANWESEND' && partialMinutes(entry) > 0
    ).length
  }
})

// Gleichnamige Personen werden über das Geburtsdatum unterschieden
const duplicateNames = computed(() => {
  const seen = new Map<string, number>()
  for (const row of rows.value ?? [])
    seen.set(row.name, (seen.get(row.name) ?? 0) + 1)
  return new Set(
    [...seen].filter(([, count]) => count > 1).map(([name]) => name)
  )
})

const isToday = computed(() => selectedDate.value === today.value)
const selectedClassCode = computed(
  () => classes.value.find(({ id }) => id === selectedClass.value)?.classCode
)

// Unterricht des gewählten Tages laut Stundenplan
const lessons = ref<ClassWeekDay['lessons']>()
let lessonRequest: AbortController | undefined

async function loadLessons() {
  const classId = selectedClass.value
  const day = selectedDate.value
  lessonRequest?.abort()
  lessons.value = undefined
  if (!classId || !day || day > today.value) return
  const request = new AbortController()
  lessonRequest = request
  try {
    const week = await loadClassWeek(classId, day, request.signal)
    if (request.signal.aborted) return
    lessons.value = week.find((entry) => entry.date === day)?.lessons ?? []
  } catch {
    // Die Anwesenheitsliste bleibt auch ohne Stundenplanhinweis nutzbar
  }
}

const currentTime = ref('')
const lessonInfo = computed(() => {
  const planned = lessons.value
  if (!planned) return ''
  if (!planned.length) return 'An diesem Tag ist kein Unterricht geplant.'
  const plan = planned.filter((lesson) => !lesson.cancelled)
  if (!plan.length) return 'Der Unterricht fällt an diesem Tag aus.'
  const describe = (lesson: ClassWeekDay['lessons'][number]) =>
    [
      `${formatTime(lesson.startsAt)}–${formatTime(lesson.endsAt)} Uhr`,
      lesson.subject,
      `Raum ${lesson.roomNumber}`
    ]
      .filter(Boolean)
      .join(' · ')
  const span = `Unterricht ${formatTime(plan[0]!.startsAt)}–${formatTime(plan.at(-1)!.endsAt)} Uhr`
  if (!isToday.value || !currentTime.value) return span
  const now = currentTime.value
  const running = plan.find(
    (lesson) =>
      formatTime(lesson.startsAt) <= now && now < formatTime(lesson.endsAt)
  )
  if (running) return `Jetzt: ${describe(running)}`
  const next = plan.find((lesson) => formatTime(lesson.startsAt) > now)
  return next
    ? `Als Nächstes: ${describe(next)}`
    : `${span} · für heute beendet`
})

function shiftDay(days: number) {
  const date = toCalendarDate(selectedDate.value)
  if (!date) return
  date.setDate(date.getDate() + days)
  const next = toIsoDate(date)
  if (next <= today.value) selectDate(next)
}

const classRequest = new AbortController()
let dayRequest: AbortController | undefined
let poll: number | undefined
let initializing = false

function serverNow() {
  return new Date(Date.now() + clockOffset)
}

function hasPresence(row: AttendanceRow) {
  return row.wasPresent || row.status === 'ANWESEND'
}

// „Abwesend“ bleibt wählbar, solange kein Kartenscan die Anwesenheit belegt
function canChoose(row: AttendanceRow, status: AttendanceStatus) {
  return status !== 'ABWESEND' || !hasPresence(row) || row.canMarkAbsent
}

function partialMinutes(row: AttendanceRow) {
  return row.fullAbsence ? 0 : row.unexcusedMinutes
}

function clearDrafts() {
  for (const id of Object.keys(drafts)) delete drafts[Number(id)]
}

async function fetchClasses() {
  if (initializing) return
  initializing = true
  classLoading.value = true
  try {
    const [visible, current] = await Promise.all([
      loadClasses(classRequest.signal),
      loadData<CurrentClass>(
        '/api/attendance/current-class',
        classRequest.signal
      )
    ])
    if (classRequest.signal.aborted) return
    clockOffset = Date.parse(current.serverTime) - Date.now()
    today.value = berlinDate(serverNow())
    const requestedDate = route?.query.datum
    selectedDate.value =
      typeof requestedDate === 'string' &&
      requestedDate <= today.value &&
      toCalendarDate(requestedDate)
        ? requestedDate
        : today.value
    historySelection = selectedDate.value !== today.value
    const requestedClass = visible.find(
      ({ id }) => String(id) === route?.query.klasse
    )
    classes.value = visible
    classError.value = false
    selectedClass.value =
      requestedClass?.id ?? current.classId ?? visible[0]?.id
  } catch {
    if (!classRequest.signal.aborted) classError.value = true
  } finally {
    initializing = false
    if (!classRequest.signal.aborted) classLoading.value = false
  }
}

async function refreshAttendance(day = selectedDate.value) {
  const classId = selectedClass.value
  if (!classId || !day || day > today.value) return
  dayRequest?.abort()
  const request = new AbortController()
  dayRequest = request
  dayLoading.value = true

  try {
    const query = new URLSearchParams({ date: day })
    const entries = await loadData<DailyStudent[]>(
      `/api/classes/${classId}/attendance?${query}`,
      request.signal
    )
    if (request.signal.aborted) return
    if (displayedDate.value !== day) clearDrafts()
    // Die Abfrage nach Mitternacht wechselt den Tag ohne den Watcher unten
    const dayChanged = selectedDate.value !== day
    displayedDate.value = day
    selectedDate.value = day
    if (dayChanged) void loadLessons()
    const now = serverNow()
    rows.value = entries.map((entry) => ({
      ...entry,
      name: `${entry.lastName}, ${entry.firstName}`
    }))
    for (const entry of entries) {
      const draft = drafts[entry.studentId]
      if (!draft?.touched && !draft?.saving) {
        drafts[entry.studentId] = {
          status: entry.status,
          time: day === today.value ? berlinTime(now) : '',
          touched: false,
          saving: false
        }
      } else if (
        entry.wasPresent &&
        draft.status === 'ABWESEND' &&
        !draft.saving
      ) {
        draft.status = entry.status
      }
    }
    lastUpdated.value = berlinTime(now)
    currentTime.value = berlinTime(now)
    dayError.value = false
  } catch {
    if (!request.signal.aborted) dayError.value = true
  } finally {
    if (!request.signal.aborted) dayLoading.value = false
  }
}

function retryClasses() {
  classError.value = false
  void fetchClasses()
}

function refreshSelectedDay() {
  dayError.value = false
  void refreshAttendance(historySelection ? selectedDate.value : today.value)
}

function refreshCurrentDay() {
  if (classError.value) return fetchClasses()
  today.value = berlinDate(serverNow())
  if (!historySelection) return refreshAttendance(today.value)
}

let historySelection = false
function selectDate(value: string) {
  historySelection = value !== today.value
  selectedDate.value = value
}

async function correct(row: AttendanceRow) {
  const draft = drafts[row.studentId]
  if (!draft || draft.saving || !clockTime.test(draft.time)) return
  const day = displayedDate.value
  const classId = selectedClass.value
  draft.saving = true
  correctionError.value = ''
  try {
    const response = await expectOk(
      await postJson(
        `/api/students/${row.studentId}/attendance/${day}/status-corrections`,
        { status: draft.status, occurredAt: berlinInstant(day, draft.time) }
      )
    )
    const { changed } = (await response.json()) as { changed: boolean }
    if (day !== displayedDate.value || classId !== selectedClass.value) return
    showNotice(
      changed
        ? `${row.name}: ${STATUS_LABELS[draft.status]} ab ${draft.time} Uhr gespeichert.`
        : `Keine Änderung für ${row.name} – der Status galt bereits.`
    )
    draft.touched = false
    draft.saving = false
    await refreshAttendance()
  } catch (failure) {
    if (day !== displayedDate.value || classId !== selectedClass.value) return
    correctionError.value =
      failure instanceof TypeError
        ? 'Statusänderung fehlgeschlagen. Bitte Verbindung prüfen.'
        : (failure as Error).message
  } finally {
    draft.saving = false
  }
}

watch([selectedClass, selectedDate], ([classId, date], [previousClass]) => {
  if (classId === previousClass && displayedDate.value === date) return
  dayRequest?.abort()
  rows.value = null
  lastUpdated.value = ''
  dayLoading.value = false
  dayError.value = false
  correctionError.value = ''
  clearDrafts()
  void refreshAttendance()
  void loadLessons()
})

function attendanceChanged() {
  void refreshAttendance()
}

onMounted(() => {
  void fetchClasses()
  window.addEventListener('attendance:changed', attendanceChanged)
  poll = window.setInterval(() => {
    if (!classRequest.signal.aborted && !dayLoading.value && !document.hidden) {
      void refreshCurrentDay()
    }
  }, 30_000)
})

onUnmounted(() => {
  window.clearInterval(poll)
  window.removeEventListener('attendance:changed', attendanceChanged)
  classRequest.abort()
  dayRequest?.abort()
  lessonRequest?.abort()
})
</script>

<template>
  <section class="attendance" aria-labelledby="attendance-title">
    <div class="attendance__heading">
      <div>
        <h1 id="attendance-title">Live-Anwesenheit</h1>
        <p>
          Wer ist da, wer fehlt? Die Liste aktualisiert sich alle 30 Sekunden.
        </p>
      </div>
      <Button
        v-if="selectedClass"
        label="Aktualisieren"
        icon="pi pi-refresh"
        outlined
        :loading="dayLoading"
        :disabled="!validDate"
        @click="refreshSelectedDay"
      />
    </div>

    <PageError
      v-if="classError"
      message="Klassen konnten nicht geladen werden."
    >
      <Button
        label="Erneut versuchen"
        outlined
        :disabled="classLoading"
        @click="retryClasses"
      />
    </PageError>
    <p v-if="classLoading" class="attendance__notice" role="status">
      Klassen werden geladen …
    </p>
    <p v-else-if="!classError && !classes.length" class="attendance__notice">
      Es sind keine Klassen verfügbar.
    </p>
    <template v-else-if="!classError">
      <div class="attendance__filters">
        <label class="class-filter">
          Klasse
          <select v-model="selectedClass">
            <option v-for="item in classes" :key="item.id" :value="item.id">
              {{ item.classCode }}
            </option>
          </select>
        </label>
        <!-- Kein <label>: Es würde den ersten Knopf darin auslösen -->
        <div
          class="attendance__date-field"
          role="group"
          aria-labelledby="attendance-date-label"
        >
          <span id="attendance-date-label">Datum</span>
          <span class="attendance__day">
            <Button
              icon="pi pi-chevron-left"
              aria-label="Vorheriger Tag"
              severity="secondary"
              outlined
              @click="shiftDay(-1)"
            />
            <GermanDateInput
              :model-value="selectedDate"
              class="attendance__date"
              label="Datum"
              :max="today"
              required
              @update:model-value="selectDate"
            />
            <Button
              icon="pi pi-chevron-right"
              aria-label="Nächster Tag"
              severity="secondary"
              outlined
              :disabled="isToday"
              @click="shiftDay(1)"
            />
          </span>
        </div>
        <Button
          v-if="!isToday"
          class="attendance__today"
          label="Heute"
          text
          @click="selectDate(today)"
        />
      </div>
      <p v-if="lessonInfo && validDate" class="attendance__lesson">
        <i class="pi pi-clock" aria-hidden="true" />
        {{ lessonInfo }}
        <span v-if="!isToday"> · {{ formatDate(selectedDate) }}</span>
      </p>

      <p v-if="!validDate" class="attendance__notice">
        Wähle heute oder ein früheres Datum.
      </p>
      <PageError
        v-if="dayError"
        message="Anwesenheit konnte nicht geladen werden. Versuch es über „Aktualisieren“ erneut."
      />
      <p
        v-else-if="dayLoading && !rows"
        class="attendance__notice"
        role="status"
      >
        Anwesenheit wird geladen …
      </p>
      <p
        v-else-if="rows?.length === 0"
        class="attendance__notice"
        role="status"
      >
        Für diese Klasse liegen an diesem Datum keine Anwesenheitsdaten vor.
      </p>

      <PageError :message="correctionError" />
      <div v-if="rows?.length" class="attendance__register">
        <dl class="attendance__summary">
          <div>
            <dt><i class="pi pi-check" aria-hidden="true" /> Anwesend</dt>
            <dd>
              {{ overview.present }}
              <span>von {{ overview.total }}</span>
            </dd>
          </div>
          <div>
            <dt><i class="pi pi-times" aria-hidden="true" /> Abwesend</dt>
            <dd>{{ overview.absent }}</dd>
          </div>
          <div>
            <dt>
              <i class="pi pi-info-circle" aria-hidden="true" /> Entschuldigt
            </dt>
            <dd>{{ overview.excused }}</dd>
          </div>
          <div>
            <dt><i class="pi pi-building" aria-hidden="true" /> Im Betrieb</dt>
            <dd>{{ overview.company }}</dd>
          </div>
        </dl>
        <p v-if="overview.late" class="attendance__late">
          {{ overview.late }}
          {{ overview.late === 1 ? 'Person ist' : 'Personen sind' }} anwesend,
          {{ overview.late === 1 ? 'hat' : 'haben' }} aber unentschuldigte
          Fehlminuten (z. B. Verspätung).
        </p>
        <AppDataTable
          :columns="columns"
          :rows="rows"
          row-key="studentId"
          caption="Anwesenheitsliste mit unentschuldigten Fehlminuten"
        >
          <template #cell-name="{ row }">
            <RouterLink
              class="attendance__student"
              :to="{
                name: 'schueler-detail',
                params: { studentId: row.studentId },
                query: { klasse: selectedClass, datum: displayedDate }
              }"
            >
              {{ row.name }}
            </RouterLink>
            <small
              v-if="duplicateNames.has(row.name) && row.birthDate"
              class="attendance__birth"
            >
              geb. {{ formatDate(row.birthDate) }}
            </small>
          </template>
          <template #cell-status="{ value }">
            <StatusBadge :status="value as AttendanceStatus" />
          </template>
          <template #cell-unexcusedMinutes="{ row }">
            <span v-if="row.fullAbsence === 'ABWESEND'">
              Ganzer Tag
              <small v-if="row.status !== 'ABWESEND'" class="attendance__birth">
                (davon {{ row.unexcusedMinutes }} Min. vor der Entschuldigung
                unentschuldigt)
              </small>
            </span>
            <span v-else-if="row.fullAbsence === 'ENTSCHULDIGT'">
              Keine (ganzer Tag entschuldigt)
            </span>
            <span v-else-if="partialMinutes(row)">
              {{ partialMinutes(row) }} Min.
            </span>
            <span v-else class="attendance__none">Keine</span>
          </template>
          <template #cell-studentId="{ row }">
            <form
              v-if="drafts[row.studentId]"
              class="correction"
              @submit.prevent="correct(row)"
            >
              <select
                v-model="drafts[row.studentId]!.status"
                :aria-label="`Neuer Status für ${row.name}`"
                @change="drafts[row.studentId]!.touched = true"
              >
                <template
                  v-for="(label, status) in STATUS_LABELS"
                  :key="status"
                >
                  <option v-if="canChoose(row, status)" :value="status">
                    {{ label }}
                  </option>
                </template>
              </select>
              <span class="correction__from" aria-hidden="true">ab</span>
              <GermanTimeInput
                v-model="drafts[row.studentId]!.time"
                :label="`Gültig ab Uhrzeit für ${row.name}`"
                required
                @update:model-value="drafts[row.studentId]!.touched = true"
              />
              <Button
                type="submit"
                label="Speichern"
                size="small"
                outlined
                :loading="drafts[row.studentId]!.saving"
              />
            </form>
          </template>
        </AppDataTable>
        <details class="attendance__legend">
          <summary>Was bedeuten die Status?</summary>
          <dl>
            <template v-for="(label, status) in STATUS_LABELS" :key="status">
              <dt>{{ label }}</dt>
              <dd>{{ STATUS_HINTS[status] }}</dd>
            </template>
          </dl>
          <p>
            Die Uhrzeit gibt an, ab wann der neue Status gilt. Hat das Terminal
            bereits eine Karte erfasst, ist „Abwesend“ nicht mehr wählbar –
            trage fehlende Zeit dann im Schülerprofil als Fehlminuten ein.
            „Abwesend“ nimmt eine von Hand eingetragene Anwesenheit nur ab der
            gewählten Uhrzeit zurück. Zum Zurücknehmen einer versehentlichen
            Anwesenheit dieselbe Uhrzeit wie bei „Anwesend“ wählen.
          </p>
        </details>
        <p class="attendance__updated">
          Stand {{ lastUpdated }} Uhr
          <span v-if="selectedClassCode"> · {{ selectedClassCode }}</span>
        </p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.attendance {
  display: grid;
  gap: 24px;
}

.attendance__heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
}

.attendance__heading h1 {
  margin: 0;
  font-size: clamp(1.9rem, 4vw, 3rem);
}

.attendance__heading p {
  margin: 8px 0 0;
  color: var(--muted);
}

.attendance__filters {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.attendance__filters :is(label, .attendance__date-field) {
  display: grid;
  gap: 6px;
  max-width: 100%;
  font-size: 0.85rem;
  font-weight: 650;
}

.attendance__filters select {
  min-width: 9rem;
  font-weight: 500;
}

.attendance__date {
  width: 10.25rem;
  max-width: 100%;
}

.attendance__student {
  white-space: nowrap;
  color: var(--accent-ink);
  font-weight: 600;
  text-underline-offset: 3px;
}

.attendance__notice {
  margin: 0;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--muted);
}

.attendance__register {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  font-variant-numeric: tabular-nums;
}

.attendance__day {
  display: flex;
  align-items: center;
  gap: 6px;
}

.attendance__today {
  align-self: end;
  min-height: 44px;
}

.attendance__lesson {
  margin: -8px 0 0;
  color: var(--accent-ink);
  font-weight: 600;
}

.attendance__lesson span {
  color: var(--muted);
  font-weight: 400;
}

.attendance__late {
  margin: 0;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
  background: #fff5e7;
  color: #6b3a00;
  font-size: 0.9rem;
}

.attendance__birth {
  display: block;
  color: var(--muted);
  font-size: 0.8rem;
}

.attendance__none {
  color: var(--muted);
}

.attendance__legend {
  padding: 12px 16px 0;
  font-size: 0.9rem;
}

.attendance__legend summary {
  width: fit-content;
  color: var(--accent-ink);
  font-weight: 600;
  cursor: pointer;
}

.attendance__legend dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 6px 16px;
  margin: 12px 0;
}

.attendance__legend dt {
  font-weight: 600;
}

.attendance__legend dd {
  margin: 0;
  color: var(--muted);
}

.attendance__legend p {
  max-width: 46rem;
  margin: 0 0 4px;
  color: var(--muted);
}

.correction__from {
  color: var(--muted);
  font-size: 0.85rem;
}

.attendance__summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  padding: 18px 0;
  background: #f1ebf9;
  border-bottom: 1px solid var(--line);
}

.attendance__summary > div {
  padding: 0 clamp(10px, 3vw, 26px);
}

.attendance__summary > div + div {
  border-inline-start: 1px solid var(--line);
}

.attendance__summary dt {
  color: var(--accent-ink);
  font-size: 0.85rem;
  font-weight: 600;
}

.attendance__summary i {
  margin-inline-end: 3px;
}

.attendance__summary dd {
  margin: 4px 0 0;
  font-size: clamp(1.25rem, 3vw, 1.8rem);
  font-weight: 650;
}

.attendance__summary dd span {
  font-size: 0.85rem;
}

.attendance__register :deep(th),
.attendance__register :deep(td) {
  white-space: normal;
  overflow-wrap: anywhere;
}

.attendance__updated {
  margin: 0;
  padding: 12px 16px;
  font-size: 0.8rem;
  color: var(--muted);
}

.correction {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
}

@media (max-width: 70rem) {
  .correction {
    flex-wrap: wrap;
  }
}

.correction :is(select, input) {
  min-height: 40px;
  max-width: 11rem;
  font-size: 0.86rem;
}

.correction :deep(.german-time) {
  width: 5rem;
}
@media (max-width: 40rem) {
  .attendance__summary dt {
    font-size: 0.75rem;
  }

  .attendance__summary i {
    display: none;
  }

  .attendance__register :deep(:is(table, thead, tbody)) {
    display: block;
  }

  .attendance__register :deep(tr) {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr) minmax(4rem, auto);
    align-items: center;
    border-bottom: 1px solid var(--line);
  }

  .attendance__register :deep(:is(th, td)) {
    padding: 10px 8px;
    border-bottom: 0;
  }

  .attendance__register :deep(th:last-child) {
    position: absolute;
    overflow: hidden;
    width: 1px;
    height: 1px;
    padding: 0;
    clip-path: inset(50%);
  }

  .attendance__register :deep(td:last-child) {
    grid-column: 1 / -1;
    padding-top: 0;
  }

  .attendance__register :deep(th:nth-child(3)) {
    font-size: 0.7rem;
    text-transform: none;
    letter-spacing: 0;
  }

  .attendance__register :deep(.status-badge) {
    font-size: 0.8rem;
  }

  .correction {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
  }

  .correction select {
    max-width: 100%;
  }
}
</style>
