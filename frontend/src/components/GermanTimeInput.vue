<script setup lang="ts">
import { ref, watch } from 'vue'
import InputText from 'primevue/inputtext'
import { timePattern, timeWithSecondsPattern } from '@/dateTime'

defineProps<{ label: string; seconds?: boolean; required?: boolean }>()
const value = defineModel<string>({ default: '' })
const field = ref<{ $el: HTMLInputElement }>()

// Auch ein per Programm gesetzter Wert hebt eine frühere Fehlermeldung auf
watch(value, () => field.value?.$el.setCustomValidity(''))

function showError(event: Event) {
  const input = event.target as HTMLInputElement
  input.setCustomValidity(
    input.validity.valueMissing ? 'Gib eine Uhrzeit ein.' : input.title
  )
}
</script>

<template>
  <InputText
    ref="field"
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
    @invalid="showError"
  />
</template>
