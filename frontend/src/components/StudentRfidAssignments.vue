<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { onMounted, ref } from 'vue'
import AppDataTable, { type DataTableColumn } from './AppDataTable.vue'
import { loadData } from '@/api/client'
type Assignment = {
  studentId: number
  studentName: string
  classCode: string
  rfidUid: string
}

const rows = ref<Assignment[]>([])
const loading = ref(true)
const error = ref('')
const columns: DataTableColumn<Assignment>[] = [
  { field: 'studentName', header: 'Schüler' },
  { field: 'classCode', header: 'Klasse' },
  { field: 'rfidUid', header: 'Kartenkennung' }
]

onMounted(async () => {
  try {
    rows.value = await loadData<Assignment[]>('/api/students/rfid-uids')
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
      row-key="studentId"
      caption="Kartenzuordnungen deiner Klassen"
    >
      <template #cell-studentName="{ row }">
        <RouterLink
          :to="{
            name: 'schueler-detail',
            params: { studentId: row.studentId }
          }"
        >
          {{ row.studentName }}
        </RouterLink>
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
