<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { changeData } from '@/api/client'
import { confirmAction } from '@/confirm'
import { showNotice } from '@/notice'

const props = defineProps<{ studentId: number }>()
const uid = ref('')
const saving = ref(false)
const error = ref('')

async function updateCard(remove: boolean) {
  if (saving.value) return
  if (
    remove &&
    !(await confirmAction({
      title: 'Karte entfernen?',
      message:
        'Danach erfasst das Terminal mit dieser Karte keine Anwesenheit mehr.',
      confirmLabel: 'Entfernen',
      danger: true
    }))
  )
    return
  saving.value = true
  error.value = ''
  const url = `/api/students/${props.studentId}/rfid-uid`
  try {
    await changeData(
      url,
      remove ? 'DELETE' : 'PUT',
      remove ? undefined : { uid: uid.value }
    )
    uid.value = ''
    showNotice(remove ? 'Karte entfernt.' : 'Karte gespeichert.')
  } catch (failure) {
    error.value =
      failure instanceof TypeError
        ? 'Der Server ist nicht erreichbar.'
        : (failure as Error).message
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
    </form>
  </section>
</template>

<style scoped>
.card-form {
  display: grid;
  gap: 0.5rem;
  max-width: 26rem;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
