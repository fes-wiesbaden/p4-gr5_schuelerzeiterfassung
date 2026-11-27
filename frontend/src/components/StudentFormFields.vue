<script setup lang="ts">
import InputText from 'primevue/inputtext'
import { ref } from 'vue'
import GermanDateInput from './GermanDateInput.vue'
import type { Student } from '@/api/students'
import { todayInBerlin } from '@/api/attendance'

const props = defineProps<{ student?: Student }>()
// Das Formular entsteht bei jedem Öffnen neu und liest den Wert einmal
const birthDate = ref(props.student?.birthDate ?? '')
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
      :max="todayInBerlin()"
      required
    />
  </label>
</template>
