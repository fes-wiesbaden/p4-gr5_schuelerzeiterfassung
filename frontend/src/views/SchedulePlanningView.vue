<script setup lang="ts">
import {
  computed,
  nextTick,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  watch
} from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'

import ClassTimetableWeek from '@/components/ClassTimetableWeek.vue'
import TimetableGrid from '@/components/TimetableGrid.vue'
import GermanDateInput from '@/components/GermanDateInput.vue'
import GermanTimeInput from '@/components/GermanTimeInput.vue'
import { formatDate } from '@/dateTime'
import { useAuthStore } from '@/stores/auth'
import {
  WEEKDAYS,
  addBlock,
  addSlot,
  assignBlock,
  changeBlockPlan,
  createBlockPlan,
  createTimetable,
  deleteSlot,
  formatTime,
  loadOverview,
  resetAccounts,
  updateSlot,
  type PlanningOverview,
  type Slot,
  type Weekday
} from '@/api/planning'

const auth = useAuthStore()

const overview = ref<PlanningOverview | null>(null)
const loadError = ref('')
const busy = ref(false)
const feedback = reactive({ text: '', error: false })

const selectedTimetableId = ref<number | null>(null)
const selectedBlockPlanId = ref<number | null>(null)
const selectedClassId = ref<number | null>(null)
const editingSlotId = ref<number | null>(null)
const slotWeekday = ref<HTMLSelectElement | null>(null)

const timetableForm = reactive({ name: '' })
const slotForm = reactive({
  weekday: 'MONTAG' as Weekday,
  startsAt: '',
  endsAt: '',
  roomId: null as number | null,
  learningField: '',
  teacherId: null as number | null
})
const blockPlanForm = reactive({ name: '', startsOn: '', endsOn: '' })
const blockForm = reactive({ startsOn: '', endsOn: '' })
const assignmentForm = reactive({ blockId: null as number | null })
const planChangeForm = reactive({ nextBlockPlanId: null as number | null })

const selectedTimetable = computed(() =>
  overview.value?.timetables.find((t) => t.id === selectedTimetableId.value)
)
const templateDays = computed(() =>
  WEEKDAYS.map((day) => ({
    lessons: (selectedTimetable.value?.slots ?? [])
      .filter((slot) => slot.weekday === day.value)
      .map((slot) => ({ ...slot, slot }))
  }))
)
const selectedBlockPlan = computed(() =>
  overview.value?.blockPlans.find((p) => p.id === selectedBlockPlanId.value)
)
const selectedClass = computed(() =>
  overview.value?.classes.find((c) => c.id === selectedClassId.value)
)
const classPlan = computed(() =>
  overview.value?.blockPlans.find(
    (p) => p.id === selectedClass.value?.blockPlanId
  )
)
const classTimetable = computed(() =>
  overview.value?.timetables.find(
    (t) => t.id === selectedClass.value?.timetableId
  )
)
// Nur Blöcke des aktuellen Blockplans, denen die Klasse noch nicht zugeordnet ist
const assignableBlocks = computed(
  () =>
    classPlan.value?.blocks.filter(
      (block) => !block.classIds.includes(selectedClassId.value ?? -1)
    ) ?? []
)
// Folgepläne müssen nach dem Ende des aktuellen Blockplans beginnen
const nextPlanCandidates = computed(
  () =>
    overview.value?.blockPlans.filter(
      (plan) => classPlan.value && plan.startsOn > classPlan.value.endsOn
    ) ?? []
)

function classCodes(classIds: number[]): string {
  const codes = classIds
    .map((id) => overview.value?.classes.find((c) => c.id === id)?.classCode)
    .filter((code): code is string => Boolean(code))
  return codes.length ? codes.join(', ') : '—'
}

function weekdayLabel(weekday: Weekday): string {
  return WEEKDAYS.find((day) => day.value === weekday)?.label ?? weekday
}

async function refresh() {
  try {
    overview.value = await loadOverview()
    loadError.value = ''
    selectedTimetableId.value ??= overview.value.timetables[0]?.id ?? null
    selectedBlockPlanId.value ??= overview.value.blockPlans[0]?.id ?? null
    selectedClassId.value ??= overview.value.classes[0]?.id ?? null
  } catch (error) {
    loadError.value =
      error instanceof Error
        ? error.message
        : 'Die Planung konnte nicht geladen werden.'
  }
}

