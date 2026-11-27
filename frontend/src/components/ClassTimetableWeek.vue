<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import Button from 'primevue/button'
import { loadClasses, todayInBerlin, type SchoolClass } from '@/api/attendance'
import {
  cancelLessons,
  formatTime,
  loadClassSchedule,
  loadClassWeek,
  restoreLessons,
  type ManualAbsenceHint,
  type ScheduledLesson,
  type ClassSchedule,
  type ClassWeekDay
} from '@/api/planning'
import {
  formatCalendarWeek,
  formatDate,
  toCalendarDate,
  toIsoDate
} from '@/dateTime'
import { confirmAction } from '@/confirm'
import { showNotice } from '@/notice'
import GermanDateInput from './GermanDateInput.vue'
import TimetableGrid from './TimetableGrid.vue'

withDefaults(defineProps<{ active?: boolean }>(), { active: true })
const route = useRoute()
const auth = useAuthStore()
const classes = ref<SchoolClass[]>()
const classId = ref<number>()
const date = ref(todayInBerlin())
const blockSelect = ref<HTMLSelectElement>()
const schedule = ref<ClassSchedule>()
const days = ref<ClassWeekDay[]>([])
const classError = ref('')
const scheduleError = ref('')
const weekError = ref('')
const loadingWeek = ref(false)
const revision = ref(0)
// Der Schuljahresplan ändert sich nicht durch einen Ausfall
const scheduleRevision = ref(0)
const weekLabel = computed(() => formatCalendarWeek(date.value))
// Das Schuljahr läuft vom 1. August bis zum 31. Juli und richtet sich nach
// der angezeigten Woche, nicht nach dem aktuellen Blockplan
const yearLabel = computed(() => {
  const shown = toCalendarDate(days.value[0]?.date ?? date.value)
  if (!shown) return ''
  const start =
    shown.getMonth() >= 7 ? shown.getFullYear() : shown.getFullYear() - 1
  return `Schuljahr ${start}/${String(start + 1).slice(-2)}`
})
const isCurrentWeek = computed(() => {
  const now = todayInBerlin()
  return days.value.some((day) => day.date === now) || date.value === now
})
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
  // Immer auf den Montag der Zielwoche springen, damit das Datumsfeld
  // den Wochenbeginn zeigt
  current.setDate(current.getDate() - ((current.getDay() + 6) % 7) + amount)
  date.value = toIsoDate(current)
}

function refreshWeek() {
  revision.value += 1
}

function reloadPlan() {
  scheduleRevision.value += 1
  refreshWeek()
}

// Unterrichtsausfall: einzelne Stunde, ganzer Tag oder ganze Woche
const changing = ref(false)
const cancelError = ref('')
// Händische Fehlminuten an Tagen mit neuem Ausfall: bitte prüfen
const manualHints = ref<ManualAbsenceHint[]>([])
const weekCancelled = computed(() => {
  const lessons = days.value.flatMap((day) => day.lessons)
  return lessons.length > 0 && lessons.every((lesson) => lesson.cancelled)
})

function pastNote(dates: string[]) {
  return dates.some((day) => day <= todayInBerlin())
    ? ' Bereits erfasste Fehlzeiten dieser Zeit werden neu berechnet.'
    : ''
}

async function changeCancellation(
  work: () => Promise<unknown>,
  success: string
) {
  if (changing.value) return
  changing.value = true
  cancelError.value = ''
  manualHints.value = []
  try {
    await work()
    showNotice(success)
  } catch (failure) {
    cancelError.value =
      failure instanceof TypeError
        ? 'Der Ausfall konnte nicht gespeichert werden. Prüfe die Verbindung.'
        : (failure as Error).message
  } finally {
    changing.value = false
    // Auch nach einem Teilfehler zeigen Stundenplan und offene
    // Anwesenheitsansichten den Stand des Servers
    window.dispatchEvent(new Event('attendance:changed'))
    refreshWeek()
  }
}

