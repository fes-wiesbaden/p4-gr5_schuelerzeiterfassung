<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import {
  WEEKDAYS,
  formatTime,
  type ScheduledLesson,
  type Slot,
  type TimetablePeriod
} from '@/api/planning'
import { formatDate } from '@/dateTime'
import { todayInBerlin } from '@/api/attendance'

type Entry = ScheduledLesson & { slot?: Slot }

const props = defineProps<{
  days: { date?: string; lessons: Entry[] }[]
  caption: string
  periods?: TimetablePeriod[]
  busy?: boolean
}>()
const emit = defineEmits<{ edit: [slot: Slot]; remove: [slot: Slot] }>()
const today = todayInBerlin()
const mobile = ref(false)
const selectedDay = ref(0)
const viewport = window.matchMedia('(max-width: 44rem)')

function updateViewport() {
  mobile.value = viewport.matches
}

onMounted(() => {
  updateViewport()
  viewport.addEventListener('change', updateViewport)
})
onUnmounted(() => viewport.removeEventListener('change', updateViewport))

watch(
  () => props.days[0]?.date,
  () => {
    const index = props.days.findIndex((day) => day.date === today)
    selectedDay.value = index >= 0 ? index : 0
  },
  { immediate: true }
)

const days = computed(() => props.days.map((day, index) => ({ ...day, index })))
const visibleDays = computed(() =>
  mobile.value
    ? days.value.filter((day) => day.index === selectedDay.value)
    : days.value
)
const hasLessons = computed(() => days.value.some((day) => day.lessons.length))
const hasVisibleLessons = computed(() =>
  visibleDays.value.some((day) => day.lessons.length)
)

const dayLabels = WEEKDAYS.map(({ label }) => ({
  short: label.slice(0, 2),
  accessible: `${label.slice(0, 2)} · ${label}`
}))

const lessonLength = 45 * 60
function clockSeconds(time: string) {
  const [hours = 0, minutes = 0, seconds = 0] = time.split(':').map(Number)
  return hours * 3600 + minutes * 60 + seconds
}

function clockLabel(seconds: number) {
  const part = (value: number) => String(Math.floor(value)).padStart(2, '0')
  return formatTime(
    `${part(seconds / 3600)}:${part((seconds % 3600) / 60)}:${part(seconds % 60)}`
  )
}

// Ohne hinterlegte Stundentafel ergibt sich das Raster aus den Unterrichtszeiten.
const rows = computed(() => {
  const periods = (props.periods ?? []).map((period) => ({
    number: period.number,
    start: clockSeconds(period.startsAt),
    end: clockSeconds(period.endsAt)
  }))
  const week = days.value.map((day) =>
    day.lessons
      .map((lesson) => ({
        entry: lesson,
        start: clockSeconds(lesson.startsAt),
        end: clockSeconds(lesson.endsAt)
      }))
      .sort((left, right) => left.start - right.start)
  )
  const usePeriods =
    periods.length > 0 &&
    week
      .flat()
      .every(
        (lesson) =>
          periods.some((period) => period.start === lesson.start) &&
          periods.some((period) => period.end === lesson.end)
      )
  const timepoints = new Set<number>()
  if (usePeriods) {
    for (const { start, end } of periods) {
      timepoints.add(start)
      timepoints.add(end)
    }
  } else {
    for (const lesson of week.flat()) {
      for (let time = lesson.start; time < lesson.end; time += lessonLength) {
        timepoints.add(time)
      }
      timepoints.add(lesson.end)
    }
  }
  const boundaries = [...timepoints].sort((left, right) => left - right)
  const positions = new Map(boundaries.map((time, index) => [time, index]))
  let elapsedTeaching = 0
  let lastPeriod = 0
  return boundaries.slice(0, -1).map((start, index) => {
    const end = boundaries[index + 1]!
    const active = week.some((day) =>
      day.some((lesson) => lesson.start <= start && start < lesson.end)
    )
    const period = usePeriods
      ? (periods.find((item) => item.start === start)?.number ?? null)
      : active
        ? Math.floor(elapsedTeaching / lessonLength) + 1
        : null
    const displayPeriod = period !== lastPeriod ? period : null
    if (period !== null) {
      lastPeriod = period
      elapsedTeaching += end - start
    }
    return {
      start: clockLabel(start),
      end: clockLabel(end),
      displayPeriod,
      pause: usePeriods ? period === null : !active,
      cells: visibleDays.value.map((day) => {
        const lesson = week[day.index]?.find(
          (item) => item.start <= start && start < item.end
        )
        return {
          entry: lesson?.entry,
          covered: lesson !== undefined && lesson.start !== start,
          span: lesson ? (positions.get(lesson.end) ?? index + 1) - index : 1,
          index: day.index
        }
      })
    }
  })
})
</script>