// Führt eine Änderung aus, lädt danach die Übersicht neu und zeigt das Ergebnis an
async function run(action: () => Promise<string>): Promise<boolean> {
  busy.value = true
  feedback.text = ''
  try {
    feedback.text = await action()
    feedback.error = false
    await refresh()
    window.dispatchEvent(new Event('planning:changed'))
    return true
  } catch (error) {
    feedback.text =
      error instanceof Error ? error.message : 'Die Aktion ist fehlgeschlagen.'
    feedback.error = true
    return false
  } finally {
    busy.value = false
  }
}

async function submitTimetable() {
  const name = timetableForm.name
  if (
    await run(async () => {
      selectedTimetableId.value = await createTimetable(name)
      return `Stundenplan „${name.trim()}“ angelegt.`
    })
  ) {
    timetableForm.name = ''
  }
}

async function submitSlot() {
  const timetableId = selectedTimetableId.value
  const roomId = slotForm.roomId
  if (timetableId === null || roomId === null) {
    return
  }
  if (
    await run(async () => {
      const slot = {
        weekday: slotForm.weekday,
        startsAt: slotForm.startsAt,
        endsAt: slotForm.endsAt,
        roomId
      }
      const current = selectedTimetable.value?.slots.find(
        (item) => item.id === editingSlotId.value
      )
      const learningField = slotForm.learningField.trim() || null
      const teacherId = slotForm.teacherId
      const detailsChanged =
        learningField !== (current?.learningField ?? null) ||
        teacherId !== (current?.teacherId ?? null)
      const payload = detailsChanged
        ? { ...slot, lessonDetails: { learningField, teacherId } }
        : slot
      if (editingSlotId.value !== null) {
        const effectiveOn = await updateSlot(
          timetableId,
          editingSlotId.value,
          payload
        )
        return `Unterrichtszeit geändert ab ${formatDate(effectiveOn)}.`
      }
      await addSlot(timetableId, payload)
      return 'Unterrichtszeit hinzugefügt.'
    })
  ) {
    resetSlotForm()
  }
}

async function editSlot(slot: Slot) {
  editingSlotId.value = slot.id
  Object.assign(slotForm, {
    weekday: slot.weekday,
    startsAt: slot.startsAt,
    endsAt: slot.endsAt,
    roomId: slot.roomId,
    learningField: slot.learningField ?? '',
    teacherId: slot.teacherId ?? null
  })
  await nextTick()
  slotWeekday.value?.focus()
}

function resetSlotForm() {
  editingSlotId.value = null
  slotForm.startsAt = ''
  slotForm.endsAt = ''
  slotForm.learningField = ''
  slotForm.teacherId = null
}

async function removeSlot(slot: Slot) {
  const timetableId = selectedTimetableId.value
  if (
    timetableId === null ||
    !window.confirm(
      `Die Unterrichtszeit am ${weekdayLabel(slot.weekday)} um ${formatTime(slot.startsAt)} Uhr für alle zukünftigen Termine löschen?`
    )
  ) {
    return
  }
  const removed = await run(async () => {
    const effectiveOn = await deleteSlot(timetableId, slot.id)
    return `Unterrichtszeit entfällt ab ${formatDate(effectiveOn)}.`
  })
  if (removed && editingSlotId.value === slot.id) {
    resetSlotForm()
  }
}

async function submitBlockPlan() {
  const plan = { ...blockPlanForm }
  if (
    await run(async () => {
      selectedBlockPlanId.value = await createBlockPlan(plan)
      return `Blockplan „${plan.name.trim()}“ angelegt.`
    })
  ) {
    blockPlanForm.name = ''
    blockPlanForm.startsOn = ''
    blockPlanForm.endsOn = ''
  }
}

async function submitBlock() {
  const planId = selectedBlockPlanId.value
  if (planId === null) {
    return
  }
  if (
    await run(async () => {
      await addBlock(planId, { ...blockForm })
      return 'Block hinzugefügt.'
    })
  ) {
    blockForm.startsOn = ''
    blockForm.endsOn = ''
  }
}

async function submitAssignment() {
  const schoolClass = selectedClass.value
  const blockId = assignmentForm.blockId
  if (!schoolClass || blockId === null) {
    return
  }
  if (
    await run(async () => {
      await assignBlock(schoolClass.id, blockId)
      return `Klasse ${schoolClass.classCode} dem Block zugeordnet.`
    })
  ) {
    assignmentForm.blockId = null
  }
}

async function submitReset() {
  const schoolClass = selectedClass.value
  if (
    !schoolClass ||
    !window.confirm(
      `Fehlminutenkonten aller Schüler der Klasse ${schoolClass.classCode} zurücksetzen?`
    )
  ) {
    return
  }
  await run(async () => {
    const count = await resetAccounts(schoolClass.id, schoolClass.blockPlanId)
    return `Konten von ${count} Schülern zurückgesetzt.`
  })
}

