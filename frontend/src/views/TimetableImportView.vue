<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'

import PageError from '@/components/PageError.vue'
import GermanDateInput from '@/components/GermanDateInput.vue'
import GermanTimeInput from '@/components/GermanTimeInput.vue'
import { todayInBerlin } from '@/api/attendance'
import { classCodeKey } from '@/api/students'
import { formatDate, timePattern, toCalendarDate, toIsoDate } from '@/dateTime'
import TimetableGrid from '@/components/TimetableGrid.vue'
import { WEEKDAYS, type Weekday } from '@/api/planning'
import {
  importTimetable,
  timetableDraft,
  TimetableImportError,
  type DraftSlot,
  type ImportClass,
  type TimetableDraft
} from '@/api/timetableImport'
import { confirmAction } from '@/confirm'
import { showNotice } from '@/notice'

const router = useRouter()
const draft = timetableDraft
const saving = ref(false)
const error = ref('')
const failedSlot = ref<number | null>(null)
const created = ref('')
const createdClass = ref('')

// Zuordnung des Plans: bestehende Klasse, neue Klasse oder keine. Die
// Antworten aus dem Chat belegen die Felder vor
function nextMonday() {
  const date = toCalendarDate(todayInBerlin()) ?? new Date()
  date.setDate(date.getDate() + ((8 - date.getDay()) % 7 || 7))
  return toIsoDate(date)
}
const classMode = ref<'existing' | 'new' | 'none'>('none')
const classId = ref<number | null>(null)
const validFrom = ref('')
// Leer bedeutet: gilt bis auf Weiteres
const validUntil = ref('')
const newClassCode = ref('')
const classTeacherId = ref<number | null>(null)
const blockPlanId = ref<number | null>(null)

function fillFrom(current: TimetableDraft | undefined) {
  const target = current?.target
  classMode.value = target?.mode ?? 'none'
  classId.value =
    target?.mode === 'existing' ? target.classId : (current?.classId ?? null)
  validFrom.value =
    (target && target.mode !== 'none' && target.validFrom) || nextMonday()
  validUntil.value =
    (target && target.mode !== 'none' && target.validUntil) || ''
  newClassCode.value =
    target?.mode === 'new' ? target.classCode : (current?.classCode ?? '')
  classTeacherId.value =
    target?.mode === 'new' ? (target.classTeacherId ?? null) : null
  blockPlanId.value =
    target?.mode === 'new' ? (target.blockPlanId ?? null) : null
}
fillFrom(draft.value)

// Ein neuer Entwurf aus dem Chat ersetzt Auswahl und Erfolgsanzeige, auch
// wenn die Seite schon offen ist
watch(draft, (next, previous) => {
  if (!next || next === previous) return
  created.value = ''
  createdClass.value = ''
  error.value = ''
  failedSlot.value = null
  fillFrom(next)
})

// Die Auswahl gehört zum Entwurf und übersteht so ein Neuladen der Seite
watch(
  [
    classMode,
    classId,
    validFrom,
    validUntil,
    newClassCode,
    classTeacherId,
    blockPlanId
  ],
  () => {
    if (!draft.value) return
    draft.value.target =
      classMode.value === 'existing' && classId.value !== null
        ? {
            mode: 'existing',
            classId: classId.value,
            validFrom: validFrom.value,
            validUntil: validUntil.value || undefined
          }
        : classMode.value === 'new'
          ? {
              mode: 'new',
              classCode: newClassCode.value,
              classTeacherId: classTeacherId.value,
              blockPlanId: blockPlanId.value,
              validFrom: validFrom.value,
              validUntil: validUntil.value || undefined
            }
          : { mode: 'none' }
  }
)

const selectedClassCode = computed(
  () =>
    draft.value?.classes.find((entry) => entry.id === classId.value)?.classCode
)

