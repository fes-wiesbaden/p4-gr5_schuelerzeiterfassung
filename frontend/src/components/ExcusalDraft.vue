<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import GermanDateInput from './GermanDateInput.vue'
import { STATUS_LABELS } from '@/api/attendance'
import GermanTimeInput from './GermanTimeInput.vue'

import {
  AssistantRequestError,
  confirmExcusal,
  previewExcusal,
  type ExcusalDay,
  type ExcusalHint,
  type ExcusalImpact,
  type ExcusalPreview,
  type ExcusalScope,
  type ExcusalStatus
} from '@/api/assistant'
import { loadClasses, todayInBerlin, type SchoolClass } from '@/api/attendance'
import { loadData } from '@/api/client'
import { formatDate, timePattern, toCalendarDate } from '@/dateTime'

const props = defineProps<{ hint: ExcusalHint }>()
const emit = defineEmits<{
  confirmed: [impact: ExcusalImpact]
  cancel: []
}>()

type Student = {
  id: number
  firstName: string
  lastName: string
  birthDate: string
}

const today = todayInBerlin()
const clockTime = new RegExp(`^${timePattern}$`)
const date = ref(
  toCalendarDate(props.hint.date ?? '') ? (props.hint.date ?? '') : ''
)
const startsAt = ref(
  /^\d{2}:\d{2}$/.test(props.hint.startsAt ?? '')
    ? (props.hint.startsAt ?? '')
    : ''
)
const endsAt = ref(
  /^\d{2}:\d{2}$/.test(props.hint.endsAt ?? '') ? (props.hint.endsAt ?? '') : ''
)
const scope = ref<ExcusalScope>(
  props.hint.scope ??
    (props.hint.endDate
      ? 'range'
      : props.hint.startsAt || props.hint.endsAt
        ? 'time'
        : 'day')
)
const endDate = ref(
  toCalendarDate(props.hint.endDate ?? '') ? (props.hint.endDate ?? '') : ''
)
const reason = ref((props.hint.reason ?? '').slice(0, 250))
const status = ref<ExcusalStatus>(props.hint.status ?? 'ENTSCHULDIGT')
const hasCertificate = computed({
  get: () => status.value === 'MIT_ATTEST_ENTSCHULDIGT',
  set: (checked: boolean) => {
    status.value = checked ? 'MIT_ATTEST_ENTSCHULDIGT' : 'ENTSCHULDIGT'
  }
})
const excusalStatuses: ExcusalStatus[] = [
  'ENTSCHULDIGT',
  'MIT_ATTEST_ENTSCHULDIGT',
  'BETRIEBLICH_ENTSCHULDIGT'
]
const classId = ref<number>()
const studentId = ref<number>()
const classes = ref<SchoolClass[]>([])
const students = ref<Student[]>([])
const loadingClasses = ref(true)
const loadingStudents = ref(false)
const selectionError = ref('')
const error = ref('')
const busy = ref(false)
const preview = ref<ExcusalPreview>()
const previewPanel = ref<HTMLElement>()

const selectedStudent = computed(() =>
  students.value.find(({ id }) => id === studentId.value)
)
const canPreview = computed(() =>
  Boolean(
    classId.value &&
    studentId.value &&
    date.value &&
    (scope.value !== 'time' ||
      (date.value <= today &&
        clockTime.test(startsAt.value) &&
        clockTime.test(endsAt.value) &&
        startsAt.value < endsAt.value)) &&
    (scope.value !== 'range' ||
      (endDate.value > date.value &&
        status.value === 'MIT_ATTEST_ENTSCHULDIGT'))
  )
)
const nameCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const student of students.value) {
    const name = normalize(fullName(student))
    counts.set(name, (counts.get(name) ?? 0) + 1)
  }
  return counts
})
const classRequest = new AbortController()

function previewLabel(day: ExcusalDay) {
  if (day.after === 'ENTSCHULDIGT') return '1 entschuldigter Fehltag'
  if (day.after === 'ABWESEND') return '1 unentschuldigter Fehltag'
  return `${day.excusedAdded} Min. entschuldigt`
}

function fullName(student: Student) {
  return `${student.firstName} ${student.lastName}`
}

function normalize(value = '') {
  return value
    .normalize()
    .trim()
    .replace(/\s+/g, ' ')
    .toLocaleLowerCase('de-DE')
}

function studentLabel(student: Student) {
  const name = fullName(student)
  return (nameCounts.value.get(normalize(name)) ?? 0) > 1
    ? `${name} · ${formatDate(student.birthDate)}`
    : name
}

