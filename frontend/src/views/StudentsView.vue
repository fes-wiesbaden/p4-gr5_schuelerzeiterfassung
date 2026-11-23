<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'

import { deleteResource, getJson, putJson } from '@/api/client'
import AppDataTable, {
  type DataTableColumn
} from '@/components/AppDataTable.vue'
import { useAuthStore } from '@/stores/auth'

type SchoolClass = { id: number; classCode: string }
type RfidAssignment = {
  studentName: string
  classCode: string
  rfidUid: string
}
type Student = {
  id: number
  firstName: string
  lastName: string
  birthDate: string
}

const auth = useAuthStore()
const classes = ref<SchoolClass[]>([])
const students = ref<Student[]>([])
const classId = ref<number | null>(null)
const studentId = ref<number | null>(null)
const uid = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const assignments = ref<RfidAssignment[]>([])
const assignmentColumns: DataTableColumn<RfidAssignment>[] = [
  { field: 'studentName', header: 'Schüler' },
  { field: 'classCode', header: 'Klasse' },
  { field: 'rfidUid', header: 'RFID-UID' }
]

onMounted(async () => {
  loading.value = true
  try {
    const response = await getJson(
      auth.isAdmin ? '/api/classes' : '/api/students/rfid-uids'
    )
    if (!response.ok) throw new Error()
    if (auth.isAdmin) {
      classes.value = (await response.json()) as SchoolClass[]
    } else {
      assignments.value = (await response.json()) as RfidAssignment[]
    }
  } catch {
    error.value = auth.isAdmin
      ? 'Klassen konnten nicht geladen werden.'
      : 'Kartenzuordnungen konnten nicht geladen werden.'
  } finally {
    loading.value = false
  }
})

function resetForm() {
  uid.value = ''
  error.value = ''
  message.value = ''
}

async function loadStudents() {
  resetForm()
  studentId.value = null
  students.value = []
  if (classId.value === null) return
  loading.value = true
  try {
    const response = await getJson(`/api/classes/${classId.value}/students`)
    if (!response.ok) throw new Error()
    students.value = (await response.json()) as Student[]
  } catch {
    error.value =
      'Schüler konnten nicht geladen werden. Bitte wähle die Klasse erneut.'
    classId.value = null
  } finally {
    loading.value = false
  }
}

async function updateCard(remove: boolean) {
  if (studentId.value === null || saving.value) return
  saving.value = true
  const url = `/api/students/${studentId.value}/rfid-uid`
  const request = remove
    ? deleteResource(url)
    : putJson(url, { uid: uid.value })
  resetForm()
  try {
    const response = await request
    if (response.ok) {
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
    <h1>Schüler</h1>
    <form
      v-if="auth.isAdmin"
      class="card-form"
      autocomplete="off"
      @submit.prevent="updateCard(false)"
    >
      <h2>Kartenzuordnung</h2>
      <label for="card-class">Klasse</label>
      <select
        id="card-class"
        v-model="classId"
        :disabled="loading || saving"
        @change="loadStudents"
      >
        <option :value="null">Klasse auswählen</option>
        <option
          v-for="schoolClass in classes"
          :key="schoolClass.id"
          :value="schoolClass.id"
        >
          {{ schoolClass.classCode }}
        </option>
      </select>
      <label for="card-student">Schüler</label>
      <select
        id="card-student"
        v-model="studentId"
        :disabled="loading || saving || !students.length"
        @change="resetForm"
      >
        <option :value="null">Schüler auswählen</option>
        <option
          v-for="student in students"
          :key="student.id"
          :value="student.id"
        >
          {{ student.lastName }}, {{ student.firstName }} ({{
            student.birthDate
          }})
        </option>
      </select>
      <p v-if="loading" role="status">Lädt …</p>
      <p v-else-if="classId !== null && !students.length && !error">
        Diese Klasse hat keine Schüler.
      </p>
      <template v-if="studentId !== null">
        <label for="card-uid">Neue Kartenkennung</label>
        <InputText
          id="card-uid"
          v-model="uid"
          type="password"
          autocomplete="off"
          spellcheck="false"
          aria-describedby="card-format"
          pattern="(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})"
          maxlength="20"
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
      </template>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
    </form>
    <template v-else>
      <p v-if="loading" role="status">Kartenzuordnungen werden geladen …</p>
      <p v-else-if="error" role="alert">{{ error }}</p>
      <AppDataTable
        v-else-if="assignments.length"
        :columns="assignmentColumns"
        :rows="assignments"
        row-key="rfidUid"
        caption="Kartenzuordnungen aller Klassen"
      />
      <p v-else>Es sind noch keine Karten zugeordnet.</p>
    </template>
  </section>
</template>

<style scoped>
.card-form {
  display: grid;
  gap: 0.75rem;
  max-width: 28rem;
}

.card-form h2,
.card-form p {
  margin: 0;
}

.card-form select {
  min-width: 0;
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--p-inputtext-border-color, #94a3b8);
  border-radius: var(--p-inputtext-border-radius, 6px);
  background: var(--p-inputtext-background, white);
  color: inherit;
  font: inherit;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
