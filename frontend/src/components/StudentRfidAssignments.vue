<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { onMounted, ref } from 'vue'
import AppDataTable, { type DataTableColumn } from './AppDataTable.vue'
import { loadData } from '@/api/client'
import { studentLabel, type Student, type StudentClass } from '@/api/students'

type Assignment = { studentName: string; classCode: string; rfidUid: string }
type AssignmentRow = Assignment & { studentId?: number; classId?: number }

const props = defineProps<{ classes: StudentClass[] }>()
const rows = ref<AssignmentRow[]>([])
const loading = ref(true)
const error = ref('')
const columns: DataTableColumn<AssignmentRow>[] = [
  { field: 'studentName', header: 'Schüler' },
  { field: 'classCode', header: 'Klasse' },
  { field: 'rfidUid', header: 'Kartenkennung' }
]

onMounted(async () => {
  try {
    const assignments = await loadData<Assignment[]>('/api/students/rfid-uids')
    const eligible = props.classes.filter((entry) =>
      assignments.some((assignment) => assignment.classCode === entry.classCode)
    )
    const rosters = await Promise.all(
      eligible.map(async (entry) => ({
        entry,
        students: await loadData<Student[]>(
          `/api/classes/${entry.id}/students`
        ).catch(() => [])
      }))
    )
    rows.value = assignments.map((assignment) => {
      const roster = rosters.find(
        ({ entry }) => entry.classCode === assignment.classCode
      )
      const matches = roster?.students.filter(
        (student) => studentLabel(student) === assignment.studentName
      )
      return {
        ...assignment,
        classId: roster?.entry.id,
        studentId: matches?.length === 1 ? matches[0]?.id : undefined
      }
    })
  } catch {
    error.value = 'Kartenzuordnungen konnten nicht geladen werden.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="card-assignments">
    <h2 class="visually-hidden">Kartenzuordnungen</h2>
    <p v-if="loading" role="status">Kartenzuordnungen werden geladen …</p>
    <PageError v-else-if="error" :message="error" />
    <AppDataTable
      v-else-if="rows.length"
      :columns="columns"
      :rows="rows"
      row-key="rfidUid"
      caption="Kartenzuordnungen deiner Klassen"
    >
      <template #cell-studentName="{ row }">
        <RouterLink
          v-if="row.studentId"
          :to="{
            name: 'schueler-detail',
            params: { studentId: row.studentId }
          }"
        >
          {{ row.studentName }}
        </RouterLink>
        <RouterLink
          v-else-if="row.classId"
          :to="{ name: 'schueler', query: { klasse: row.classId } }"
        >
          {{ row.studentName }}
        </RouterLink>
        <template v-else>{{ row.studentName }}</template>
      </template>
    </AppDataTable>
    <p v-else>Es sind noch keine Karten zugeordnet.</p>
  </section>
</template>

<style scoped>
.card-assignments {
  margin-top: 0.5rem;
}

a {
  color: var(--accent);
}
</style>