async function fetchClasses() {
  loadingClasses.value = true
  selectionError.value = ''
  try {
    classes.value = await loadClasses(classRequest.signal)
    const hint = normalize(props.hint.classCode ?? '')
    const match = classes.value.find(
      ({ classCode }) => normalize(classCode) === hint
    )
    classId.value =
      match?.id ??
      (!hint && classes.value.length === 1 ? classes.value[0]?.id : undefined)
  } catch {
    if (!classRequest.signal.aborted)
      selectionError.value = 'Klassen konnten nicht geladen werden.'
  } finally {
    if (!classRequest.signal.aborted) loadingClasses.value = false
  }
}

watch(classId, async (id, _old, onCleanup) => {
  students.value = []
  studentId.value = undefined
  selectionError.value = ''
  if (!id) {
    loadingStudents.value = false
    return
  }
  const request = new AbortController()
  onCleanup(() => request.abort())
  loadingStudents.value = true
  try {
    const result = await loadData<Student[]>(
      `/api/classes/${id}/students`,
      request.signal
    )
    if (request.signal.aborted) return
    students.value = result
    const hint = normalize(props.hint.studentName ?? '')
    const matches = result.filter(
      (student) =>
        normalize(fullName(student)) === hint ||
        normalize(`${student.lastName}, ${student.firstName}`) === hint
    )
    if (matches.length === 1) studentId.value = matches[0]?.id
  } catch {
    if (!request.signal.aborted)
      selectionError.value = 'Die Klassenliste konnte nicht geladen werden.'
  } finally {
    if (!request.signal.aborted) loadingStudents.value = false
  }
})

watch(
  [classId, studentId, date, endDate, startsAt, endsAt, reason, scope, status],
  () => {
    preview.value = undefined
    error.value = ''
  }
)

async function calculatePreview() {
  if (!canPreview.value || busy.value || !classId.value || !studentId.value)
    return
  busy.value = true
  preview.value = undefined
  error.value = ''
  try {
    preview.value = await previewExcusal({
      classId: classId.value,
      studentId: studentId.value,
      date: date.value,
      status: status.value,
      reason: reason.value.trim() || undefined,
      ...(scope.value === 'time'
        ? { startsAt: startsAt.value, endsAt: endsAt.value }
        : {}),
      ...(scope.value === 'range' ? { endDate: endDate.value } : {})
    })
    await nextTick()
    previewPanel.value?.scrollIntoView?.({ block: 'nearest' })
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : 'Vorschau fehlgeschlagen.'
  } finally {
    busy.value = false
  }
}

async function book() {
  if (!preview.value?.id || busy.value) return
  busy.value = true
  error.value = ''
  try {
    emit('confirmed', await confirmExcusal(preview.value.id))
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : 'Buchung fehlgeschlagen.'
    if (
      failure instanceof AssistantRequestError &&
      failure.status >= 400 &&
      failure.status < 500
    ) {
      preview.value = undefined
    }
  } finally {
    busy.value = false
  }
}

onMounted(fetchClasses)
onUnmounted(() => classRequest.abort())
</script>