// Liefert die Klassenangaben für die Übernahme oder einen Hinweis, was fehlt
function classSelection(): { value: ImportClass } | { problem: string } {
  if (classMode.value === 'none') return { value: null }
  if (!validFrom.value) return { problem: 'Wähle, ab wann der Plan gilt.' }
  // Eine neue Klasse hat keine Vergangenheit, ihr Plan darf sofort gelten
  if (classMode.value === 'existing' && validFrom.value <= todayInBerlin())
    return {
      problem:
        'Der neue Stundenplan kann frühestens ab morgen gelten. Wähle ein späteres Datum.'
    }
  if (validUntil.value && validUntil.value < validFrom.value)
    return {
      problem: 'Das Ende der Gültigkeit darf nicht vor dem Beginn liegen.'
    }
  if (classMode.value === 'existing') {
    if (classId.value === null) return { problem: 'Wähle die Klasse aus.' }
    return {
      value: {
        id: classId.value,
        validFrom: validFrom.value,
        validUntil: validUntil.value || null
      }
    }
  }
  const classCode = newClassCode.value.trim()
  if (!classCode) return { problem: 'Trag das Kürzel der neuen Klasse ein.' }
  if (
    draft.value?.classes.some(
      (entry) => classCodeKey(entry.classCode) === classCodeKey(classCode)
    )
  )
    return {
      problem: `Die Klasse ${classCode} gibt es bereits. Wähle „Bestehende Klasse“.`
    }
  if (classTeacherId.value === null)
    return { problem: 'Wähle die Klassenleitung der neuen Klasse.' }
  if (blockPlanId.value === null)
    return { problem: 'Wähle den Blockplan der neuen Klasse.' }
  return {
    value: {
      classCode,
      classTeacherId: classTeacherId.value,
      blockPlanId: blockPlanId.value,
      validFrom: validFrom.value,
      validUntil: validUntil.value || null
    }
  }
}

const periodText = computed(() =>
  validUntil.value
    ? `vom ${formatDate(validFrom.value)} bis ${formatDate(validUntil.value)}`
    : `ab dem ${formatDate(validFrom.value)} bis auf Weiteres`
)
const clock = new RegExp(`^${timePattern}$`)

const order = new Map(WEEKDAYS.map((day, index) => [day.value, index]))

// Beschreibt, was an einer Zeile noch fehlt. Leer bedeutet: übernahmebereit
function problem(slot: DraftSlot): string {
  if (!clock.test(slot.startsAt) || !clock.test(slot.endsAt))
    return 'Beginn und Ende als Uhrzeit (HH:MM) eintragen.'
  if (slot.startsAt >= slot.endsAt)
    return 'Das Ende muss nach dem Beginn liegen.'
  if (slot.roomId === null)
    return slot.roomNumber
      ? `Raum „${slot.roomNumber}“ ist nicht in den Stammdaten – bitte einen Raum wählen.`
      : 'Bitte einen Raum wählen.'
  return ''
}

const overlaps = computed(() => {
  const slots = draft.value?.slots ?? []
  const hit = new Set<number>()
  slots.forEach((slot, index) => {
    if (problem(slot)) return
    slots.forEach((other, otherIndex) => {
      if (otherIndex <= index || problem(other)) return
      if (
        slot.weekday === other.weekday &&
        slot.startsAt < other.endsAt &&
        other.startsAt < slot.endsAt
      ) {
        hit.add(index)
        hit.add(otherIndex)
      }
    })
  })
  return hit
})

function rowProblem(slot: DraftSlot, index: number) {
  return (
    problem(slot) ||
    (overlaps.value.has(index)
      ? 'Überschneidet sich mit einer anderen Unterrichtszeit am selben Tag.'
      : '')
  )
}

const openProblems = computed(
  () =>
    (draft.value?.slots ?? []).filter((slot, index) => rowProblem(slot, index))
      .length
)

// Vorschau im gewohnten Wochenraster; unvollständige Zeilen fehlen dort noch
const previewDays = computed(() =>
  WEEKDAYS.map((day) => ({
    lessons: (draft.value?.slots ?? [])
      .filter(
        (slot) =>
          slot.weekday === day.value &&
          clock.test(slot.startsAt) &&
          clock.test(slot.endsAt) &&
          slot.startsAt < slot.endsAt
      )
      .map((slot) => ({
        startsAt: `${slot.startsAt}:00`,
        endsAt: `${slot.endsAt}:00`,
        subject: slot.subject,
        roomNumber:
          draft.value?.rooms.find((room) => room.id === slot.roomId)
            ?.roomNumber ??
          slot.roomNumber ??
          '',
        teacherName:
          draft.value?.teachers.find((teacher) => teacher.id === slot.teacherId)
            ?.name ?? slot.teacherName
      }))
  }))
)

