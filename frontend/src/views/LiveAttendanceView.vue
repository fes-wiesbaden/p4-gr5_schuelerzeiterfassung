<script setup lang="ts">
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'

import {
  ATTENDANCE_STATUSES,
  BERLIN,
  STATUS_LABELS,
  berlinDate,
  berlinInstant,
  berlinTime,
  type AttendanceStatus,
  type CurrentClass,
  type DailyStudent
} from '@/api/attendance'
import { loadData, postJson } from '@/api/client'
import { expectOk } from '@/api/planning'
import AppDataTable, {
  type DataTableColumn
} from '@/components/AppDataTable.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const POLL_INTERVAL_MS = 30_000

type Row = DailyStudent & { name: string }

type SchoolClass = { id: number; classCode: string }

// Eingaben der Statusänderung je Schüler. touched verhindert, dass Polling
// eine begonnene Eingabe überschreibt
type Draft = { status: AttendanceStatus; time: string; touched: boolean }

const columns: DataTableColumn<Row>[] = [
  { field: 'name', header: 'Schüler' },
  { field: 'status', header: 'Status' },
  { field: 'unexcusedMinutes', header: 'Unentschuldigt heute (min)' },
  { field: 'studentId', header: 'Status ändern' }
]

const classes = ref<SchoolClass[]>([])
const classId = ref<number>()
const date = ref('')
const rows = ref<Row[]>([])
const loaded = ref(false)
const updatedAt = ref('')
const error = ref('')
const message = ref('')
const drafts = reactive<Record<number, Draft>>({})
const initialized = ref(false)
let latestRequest = 0
// Abstand der Browseruhr zur Serverzeit, damit Tageswechsel und vorbelegte
// Uhrzeit nicht an einer falsch gehenden Browseruhr scheitern
let clockOffset = 0
let timer: ReturnType<typeof setInterval> | undefined

function serverNow() {
  return new Date(Date.now() + clockOffset)
}

// Lädt den Tagesstatus per REST. Nur die Antwort der jüngsten Anfrage wird
// angezeigt, damit ein langsamer Polling-Abruf keinen neueren Stand überschreibt
async function load() {
  if (classId.value === undefined) return
  // Bleibt die Seite über Mitternacht offen, wechselt die Ansicht auf den neuen
  // Tag. Anzeige und Eingaben wechseln erst mit der erfolgreichen Antwort
  // Tag, vorbelegte Uhrzeit und Stand stammen aus demselben Zeitpunkt
  const now = serverNow()
  const day = berlinDate(now)
  const request = ++latestRequest
  try {
    const students = await loadData<DailyStudent[]>(
      `/api/classes/${classId.value}/attendance?date=${day}`
    )
    if (request !== latestRequest) return
    if (day !== date.value) {
      date.value = day
      clearDrafts()
    }
    rows.value = students.map((student) => ({
      ...student,
      name: `${student.lastName}, ${student.firstName}`
    }))
    // Unbearbeitete Eingaben folgen dem aktuellen Status und der aktuellen Uhrzeit,
    // damit ein Klick auf "Ändern" keinen zwischenzeitlichen Scan zurücksetzt
    for (const student of students) {
      if (!drafts[student.studentId]?.touched) {
        drafts[student.studentId] = {
          status: student.status,
          time: berlinTime(now),
          touched: false
        }
      }
    }
    updatedAt.value = now.toLocaleTimeString('de-DE', {
      timeZone: BERLIN
    })
    loaded.value = true
    error.value = ''
  } catch {
    if (request === latestRequest)
      error.value = 'Die Anwesenheit konnte nicht geladen werden.'
  }
}

function clearDrafts() {
  for (const studentId of Object.keys(drafts)) delete drafts[Number(studentId)]
}

