<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { loadClasses, todayInBerlin, type SchoolClass } from '@/api/attendance'
import {
  loadClassSchedule,
  loadClassWeek,
  type ClassSchedule,
  type ClassWeekDay
} from '@/api/planning'
import {
  formatCalendarWeek,
  formatDate,
  toCalendarDate,
  toIsoDate
} from '@/dateTime'
import GermanDateInput from './GermanDateInput.vue'
import TimetableGrid from './TimetableGrid.vue'

const route = useRoute()
const classes = ref<SchoolClass[]>()
const classId = ref<number>()
const date = ref(todayInBerlin())
const view = ref<'week' | 'regular'>('week')
const weekViewButton = ref<HTMLButtonElement>()
const schedule = ref<ClassSchedule>()
const days = ref<ClassWeekDay[]>([])
const classError = ref('')
const scheduleError = ref('')
const weekError = ref('')
const loadingWeek = ref(false)
const revision = ref(0)
const weekLabel = computed(() => formatCalendarWeek(date.value))
const yearLabel = computed(() =>
  schedule.value
    ? `Schuljahr ${schedule.value.schoolYear}/${String(schedule.value.schoolYear + 1).slice(-2)}`
    : ''
)
const weekBlock = computed(() =>
  schedule.value?.blocks.find((block) =>
    days.value.some(
      (day) => block.startsOn <= day.date && day.date <= block.endsOn
    )
  )
)
const nextBlock = computed(() => {
  const friday = days.value.at(-1)?.date
  return friday
    ? schedule.value?.blocks.find((block) => block.startsOn > friday)
    : undefined
})
const classRequest = new AbortController()
const selectedClass = computed(() =>
  classes.value?.find((item) => item.id === classId.value)
)

function useRouteSelection() {
  const requestedClass = classes.value?.find(
    (item) => String(item.id) === route?.query.klasse
  )
  classId.value =
    requestedClass?.id ??
    classes.value?.find((item) => item.id === classId.value)?.id ??
    classes.value?.[0]?.id
  const requestedDate = route?.query.datum
  if (typeof requestedDate === 'string' && toCalendarDate(requestedDate))
    date.value = requestedDate
}

async function fetchClasses() {
  classError.value = ''
  try {
    const visible = await loadClasses(classRequest.signal)
    if (classRequest.signal.aborted) return
    classes.value = visible
    useRouteSelection()
  } catch {
    if (!classRequest.signal.aborted)
      classError.value = 'Klassen konnten nicht geladen werden.'
  }
}

function shiftWeek(amount: number) {
  const current = toCalendarDate(date.value || todayInBerlin())
  if (!current) return
  current.setDate(current.getDate() + amount)
  date.value = toIsoDate(current)
}

function refreshWeek() {
  revision.value += 1
}

function jumpToBlock(startsOn: string) {
  if (!startsOn) return
  date.value = startsOn
  view.value = 'week'
}

async function jumpToNextBlock() {
  if (!nextBlock.value) return
  jumpToBlock(nextBlock.value.startsOn)
  await nextTick()
  weekViewButton.value?.focus()
}

watch(
  () => [route?.query.klasse, route?.query.datum],
  () => {
    useRouteSelection()
    view.value = 'week'
  }
)
watch([classId, revision], async ([id], _previous, onCleanup) => {
  schedule.value = undefined
  scheduleError.value = ''
  if (!id) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  try {
    const response = await loadClassSchedule(id, request.signal)
    if (!request.signal.aborted) schedule.value = response
  } catch {
    if (!request.signal.aborted)
      scheduleError.value = 'Der Schuljahresplan konnte nicht geladen werden.'
  }
})
watch([classId, date, revision], async ([id, day], _previous, onCleanup) => {
  weekError.value = ''
  days.value = []
  loadingWeek.value = Boolean(id && day)
  if (!id || !day) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  try {
    const response = await loadClassWeek(id, day, request.signal)
    if (!request.signal.aborted) days.value = response
  } catch {
    if (!request.signal.aborted)
      weekError.value = 'Die Unterrichtszeiten konnten nicht geladen werden.'
  } finally {
    if (!request.signal.aborted) loadingWeek.value = false
  }
})
onMounted(() => {
  void fetchClasses()
  window.addEventListener('planning:changed', refreshWeek)
  window.addEventListener('management:changed', fetchClasses)
})
onUnmounted(() => {
  classRequest.abort()
  window.removeEventListener('planning:changed', refreshWeek)
  window.removeEventListener('management:changed', fetchClasses)
})
</script>