// Zeitraster der Vorschau aus den Zeiten des Entwurfs: Jeder Abschnitt
// zwischen zwei Grenzen ist eine Zeile, kurze Abschnitte gelten als Pause.
// Absicht: Der 45-Minuten-Rückfall von TimetableGrid zerlegt Blöcke mit Pausen in unruhige Zeilen
const previewPeriods = computed(() => {
  const points = new Set<string>()
  for (const day of previewDays.value)
    for (const lesson of day.lessons) {
      points.add(lesson.startsAt)
      points.add(lesson.endsAt)
    }
  const sorted = [...points].sort()
  const minutes = (time: string) =>
    Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))
  let number = 0
  return sorted.slice(0, -1).flatMap((startsAt, index) => {
    const endsAt = sorted[index + 1]!
    return minutes(endsAt) - minutes(startsAt) < 30
      ? []
      : [{ number: ++number, startsAt, endsAt }]
  })
})

function sortSlots() {
  draft.value?.slots.sort(
    (left, right) =>
      (order.get(left.weekday) ?? 0) - (order.get(right.weekday) ?? 0) ||
      left.startsAt.localeCompare(right.startsAt)
  )
}

function addSlot() {
  failedSlot.value = null
  draft.value?.slots.push({
    weekday: 'MONTAG' as Weekday,
    startsAt: '',
    endsAt: '',
    subject: null,
    teacherName: null,
    roomNumber: null,
    teacherId: null,
    roomId: null
  })
}

function removeSlot(index: number) {
  failedSlot.value = null
  draft.value?.slots.splice(index, 1)
}

async function discard() {
  if (
    !(await confirmAction({
      title: 'Entwurf verwerfen?',
      message:
        'Der gelesene Stundenplan und deine Korrekturen gehen verloren. Gespeichert wurde bisher nichts.',
      confirmLabel: 'Verwerfen',
      danger: true
    }))
  )
    return
  draft.value = undefined
  await router.push({ name: 'planung' })
}