<template>
  <section class="excusal" aria-labelledby="excusal-title">
    <h3 id="excusal-title" tabindex="-1">Fehlzeit entschuldigen</h3>
    <form @submit.prevent="calculatePreview">
      <fieldset class="excusal__fields" :disabled="busy">
        <label>
          Klasse
          <select
            v-model="classId"
            required
            :disabled="loadingClasses || !classes.length"
          >
            <option :value="undefined" disabled>Klasse auswählen</option>
            <option v-for="item in classes" :key="item.id" :value="item.id">
              {{ item.classCode }}
            </option>
          </select>
        </label>
        <label>
          Person
          <select
            v-model="studentId"
            required
            :disabled="loadingStudents || !students.length"
          >
            <option :value="undefined" disabled>Person auswählen</option>
            <option
              v-for="student in students"
              :key="student.id"
              :value="student.id"
            >
              {{ studentLabel(student) }}
            </option>
          </select>
        </label>
        <label class="excusal__wide">
          Umfang
          <select v-model="scope">
            <option value="time">Bestimmte Uhrzeiten</option>
            <option value="day">Ganzer Schultag</option>
            <option value="range">Mehrere Schultage mit Attest</option>
          </select>
        </label>
        <label :class="{ excusal__wide: scope !== 'range' }">
          {{ scope === 'range' ? 'Erster Tag' : 'Datum' }}
          <GermanDateInput
            v-model="date"
            with-dialog
            :label="scope === 'range' ? 'Erster Tag' : 'Datum'"
            :max="scope === 'time' ? today : undefined"
            required
          />
        </label>
        <label v-if="scope === 'range'">
          Letzter Tag
          <GermanDateInput
            v-model="endDate"
            with-dialog
            label="Letzter Tag"
            required
          />
        </label>
        <template v-if="scope === 'time'">
          <label>
            Von
            <GermanTimeInput v-model="startsAt" label="Von" required />
          </label>
          <label>
            Bis
            <GermanTimeInput v-model="endsAt" label="Bis" required />
          </label>
        </template>
        <label v-if="scope !== 'range'" class="excusal__wide">
          Entschuldigungsart
          <select v-model="status">
            <option v-for="kind in excusalStatuses" :key="kind" :value="kind">
              {{ STATUS_LABELS[kind] }}
            </option>
          </select>
        </label>
        <label v-else class="excusal__attest excusal__wide">
          <Checkbox
            v-model="hasCertificate"
            input-id="excusal-certificate"
            binary
            required
          />
          Ein Attest liegt vor
        </label>
        <label class="excusal__wide">
          Grund (optional)
          <InputText v-model="reason" maxlength="250" />
        </label>
      </fieldset>
      <p v-if="loadingClasses || loadingStudents" role="status">
        Auswahl wird geladen …
      </p>
      <p v-else-if="selectionError" class="excusal__error" role="alert">
        {{ selectionError }}
      </p>
      <p v-else-if="!classes.length">Es sind keine Klassen verfügbar.</p>
      <p v-else-if="classId && !students.length">
        In dieser Klasse sind keine Personen erfasst.
      </p>
      <p v-if="error" class="excusal__error" role="alert">{{ error }}</p>
      <div class="excusal__actions">
        <Button
          type="submit"
          label="Vorschau berechnen"
          size="small"
          outlined
          :disabled="!canPreview || busy"
        />
        <Button
          type="button"
          label="Abbrechen"
          size="small"
          text
          :disabled="busy"
          @click="emit('cancel')"
        />
      </div>
    </form>
    <div
      v-if="preview"
      ref="previewPanel"
      class="excusal__preview"
      aria-live="polite"
    >
      <template v-if="preview.id && selectedStudent">
        <p>
          <strong>{{ studentLabel(selectedStudent) }}</strong>
        </p>
        <p>{{ STATUS_LABELS[status] }}</p>
        <p v-if="scope === 'time'">{{ startsAt }}–{{ endsAt }} Uhr</p>
        <p v-if="reason">{{ reason }}</p>
        <ul class="excusal__days">
          <li v-for="day in preview.impact.days" :key="day.date">
            <strong>{{ formatDate(day.date) }}</strong>
            <span v-if="day.pending"> · Vormerkung</span>
            <span> · {{ previewLabel(day) }}</span>
          </li>
        </ul>
        <dl v-if="scope === 'time'">
          <div>
            <dt>Unentschuldigt</dt>
            <dd>−{{ preview.impact.unexcusedRemoved }} Min.</dd>
          </div>
          <div>
            <dt>Entschuldigt</dt>
            <dd>+{{ preview.impact.excusedAdded }} Min.</dd>
          </div>
        </dl>
        <Button
          type="button"
          :label="
            preview.impact.days.every(({ pending }) => pending)
              ? 'Entschuldigung vormerken'
              : 'Entschuldigung buchen'
          "
          icon="pi pi-check"
          size="small"
          :loading="busy"
          @click="book"
        />
      </template>
      <p v-else>Für die Auswahl gibt es keine offenen Unterrichtsfehlzeiten.</p>
    </div>
  </section>
</template>

<style scoped>
.excusal {
  display: grid;
  gap: 12px;
  padding: 14px 0 0;
  border-top: 1px solid var(--line);
}

h3,
p,
dl,
dd {
  margin: 0;
}

h3 {
  font-size: 1rem;
}

p,
dl {
  font-size: 0.875rem;
}

form,
.excusal__preview {
  display: grid;
  gap: 12px;
}

.excusal__fields,
.excusal__preview dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.excusal__fields {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.excusal__fields label {
  display: grid;
  gap: 4px;
  min-width: 0;
  font-size: 0.82rem;
  font-weight: 600;
}

.excusal__fields :is(input:not([type='checkbox']), select) {
  min-width: 0;
  width: 100%;
  min-height: 42px;
  font-size: 0.86rem;
  font-weight: 400;
}

.excusal__wide {
  grid-column: 1 / -1;
}

.excusal__actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.excusal__error {
  color: #8c3038;
}

.excusal__preview {
  padding: 12px;
  border-radius: 10px;
  background: var(--accent-soft);
}

.excusal__preview dt {
  color: var(--accent-ink);
}

.excusal__preview dd {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.excusal__fields .excusal__attest {
  display: flex;
  align-items: center;
  gap: 8px;
}

.excusal__fields .excusal__attest input {
  min-height: auto;
  width: 1.1rem;
  height: 1.1rem;
  accent-color: var(--accent);
}

.excusal__days {
  display: grid;
  gap: 4px;
  margin: 0;
  padding-inline-start: 1.2rem;
  font-size: 0.85rem;
}

.excusal__preview p {
  overflow-wrap: anywhere;
}
</style>
