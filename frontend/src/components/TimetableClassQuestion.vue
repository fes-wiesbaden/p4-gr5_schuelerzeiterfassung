<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'

import { todayInBerlin } from '@/api/attendance'
import { classCodeKey } from '@/api/students'
import type { ClassTarget, TimetableDraft } from '@/api/timetableImport'
import { formatDate, toCalendarDate, toIsoDate } from '@/dateTime'
import GermanDateInput from './GermanDateInput.vue'

const props = defineProps<{ draft: TimetableDraft }>()
const emit = defineEmits<{ done: [target: ClassTarget]; cancel: [] }>()

// Schritt 1 klärt die Klasse, Schritt 2 nur bei einer noch unbekannten Klasse,
// ob sie gleich mit angelegt werden soll, Schritt 3 den Gültigkeitszeitraum
const step = ref<'confirm' | 'create' | 'period'>('confirm')
const code = ref(props.draft.classCode ?? '')
const createClass = ref(false)
const field = ref<{ $el: HTMLInputElement }>()
const heading = ref<HTMLElement>()
const hint = ref('')
const today = todayInBerlin()

function shiftDays(iso: string, days: number) {
  const date = toCalendarDate(iso) ?? new Date()
  date.setDate(date.getDate() + days)
  return toIsoDate(date)
}

// Vorschlag: die Woche vom Bild, sofern sie noch vor uns liegt, sonst morgen
const validFrom = ref(
  props.draft.weekStartsOn && props.draft.weekStartsOn > today
    ? props.draft.weekStartsOn
    : shiftDays(today, 1)
)
const validUntil = ref('')

const existing = computed(() =>
  props.draft.classes.find(
    (entry) => classCodeKey(entry.classCode) === classCodeKey(code.value)
  )
)
// Noch nicht beendete Unterrichtsblöcke der Klasse als schnelle Auswahl
const blocks = computed(() =>
  (existing.value?.blocks ?? []).filter((block) => block.endsOn > today)
)

async function show(next: 'confirm' | 'create' | 'period') {
  hint.value = ''
  step.value = next
  await nextTick()
  heading.value?.focus()
}

function confirmClass() {
  const value = code.value.trim()
  if (!value) {
    hint.value =
      'Trag das Klassenkürzel ein oder wähle „Keiner Klasse zuordnen“.'
    field.value?.$el.focus()
    return
  }
  createClass.value = false
  void show(existing.value ? 'period' : 'create')
}

function chooseBlock(block: { startsOn: string; endsOn: string }) {
  validFrom.value =
    block.startsOn > today ? block.startsOn : shiftDays(today, 1)
  validUntil.value = block.endsOn
  hint.value = ''
}

function finish() {
  const forExisting = Boolean(existing.value) && !createClass.value
  // Eine neue Klasse hat keine Vergangenheit, ihr Plan darf sofort gelten
  if (!validFrom.value || (forExisting && validFrom.value <= today)) {
    hint.value =
      'Der Plan kann frühestens ab morgen gelten – vergangene Tage bleiben unverändert.'
    return
  }
  if (validUntil.value && validUntil.value < validFrom.value) {
    hint.value = 'Das Ende darf nicht vor dem Beginn liegen.'
    return
  }
  const period = {
    validFrom: validFrom.value,
    validUntil: validUntil.value || undefined
  }
  emit(
    'done',
    forExisting && existing.value
      ? { mode: 'existing', classId: existing.value.id, ...period }
      : { mode: 'new', classCode: code.value.trim(), ...period }
  )
}

onMounted(() => heading.value?.focus())
</script>

