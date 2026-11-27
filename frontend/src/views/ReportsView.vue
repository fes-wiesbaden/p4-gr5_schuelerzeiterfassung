<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { loadData, postJson } from '@/api/client'

type SchoolClass = { id: number; classCode: string }
type Student = { id: number; firstName: string; lastName: string }
type Mode = 'class' | 'student' | 'group'

const classes = ref<SchoolClass[]>([])
const students = ref<Record<number, Student[]>>({})
const mode = ref<Mode>('class')
const classId = ref<number>()
const studentId = ref<number>()
const group = ref<number[]>([])
const loading = ref(true)
const downloading = ref(false)
const rosterLoaded = ref(false)
const error = ref('')

onMounted(async () => {
  try {
    classes.value = await loadData('/api/classes')
    classId.value = classes.value[0]?.id
  } catch {
    error.value = 'Klassen konnten nicht geladen werden.'
  } finally {
    loading.value = false
  }
})

async function changeMode() {
  error.value = ''
  if (mode.value === 'class' || rosterLoaded.value) return
  loading.value = true
  try {
    students.value = Object.fromEntries(
      await Promise.all(
        classes.value.map(async ({ id }) => [
          id,
          await loadData<Student[]>(`/api/classes/${id}/students`)
        ])
      )
    )
    rosterLoaded.value = true
  } catch {
    error.value = 'Schüler konnten nicht geladen werden.'
  } finally {
    loading.value = false
  }
}

const selection = computed<Record<number, number[]>>(() => {
  if (mode.value === 'class') {
    return classId.value === undefined ? {} : { [classId.value]: [] }
  }
  const ids = mode.value === 'student' ? [studentId.value] : group.value
  const selected = new Set(ids)
  const result: Record<number, number[]> = {}
  for (const { id } of classes.value) {
    const matches = students.value[id]?.filter((student) =>
      selected.has(student.id)
    )
    if (matches?.length) result[id] = matches.map((student) => student.id)
  }
  return result
})

async function download() {
  error.value = ''
  downloading.value = true
  try {
    const response = await postJson('/api/attendance/export', {
      classes: selection.value
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      error.value =
        body?.code === 'NO_ACTIVE_EXPORT_BLOCK'
          ? 'Mindestens eine ausgewählte Klasse hat heute keinen laufenden Unterrichtsblock.'
          : 'CSV konnte nicht exportiert werden. Bitte Auswahl und Berechtigung prüfen.'
      return
    }
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url
    link.download = 'anwesenheit.csv'
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    error.value = 'CSV konnte nicht exportiert werden.'
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <section>
    <h1>Anwesenheit exportieren</h1>
    <p>
      Die CSV enthält die Tagesanwesenheiten vom Beginn des laufenden Blocks bis
      heute und die aktuellen Minutenkonten.
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">Daten werden geladen …</p>
    <p v-else-if="!classes.length && !error">Keine Klasse verfügbar.</p>

    <form v-if="classes.length" @submit.prevent="download">
      <fieldset class="export-form" :disabled="loading || downloading">
        <legend>CSV-Export</legend>
        <label>
          Exportumfang
          <select v-model="mode" @change="changeMode">
            <option value="class">Ganze Klasse</option>
            <option value="student">Einzelner Schüler</option>
            <option value="group">Schülergruppe</option>
          </select>
        </label>

        <label v-if="mode === 'class'">
          Klasse
          <select v-model="classId">
            <option v-for="item in classes" :key="item.id" :value="item.id">
              {{ item.classCode }}
            </option>
          </select>
        </label>

        <label v-else-if="mode === 'student' && rosterLoaded">
          Schüler
          <select v-model="studentId">
            <option :value="undefined" disabled>Schüler auswählen</option>
            <optgroup
              v-for="item in classes"
              :key="item.id"
              :label="item.classCode"
            >
              <option
                v-for="student in students[item.id]"
                :key="student.id"
                :value="student.id"
              >
                {{ student.lastName }}, {{ student.firstName }}
              </option>
            </optgroup>
          </select>
        </label>

        <template v-else-if="mode === 'group' && rosterLoaded">
          <fieldset v-for="item in classes" :key="item.id" class="export-group">
            <legend>{{ item.classCode }}</legend>
            <p v-if="!students[item.id]?.length">
              Keine Schüler in dieser Klasse.
            </p>
            <label v-for="student in students[item.id]" :key="student.id">
              <input v-model="group" type="checkbox" :value="student.id" />
              {{ student.lastName }}, {{ student.firstName }}
            </label>
          </fieldset>
        </template>

        <button type="submit" :disabled="!Object.keys(selection).length">
          {{ downloading ? 'CSV wird erstellt …' : 'CSV herunterladen' }}
        </button>
      </fieldset>
    </form>
  </section>
</template>

<style scoped>
.export-form,
.export-group {
  display: grid;
  gap: 0.75rem;
}

.export-form {
  max-width: 36rem;
  justify-items: start;
}

.export-form select {
  display: block;
}
</style>
