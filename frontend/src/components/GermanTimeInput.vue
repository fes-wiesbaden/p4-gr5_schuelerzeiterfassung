<script setup lang="ts">
import InputText from 'primevue/inputtext'
import { timePattern, timeWithSecondsPattern } from '@/dateTime'

defineProps<{ label: string; seconds?: boolean; required?: boolean }>()
const value = defineModel<string>({ default: '' })

function clearError(event: Event) {
  const field = event.target as HTMLInputElement
  field.setCustomValidity('')
}

function showError(event: Event) {
  const field = event.target as HTMLInputElement
  field.setCustomValidity(
    field.validity.valueMissing ? 'Gib eine Uhrzeit ein.' : field.title
  )
}
</script>

<template>
  <InputText
    v-model="value"
    type="text"
    class="german-time"
    :aria-label="label"
    :pattern="seconds ? timeWithSecondsPattern : timePattern"
    :title="
      seconds
        ? 'Uhrzeit im Format HH:MM oder HH:MM:SS eingeben.'
        : 'Uhrzeit im Format HH:MM eingeben.'
    "
    placeholder="HH:MM"
    :maxlength="seconds ? 8 : 5"
    :required="required"
    @input="clearError"
    @invalid="showError"
  />
</template>