<template>
  <div class="week-grid">
    <div
      v-if="hasLessons && mobile"
      class="week-grid__days"
      role="group"
      aria-label="Unterrichtstag auswählen"
    >
      <button
        v-for="day in days"
        :key="day.index"
        type="button"
        :aria-label="dayLabels[day.index]?.accessible"
        :aria-pressed="selectedDay === day.index"
        @click="selectedDay = day.index"
      >
        {{ dayLabels[day.index]?.short }}
      </button>
    </div>

    <div v-if="hasVisibleLessons" class="week-grid__table">
      <table>
        <caption class="week-grid__caption" v-text="caption" />
        <thead>
          <tr>
            <th scope="col">Stunde</th>
            <th
              v-for="day in visibleDays"
              :key="day.index"
              scope="col"
              :class="{ 'week-grid__today': day.date === today }"
              :aria-current="day.date === today ? 'date' : undefined"
            >
              {{ WEEKDAYS[day.index]?.label }}
              <span v-if="day.date">{{ formatDate(day.date) }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.start"
            :class="{ 'week-grid__break': row.pause }"
          >
            <th scope="row" class="week-grid__times">
              <span
                v-if="row.displayPeriod"
                class="week-grid__period"
                v-text="row.displayPeriod"
              />
              <span v-if="row.pause" class="week-grid__pause-label">Pause</span>
              <span class="week-grid__clock">
                <time>{{ row.start }}</time>
                <time>{{ row.end }}</time>
              </span>
            </th>
            <template v-for="cell in row.cells" :key="cell.index">
              <td
                v-if="!cell.covered"
                :rowspan="cell.span"
                :class="{
                  'week-grid__filled': cell.entry,
                  'week-grid__last-column':
                    cell.index === visibleDays.at(-1)?.index
                }"
              >
                <div v-if="cell.entry" class="week-grid__lesson">
                  <strong>Unterricht</strong>
                  <small v-if="cell.entry.roomNumber">
                    Raum {{ cell.entry.roomNumber }}
                  </small>
                  <small v-if="cell.entry.slot?.validFrom">
                    Ab {{ formatDate(cell.entry.slot.validFrom) }}
                  </small>
                  <div v-if="cell.entry.slot" class="week-grid__actions">
                    <Button
                      type="button"
                      icon="pi pi-pencil"
                      size="small"
                      text
                      :disabled="busy"
                      :aria-label="`Unterrichtszeit ${WEEKDAYS[cell.index]?.label} ${row.start} bearbeiten`"
                      @click="emit('edit', cell.entry.slot)"
                    />
                    <Button
                      type="button"
                      icon="pi pi-trash"
                      size="small"
                      text
                      severity="danger"
                      :disabled="busy"
                      :aria-label="`Unterrichtszeit ${WEEKDAYS[cell.index]?.label} ${row.start} löschen`"
                      @click="emit('remove', cell.entry.slot)"
                    />
                  </div>
                </div>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="week-grid__empty">
      {{
        hasLessons
          ? 'An diesem Tag ist kein Unterricht geplant.'
          : 'Für diese Auswahl ist kein Unterricht geplant.'
      }}
    </p>
  </div>
</template>

<style scoped>
.week-grid {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.week-grid__days {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  width: 100%;
  gap: 4px;
  padding: 4px;
  border-radius: 10px;
  background: var(--paper);
}

.week-grid__days button {
  border: 0;
  border-radius: 7px;
  min-height: 44px;
  padding: 4px 2px;
  color: var(--muted);
  background: transparent;
  font: inherit;
  font-size: 0.84rem;
  font-weight: 650;
  cursor: pointer;
}

.week-grid__days button:hover,
.week-grid__days button[aria-pressed='true'] {
  background: var(--accent-soft);
  color: var(--accent-ink);
}

.week-grid__table {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
}

table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  table-layout: fixed;
  text-align: left;
  font-variant-numeric: tabular-nums;
}

.week-grid__caption {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}

th,
td {
  border-bottom: 1px solid var(--line);
  border-inline-end: 1px solid var(--line);
}

thead th:last-child,
.week-grid__last-column {
  border-inline-end: 0;
}

tbody tr:last-child > * {
  border-bottom: 0;
}

thead {
  background: #f0eaf8;
  color: var(--ink);
}

thead th {
  padding: 11px 12px;
  font-size: 0.88rem;
}

thead .week-grid__today {
  background: #ddd0f1;
  color: #422477;
}

thead span {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 0.72rem;
  font-weight: 500;
}

thead th:first-child {
  width: 5.75rem;
}

.week-grid__times {
  position: relative;
  height: 41px;
  padding: 6px 9px 6px 31px;
  background: var(--paper);
  font-size: 0.72rem;
  font-weight: 550;
}

.week-grid__period {
  position: absolute;
  inset-inline-start: 10px;
  top: 7px;
  font-size: 0.83rem;
  font-weight: 700;
  color: var(--accent-ink);
}

.week-grid__clock {
  display: grid;
  gap: 2px;
  color: var(--muted);
  white-space: nowrap;
}

.week-grid__clock time:first-child {
  color: var(--ink);
}

td {
  vertical-align: top;
  background: var(--paper);
}

.week-grid__filled {
  background: #e8efda;
  --lesson-ink: #354b21;
  --lesson-muted: #485939;
}

.week-grid__lesson {
  display: grid;
  gap: 3px;
  padding: 9px 11px;
  overflow-wrap: anywhere;
}

.week-grid__lesson strong {
  font-size: 0.9rem;
  font-weight: 720;
  color: var(--lesson-ink, var(--accent-ink));
}

.week-grid__lesson small {
  color: var(--lesson-muted, var(--muted));
  font-size: 0.72rem;
}

.week-grid__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}

.week-grid__actions button {
  width: 32px;
  height: 32px;
}

.week-grid__break .week-grid__times,
.week-grid__break td {
  background: #f1edf5;
  height: auto;
  padding: 4px 9px;
}

.week-grid__pause-label {
  font-size: 0.66rem;
  font-weight: 700;
  color: var(--muted);
}

.week-grid__break .week-grid__clock {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  font-size: 0.64rem;
}

.week-grid__break .week-grid__clock time + time::before {
  content: '– ';
}

.week-grid__empty {
  margin: 0;
  padding: 1.2rem;
  color: var(--muted);
  background: var(--paper);
  border-radius: var(--radius);
}
</style>