async function save() {
  const current = draft.value
  if (!current || saving.value) return
  error.value = ''
  failedSlot.value = null
  const name = current.name.trim()
  if (!name) {
    error.value = 'Gib dem Stundenplan einen Namen.'
    return
  }
  if (!current.slots.length) {
    error.value = 'Der Entwurf enthält noch keine Unterrichtszeit.'
    return
  }
  if (openProblems.value) {
    error.value = `Bei ${openProblems.value} ${openProblems.value === 1 ? 'Unterrichtszeit fehlt' : 'Unterrichtszeiten fehlen'} noch Angaben. Die betroffenen Zeilen sind markiert.`
    return
  }
  const selection = classSelection()
  if ('problem' in selection) {
    error.value = selection.problem
    return
  }
  const schoolClass = selection.value
  const classText =
    schoolClass === null
      ? 'Er wird keiner Klasse zugeordnet; bestehende Stundenpläne und Klassen ändern sich nicht.'
      : 'id' in schoolClass
        ? `Für die Klasse ${selectedClassCode.value} gilt er ${periodText.value}. Davor${schoolClass.validUntil ? ' und danach' : ''} gilt ihr bisheriger Plan; vergangene Tage ändern sich nicht.`
        : `Zusätzlich wird die Klasse ${schoolClass.classCode} neu angelegt; der Plan gilt für sie ${periodText.value}.`
  if (
    !(await confirmAction({
      title:
        schoolClass && 'classCode' in schoolClass
          ? 'Stundenplan und Klasse anlegen?'
          : 'Stundenplan anlegen?',
      message: `„${name}“ wird mit ${current.slots.length} Unterrichtszeiten als neuer Stundenplan angelegt.\n${classText}`,
      confirmLabel: 'Jetzt anlegen'
    }))
  )
    return
  saving.value = true
  try {
    await importTimetable(
      name,
      current.slots.map((slot) => ({
        weekday: slot.weekday,
        startsAt: slot.startsAt,
        endsAt: slot.endsAt,
        roomId: slot.roomId!,
        subject: slot.subject?.trim() || null,
        teacherId: slot.teacherId
      })),
      schoolClass
    )
    createdClass.value =
      schoolClass === null
        ? ''
        : 'id' in schoolClass
          ? `Für die Klasse ${selectedClassCode.value} gilt er ${periodText.value}.`
          : `Die Klasse ${schoolClass.classCode} wurde neu angelegt; der Plan gilt für sie ${periodText.value}.`
    created.value = name
    draft.value = undefined
    showNotice(`Stundenplan „${name}“ angelegt.`)
    window.dispatchEvent(new Event('planning:changed'))
    window.dispatchEvent(new Event('management:changed'))
  } catch (failure) {
    if (failure instanceof TimetableImportError) {
      failedSlot.value = failure.slotIndex
      error.value =
        failure.slotIndex === null
          ? failure.message
          : `Zeile ${failure.slotIndex + 1}: ${failure.message}`
    } else {
      error.value =
        'Der Stundenplan konnte nicht angelegt werden. Prüfe die Verbindung.'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="import">
    <RouterLink class="import__back" :to="{ name: 'planung' }">
      <i class="pi pi-arrow-left" aria-hidden="true" /> Zum Stunden- und
      Blockplan
    </RouterLink>
    <h1>Stundenplan-Entwurf</h1>

    <div v-if="created" class="import__done" role="status">
      <h2>
        <i class="pi pi-check-circle" aria-hidden="true" /> „{{ created }}“ ist
        angelegt
      </h2>
      <p v-if="createdClass">{{ createdClass }}</p>
      <p>
        Du findest den Stundenplan unter „Stunden- und Blockplan“ im Abschnitt
        „Stundenpläne bearbeiten“. Dort lässt er sich weiter anpassen.
      </p>
    </div>

    <p v-else-if="!draft" class="import__empty">
      Es ist kein Entwurf geöffnet. Lade im Klassenlotsen (unten rechts) über
      „Stundenplan aus Bild“ ein Foto oder einen Screenshot hoch – daraus
      entsteht ein Entwurf, den du hier prüfst und bestätigst.
    </p>

    <template v-else>
      <p class="import__lead">
        <i class="pi pi-info-circle" aria-hidden="true" />
        Klassenlotse hat diesen Plan vom Bild abgelesen.
        <strong>Noch ist nichts gespeichert.</strong> Prüfe die Angaben,
        korrigiere sie bei Bedarf und bestätige unten die Übernahme.
      </p>

      <ul v-if="draft.notes.length" class="import__notes">
        <li v-for="(note, index) in draft.notes" :key="index">{{ note }}</li>
      </ul>

      <PageError :message="error" />

      <label class="import__name">
        Name des Stundenplans
        <InputText v-model="draft.name" maxlength="150" :disabled="saving" />
      </label>

      <article class="import__card">
        <h2>Für welche Klasse?</h2>
        <label class="import__mode">
          Zuordnung
          <select v-model="classMode" :disabled="saving">
            <option value="existing">Bestehende Klasse</option>
            <option value="new">Neue Klasse anlegen</option>
            <option value="none">Keiner Klasse zuordnen</option>
          </select>
        </label>
        <div v-if="classMode !== 'none'" class="import__class-fields">
          <label v-if="classMode === 'existing'">
            Klasse
            <select v-model="classId" :disabled="saving">
              <option :value="null" disabled>Bitte wählen</option>
              <option
                v-for="entry in draft.classes"
                :key="entry.id"
                :value="entry.id"
              >
                {{ entry.classCode }}
              </option>
            </select>
          </label>
          <template v-else>
            <label>
              Klassenkürzel
              <InputText
                v-model="newClassCode"
                maxlength="30"
                :disabled="saving"
              />
            </label>
            <label>
              Klassenleitung
              <select v-model="classTeacherId" :disabled="saving">
                <option :value="null" disabled>Bitte wählen</option>
                <option
                  v-for="teacher in draft.teachers"
                  :key="teacher.id"
                  :value="teacher.id"
                >
                  {{ teacher.name }}
                </option>
              </select>
            </label>
            <label>
              Blockplan
              <select v-model="blockPlanId" :disabled="saving">
                <option :value="null" disabled>Bitte wählen</option>
                <option
                  v-for="plan in draft.blockPlans"
                  :key="plan.id"
                  :value="plan.id"
                >
                  {{ plan.name }} ({{ formatDate(plan.startsOn) }} –
                  {{ formatDate(plan.endsOn) }})
                </option>
              </select>
            </label>
          </template>
          <label>
            Gilt ab
            <GermanDateInput v-model="validFrom" label="Gilt ab" />
          </label>
          <label>
            Gilt bis (leer = bis auf Weiteres)
            <GermanDateInput v-model="validUntil" label="Gilt bis" />
          </label>
          <p v-if="classMode === 'existing'">
            Außerhalb dieses Zeitraums und für alle vergangenen Tage gilt der
            bisherige Stundenplan der Klasse – bereits erfasste Fehlzeiten
            ändern sich nicht.
          </p>
          <p v-else>
            Die Klasse wird zusammen mit dem Stundenplan angelegt. Schüler und
            Unterrichtsblöcke ordnest du danach wie gewohnt zu.
          </p>
        </div>
        <p v-else class="import__class-note">
          Es entsteht nur der Stundenplan. Du kannst ihn später beim Anlegen
          einer Klasse auswählen.
        </p>
      </article>

      <article class="import__card">
        <h2>Vorschau</h2>
        <TimetableGrid
          :days="previewDays"
          :periods="previewPeriods"
          caption="Vorschau des Entwurfs"
        />
      </article>

      <article class="import__card">
        <header class="import__card-head">
          <h2>Unterrichtszeiten prüfen ({{ draft.slots.length }})</h2>
          <Button
            type="button"
            label="Nach Tag und Uhrzeit sortieren"
            size="small"
            text
            :disabled="saving"
            @click="sortSlots"
          />
        </header>
        <p v-if="openProblems" class="import__open">
          Bei {{ openProblems }}
          {{ openProblems === 1 ? 'Zeile fehlt' : 'Zeilen fehlen' }} noch
          Angaben – sie sind markiert.
        </p>
        <div class="import__rows">
          <fieldset
            v-for="(slot, index) in draft.slots"
            :key="index"
            class="import__row"
            :class="{
              'import__row--problem':
                rowProblem(slot, index) || failedSlot === index
            }"
            :disabled="saving"
          >
            <legend class="visually-hidden">Zeile {{ index + 1 }}</legend>
            <label>
              Wochentag
              <select v-model="slot.weekday">
                <option
                  v-for="day in WEEKDAYS"
                  :key="day.value"
                  :value="day.value"
                >
                  {{ day.label }}
                </option>
              </select>
            </label>
            <label>
              Beginn
              <GermanTimeInput v-model="slot.startsAt" label="Beginn" />
            </label>
            <label>
              Ende
              <GermanTimeInput v-model="slot.endsAt" label="Ende" />
            </label>
            <label class="import__wide">
              Fach oder Lernfeld
              <InputText
                :model-value="slot.subject ?? ''"
                maxlength="80"
                @update:model-value="slot.subject = $event || null"
              />
            </label>
            <label class="import__wide">
              Lehrkraft
              <select v-model="slot.teacherId">
                <option :value="null">
                  {{
                    slot.teacherName
                      ? `Nicht zugeordnet (gelesen: ${slot.teacherName})`
                      : 'Keine Angabe'
                  }}
                </option>
                <option
                  v-for="teacher in draft.teachers"
                  :key="teacher.id"
                  :value="teacher.id"
                >
                  {{ teacher.name }}
                </option>
              </select>
            </label>
            <label>
              Raum
              <select v-model="slot.roomId">
                <option :value="null" disabled>
                  {{
                    slot.roomNumber
                      ? `Bitte wählen (gelesen: ${slot.roomNumber})`
                      : 'Bitte wählen'
                  }}
                </option>
                <option
                  v-for="room in draft.rooms"
                  :key="room.id"
                  :value="room.id"
                >
                  {{ room.roomNumber }}
                </option>
              </select>
            </label>
            <Button
              type="button"
              icon="pi pi-trash"
              severity="danger"
              text
              :aria-label="`Zeile ${index + 1} entfernen`"
              @click="removeSlot(index)"
            />
            <p v-if="rowProblem(slot, index)" class="import__hint">
              <i class="pi pi-exclamation-triangle" aria-hidden="true" />
              {{ rowProblem(slot, index) }}
            </p>
          </fieldset>
        </div>
        <Button
          type="button"
          label="Unterrichtszeit hinzufügen"
          icon="pi pi-plus"
          outlined
          size="small"
          :disabled="saving"
          @click="addSlot"
        />
      </article>

      <div class="import__actions">
        <Button
          type="button"
          :label="
            classMode === 'new'
              ? 'Stundenplan und Klasse anlegen'
              : 'Stundenplan anlegen'
          "
          icon="pi pi-check"
          :loading="saving"
          @click="save"
        />
        <Button
          type="button"
          label="Entwurf verwerfen"
          severity="secondary"
          text
          :disabled="saving"
          @click="discard"
        />
        <p>
          Gespeichert wird erst nach deiner Bestätigung – vollständig oder gar
          nicht.
        </p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.import {
  display: grid;
  gap: 18px;
  min-width: 0;
}

.import h1,
.import h2,
.import p {
  margin: 0;
}

.import h2 {
  font-size: 1.15rem;
}

.import__back {
  width: fit-content;
  color: var(--accent-ink);
  text-underline-offset: 3px;
}

.import__lead,
.import__empty,
.import__done {
  max-width: 52rem;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  line-height: 1.5;
}

.import__done {
  display: grid;
  gap: 8px;
  border-color: #bfe8cc;
  background: #e7f6ec;
  color: #14602f;
}

.import__notes {
  max-width: 52rem;
  margin: 0;
  padding: 12px 16px 12px 34px;
  border: 1px solid #f1d9a8;
  border-radius: var(--radius);
  background: #fff5e7;
  color: #6b3a00;
  font-size: 0.92rem;
  line-height: 1.45;
}

.import__name {
  display: grid;
  gap: 6px;
  max-width: 32rem;
  font-weight: 650;
}

.import__card {
  display: grid;
  gap: 14px;
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.import__mode {
  display: grid;
  gap: 4px;
  max-width: 18rem;
  font-size: 0.82rem;
  font-weight: 650;
}

.import__mode select {
  min-height: 40px;
  font-weight: 400;
}

.import__class-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  align-items: end;
  gap: 10px 14px;
  max-width: 56rem;
}

.import__class-fields label {
  display: grid;
  gap: 4px;
  min-width: 0;
  font-size: 0.82rem;
  font-weight: 650;
}

.import__class-fields :is(select, input) {
  width: 100%;
  min-height: 40px;
  font-weight: 400;
}

.import__class-fields p,
.import__class-note {
  grid-column: 1 / -1;
  color: var(--muted);
  font-size: 0.88rem;
  line-height: 1.4;
}

.import__card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.import__open {
  color: var(--danger);
  font-weight: 600;
}

.import__rows {
  display: grid;
  gap: 10px;
}

.import__row {
  display: grid;
  grid-template-columns:
    9rem 6rem 6rem minmax(9rem, 1fr) minmax(11rem, 1fr)
    10rem auto;
  align-items: end;
  gap: 8px 12px;
  min-width: 0;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--paper);
}

.import__row--problem {
  border-color: var(--danger-line);
  background: var(--danger-soft);
}

.import__row label {
  display: grid;
  gap: 4px;
  min-width: 0;
  font-size: 0.8rem;
  font-weight: 650;
}

.import__row :is(select, input) {
  width: 100%;
  min-width: 0;
  min-height: 40px;
  font-size: 0.92rem;
  font-weight: 400;
}

.import__hint {
  grid-column: 1 / -1;
  color: var(--danger);
  font-size: 0.86rem;
}

.import__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 14px;
}

.import__actions p {
  flex: 1 1 16rem;
  color: var(--muted);
  font-size: 0.88rem;
}

@media (max-width: 70rem) {
  .import__row {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .import__wide {
    grid-column: span 3;
  }
}

@media (max-width: 36rem) {
  .import__row {
    grid-template-columns: minmax(0, 1fr);
  }

  .import__wide {
    grid-column: auto;
  }
}
</style>