async function toggleLesson(day: string, lesson: ScheduledLesson) {
  const id = classId.value
  if (!id) return
  const label = `${lesson.subject || 'Unterricht'} am ${formatDate(day)}, ${formatTime(lesson.startsAt)}–${formatTime(lesson.endsAt)} Uhr`
  if (lesson.cancelled) {
    if (
      !(await confirmAction({
        title: 'Ausfall zurücknehmen?',
        message: `${label} findet wieder statt.${pastNote([day])}`,
        confirmLabel: 'Zurücknehmen'
      }))
    )
      return
    await changeCancellation(
      () => restoreLessons(id, day, lesson.startsAt),
      `Ausfall zurückgenommen: ${label}.`
    )
    return
  }
  if (
    !(await confirmAction({
      title: 'Stunde fällt aus?',
      message: `${label} wird als Ausfall eingetragen. Dafür entstehen keine Fehlminuten.${pastNote([day])}`,
      confirmLabel: 'Als Ausfall eintragen'
    }))
  )
    return
  await changeCancellation(async () => {
    manualHints.value = await cancelLessons(id, day, lesson.startsAt)
  }, `Ausfall eingetragen: ${label}.`)
}

async function toggleDays(dates: string[], cancel: boolean, scope: string) {
  const id = classId.value
  if (!id || !dates.length) return
  if (
    !(await confirmAction({
      title: cancel
        ? `${scope}: Unterricht fällt aus?`
        : `${scope}: Ausfall zurücknehmen?`,
      message: cancel
        ? `Alle Stunden werden als Ausfall eingetragen. Dafür entstehen keine Fehlminuten.${pastNote(dates)}`
        : `Alle Stunden finden wieder statt.${pastNote(dates)}`,
      confirmLabel: cancel ? 'Als Ausfall eintragen' : 'Zurücknehmen'
    }))
  )
    return
  await changeCancellation(
    async () => {
      // Hinweise sofort sammeln, damit sie auch bei einem Teilfehler bleiben
      for (const day of dates) {
        if (cancel) manualHints.value.push(...(await cancelLessons(id, day)))
        else await restoreLessons(id, day)
      }
    },
    cancel
      ? `${scope}: Ausfall eingetragen.`
      : `${scope}: Ausfall zurückgenommen.`
  )
}

function toggleDay(day: string, cancel: boolean) {
  return toggleDays([day], cancel, formatDate(day))
}

function toggleWeek() {
  const dates = days.value
    .filter((day) => day.lessons.length)
    .map((day) => day.date)
  return toggleDays(dates, !weekCancelled.value, weekLabel.value)
}

function jumpToBlock(startsOn: string) {
  if (!startsOn) return
  date.value = startsOn
}

async function jumpToNextBlock() {
  if (!nextBlock.value) return
  jumpToBlock(nextBlock.value.startsOn)
  await nextTick()
  blockSelect.value?.focus()
}