async function submitPlanChange() {
  const schoolClass = selectedClass.value
  const nextPlanId = planChangeForm.nextBlockPlanId
  if (
    !schoolClass ||
    nextPlanId === null ||
    !window.confirm(
      `Für ${schoolClass.classCode} werden alle Konten abschließend zurückgesetzt und der Folgeplan aktiviert. Fortfahren?`
    )
  ) {
    return
  }
  if (
    await run(async () => {
      await changeBlockPlan(schoolClass.id, schoolClass.blockPlanId, nextPlanId)
      return `Blockplan der Klasse ${schoolClass.classCode} gewechselt.`
    })
  ) {
    planChangeForm.nextBlockPlanId = null
  }
}

watch(selectedTimetableId, resetSlotForm)

watch(selectedClassId, () => {
  assignmentForm.blockId = null
  planChangeForm.nextBlockPlanId = null
})

onMounted(() => {
  if (auth.isAdmin) {
    void refresh()
    window.addEventListener('management:changed', refresh)
  }
})
onUnmounted(() => window.removeEventListener('management:changed', refresh))
</script>

<template>
  <section class="planning">
    <h1>Stunden- und Blockplan</h1>

    <ClassTimetableWeek />

    <p
      v-if="auth.isAdmin && loadError"
      class="planning__message planning__message--error"
      role="alert"
    >
      {{ loadError }}
    </p>
    <template v-else-if="auth.isAdmin && overview">
      <p
        v-if="feedback.text"
        class="planning__message"
        :class="{ 'planning__message--error': feedback.error }"
        :role="feedback.error ? 'alert' : 'status'"
      >
        {{ feedback.text }}
      </p>

      <!-- Stundenpläne mit festen Slots -->
      <article class="planning__card">
        <header class="planning__card-header">
          <h2>Stundenpläne bearbeiten</h2>
          <label class="planning__inline">
            <span>Stundenplan</span>
            <select
              v-model="selectedTimetableId"
              :disabled="busy || !overview.timetables.length"
            >
              <option
                v-for="timetable in overview.timetables"
                :key="timetable.id"
                :value="timetable.id"
              >
                {{ timetable.name }}
              </option>
            </select>
          </label>
        </header>

        <p class="planning__hint">
          Das Wochenraster gilt in allen Unterrichtsblöcken der zugeordneten
          Klassen.
        </p>
        <TimetableGrid
          :days="templateDays"
          :periods="selectedTimetable?.periods"
          :caption="selectedTimetable?.name ?? 'Stundenplan'"
          :busy="busy"
          @edit="editSlot"
          @remove="removeSlot"
        />

        <div class="planning__forms">
          <form class="planning__form" @submit.prevent="submitSlot">
            <h3>
              {{
                editingSlotId === null
                  ? 'Unterrichtszeit hinzufügen'
                  : 'Unterrichtszeit bearbeiten'
              }}
            </h3>
            <p v-if="editingSlotId !== null" class="planning__hint">
              Änderungen gelten ab einem Termin, der noch nicht begonnen hat.
            </p>
            <label>
              <span>Wochentag</span>
              <select ref="slotWeekday" v-model="slotForm.weekday" required>
                <option
                  v-for="day in WEEKDAYS"
                  :key="day.value"
                  :value="day.value"
                >
                  {{ day.label }}
                </option>
              </select>
            </label>
            <div class="planning__pair">
              <label>
                <span>Beginn</span>
                <GermanTimeInput
                  v-model="slotForm.startsAt"
                  label="Beginn"
                  seconds
                  required
                />
              </label>
              <label>
                <span>Ende</span>
                <GermanTimeInput
                  v-model="slotForm.endsAt"
                  label="Ende"
                  seconds
                  required
                />
              </label>
            </div>
            <label>
              <span>Raum</span>
              <select v-model="slotForm.roomId" required>
                <option
                  v-for="room in overview.rooms"
                  :key="room.id"
                  :value="room.id"
                >
                  {{ room.roomNumber }}
                </option>
              </select>
            </label>
            <label>
              <span>Lernfeld oder Fach</span>
              <InputText v-model="slotForm.learningField" maxlength="100" />
            </label>
            <label>
              <span>Zuständige Lehrkraft</span>
              <select v-model="slotForm.teacherId">
                <option :value="null">Ohne Zuordnung</option>
                <option
                  v-for="teacher in overview.teachers ?? []"
                  :key="teacher.id"
                  :value="teacher.id"
                >
                  {{ teacher.name }}
                </option>
              </select>
            </label>
            <Button
              type="submit"
              label="Unterrichtszeit speichern"
              :disabled="busy || selectedTimetableId === null"
            />
            <Button
              v-if="editingSlotId !== null"
              type="button"
              label="Abbrechen"
              severity="secondary"
              :disabled="busy"
              @click="resetSlotForm"
            />
          </form>

          <form class="planning__form" @submit.prevent="submitTimetable">
            <h3>Neuer Stundenplan</h3>
            <label>
              <span>Name</span>
              <InputText
                v-model="timetableForm.name"
                maxlength="150"
                required
              />
            </label>
            <Button
              type="submit"
              label="Stundenplan anlegen"
              severity="secondary"
              :disabled="busy"
            />
          </form>
        </div>
      </article>

      <!-- Blockpläne mit Blöcken und zugeordneten Klassen -->
      <article class="planning__card">
        <header class="planning__card-header">
          <h2>Blockpläne</h2>
          <label class="planning__inline">
            <span>Blockplan</span>
            <select
              v-model="selectedBlockPlanId"
              :disabled="!overview.blockPlans.length"
            >
              <option
                v-for="plan in overview.blockPlans"
                :key="plan.id"
                :value="plan.id"
              >
                {{ plan.name }}
              </option>
            </select>
          </label>
        </header>

        <dl v-if="selectedBlockPlan" class="planning__facts">
          <div>
            <dt>Zeitraum</dt>
            <dd>
              {{ formatDate(selectedBlockPlan.startsOn) }} –
              {{ formatDate(selectedBlockPlan.endsOn) }}
            </dd>
          </div>
          <div>
            <dt>Löschung der Anwesenheitsdaten ab</dt>
            <dd>{{ formatDate(selectedBlockPlan.deleteAfter) }}</dd>
          </div>
        </dl>

        <div class="planning__table-scroll">
          <table class="planning__table">
            <caption class="planning__sr-only">
              Blöcke des gewählten Blockplans
            </caption>
            <thead>
              <tr>
                <th scope="col">Beginn</th>
                <th scope="col">Ende</th>
                <th scope="col">Klassen</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="block in selectedBlockPlan?.blocks ?? []"
                :key="block.id"
              >
                <td>{{ formatDate(block.startsOn) }}</td>
                <td>{{ formatDate(block.endsOn) }}</td>
                <td>{{ classCodes(block.classIds) }}</td>
              </tr>
              <tr v-if="!selectedBlockPlan?.blocks.length">
                <td colspan="3" class="planning__empty">
                  Noch keine Blöcke vorhanden.
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="planning__forms">
          <form class="planning__form" @submit.prevent="submitBlock">
            <h3>Block hinzufügen</h3>
            <div class="planning__pair">
              <label>
                <span>Beginn</span>
                <GermanDateInput
                  v-model="blockForm.startsOn"
                  label="Beginn"
                  required
                />
              </label>
              <label>
                <span>Ende</span>
                <GermanDateInput
                  v-model="blockForm.endsOn"
                  label="Ende"
                  required
                />
              </label>
            </div>
            <Button
              type="submit"
              label="Block speichern"
              :disabled="busy || selectedBlockPlanId === null"
            />
          </form>

          <form class="planning__form" @submit.prevent="submitBlockPlan">
            <h3>Neuer Blockplan</h3>
            <label>
              <span>Name</span>
              <InputText
                v-model="blockPlanForm.name"
                maxlength="150"
                required
              />
            </label>
            <div class="planning__pair">
              <label>
                <span>Beginn</span>
                <GermanDateInput
                  v-model="blockPlanForm.startsOn"
                  label="Beginn"
                  required
                />
              </label>
              <label>
                <span>Ende</span>
                <GermanDateInput
                  v-model="blockPlanForm.endsOn"
                  label="Ende"
                  required
                />
              </label>
            </div>
            <Button
              type="submit"
              label="Blockplan anlegen"
              severity="secondary"
              :disabled="busy"
            />
          </form>
        </div>
      </article>

      <!-- Zuordnung einer Klasse zu Blöcken, Kontenreset und Planwechsel -->
      <article class="planning__card">
        <header class="planning__card-header">
          <h2>Klassenplanung</h2>
          <label class="planning__inline">
            <span>Klasse</span>
            <select
              v-model="selectedClassId"
              :disabled="!overview.classes.length"
            >
              <option
                v-for="schoolClass in overview.classes"
                :key="schoolClass.id"
                :value="schoolClass.id"
              >
                {{ schoolClass.classCode }}
              </option>
            </select>
          </label>
        </header>

        <template v-if="selectedClass && classPlan">
          <dl class="planning__facts">
            <div>
              <dt>Aktueller Blockplan</dt>
              <dd>
                {{ classPlan.name }} ({{ formatDate(classPlan.startsOn) }} –
                {{ formatDate(classPlan.endsOn) }})
              </dd>
            </div>
            <div>
              <dt>Stundenplan</dt>
              <dd>{{ classTimetable?.name ?? '—' }}</dd>
            </div>
          </dl>

          <div class="planning__forms">
            <form class="planning__form" @submit.prevent="submitAssignment">
              <h3>Block zuordnen</h3>
              <label>
                <span>Block des aktuellen Blockplans</span>
                <select
                  v-model="assignmentForm.blockId"
                  required
                  :disabled="!assignableBlocks.length"
                >
                  <option
                    v-for="block in assignableBlocks"
                    :key="block.id"
                    :value="block.id"
                  >
                    {{ formatDate(block.startsOn) }} –
                    {{ formatDate(block.endsOn) }}
                  </option>
                </select>
              </label>
              <Button
                type="submit"
                label="Zuordnen"
                :disabled="busy || assignmentForm.blockId === null"
              />
            </form>

            <form class="planning__form" @submit.prevent="submitPlanChange">
              <h3>Blockplan wechseln</h3>
              <p class="planning__note">
                Erst nach Ende aller Blöcke möglich. Die Konten werden dabei
                abschließend zurückgesetzt; Klasse und Schüler bleiben erhalten.
              </p>
              <label>
                <span>Folgeplan</span>
                <select
                  v-model="planChangeForm.nextBlockPlanId"
                  required
                  :disabled="!nextPlanCandidates.length"
                >
                  <option
                    v-for="plan in nextPlanCandidates"
                    :key="plan.id"
                    :value="plan.id"
                  >
                    {{ plan.name }} (ab {{ formatDate(plan.startsOn) }})
                  </option>
                </select>
              </label>
              <div class="planning__actions">
                <Button
                  type="submit"
                  label="Plan wechseln"
                  :disabled="busy || planChangeForm.nextBlockPlanId === null"
                />
                <Button
                  type="button"
                  label="Nur Konten zurücksetzen"
                  severity="secondary"
                  :disabled="busy"
                  @click="submitReset"
                />
              </div>
            </form>
          </div>
        </template>
        <p v-else class="planning__empty">Noch keine Klassen vorhanden.</p>
      </article>
    </template>

    <p v-else-if="auth.isAdmin" class="planning__hint">
      Planung wird geladen …
    </p>
  </section>
