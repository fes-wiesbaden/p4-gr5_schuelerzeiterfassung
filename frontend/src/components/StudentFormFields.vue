<script setup lang="ts">
import InputText from 'primevue/inputtext'
import type { Student } from '@/api/students'

defineProps<{ student?: Student }>()
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
    <InputText
      name="birthDate"
      type="date"
      :default-value="student?.birthDate"
      required
    />
  </label>
</template>
