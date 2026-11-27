<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { deleteResource, putJson } from '@/api/client'

const props = defineProps<{ studentId: number }>()
const uid = ref('')
const saving = ref(false)
const error = ref('')
const message = ref('')

async function updateCard(remove: boolean) {
  if (saving.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  const url = `/api/students/${props.studentId}/rfid-uid`
  try {
    const response = await (remove
      ? deleteResource(url)
      : putJson(url, { uid: uid.value }))
    if (response.ok) {
      uid.value = ''
      message.value = remove ? 'Karte entfernt.' : 'Karte gespeichert.'
    } else if (response.status === 409) {
      error.value = 'Diese Karte ist bereits einem anderen Schüler zugeordnet.'
    } else if (response.status === 400) {
      error.value = 'Die Kartenkennung hat ein ungültiges Format.'
    } else {
      error.value = 'Die Kartenzuordnung konnte nicht geändert werden.'
    }
  } catch {
    error.value = 'Der Server ist nicht erreichbar.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section>
    <h2>RFID-Karte</h2>
    <form
      class="card-form"
      autocomplete="off"
      @submit.prevent="updateCard(false)"
    >
      <label for="card-uid">Neue Kartenkennung</label>
      <InputText
        id="card-uid"
        v-model="uid"
        type="password"
        autocomplete="off"
        spellcheck="false"
        aria-describedby="card-format"
        required
        :disabled="saving"
      />
      <small id="card-format">8, 14 oder 20 Hexzeichen (A–F, 0–9).</small>
      <div class="card-actions">
        <Button
          type="submit"
          label="Karte speichern"
          :disabled="saving || !uid"
        />
        <Button
          type="button"
          label="Karte entfernen"
          severity="secondary"
          :disabled="saving"
          @click="updateCard(true)"
        />
      </div>
      <PageError :message="error" />
      <p v-if="message" role="status">{{ message }}</p>
    </form>
  </section>
</template>

<style scoped>
.card-form {
  display: grid;
  gap: 0.5rem;
  max-width: 26rem;
}

.card-form p {
  margin: 0;
}

.card-form select {
  min-width: 0;
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--control-border);
  border-radius: 10px;
  background: var(--surface);
  color: inherit;
  font: inherit;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