</template>

<style scoped>
.planning {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.planning h1 {
  margin: 0;
}

.planning__card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
}

.planning__card-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.planning__card h2 {
  margin: 0;
  font-size: 1.05rem;
  color: var(--ink);
}

.planning__form h3 {
  margin: 0;
  font-size: 0.95rem;
  color: var(--ink);
}

.planning__forms {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr));
  gap: 16px;
}

.planning__form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: var(--paper);
  border-radius: 8px;
}

.planning__form label,
.planning__inline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--ink);
}

.planning__inline {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

.planning__pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.planning select {
  font-weight: 400;
}

.planning__form :deep(input) {
  width: 100%;
}

.planning__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.planning__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 32px;
  margin: 0;
}

.planning__facts dt {
  font-size: 0.8rem;
  color: var(--muted);
}

.planning__facts dd {
  margin: 2px 0 0;
  font-weight: 600;
}

.planning__table-scroll {
  overflow-x: auto;
}

.planning__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.planning__table th,
.planning__table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  white-space: nowrap;
}

.planning__table th {
  color: var(--muted);
  font-size: 0.8rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.planning__empty,
.planning__hint,
.planning__note {
  margin: 0;
  color: var(--muted);
  font-size: 0.9rem;
}

.planning__note {
  font-size: 0.8rem;
}

.planning__message {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid #b6d7c0;
  border-radius: 6px;
  background: #f1f9f3;
  font-size: 0.9rem;
  color: #1f6a37;
}

.planning__message--error {
  border-color: #f0b4b4;
  background: #fdf2f2;
  color: #a12525;
}

.planning__sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

@media (max-width: 480px) {
  .planning__card {
    padding: 16px;
  }

  .planning__pair {
    grid-template-columns: 1fr;
  }
}
</style>