<template>
  <article class="class-week" aria-labelledby="class-week-title">
    <header class="class-week__header">
      <div>
        <h2 id="class-week-title">Wochenstundenplan</h2>
        <p
          v-if="yearLabel || (view === 'week' && weekLabel)"
          class="class-week__period"
        >
          <strong v-if="yearLabel">{{ yearLabel }}</strong>
          <span v-if="view === 'week'">{{ weekLabel }}</span>
          <span v-if="view === 'week' && days.length">
            {{ formatDate(days[0]!.date) }} –
            {{ formatDate(days.at(-1)!.date) }}
          </span>
        </p>
      </div>
      <label v-if="classes?.length">
        Klasse
        <select v-model="classId">
          <option v-for="item in classes" :key="item.id" :value="item.id">
            {{ item.classCode }}
          </option>
        </select>
      </label>
    </header>
    <div v-if="schedule" class="class-week__controls">
      <div
        class="class-week__views"
        role="group"
        aria-label="Stundenplanansicht"
      >
        <button
          ref="weekViewButton"
          type="button"
          :aria-pressed="view === 'week'"
          @click="view = 'week'"
        >
          Kalenderwoche
        </button>
        <button
          type="button"
          :aria-pressed="view === 'regular'"
          @click="view = 'regular'"
        >
          Regelstundenplan
        </button>
      </div>
      <label v-if="schedule.blocks.length">
        Unterrichtsblock
        <select
          :value="view === 'week' ? (weekBlock?.startsOn ?? '') : ''"
          @change="jumpToBlock(($event.target as HTMLSelectElement).value)"
        >
          <option disabled value="">Block auswählen</option>
          <option
            v-for="block in schedule.blocks"
            :key="block.startsOn"
            :value="block.startsOn"
          >
            {{ formatDate(block.startsOn) }} – {{ formatDate(block.endsOn) }}
          </option>
        </select>
      </label>
    </div>
    <div
      v-if="view === 'week' && classes?.length"
      class="class-week__navigation"
    >
      <Button
        type="button"
        icon="pi pi-chevron-left"
        outlined
        aria-label="Vorherige Stundenplanwoche"
        @click="shiftWeek(-7)"
      />
      <GermanDateInput v-model="date" label="Stundenplanwoche" />
      <Button
        type="button"
        icon="pi pi-chevron-right"
        outlined
        aria-label="Nächste Stundenplanwoche"
        @click="shiftWeek(7)"
      />
      <Button
        type="button"
        label="Diese Woche"
        text
        @click="date = todayInBerlin()"
      />
    </div>
    <p v-if="scheduleError" role="status">{{ scheduleError }}</p>
    <div v-if="classError || (view === 'week' && weekError)" role="alert">
      <p>{{ classError || weekError }}</p>
      <Button
        type="button"
        label="Erneut laden"
        outlined
        @click="classError ? fetchClasses() : refreshWeek()"
      />
    </div>
    <p v-else-if="!classes || (view === 'week' && loadingWeek)" role="status">
      Wochenstundenplan wird geladen …
    </p>
    <p v-else-if="!classes.length">Dir ist keine Klasse zugeordnet.</p>
    <template v-else-if="view === 'regular' && schedule">
      <p class="class-week__hint">
        Dieses Wochenraster gilt in den Unterrichtsblöcken der Klasse.
      </p>
      <TimetableGrid
        key="regular"
        :days="schedule.regularWeek"
        :periods="schedule.periods"
        :caption="`Regelstundenplan für ${selectedClass?.classCode}`"
      />
    </template>
    <p v-else-if="view === 'regular' && !scheduleError" role="status">
      Regelstundenplan wird geladen …
    </p>
    <p v-else-if="!date">Wähle ein Datum aus der gewünschten Woche.</p>
    <div
      v-else-if="days.length && schedule && !weekBlock"
      class="class-week__break"
    >
      <p role="status">
        In dieser Kalenderwoche ist kein Unterrichtsblock zugeordnet.
      </p>
      <Button
        v-if="nextBlock"
        type="button"
        :label="`Zum nächsten Block ab ${formatDate(nextBlock.startsOn)}`"
        text
        @click="jumpToNextBlock"
      />
    </div>
    <TimetableGrid
      v-else-if="days.length"
      key="week"
      :days="days"
      :periods="days[0]?.periods"
      :caption="`Wochenstundenplan für ${selectedClass?.classCode}`"
    />
  </article>
</template>

<style scoped>
.class-week {
  display: grid;
  gap: 16px;
  min-width: 0;
  padding: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
}

.class-week__header,
.class-week__navigation {
  display: flex;
  align-items: center;
  gap: 8px;
}

.class-week__navigation {
  display: grid;
  grid-template-columns: auto minmax(0, 13rem) auto auto;
  justify-content: start;
}

.class-week__header {
  flex-wrap: wrap;
  justify-content: space-between;
}

.class-week__header h2,
p {
  margin: 0;
}

.class-week__controls {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: 12px 20px;
}

.class-week__views {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  border-radius: 11px;
  background: var(--paper);
}

.class-week__views button {
  min-height: 40px;
  padding: 5px 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 0.86rem;
  font-weight: 650;
  cursor: pointer;
}

.class-week__views button:is(:hover, [aria-pressed='true']) {
  background: var(--accent-soft);
  color: var(--accent-ink);
}

.class-week__break {
  display: grid;
  justify-items: start;
  gap: 7px;
  padding: 15px;
  border-radius: 10px;
  background: var(--paper);
}

.class-week__hint,
.class-week__break {
  color: var(--muted);
  font-size: 0.875rem;
}

.class-week__period {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px 12px;
  margin-top: 6px;
  color: var(--muted);
  font-size: 0.9rem;
}

.class-week__period strong {
  color: var(--accent-ink);
  font-weight: 650;
}

label {
  display: grid;
  gap: 4px;
  min-width: 8rem;
  font-size: 0.85rem;
  font-weight: 650;
}

@media (max-width: 40rem) {
  .class-week {
    padding: 14px;
  }

  .class-week__navigation {
    grid-template-columns: auto minmax(0, 13rem) auto;
  }

  .class-week__navigation > :last-child {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
