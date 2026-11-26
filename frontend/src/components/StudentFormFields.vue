<script setup lang="ts">
import InputText from 'primevue/inputtext'
import { ref, watch } from 'vue'
import GermanDateInput from './GermanDateInput.vue'
import type { Student } from '@/api/students'

const props = defineProps<{ student?: Student }>()
const birthDate = ref('')
watch(
  () => props.student?.birthDate,
  (date) => {
    birthDate.value = date ?? ''
  },
  { immediate: true }
)
</script>

<template>
  <label>
    Vorname
    <InputText
      name="firstName"
      :default-value="student?.firstName"
      maxlength="100"
      required
    />
  </label>
  <label>
    Nachname
    <InputText
      name="lastName"
      :default-value="student?.lastName"
      maxlength="100"
      required
    />
  </label>
  <label>
    Geburtsdatum
    <GermanDateInput
      v-model="birthDate"
      label="Geburtsdatum"
      name="birthDate"
      required
    />
  </label>
</template>