watch(() => [route?.query.klasse, route?.query.datum], useRouteSelection)
watch([classId, scheduleRevision], async ([id], _previous, onCleanup) => {
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
watch([classId, date, revision], async ([id, day], previous, onCleanup) => {
  weekError.value = ''
  const staleDays =
    id !== previous?.[0] || !days.value.some((entry) => entry.date === day)
  if (id !== previous?.[0] || day !== previous?.[1]) cancelError.value = ''
  // Beim Neuladen derselben Woche bleibt der Plan stehen, damit er nach einem
  // eingetragenen Ausfall nicht aufblitzt. Eine andere Klasse zeigt ihn nie
  if (staleDays) days.value = []
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
  window.addEventListener('planning:changed', reloadPlan)
  window.addEventListener('management:changed', fetchClasses)
})
onUnmounted(() => {
  classRequest.abort()
  window.removeEventListener('planning:changed', reloadPlan)
  window.removeEventListener('management:changed', fetchClasses)
})
</script>

<template>
  <article class="class-week" aria-labelledby="class-week-title">
    <header class="class-week__header">
      <div>
        <h2 id="class-week-title">Wochenstundenplan</h2>
        <p v-if="yearLabel || weekLabel" class="class-week__period">
          <strong v-if="yearLabel">{{ yearLabel }}</strong>
          <span v-if="weekLabel">{{ weekLabel }}</span>
          <span v-if="days.length">
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
    <div v-if="schedule?.blocks.length" class="class-week__controls">
      <label>
        Zu einem Unterrichtsblock springen
        <select
          ref="blockSelect"
          :value="weekBlock?.startsOn ?? ''"
          @change="jumpToBlock(($event.target as HTMLSelectElement).value)"
        >
          <option disabled value="">Woche ohne Unterrichtsblock</option>
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
    <div v-if="classes?.length" class="class-week__navigation">
      <Button
        type="button"
        icon="pi pi-chevron-left"
        outlined
        aria-label="Vorherige Stundenplanwoche"
        @click="shiftWeek(-7)"
      />
      <GermanDateInput v-model="date" label="Datum in der gewünschten Woche" />
      <Button
        type="button"
        icon="pi pi-chevron-right"
        outlined
        aria-label="Nächste Stundenplanwoche"
        @click="shiftWeek(7)"
      />
      <Button
        v-if="!isCurrentWeek"
        type="button"
        label="Diese Woche"
        text
        @click="date = todayInBerlin()"
      />
    </div>
    <PageError v-if="active" :message="scheduleError" />
    <PageError v-if="active" :message="cancelError" />
    <PageError
      v-if="classError || weekError"
      :message="active ? classError || weekError : ''"
    >
      <Button
        type="button"
        label="Erneut versuchen"
        outlined
        @click="classError ? fetchClasses() : reloadPlan()"
      />
    </PageError>
    <p v-else-if="!classes || (loadingWeek && !days.length)" role="status">
      Wochenstundenplan wird geladen …
    </p>
    <p v-else-if="!classes.length">
      {{
        auth.isAdmin
          ? 'Es gibt noch keine Klassen. Lege zuerst unter „Klassen“ eine an.'
          : 'Dir ist noch keine Klasse zugeordnet. Die Verwaltung kann das ändern.'
      }}
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
    <template v-else-if="days.length">
      <div v-if="manualHints.length" class="class-week__manual" role="status">
        <p>
          <i class="pi pi-exclamation-triangle" aria-hidden="true" />
          {{
            new Set(manualHints.map((hint) => hint.date)).size > 1
              ? 'An diesen Tagen'
              : 'An diesem Tag'
          }}
          stehen von Hand eingetragene Fehlminuten. Sie bleiben bestehen – prüfe
          bitte, ob sie zur ausgefallenen Zeit gehören, und entferne sie dann im
          Profil:
        </p>
        <ul>
          <li
            v-for="hint in manualHints"
            :key="`${hint.studentId}/${hint.date}`"
          >
            <RouterLink
              :to="{
                name: 'schueler-detail',
                params: { studentId: hint.studentId },
                query: { woche: hint.date }
              }"
            >
              {{ hint.lastName }}, {{ hint.firstName }}
            </RouterLink>
            · {{ formatDate(hint.date) }} · {{ hint.minutes }} Min.
          </li>
        </ul>
        <Button
          type="button"
          label="Hinweis schließen"
          size="small"
          text
          @click="manualHints = []"
        />
      </div>
      <div class="class-week__cancel">
        <p>
          <i class="pi pi-info-circle" aria-hidden="true" />
          Fällt Unterricht aus? Klick auf eine Stunde, um sie für diesen Tag als
          Ausfall zu markieren – ein weiterer Klick nimmt das zurück. Für
          ausgefallene Stunden entstehen keine Fehlminuten.
        </p>
        <Button
          type="button"
          :label="
            weekCancelled
              ? 'Ausfall der ganzen Woche zurücknehmen'
              : 'Ganze Woche fällt aus'
          "
          size="small"
          outlined
          :disabled="changing"
          @click="toggleWeek"
        />
      </div>
      <TimetableGrid
        :days="days"
        :periods="days[0]?.periods"
        :caption="`Wochenstundenplan für ${selectedClass?.classCode}`"
        :busy="changing"
        cancellable
        @toggle-lesson="toggleLesson"
        @toggle-day="toggleDay"
      />
    </template>
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

.class-week__manual {
  display: grid;
  justify-items: start;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid #f1d9a8;
  border-radius: 10px;
  background: #fff5e7;
  color: #6b3a00;
  font-size: 0.9rem;
  line-height: 1.4;
}

.class-week__manual ul {
  margin: 0;
  padding-left: 1.2rem;
}

.class-week__manual a {
  color: inherit;
  font-weight: 600;
}

.class-week__cancel {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--paper);
  color: var(--muted);
  font-size: 0.88rem;
}

.class-week__cancel p {
  flex: 1 1 24rem;
  line-height: 1.4;
}

.class-week__break {
  display: grid;
  justify-items: start;
  gap: 7px;
  padding: 15px;
  border-radius: 10px;
  background: var(--paper);
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