async function correct(row: Row) {
  const draft = drafts[row.studentId]
  message.value = ''
  const occurredAt = berlinInstant(date.value, draft.time)
  try {
    const response = await expectOk(
      await postJson(
        `/api/students/${row.studentId}/attendance/${date.value}/status-corrections`,
        { status: draft.status, occurredAt }
      )
    )
    const { changed } = (await response.json()) as { changed: boolean }
    message.value = changed
      ? `Status von ${row.name} gespeichert.`
      : `Keine Änderung: ${row.name} hatte ab dieser Uhrzeit bereits diesen Status.`
  } catch (failure) {
    // fetch meldet Netzwerkfehler als TypeError, expectOk abgelehnte Anfragen mit Hinweistext
    message.value =
      failure instanceof TypeError
        ? 'Statusänderung fehlgeschlagen. Bitte Verbindung prüfen.'
        : (failure as Error).message
    return
  }
  // Die Eingabe bleibt sichtbar und übernimmt beim nächsten Abruf den neuen Status
  draft.touched = false
  await load()
}

watch(classId, () => {
  rows.value = []
  loaded.value = false
  clearDrafts()
  load()
})

// Lädt Klassen, Vorauswahl und Serverzeit. Schlägt das fehl, versucht es der
// nächste Polling-Takt erneut
async function initialize() {
  try {
    const [visible, current] = await Promise.all([
      loadData<SchoolClass[]>('/api/classes'),
      loadData<CurrentClass>('/api/attendance/current-class')
    ])
    clockOffset = Date.parse(current.serverTime) - Date.now()
    classes.value = visible
    classId.value = current.classId ?? visible[0]?.id
    initialized.value = true
    error.value = ''
  } catch {
    error.value = 'Die Klassen konnten nicht geladen werden.'
  }
}

onMounted(() => {
  // Das Intervall startet vor dem ersten Abruf, damit onUnmounted es auch
  // während eines noch laufenden Ladevorgangs beendet
  timer = setInterval(
    () => (initialized.value ? load() : initialize()),
    POLL_INTERVAL_MS
  )
  initialize()
})

onUnmounted(() => clearInterval(timer))
</script>

<template>
  <section>
    <h1>Live-Anwesenheit</h1>
    <div class="toolbar">
      <label class="class-filter">
        Klasse
        <select v-model="classId">
          <option v-for="c in classes" :key="c.id" :value="c.id">
            {{ c.classCode }}
          </option>
        </select>
      </label>
      <Button
        label="Aktualisieren"
        icon="pi pi-refresh"
        :disabled="classId === undefined"
        @click="load"
      />
      <span v-if="updatedAt" class="updated">Stand: {{ updatedAt }}</span>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="loaded && !rows.length">
      Für heute ist für diese Klasse kein Tagesstatus vorhanden.
    </p>
    <p v-if="initialized && !classes.length">
      Ihnen ist keine Klasse zugeordnet.
    </p>
    <AppDataTable
      v-if="rows.length"
      caption="Tagesanwesenheit der ausgewählten Klasse"
      :columns="columns"
      :rows="rows"
      row-key="studentId"
    >
      <template #cell-status="{ value }">
        <StatusBadge :status="value as AttendanceStatus" />
      </template>
      <template #cell-studentId="{ row }">
        <form
          v-if="drafts[row.studentId]"
          class="correction"
          @submit.prevent="correct(row)"
        >
          <select
            v-model="drafts[row.studentId].status"
            :aria-label="`Neuer Status für ${row.name}`"
            @change="drafts[row.studentId].touched = true"
          >
            <option v-for="s in ATTENDANCE_STATUSES" :key="s" :value="s">
              {{ STATUS_LABELS[s] }}
            </option>
          </select>
          <InputText
            v-model="drafts[row.studentId].time"
            type="time"
            required
            :aria-label="`Gültig ab Uhrzeit für ${row.name}`"
            @input="drafts[row.studentId].touched = true"
          />
          <Button type="submit" label="Ändern" size="small" />
        </form>
      </template>
    </AppDataTable>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1rem;
}

.updated {
  color: #4a5568;
  font-size: 0.9rem;
}

.correction {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
</style>
