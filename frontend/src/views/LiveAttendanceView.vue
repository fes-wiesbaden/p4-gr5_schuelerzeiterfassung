<script setup lang="ts">
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'

import {
  ATTENDANCE_STATUSES,
  STATUS_LABELS,
  berlinDate,
  berlinInstant,
  berlinTime,
  type AttendanceStatus,
  type CurrentClass,
  type DailyStudent
} from '@/api/attendance'
import { loadData, postJson } from '@/api/client'
import { errorMessage } from '@/api/planning'
import AppDataTable, {
  type DataTableColumn
} from '@/components/AppDataTable.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const POLL_INTERVAL_MS = 30_000

type Row = {
  studentId: number
  name: string
  status: AttendanceStatus
  unexcusedMinutes: number
}

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
let latestRequest = 0
let timer: ReturnType<typeof setInterval> | undefined

// Lädt den Tagesstatus per REST. Nur die Antwort der jüngsten Anfrage wird
// angezeigt, damit ein langsamer Polling-Abruf keinen neueren Stand überschreibt
async function load() {
  if (classId.value === undefined) return
  // Bleibt die Seite über Mitternacht offen, wechselt die Ansicht auf den neuen Tag
  const today = berlinDate(new Date())
  if (today > date.value) {
    date.value = today
    clearDrafts()
  }
  const request = ++latestRequest
  try {
    const students = await loadData<DailyStudent[]>(
      `/api/classes/${classId.value}/attendance?date=${date.value}`
    )
    if (request !== latestRequest) return
    rows.value = students.map((student) => ({
      studentId: student.studentId,
      name: `${student.lastName}, ${student.firstName}`,
      status: student.status,
      unexcusedMinutes: student.unexcusedMinutes
    }))
    // Unbearbeitete Eingaben folgen dem aktuellen Status und der aktuellen Uhrzeit,
    // damit ein Klick auf "Ändern" keinen zwischenzeitlichen Scan zurücksetzt
    for (const student of students) {
      if (!drafts[student.studentId]?.touched) {
        drafts[student.studentId] = {
          status: student.status,
          time: berlinTime(new Date()),
          touched: false
        }
      }
    }
    updatedAt.value = new Date().toLocaleTimeString('de-DE')
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
  let changed: boolean
  try {
    const response = await postJson(
      `/api/students/${row.studentId}/attendance/${date.value}/status-corrections`,
      { status: draft.status, occurredAt }
    )
    const body = await response.json().catch(() => ({}))
    if (!response.ok) {
      message.value = errorMessage(body.code)
      return
    }
    changed = body.changed === true
  } catch {
    message.value = 'Statusänderung fehlgeschlagen. Bitte Verbindung prüfen.'
    return
  }
  message.value = changed
    ? `Status von ${row.name} gespeichert.`
    : `Keine Änderung: ${row.name} hatte ab dieser Uhrzeit bereits diesen Status.`
  delete drafts[row.studentId]
  await load()
}

watch(classId, () => {
  rows.value = []
  loaded.value = false
  clearDrafts()
  load()
})

onMounted(async () => {
  try {
    const [visible, current] = await Promise.all([
      loadData<SchoolClass[]>('/api/classes'),
      loadData<CurrentClass>('/api/attendance/current-class')
    ])
    classes.value = visible
    date.value = current.date
    classId.value = current.classId ?? visible[0]?.id
  } catch {
    error.value = 'Die Klassen konnten nicht geladen werden.'
  }
  timer = setInterval(load, POLL_INTERVAL_MS)
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
    <p v-if="!error && !classes.length && date">
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
