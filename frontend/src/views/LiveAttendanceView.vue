<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'

import {
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
import { expectOk } from '@/api/planning'
import GermanDateInput from '@/components/GermanDateInput.vue'
import GermanTimeInput from '@/components/GermanTimeInput.vue'
import { timePattern, toCalendarDate } from '@/dateTime'
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
const message = ref('')
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
  { field: 'unexcusedMinutes', header: 'Unentschuldigt' },
  { field: 'studentId', header: 'Status ändern' }
]

const overview = computed(() => {
  const entries = rows.value ?? []
  return {
    present: entries.filter(({ status }) => status === 'ANWESEND').length,
    absent: entries.filter(({ status }) => status === 'ABWESEND').length,
    days: entries.filter(({ fullAbsence }) => fullAbsence === 'ABWESEND')
      .length,
    minutes: entries.reduce((sum, entry) => sum + partialMinutes(entry), 0)
  }
})

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
    displayedDate.value = day
    selectedDate.value = day
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
  message.value = ''
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
    message.value = changed
      ? `Status von ${row.name} gespeichert.`
      : `Keine Änderung für ${row.name}.`
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
  message.value = ''
  clearDrafts()
  void refreshAttendance()
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
})
</script>

<template>
  <section class="attendance" aria-labelledby="attendance-title">
    <div class="attendance__heading">
      <div>
        <h1 id="attendance-title">Live-Anwesenheit</h1>
        <p>Tagesstatus und Fehlzeiten deiner Klassen.</p>
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
        <label>
          Datum
          <GermanDateInput
            :model-value="selectedDate"
            class="attendance__date"
            label="Datum"
            :max="today"
            required
            @update:model-value="selectDate"
          />
        </label>
      </div>

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

      <p v-if="message" class="attendance__notice" role="status">
        {{ message }}
      </p>
      <PageError :message="correctionError" />
      <div v-if="rows?.length" class="attendance__register">
        <dl class="attendance__summary">
          <div>
            <dt><i class="pi pi-check" aria-hidden="true" /> Anwesend</dt>
            <dd>{{ overview.present }}</dd>
          </div>
          <div>
            <dt><i class="pi pi-times" aria-hidden="true" /> Abwesend</dt>
            <dd>{{ overview.absent }}</dd>
          </div>
          <div>
            <dt><i class="pi pi-clock" aria-hidden="true" /> Unentschuldigt</dt>
            <dd>
              <template v-if="overview.days">
                {{ overview.days }}
                <span>{{ overview.days === 1 ? 'Tag' : 'Tage' }}</span> ·
              </template>
              {{ overview.minutes }} <span>Min.</span>
            </dd>
          </div>
        </dl>
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
          </template>
          <template #cell-status="{ value }">
            <StatusBadge :status="value as AttendanceStatus" />
          </template>
          <template #cell-unexcusedMinutes="{ row }">
            <span v-if="row.fullAbsence === 'ABWESEND'">Ganzer Tag</span>
            <span v-else>{{ partialMinutes(row) }} Min.</span>
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
                  <option
                    v-if="status !== 'ABWESEND' || !hasPresence(row)"
                    :value="status"
                  >
                    {{ label }}
                  </option>
                </template>
              </select>
              <GermanTimeInput
                v-model="drafts[row.studentId]!.time"
                :label="`Gültig ab Uhrzeit für ${row.name}`"
                required
                @update:model-value="drafts[row.studentId]!.touched = true"
              />
              <Button
                type="submit"
                label="Ändern"
                size="small"
                outlined
                :loading="drafts[row.studentId]!.saving"
              />
            </form>
          </template>
        </AppDataTable>
        <p class="attendance__updated">Stand {{ lastUpdated }} Uhr</p>
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

.attendance__filters label {
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

.attendance__summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
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
  flex-wrap: wrap;
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
