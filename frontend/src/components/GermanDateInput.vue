<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import DatePicker from 'primevue/datepicker'
import InputText from 'primevue/inputtext'

import { parseGermanDate, toCalendarDate, toIsoDate } from '@/dateTime'

const props = defineProps<{
  label: string
  name?: string
  max?: string
  required?: boolean
  withDialog?: boolean
}>()
const value = defineModel<string>({ default: '' })

const picker = ref<{ $el: HTMLElement }>()
// Sichtbarer Hinweis, damit eine ungültige Eingabe nicht kommentarlos verschwindet
const hint = ref('')
// Abgelehnte Eingabe: Sie bleibt im Feld stehen, damit sich der Tippfehler
// korrigieren lässt. Der Kalender selbst kennt nur gültige Daten und würde das
// Feld beim nächsten Neuzeichnen leeren
const rejected = ref('')
const selected = computed(() => toCalendarDate(value.value))
const maxDate = computed(() => toCalendarDate(props.max ?? '') ?? undefined)

function setDate(date: unknown) {
  hint.value = ''
  if (date instanceof Date) value.value = toIsoDate(date)
  else if (date === null) value.value = ''
}

function validationMessage(text: string) {
  if (!text.trim()) return ''
  const iso = parseGermanDate(text)
  if (!iso) return 'Gib ein gültiges Datum im Format TT.MM.JJJJ ein.'
  if (props.max && iso > props.max)
    return 'Das Datum darf nicht in der Zukunft liegen.'
  return ''
}

function validate(event: Event) {
  const input = event.target as HTMLInputElement
  const message = validationMessage(input.value)
  input.setCustomValidity(message)
  rejected.value = ''
  if (!message) hint.value = ''
  if (!input.value) value.value = ''
}

async function finishTyping(event: { originalEvent: Event; value: string }) {
  const message = validationMessage(event.value)
  hint.value = message
  if (!message) return
  rejected.value = event.value
  value.value = ''
  await restoreRejected(event.originalEvent.target as HTMLInputElement)
}

async function restoreRejected(input: HTMLInputElement) {
  await nextTick()
  if (!rejected.value) return
  input.value = rejected.value
  input.setCustomValidity(validationMessage(rejected.value))
}

function chooseDate() {
  hint.value = ''
  rejected.value = ''
  picker.value?.$el.querySelector('input')?.setCustomValidity('')
}
</script>

<template>
  <span class="german-date">
    <DatePicker
      ref="picker"
      :model-value="selected"
      :max-date="maxDate"
      :aria-label="label"
      :required="required"
      :append-to="withDialog ? 'self' : 'body'"
      :show-on-focus="false"
      :pt="{ pcInputText: { root: { inputmode: 'text' } } }"
      date-format="dd.mm.yy"
      placeholder="TT.MM.JJJJ"
      show-icon
      fluid
      @update:model-value="setDate"
      @input="validate"
      @blur="finishTyping"
      @focus="restoreRejected($event.target as HTMLInputElement)"
      @date-select="chooseDate"
    />
    <InputText v-if="name" :name="name" :model-value="value" type="hidden" />
    <small v-if="hint" class="german-date__hint" role="alert">{{ hint }}</small>
  </span>
</template>

<style scoped>
.german-date {
  display: block;
  min-width: 0;
}

.german-date__hint {
  display: block;
  margin-top: 4px;
  color: var(--danger);
  font-size: 0.82rem;
  font-weight: 500;
}

.german-date :deep(.p-datepicker-input) {
  min-width: 0;
  min-height: 44px;
}

.german-date :deep(.p-datepicker-input:invalid:not(:placeholder-shown)) {
  border-color: var(--danger);
}
</style>