<template>
  <section class="class-question" aria-labelledby="class-question-title">
    <template v-if="step === 'confirm'">
      <h3 id="class-question-title" ref="heading" tabindex="-1">
        {{
          draft.classCode
            ? `Ich habe die Klasse „${draft.classCode}“ erkannt. Stimmt das?`
            : 'Ich konnte keine Klasse erkennen. Für welche Klasse ist der Stundenplan?'
        }}
      </h3>
      <label>
        Klasse
        <InputText
          ref="field"
          v-model="code"
          list="class-question-classes"
          maxlength="30"
          autocomplete="off"
          @keydown.enter.prevent="confirmClass"
        />
      </label>
      <datalist id="class-question-classes">
        <option
          v-for="entry in draft.classes"
          :key="entry.id"
          :value="entry.classCode"
        />
      </datalist>
      <p class="class-question__note">
        {{
          !code.trim()
            ? 'Du kannst ein Kürzel eintippen oder eine vorhandene Klasse wählen.'
            : existing
              ? `Die Klasse ${existing.classCode} gibt es bereits. Der neue Stundenplan wird ihr zugeordnet.`
              : 'Diese Klasse gibt es noch nicht.'
        }}
      </p>
      <p v-if="hint" class="class-question__hint" role="alert">{{ hint }}</p>
      <div class="class-question__actions">
        <Button
          type="button"
          :label="
            draft.classCode && code.trim() === draft.classCode
              ? 'Ja, stimmt'
              : 'Diese Klasse verwenden'
          "
          size="small"
          @click="confirmClass"
        />
        <Button
          type="button"
          label="Keiner Klasse zuordnen"
          size="small"
          outlined
          @click="emit('done', { mode: 'none' })"
        />
        <Button
          type="button"
          label="Abbrechen"
          size="small"
          text
          @click="emit('cancel')"
        />
      </div>
    </template>
    <template v-else-if="step === 'create'">
      <h3 id="class-question-title" ref="heading" tabindex="-1">
        Die Klasse „{{ code.trim() }}“ gibt es noch nicht. Soll ich sie direkt
        mit anlegen?
      </h3>
      <p class="class-question__note">
        Klassenleitung und Blockplan wählst du gleich im Entwurf. Angelegt wird
        alles erst, wenn du den Entwurf bestätigst.
      </p>
      <div class="class-question__actions">
        <Button
          type="button"
          label="Ja, Klasse mit anlegen"
          size="small"
          @click="((createClass = true), show('period'))"
        />
        <Button
          type="button"
          label="Nein, nur den Stundenplan"
          size="small"
          outlined
          @click="emit('done', { mode: 'none' })"
        />
        <Button
          type="button"
          label="Zurück"
          size="small"
          text
          @click="show('confirm')"
        />
      </div>
    </template>
    <template v-else>
      <h3 id="class-question-title" ref="heading" tabindex="-1">
        Wie lange gilt dieser Stundenplan für die Klasse „{{
          existing && !createClass ? existing.classCode : code.trim()
        }}“?
      </h3>
      <div v-if="blocks.length && !createClass" class="class-question__blocks">
        <span>Unterrichtsblock übernehmen:</span>
        <Button
          v-for="block in blocks"
          :key="block.startsOn"
          type="button"
          :label="`${formatDate(block.startsOn)} – ${formatDate(block.endsOn)}`"
          size="small"
          outlined
          @click="chooseBlock(block)"
        />
      </div>
      <div class="class-question__period">
        <label>
          Gilt ab
          <GermanDateInput v-model="validFrom" label="Gilt ab" with-dialog />
        </label>
        <label>
          Gilt bis (leer = bis auf Weiteres)
          <GermanDateInput v-model="validUntil" label="Gilt bis" with-dialog />
        </label>
      </div>
      <p class="class-question__note">
        {{
          validUntil
            ? 'Nach dem Enddatum gilt wieder der Stundenplan, der vorher galt.'
            : 'Ohne Enddatum gilt der Plan, bis ein neuer eingetragen wird.'
        }}
        Vergangene Tage bleiben unverändert.
      </p>
      <p v-if="hint" class="class-question__hint" role="alert">{{ hint }}</p>
      <div class="class-question__actions">
        <Button
          type="button"
          label="Weiter zum Entwurf"
          size="small"
          @click="finish"
        />
        <Button
          type="button"
          label="Zurück"
          size="small"
          text
          @click="show('confirm')"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.class-question {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--accent-soft);
}

.class-question h3 {
  margin: 0;
  font-size: 0.98rem;
  line-height: 1.35;
}

.class-question label {
  display: grid;
  gap: 4px;
  min-width: 0;
  font-size: 0.82rem;
  font-weight: 650;
}

.class-question :deep(input) {
  min-height: 40px;
}

.class-question__period {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.class-question__blocks {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 0.84rem;
  font-weight: 600;
}

.class-question__note,
.class-question__hint {
  margin: 0;
  color: var(--muted);
  font-size: 0.86rem;
  line-height: 1.4;
}

.class-question__hint {
  color: #8c3038;
}

.class-question__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
