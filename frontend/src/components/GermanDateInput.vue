<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import DatePicker from 'primevue/datepicker'
import InputText from 'primevue/inputtext'

import { parseGermanDate, toCalendarDate, toIsoDate } from '@/dateTime'

const props = defineProps<{
  label: string
  name?: string
  defaultValue?: string
  max?: string
  required?: boolean
  withDialog?: boolean
}>()
const value = defineModel<string>({ default: '' })
if (props.defaultValue) value.value = props.defaultValue

const picker = ref<InstanceType<typeof DatePicker>>()
const selected = computed(() => toCalendarDate(value.value))
const maxDate = computed(() => toCalendarDate(props.max ?? '') ?? undefined)

function setDate(date: unknown) {
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
  if (!input.value) value.value = ''
}

async function finishTyping(event: { originalEvent: Event; value: string }) {
  const message = validationMessage(event.value)
  if (!message) return
  value.value = ''
  await nextTick()
  const input = event.originalEvent.target as HTMLInputElement
  input.value = event.value
  input.setCustomValidity(message)
}

function chooseDate() {
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
      @date-select="chooseDate"
    />
    <InputText v-if="name" :name="name" :model-value="value" type="hidden" />
  </span>
</template>

<style scoped>
.german-date {
  display: block;
  min-width: 0;
}

.german-date :deep(.p-datepicker-input) {
  min-width: 0;
  min-height: 44px;
}

.german-date :deep(.p-datepicker-input:invalid:not(:placeholder-shown)) {
  border-color: #8c3038;
}
</style>
